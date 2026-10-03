package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Additive account management using the existing SESL page and card components. */
public final class AccountsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean busy;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AppConstants.ACTION_OAUTH_READY.equals(intent.getAction())) {
                String url = intent.getStringExtra(AppConstants.EXTRA_AUTH_URL);
                if (url != null && !url.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)));
            } else {
                Toast.makeText(AccountsActivity.this,
                        intent.getStringExtra(AppConstants.EXTRA_MESSAGE), Toast.LENGTH_LONG).show();
                rebuild();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        rebuild();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AppConstants.ACTION_OAUTH_READY);
        filter.addAction(AppConstants.ACTION_OAUTH_RESULT);
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter,
                AppConstants.INTERNAL_PERMISSION, null,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @Override protected void onResume() { super.onResume(); rebuild(); }
    @Override protected void onStop() { unregisterReceiver(receiver); super.onStop(); }
    @Override protected void onDestroy() { executor.shutdown(); super.onDestroy(); }

    private void rebuild() {
        boolean dark = Ui.isDark(this);
        LinearLayout content = Ui.installPage(this, "Accounts", true).content;
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout pull = findViewById(R.id.dashboard_refresh);
        pull.setEnabled(false);
        Button add = Ui.button(this, "Add account", dark, true);
        add.setEnabled(!busy);
        add.setOnClickListener(view -> startForegroundService(new Intent(this, OAuthService.class)
                .setAction(OAuthService.ACTION_START).putExtra("add_account", true)));
        content.addView(add);
        Button all = Ui.button(this, busy ? "Refreshing…" : "Refresh all accounts", dark, false);
        all.setEnabled(!busy);
        all.setOnClickListener(view -> run(() -> UsageApi.refreshAllAndCache(getApplicationContext())));
        content.addView(all);
        String selected = AccountRepository.selectedId(this);
        for (AccountProfile account : AccountRepository.accounts(this)) {
            LinearLayout card = Ui.card(this, dark);
            content.addView(Ui.sectionTitle(this, account.label
                    + (account.id.equals(selected) ? " · Selected" : ""), dark));
            content.addView(card);
            UsageSnapshot snapshot = AccountRepository.snapshot(this, account.id);
            String quota = snapshot == null ? "No usage yet" : "5h: " + remaining(snapshot.fiveHour)
                    + " · " + (snapshot.longWindowIsMonthly() ? "Monthly" : "Weekly")
                    + ": " + remaining(snapshot.longWindow()) + "\n" + snapshot.planType;
            card.addView(Ui.text(this, quota, 16f, Ui.mainText(dark)));
            String session = account.isAuthenticated() ? "Connected" : "Sign-in required";
            if (snapshot != null) session += " · Updated " + android.text.format.DateUtils
                    .getRelativeTimeSpanString(snapshot.fetchedAtMillis, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS);
            card.addView(Ui.text(this, session, 13f, Ui.mainText(dark)));
            String error = AccountRepository.usagePreferences(this, account.id).getString("last_error", "");
            if (!error.isEmpty()) card.addView(Ui.text(this, error, 14f, Ui.mainText(dark)));
            action(card, "Use this account", () -> AccountRepository.select(this, account.id));
            action(card, "Refresh", () -> UsageApi.refreshAndCache(this, account.id));
            Button rename = Ui.button(this, "Rename", dark, false);
            rename.setEnabled(!busy);
            rename.setOnClickListener(view -> {
                EditText input = new EditText(this);
                input.setSingleLine(true);
                input.setText(account.label);
                input.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(40) });
                new AlertDialog.Builder(this).setTitle("Account label").setView(input)
                        .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) ->
                                run(() -> AccountRepository.rename(this, account.id, input.getText().toString()))).show();
            });
            card.addView(rename);
            Button remove = Ui.button(this, "Remove / sign out", dark, false);
            remove.setEnabled(!busy);
            remove.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Remove " + account.label + "?")
                    .setMessage("Removes this account’s encrypted session and cached usage.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) ->
                            run(() -> {
                                AuthTokens tokens = AccountRepository.remove(this, account.id);
                                OAuthClient.revokeBestEffort(this, tokens);
                            })).show());
            card.addView(remove);
        }
    }

    private static String remaining(UsageWindow window) {
        return window == null ? "—" : window.remainingPercent() + "% remaining";
    }

    private void action(LinearLayout card, String label, Operation operation) {
        Button button = Ui.button(this, label, Ui.isDark(this), false);
        button.setEnabled(!busy);
        button.setOnClickListener(view -> run(operation));
        card.addView(button);
    }

    private void run(Operation operation) {
        busy = true;
        rebuild();
        Context app = getApplicationContext();
        executor.execute(() -> {
            String error = "";
            try { operation.run(); WidgetRenderer.updateAll(app); }
            catch (Exception exception) { error = "Account operation failed. Please try again."; }
            String message = error;
            runOnUiThread(() -> {
                busy = false;
                if (!isFinishing() && !isDestroyed()) {
                    if (!message.isEmpty()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    rebuild();
                }
            });
        });
    }

    private interface Operation { void run() throws Exception; }
}
