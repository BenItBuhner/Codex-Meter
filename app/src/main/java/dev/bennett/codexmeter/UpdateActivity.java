package dev.bennett.codexmeter;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-facing secure download and PackageInstaller hand-off flow. */
public final class UpdateActivity extends AppCompatActivity {
    public static final String EXTRA_VERSION = "release_version";
    public static final String EXTRA_FORCE_CHECK = "force_check";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private GitHubRelease release;
    private ProgressBar progress;
    private TextView status;
    private boolean waitingForInstallPermission;
    private boolean operationRunning;
    private boolean dark;

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        dark = Ui.isDark(this);
        content = Ui.installPage(this, "App update", true).content;
        String requested = getIntent().getStringExtra(EXTRA_VERSION);
        release = UpdatePreferences.findVersion(this, requested);
        boolean force = getIntent().getBooleanExtra(EXTRA_FORCE_CHECK, false);
        if (release == null || force) {
            checkReleases(requested);
        } else {
            render();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForInstallPermission && canInstallPackages()) {
            waitingForInstallPermission = false;
            beginInstall();
            return;
        }
        if (status != null && !operationRunning) {
            String error = UpdatePreferences.installError(this);
            if (!error.isEmpty()) {
                setStatus(error, Ui.danger(dark));
            }
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void checkReleases(String requestedVersion) {
        operationRunning = true;
        content.removeAllViews();
        content.addView(Ui.indeterminateLoading(this, "Checking for updates"));
        executor.execute(() -> {
            try {
                List<GitHubRelease> releases = ReleaseUpdateClient.check(getApplicationContext());
                GitHubRelease selected = GitHubReleaseParser.findVersion(releases, requestedVersion);
                if (selected == null) {
                    selected = GitHubReleaseParser.latestStable(releases);
                }
                GitHubRelease result = selected;
                postUi(() -> {
                    operationRunning = false;
                    release = result;
                    render();
                });
            } catch (Exception exception) {
                postUi(() -> {
                    operationRunning = false;
                    renderError(ReleaseUpdateClient.safeMessage(exception));
                });
            }
        });
    }

    private void render() {
        content.removeAllViews();
        if (release == null) {
            String error = UpdatePreferences.lastError(this);
            renderError(error.isEmpty()
                    ? "No installable GitHub releases are published yet."
                    : error);
            return;
        }
        String installedVersion = UpdatePreferences.installedVersion(this);
        int comparison = ReleaseVersion.compare(release.version, installedVersion);
        boolean irreversible = ReleaseUpdatePolicy.isIrreversible(release.version);
        LinearLayout card = Ui.card(this, dark);
        TextView title = Ui.text(this,
                comparison > 0 ? "Codex Meter " + release.version + " is available"
                        : comparison == 0 ? "Codex Meter " + release.version
                        : "Older release " + release.version,
                20, Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        String detail;
        if (irreversible) {
            detail = ReleaseUpdatePolicy.irreversibleSummary()
                    + " · Installed: " + installedVersion;
        } else if (comparison > 0) {
            detail = "Installed: " + installedVersion + " · Verified GitHub upgrade";
        } else if (comparison == 0) {
            detail = "This version is currently installed. You can verify and reinstall it.";
        } else {
            detail = "Installed: " + installedVersion
                    + " · Android requires uninstalling before this downgrade.";
        }
        TextView summary = Ui.text(this, detail, 14,
                irreversible ? Ui.danger(dark) : Ui.secondaryText(dark));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 18));
        card.addView(summary, summaryParams);

        if (irreversible) {
            TextView irreversibleDetail = Ui.text(this, ReleaseUpdatePolicy.irreversibleDetail(),
                    13, Ui.secondaryText(dark));
            LinearLayout.LayoutParams irreversibleParams = new LinearLayout.LayoutParams(-1, -2);
            irreversibleParams.setMargins(0, 0, 0, Ui.dp(this, 18));
            card.addView(irreversibleDetail, irreversibleParams);
            Button github = Ui.nativePrimaryButton(this, "Open on GitHub");
            github.setOnClickListener(view -> openReleasePage());
            card.addView(github, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
            progress = null;
            status = null;
        } else {
            Button action = Ui.nativePrimaryButton(this,
                    comparison < 0 ? "Download older APK"
                            : comparison == 0 ? "Verify and reinstall"
                            : "Download and install");
            action.setOnClickListener(view -> {
                if (comparison < 0) {
                    confirmOlderDownload();
                } else {
                    requestInstall();
                }
            });
            card.addView(action, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));

            progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setVisibility(View.GONE);
            LinearLayout.LayoutParams progressParams =
                    new LinearLayout.LayoutParams(-1, Ui.dp(this, 8));
            progressParams.setMargins(0, Ui.dp(this, 18), 0, 0);
            card.addView(progress, progressParams);
            status = Ui.text(this, "", 13, Ui.secondaryText(dark));
            status.setVisibility(View.GONE);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
            statusParams.setMargins(0, Ui.dp(this, 10), 0, 0);
            card.addView(status, statusParams);
        }
        content.addView(card);

        if (!release.notes.isEmpty()) {
            TextView heading = Ui.text(this, "What’s new", 15, Ui.secondaryText(dark));
            heading.setTypeface(Ui.mediumTypeface(this));
            LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
            headingParams.setMargins(Ui.dp(this, 4), Ui.dp(this, 24), 0, Ui.dp(this, 10));
            content.addView(heading, headingParams);
            LinearLayout notesCard = Ui.card(this, dark);
            notesCard.addView(ReleaseNotesUi.create(this, release.notes, dark));
            content.addView(notesCard);
        }

        Button history = Ui.button(this, "Release history", false, dark);
        history.setOnClickListener(view ->
                Ui.startSecondaryActivity(this, ReleaseHistoryActivity.class));
        LinearLayout.LayoutParams historyParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        historyParams.setMargins(0, Ui.dp(this, 20), 0, 0);
        content.addView(history, historyParams);
    }

