package dev.bennett.codexmeter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Card;
import dev.bennett.codexmeter.glass.GlassModel.Row;
import dev.bennett.codexmeter.glass.GlassModel.Section;
import java.util.List;

/** App update in Liquid Glass (UpdateActivity): the verified download and installer hand-off. */
final class GlassUpdateScreen extends GlassScreens.Screen {
    private GitHubRelease release;
    private String requested;
    private boolean checking;
    private String checkError;
    private boolean operationRunning;
    private boolean waitingForPermission;
    private boolean startInstallPending;
    private double progress = -1;
    private String status;
    private boolean statusError;

    GlassUpdateScreen(GlassScreens screens) {
        super(screens);
    }

    @Override
    void opened(Intent intent) {
        Context c = activity();
        requested = intent == null ? null : intent.getStringExtra(UpdateActivity.EXTRA_VERSION);
        boolean force = intent != null && intent.getBooleanExtra(UpdateActivity.EXTRA_FORCE_CHECK, false);
        startInstallPending = intent != null && intent.getBooleanExtra(UpdateActivity.EXTRA_START_INSTALL, false);
        UpdateNotificationManager.dismiss(c);
        if (!operationRunning) {
            progress = -1;
            status = null;
        }
        release = UpdatePreferences.findVersion(c, requested);
        if (release == null || force) {
            check();
        } else {
            maybeStartInstall();
        }
    }

    /** The activity came back (from the install permission screen, say). */
    @Override
    void resumed() {
        if (waitingForPermission && canInstall()) {
            waitingForPermission = false;
            beginInstall();
            return;
        }
        if (!operationRunning) {
            String error = UpdatePreferences.installError(activity());
            if (!error.isEmpty() && release != null) {
                setStatus(error, true);
            }
        }
    }

    private void check() {
        checking = true;
        checkError = null;
        operationRunning = true;
        changed();
        Context app = activity().getApplicationContext();
        String version = requested;
        screens.executor.execute(() -> {
            try {
                List<GitHubRelease> releases = ReleaseUpdateClient.check(app);
                GitHubRelease selected = GitHubReleaseParser.findVersion(releases, version);
                if (selected == null) {
                    selected = UpdateChannel.trackedRelease(releases, UpdatePreferences.channel(app));
                }
                GitHubRelease result = selected;
                activity().runOnUiThread(() -> {
                    checking = false;
                    operationRunning = false;
                    release = result;
                    if (release == null) {
                        String error = UpdatePreferences.lastError(app);
                        checkError = error.isEmpty() ? "No installable GitHub releases are published yet." : error;
                    }
                    maybeStartInstall();
                    changed();
                });
            } catch (Exception e) {
                activity().runOnUiThread(() -> {
                    checking = false;
                    operationRunning = false;
                    checkError = ReleaseUpdateClient.safeMessage(e);
                    startInstallPending = false;
                    changed();
                });
            }
        });
    }

    private void maybeStartInstall() {
        if (!startInstallPending || release == null) {
            startInstallPending = false;
            return;
        }
        startInstallPending = false;
        String installed = UpdatePreferences.installedVersion(activity());
        boolean upgrade = ReleaseVersion.compare(release.version, installed) > 0
                || UpdateChannel.isReturnToStable(release, installed);
        if (upgrade && !ReleaseUpdatePolicy.isIrreversible(release.version)) {
            screens.app.post(this::requestInstall);
        }
    }

