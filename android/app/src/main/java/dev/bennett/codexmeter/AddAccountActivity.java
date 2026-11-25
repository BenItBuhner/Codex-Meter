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
 * Settings → Providers → Add account: every provider that isn't connected yet, searchable.
 * Picking one opens {@link SignInActivity}; once it connects, this page closes too and the
 * provider gets a dashboard tab.
 */
public final class AddAccountActivity extends AppCompatActivity {
    /** Material 3 Expressive: the same page drawn with Material views, or null. */
    private ExpressivePages expressive;
    private String query = "";

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
        // Liquid Glass shows Add account as a page of Settings in MainActivity, so it slides in
        // and swipes back like the pages around it (no second activity, transition or glass host).
        if (AppPreferences.isLiquidGlassDesign(this) && GlassApp.redirect(this)) {
            return;
        }
        if (AppPreferences.isMaterialExpressiveDesign(this)) {
            expressive = ExpressivePages.attach(this, GlassScreens.PREFIX + "add_account", getIntent());
            if (expressive != null) {
                return;
            }
        }
        render();
    }

    private void pick(UsageProvider provider) {
        DiagnosticLog.info(this, "user", "add_account_picked", "provider", provider.id);
        signIn.launch(SignInActivity.intent(this, provider));
    }

    /** This page for Material 3 Expressive, shown in this activity. */
    GlassModel.Page glassPage() {
        return glassPage(this, query, text -> {
            query = text;
            if (expressive != null) {
                expressive.changed();
            }
        }, this::pick);
    }

    /** What picking a provider on the page does. */
    interface Pick {
        void pick(UsageProvider provider);
    }

    /**
     * The Add account page for Liquid Glass and Material 3 Expressive: a search field over the
     * providers not yet connected. Liquid Glass shows it as a page of its own Settings stack
     * ({@link GlassScreens}), so it slides in and swipes back like every other page.
     */
    static GlassModel.Page glassPage(Context context, String query, GlassModel.OnText onQuery, Pick onPick) {
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "add_account", "Add account");
        page.intro = "Choose a service to connect. Each one gets its own tab on the dashboard.";
        GlassModel.Section search = GlassScreens.section(null);
        GlassModel.Row field = GlassScreens.row(GlassModel.Row.Kind.FIELD, "search", "", null);
        field.hint = "Search providers";
        field.value = query;
        field.onText = onQuery;
        search.rows.add(field);
        page.sections.add(search);
        GlassModel.Section list = GlassScreens.section(null);
        for (UsageProvider provider : ProviderCatalog.pickerProviders(connected(context), query)) {
            ProviderCatalog.Entry entry = ProviderCatalog.get(provider);
            GlassModel.Row row = GlassScreens.row(GlassModel.Row.Kind.LINK, provider.id,
                    provider.displayName, entry.pickerSummary());
            row.icon = "provider_" + provider.id;
            row.plainIcon = true;
            row.tag = entry.inferred ? "Experimental" : null;
            row.onPress = () -> onPick.pick(provider);
            list.rows.add(row);
        }
        if (list.rows.isEmpty()) {
            list.rows.add(GlassScreens.text("none", "No providers match."));
        }
        page.sections.add(list);
        return page;
    }

    static List<UsageProvider> connected(Context context) {
        List<UsageProvider> connected = new ArrayList<>();
        for (UsageProvider provider : UsageProvider.values()) {
            if (ProviderAccounts.isSignedIn(context, provider)) connected.add(provider);
        }
        return connected;
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
        ProviderPicker picker = new ProviderPicker(this, dark, connected(this), null, this::pick);
        content.addView(picker.view());
        Ui.addSpacer(content, 24);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