    private void renderError(String message) {
        content.removeAllViews();
        LinearLayout card = Ui.card(this, dark);
        TextView title = Ui.text(this, "Update check unavailable", 20, Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        TextView detail = Ui.text(this, message, 14, Ui.secondaryText(dark));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 18));
        card.addView(detail, detailParams);
        Button retry = Ui.nativePrimaryButton(this, "Check again");
        retry.setOnClickListener(view -> checkReleases(getIntent().getStringExtra(EXTRA_VERSION)));
        card.addView(retry, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        content.addView(card);

        Button history = Ui.button(this, "Release history", false, dark);
        history.setOnClickListener(view ->
                Ui.startSecondaryActivity(this, ReleaseHistoryActivity.class));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        params.setMargins(0, Ui.dp(this, 20), 0, 0);
        content.addView(history, params);
    }

    private void requestInstall() {
        if (operationRunning || release == null
                || ReleaseUpdatePolicy.isIrreversible(release.version)) {
            return;
        }
        UpdatePreferences.setInstallError(this, "");
        if (!canInstallPackages()) {
            waitingForInstallPermission = true;
            new AlertDialog.Builder(this)
                    .setTitle("Allow app installs")
                    .setMessage("Android requires permission for Codex Meter to hand its verified "
                            + "GitHub APK to the system installer. You still approve every update.")
                    .setNegativeButton("Cancel", (dialog, which) ->
                            waitingForInstallPermission = false)
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException exception) {
                            waitingForInstallPermission = false;
                            Toast.makeText(this, "Could not open install permission settings.",
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }
        beginInstall();
    }

    private boolean canInstallPackages() {
        return getPackageManager().canRequestPackageInstalls();
    }

    private void beginInstall() {
        if (operationRunning || release == null
                || ReleaseUpdatePolicy.isIrreversible(release.version)) {
            return;
        }
        operationRunning = true;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        setStatus(getString(R.string.update_downloading), Ui.secondaryText(dark));
        executor.execute(() -> {
            try {
                UpdateInstaller.PreparedUpdate prepared = UpdateInstaller.prepare(
                        getApplicationContext(), release, (downloaded, total) ->
                                postUi(() -> {
                                    if (progress != null && total > 0L) {
                                        progress.setProgress((int) Math.min(1000L,
                                                downloaded * 1000L / total));
                                    }
                                }));
                postUi(() -> setStatus(getString(R.string.update_opening_installer),
                        Ui.secondaryText(dark)));
                UpdateInstaller.commit(getApplicationContext(), prepared);
                postUi(() -> {
                    operationRunning = false;
                    setStatus(getString(R.string.update_waiting_for_installer),
                            Ui.secondaryText(dark));
                });
            } catch (Exception exception) {
                UpdatePreferences.setInstallError(getApplicationContext(),
                        safeMessage(exception));
                postUi(() -> {
                    operationRunning = false;
                    progress.setVisibility(View.GONE);
                    setStatus(safeMessage(exception), Ui.danger(dark));
                });
            }
        });
    }

    private void setStatus(String message, int color) {
        if (status == null) {
            return;
        }
        status.setTextColor(color);
        status.setText(message);
        status.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmOlderDownload() {
        new AlertDialog.Builder(this)
                .setTitle("Downgrade requires uninstalling")
                .setMessage("Android blocks in-place downgrades for ordinary apps. Uninstalling "
                        + "Codex Meter removes its account, settings, cached usage, and widgets. "
                        + "The older APK will open in your browser so it remains available after "
                        + "uninstalling.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open APK download", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)));
                    } catch (RuntimeException exception) {
                        Toast.makeText(this, "No browser can open the APK download.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void openReleasePage() {
        String url = release == null ? "" : release.pageUrl;
        if (url.isEmpty() && release != null) {
            url = release.apkUrl;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "No browser can open the GitHub release page.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception == null ? "" : exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "The update could not be prepared.";
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }
}
