package dev.bennett.codexmeter;

import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import dev.bennett.codexmeter.glass.GlassModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Accounts → Add account: every provider that isn't connected yet, searchable.
 * Picking one opens {@link SignInActivity}; once it connects, this page closes too and the
 * provider gets a dashboard tab.
 */
public final class AddAccountActivity extends AppCompatActivity implements GlassApp.Host {
    /** Liquid Glass: this page drawn by Glassé's kit ({@link #glassPage}), or null for One UI. */
    private GlassApp glass;
    private String query = "";

    @Override
    public GlassApp glass() {
        return glass;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

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
        if (AppPreferences.isLiquidGlassDesign(this)) {
            glass = new GlassApp(this, super::dispatchTouchEvent, GlassScreens.PREFIX + "add_account");
            glass.start(state);
            return;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glass != null) {
            glass.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (glass != null) {
            glass.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (glass != null) {
            glass.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (glass != null) {
            glass.onConfigurationChanged(config);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        return glass != null ? glass.dispatchTouchEvent(ev) : super.dispatchTouchEvent(ev);
    }

    private void pick(UsageProvider provider) {
        DiagnosticLog.info(this, "user", "add_account_picked", "provider", provider.id);
        signIn.launch(SignInActivity.intent(this, provider));
    }

    /** This page for the Liquid Glass design: a search field over the providers not yet connected. */
    GlassModel.Page glassPage() {
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "add_account", "Add account");
        page.intro = "Choose a service to connect. Each one gets its own tab on the dashboard.";
        GlassModel.Section search = GlassScreens.section(null);
        GlassModel.Row field = GlassScreens.row(GlassModel.Row.Kind.FIELD, "search", "", null);
        field.hint = "Search providers";
        field.value = query;
        field.onText = text -> {
            query = text;
            glass.changed();
        };
        search.rows.add(field);
        page.sections.add(search);
        List<UsageProvider> connected = new ArrayList<>();
        for (UsageProvider provider : UsageProvider.values()) {
            if (ProviderAccounts.isSignedIn(this, provider)) connected.add(provider);
        }
        GlassModel.Section list = GlassScreens.section(null);
        for (UsageProvider provider : ProviderCatalog.pickerProviders(connected, query)) {
            ProviderCatalog.Entry entry = ProviderCatalog.get(provider);
            GlassModel.Row row = GlassScreens.row(GlassModel.Row.Kind.LINK, provider.id,
                    provider.displayName, entry.pickerSummary());
            row.icon = "provider_" + provider.id;
            row.plainIcon = true;
            row.tag = entry.inferred ? "Experimental" : null;
            row.onPress = () -> pick(provider);
            list.rows.add(row);
        }
        if (list.rows.isEmpty()) {
            list.rows.add(GlassScreens.text("none", "No providers match."));
        }
        page.sections.add(list);
        return page;
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
        ProviderPicker picker = new ProviderPicker(this, dark, connected, null, this::pick);
        content.addView(picker.view());
        Ui.addSpacer(content, 24);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
