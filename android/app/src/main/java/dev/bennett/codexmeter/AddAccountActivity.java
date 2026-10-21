package dev.bennett.codexmeter;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Accounts → Add account: every provider that isn't connected yet, searchable.
 * Picking one opens {@link SignInActivity}; once it connects, this page closes too and the
 * provider gets a dashboard tab.
 */
public final class AddAccountActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> signIn;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        signIn = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        setResult(RESULT_OK);
                        finish();
                    }
                });
        render();
    }

    private void render() {
        boolean dark = Ui.isDark(this);
        LinearLayout content = Ui.installPage(this, "Add account", true).content;
        findViewById(R.id.dashboard_refresh).setEnabled(false);
        TextView hint = Ui.text(this, "Choose a service to connect. Each one gets its own tab "
                + "on the dashboard.", 14.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.setMargins(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 16));
        content.addView(hint, hintParams);
        List<UsageProvider> connected = new ArrayList<>();
        for (UsageProvider provider : UsageProvider.values()) {
            if (ProviderAccounts.isSignedIn(this, provider)) connected.add(provider);
        }
        ProviderPicker picker = new ProviderPicker(this, dark, connected, null, provider -> {
            DiagnosticLog.info(this, "user", "add_account_picked", "provider", provider.id);
            signIn.launch(SignInActivity.intent(this, provider));
        });
        content.addView(picker.view());
        Ui.addSpacer(content, 24);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