    @Override
    GlassModel.Page page() {
        Context c = activity();
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "update", "App update");
        if (checking) {
            Card wait = new Card(Card.Kind.MESSAGE);
            wait.title = "Checking for updates…";
            page.sections.add(GlassScreens.cards(wait));
            return page;
        }
        if (release == null) {
            Card card = new Card(Card.Kind.UPDATE);
            card.title = "Update check unavailable";
            card.summary = checkError == null ? "No installable GitHub releases are published yet." : checkError;
            card.button = "Check again";
            card.onPress = this::check;
            page.sections.add(GlassScreens.cards(card));
            page.sections.add(historyButton());
            return page;
        }
        String installed = UpdatePreferences.installedVersion(c);
        int comparison = ReleaseVersion.compare(release.version, installed);
        boolean irreversible = ReleaseUpdatePolicy.isIrreversible(release.version);
        boolean returnToStable = UpdateChannel.isReturnToStable(release, installed);
        Card card = new Card(Card.Kind.UPDATE);
        card.title = comparison > 0 ? "Codex Meter " + release.version + " is available"
                : comparison == 0 ? "Codex Meter " + release.version
                : returnToStable ? "Return to Codex Meter " + release.version
                : "Older release " + release.version;
        if (irreversible) {
            card.summary = ReleaseUpdatePolicy.irreversibleSummary() + " · Installed: " + installed;
            card.warning = true;
            card.detail = ReleaseUpdatePolicy.irreversibleDetail();
            card.button = "Open on GitHub";
            card.onPress = this::openReleasePage;
        } else {
            card.summary = comparison > 0
                    ? "Installed: " + installed + (release.prerelease
                            ? " · Verified GitHub alpha upgrade" : " · Verified GitHub upgrade")
                    : comparison == 0 ? "This version is currently installed. You can verify and reinstall it."
                    : returnToStable ? "Installed: " + installed + " · Alpha builds share the stable "
                            + "version code, so the newest stable release installs in place without "
                            + "uninstalling or losing data."
                    : "Installed: " + installed + " · Android requires uninstalling before this downgrade.";
            card.button = returnToStable ? "Return to stable"
                    : comparison < 0 ? "Download older APK"
                    : comparison == 0 ? "Verify and reinstall" : "Download and install";
            card.buttonEnabled = !operationRunning;
            card.onPress = () -> {
                if (comparison < 0 && !returnToStable) {
                    confirmOlderDownload();
                } else {
                    requestInstall();
                }
            };
            card.progress = progress;
            card.value = status;
            card.fast = statusError;
        }
        page.sections.add(GlassScreens.cards(card));
        if (!release.notes.isEmpty()) {
            Card notes = GlassScreens.note(null, GlassScreens.plainNotes(release.notes));
            notes.detail = "What’s new";
            page.sections.add(GlassScreens.cards(notes));
        }
        page.sections.add(historyButton());
        return page;
    }

    private Section historyButton() {
        Section section = GlassScreens.section(null);
        Row row = GlassScreens.row(Row.Kind.LINK, "history", "Release history", null);
        row.icon = "clock";
        row.color = "#8e8e93";
        row.onPress = () -> screens.app.openPage(GlassScreens.PREFIX + "releases", null);
        section.rows.add(row);
        return section;
    }

    private boolean canInstall() {
        return activity().getPackageManager().canRequestPackageInstalls();
    }

    private void requestInstall() {
        Context c = activity();
        if (operationRunning || release == null || ReleaseUpdatePolicy.isIrreversible(release.version)) {
            return;
        }
        UpdatePreferences.setInstallError(c, "");
        if (!canInstall()) {
            waitingForPermission = true;
            new AppDialog(c)
                    .setTitle("Allow app installs")
                    .setMessage("Android requires permission for Codex Meter to hand its verified "
                            + "GitHub APK to the system installer. You still approve every update.")
                    .setNegativeButton("Cancel", (dialog, which) -> waitingForPermission = false)
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        try {
                            c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + c.getPackageName())));
                        } catch (RuntimeException e) {
                            waitingForPermission = false;
                            Toast.makeText(c, "Could not open install permission settings.",
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }
        beginInstall();
    }

    private void beginInstall() {
        if (operationRunning || release == null || ReleaseUpdatePolicy.isIrreversible(release.version)) {
            return;
        }
        Context app = activity().getApplicationContext();
        GitHubRelease target = release;
        operationRunning = true;
        progress = 0;
        setStatus(app.getString(R.string.update_downloading), false);
        screens.executor.execute(() -> {
            try {
                UpdateInstaller.PreparedUpdate prepared = UpdateInstaller.prepare(app, target,
                        (downloaded, total) -> activity().runOnUiThread(() -> {
                            if (total > 0L) {
                                progress = Math.min(1d, downloaded / (double) total);
                                changed();
                            }
                        }));
                activity().runOnUiThread(() -> setStatus(app.getString(R.string.update_opening_installer), false));
                UpdateInstaller.commit(app, prepared);
                activity().runOnUiThread(() -> {
                    operationRunning = false;
                    setStatus(app.getString(R.string.update_waiting_for_installer), false);
                });
            } catch (Exception e) {
                String message = safeMessage(e);
                UpdatePreferences.setInstallError(app, message);
                activity().runOnUiThread(() -> {
                    operationRunning = false;
                    progress = -1;
                    setStatus(message, true);
                });
            }
        });
    }

    private void setStatus(String message, boolean error) {
        status = message == null || message.isEmpty() ? null : message;
        statusError = error;
        changed();
    }

    private void confirmOlderDownload() {
        Context c = activity();
        new AppDialog(c)
                .setTitle("Downgrade requires uninstalling")
                .setMessage("Android blocks in-place downgrades for ordinary apps. Uninstalling "
                        + "Codex Meter removes its account, settings, cached usage, and widgets. "
                        + "The older APK will open in your browser so it remains available after "
                        + "uninstalling.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open APK download", (dialog, which) ->
                        screens.openUrl(release.apkUrl, "No browser can open the APK download."))
                .show();
    }

    private void openReleasePage() {
        String url = release.pageUrl.isEmpty() ? release.apkUrl : release.pageUrl;
        screens.openUrl(url, "No browser can open the GitHub release page.");
    }

    private static String safeMessage(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "The update could not be prepared.";
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
