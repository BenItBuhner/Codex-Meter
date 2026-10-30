package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import dev.oneuiproject.oneui.widget.BottomTabLayout;

/* JADX INFO: loaded from: classes.dex */
public final class MainActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    private static final int MENU_SETTINGS = 8101;
    private static final int MENU_REORDER = 8102;
    /** {@link UsageProvider#id} of the tab to open, e.g. from a Claude Code widget. */
    public static final String EXTRA_PROVIDER = "provider";
    /** With {@code start_sign_in}: which provider to sign in to (Settings → Accounts). */
    public static final String EXTRA_SIGN_IN_PROVIDER = "sign_in_provider";
    private String appliedTheme;
    private boolean appliedMaterialYou;
    private LinearLayout content;
    private SwipeRefreshLayout swipeRefresh;
    private boolean dark;
    private boolean receiverRegistered;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastLaunchedAuthUrl = "";
    private boolean launchSignInRequested;
    private UsageProvider launchSignInProvider = UsageProvider.CODEX;
    /** Provider whose usage the dashboard shows; switched from the One UI bottom tabs. */
    private UsageProvider provider = UsageProvider.DEFAULT;
    private BottomTabLayout providerTabs;
    /** Menu slots in {@code res/menu/provider_tabs.xml}, filled in tab order. */
    private static final int[] TAB_SLOTS = {
            R.id.provider_tab_0, R.id.provider_tab_1, R.id.provider_tab_2, R.id.provider_tab_3,
            R.id.provider_tab_4, R.id.provider_tab_5, R.id.provider_tab_6, R.id.provider_tab_7,
            R.id.provider_tab_8, R.id.provider_tab_9, R.id.provider_tab_10, R.id.provider_tab_11,
            R.id.provider_tab_12, R.id.provider_tab_13, R.id.provider_tab_14,
            R.id.provider_tab_15, R.id.provider_tab_16, R.id.provider_tab_17,
            R.id.provider_tab_18, R.id.provider_tab_19, R.id.provider_tab_20,
            R.id.provider_tab_21, R.id.provider_tab_22, R.id.provider_tab_23};
    /** Providers currently shown in the tab slots, in order. */
    private List<UsageProvider> tabProviders = new ArrayList<>();
    /** Slots still in the tab menu; the layout inflates all of them. */
    private int menuSlots = TAB_SLOTS.length;
    private Ui.Header header;
    /** One UI 9 design: SESL9's floating tab bar instead of the stock tabs. */
    private OneUi9ProviderBar oneUi9Bar;
    private final BroadcastReceiver authReceiver = new BroadcastReceiver() { // from class: dev.bennett.codexmeter.MainActivity.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (AppConstants.ACTION_OAUTH_READY.equals(action)) {
                String stringExtra = intent.getStringExtra(AppConstants.EXTRA_AUTH_URL);
                if (stringExtra != null && !stringExtra.isEmpty()) {
                    MainActivity.this.openAuthUrl(stringExtra);
                    return;
                }
                return;
            }
            if (AppConstants.ACTION_OAUTH_RESULT.equals(action)) {
                boolean booleanExtra = intent.getBooleanExtra(AppConstants.EXTRA_SUCCESS, false);
                String stringExtra2 = intent.getStringExtra(AppConstants.EXTRA_MESSAGE);
                MainActivity mainActivity = MainActivity.this;
                if (stringExtra2 == null) {
                    stringExtra2 = booleanExtra ? "Signed in." : "Sign-in failed.";
                }
                Toast.makeText(mainActivity, stringExtra2, Toast.LENGTH_LONG).show();
                PhoneWearSync.pushAll(MainActivity.this);
                MainActivity.this.rebuild();
                return;
            }
            if (AppConstants.ACTION_USAGE_UPDATED.equals(action) || AppConstants.ACTION_RESET_CREDITS_UPDATED.equals(action)) {
                MainActivity.this.rebuild();
                return;
            }
            if (AppConstants.ACTION_RELEASES_UPDATED.equals(action)) {
                MainActivity.this.rebuild();
            }
        }
    };

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        this.appliedTheme = AppPreferences.getAppTheme(this);
        this.appliedMaterialYou = AppPreferences.isMaterialYouEnabled(this);
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        PhoneWearSync.pushAll(this);
        if (routeToOnboarding(getIntent())) {
            return;
        }
        this.dark = Ui.isDark(this);
        Ui.Page page = Ui.installPage(this, "Codex Meter", false,
                R.layout.activity_main_dashboard);
        this.content = page.content;
        this.header = page.header;
        if (page.oneUi9 != null) {
            this.oneUi9Bar = new OneUi9ProviderBar(page.oneUi9, this::selectProvider);
        }
        this.provider = ProviderPreferences.getSelected(this);
        setUpProviderTabs();
        this.swipeRefresh = findViewById(R.id.dashboard_refresh);
        int refreshAccent = Ui.accent(this, this.dark);
        // OneUI's four-dot SwipeRefresh drawable indexes two palette entries while drawing.
        this.swipeRefresh.setColorSchemeColors(refreshAccent, refreshAccent);
        this.swipeRefresh.setProgressBackgroundColorSchemeColor(Ui.cardColor(this, this.dark));
        this.swipeRefresh.setOnRefreshListener(this::refreshFromPull);
        handleLaunchIntent(getIntent());
        WidgetUpgradeRepair.runIfNeeded(this);
        rebuild();
        RefreshScheduler.schedulePeriodic(this);
        ReleaseUpdateScheduler.ensureScheduled(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_REORDER, 0, "Edit dashboard")
                .setIcon(R.drawable.ic_oui_edit_outline)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_SETTINGS, 1, "Settings")
                .setIcon(R.drawable.ic_oui_settings_outline)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            DiagnosticLog.info(this, "user", "settings_opened");
            Ui.startSecondaryActivity(this, SettingsActivity.class);
            return true;
        }
        if (item.getItemId() == MENU_REORDER) {
            DiagnosticLog.info(this, "user", "dashboard_editor_opened");
            Ui.startSecondaryActivity(this, new Intent(this, DashboardReorderActivity.class)
                    .putExtra(DashboardReorderActivity.EXTRA_PROVIDER, this.provider.id));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (routeToOnboarding(intent)) {
            return;
        }
        handleLaunchIntent(intent);
        rebuild();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        String appTheme = AppPreferences.getAppTheme(this);
        boolean zIsDark = Ui.isDark(this);
        boolean materialYou = AppPreferences.isMaterialYouEnabled(this);
        if (!appTheme.equals(this.appliedTheme) || zIsDark != this.dark
                || materialYou != this.appliedMaterialYou) {
            recreate();
        } else {
            applyEnabledProviders();
            handleLaunchIntent(getIntent());
            rebuild();
        }
    }

    @Override // android.app.Activity
    @SuppressLint({"UnspecifiedRegisterReceiverFlag"})
    protected void onStart() {
        super.onStart();
        RefreshEngagement.onForeground(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AppConstants.ACTION_OAUTH_READY);
        intentFilter.addAction(AppConstants.ACTION_OAUTH_RESULT);
        intentFilter.addAction(AppConstants.ACTION_USAGE_UPDATED);
        intentFilter.addAction(AppConstants.ACTION_RESET_CREDITS_UPDATED);
        intentFilter.addAction(AppConstants.ACTION_RELEASES_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(this.authReceiver, intentFilter, "dev.bennett.codexmeter.permission.INTERNAL", null, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(this.authReceiver, intentFilter, "dev.bennett.codexmeter.permission.INTERNAL", null);
            }
            this.receiverRegistered = true;
        } catch (RuntimeException e) {
            this.receiverRegistered = false;
            AppPreferences.setSchedulerError(this, "App update receiver: " + safeMessage(e));
        }
        rebuild();
        if (this.launchSignInRequested) {
            this.launchSignInRequested = false;
            startOrContinueSignIn(this.launchSignInProvider);
        }
        refreshSelectedProviderIfStale();
        if (SecureTokenStore.isSignedIn(this)) {
            if (ProviderPreferences.getPendingSignIn(this) == UsageProvider.CODEX) {
                AppPreferences.setOAuthPending(this, false, "");
            }
            UsageSnapshot usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(this);
            if (AppPreferences.getRefreshOnLaunch(this)
                    && (usageSnapshotLoadSnapshot == null || System.currentTimeMillis() - usageSnapshotLoadSnapshot.fetchedAtMillis > 300000)) {
                RefreshScheduler.scheduleImmediate(this);
            }
        }
    }

    @Override // android.app.Activity
    protected void onStop() {
        RefreshEngagement.onBackground(this);
        if (AppPreferences.getAutomaticRefresh(this)) {
            RefreshScheduler.schedulePeriodic(this);
        }
        if (this.receiverRegistered) {
            try {
                unregisterReceiver(this.authReceiver);
            } catch (RuntimeException e) {
            }
            this.receiverRegistered = false;
        }
        super.onStop();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        this.executor.shutdownNow();
        super.onDestroy();
    }

    private void handleLaunchIntent(Intent intent) {
        String requestedProvider = intent == null ? null
                : intent.getStringExtra(EXTRA_PROVIDER);
        if (requestedProvider != null) {
            intent.removeExtra(EXTRA_PROVIDER);
            UsageProvider requested = UsageProvider.fromId(requestedProvider);
            if (requested != this.provider
                    && ProviderPreferences.getEnabled(this).contains(requested)) {
                this.provider = requested;
                ProviderPreferences.setSelected(this, requested);
                if (this.providerTabs != null) {
                    this.providerTabs.setSelectedItem(tabId(requested));
                }
                if (this.oneUi9Bar != null) {
                    this.oneUi9Bar.update(ProviderPreferences.getEnabled(this), requested);
                }
            }
        }
        if (intent != null && intent.getBooleanExtra("start_sign_in", false)) {
            this.launchSignInRequested = true;
            this.launchSignInProvider = UsageProvider.fromId(
                    intent.getStringExtra(EXTRA_SIGN_IN_PROVIDER));
            intent.removeExtra("start_sign_in");
            intent.removeExtra(EXTRA_SIGN_IN_PROVIDER);
        }
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "codexmeter".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            if (SecureTokenStore.isSignedIn(this)) {
                AppPreferences.setOAuthPending(this, false, "");
                RefreshScheduler.scheduleImmediate(this);
            }
            intent.setData(null);
        }
    }

    private boolean routeToOnboarding(Intent intent) {
        boolean oauthReturn = isOAuthReturnIntent(intent);
        boolean anySignedIn = false;
        for (UsageProvider candidate : UsageProvider.values()) {
            anySignedIn |= SecureTokenStore.isSignedIn(this, candidate);
        }
        int action = OnboardingFlow.launchAction(
                AppPreferences.isOnboardingComplete(this),
                anySignedIn,
                oauthReturn,
                AppPreferences.getOnboardingStep(this) > OnboardingFlow.STEP_WELCOME);
        if (action == OnboardingFlow.LAUNCH_MAIN_AND_COMPLETE) {
            // Existing signed-in installs predate onboarding and should not be interrupted.
            AppPreferences.completeOnboarding(this);
            return false;
        }
        if (action != OnboardingFlow.LAUNCH_ONBOARDING) {
            return false;
        }
        if (oauthReturn) {
            if (SecureTokenStore.isSignedIn(this)) {
                AppPreferences.setOAuthPending(this, false, "");
            }
            intent.setData(null);
        }
        startActivity(new Intent(this, OnboardingActivity.class)
                .putExtra(OnboardingActivity.EXTRA_AUTH_RETURN, oauthReturn)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
        return true;
    }

    private static boolean isOAuthReturnIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        return data != null
                && "codexmeter".equals(data.getScheme())
                && "auth".equals(data.getHost())
                && data.getPath() != null
                && data.getPath().startsWith("/complete");
    }

    private void setUpProviderTabs() {
        this.providerTabs = findViewById(R.id.provider_tabs);
        if (this.providerTabs == null) {
            return;
        }
        applyEnabledProviders();
        this.providerTabs.setOnMenuItemClickListener(this::onProviderTabClicked);
    }

    /** Tabs and the "More" grid both land here. */
    private boolean onProviderTabClicked(MenuItem item) {
        UsageProvider selected = providerForTab(item.getItemId());
        if (selected != null) {
            selectProvider(selected);
            if (this.providerTabs != null) {
                this.providerTabs.setSelectedItem(item.getItemId());
            }
        }
        return true;
    }

    /**
     * Fills the tab slots with the providers turned on in Settings → Providers, in the order
     * chosen there, and hides the tabs entirely when only one is on. A tab turned off while
     * selected falls back to the first provider still on.
     *
     * <p>Up to four providers get a tab each. With five or more, One UI's BottomTabLayout keeps
     * the first three as tabs and moves the rest into its stock "More" grid
     * ({@link ProviderTabs}). It counts hidden menu items when deciding, so unused slots are
     * removed from the menu, and the menu is re-inflated when the set of providers changes.
     */
    private void applyEnabledProviders() {
        List<UsageProvider> enabled = ProviderPreferences.getEnabled(this);
        UsageProvider visible = UsageProvider.visibleSelection(this.provider, enabled);
        if (visible != this.provider) {
            this.provider = visible;
            ProviderPreferences.setSelected(this, visible);
        }
        if (this.oneUi9Bar != null) {
            this.oneUi9Bar.update(enabled, this.provider);
            return;
        }
        if (this.providerTabs == null) {
            return;
        }
        if (!enabled.equals(this.tabProviders)) {
            this.tabProviders = new ArrayList<>(enabled);
            int needed = Math.min(enabled.size(), TAB_SLOTS.length);
            if (this.menuSlots < needed) {
                // Removed slots can't come back one by one, so start from a full menu again.
                for (int slot = 0; slot < this.menuSlots; slot++) {
                    this.providerTabs.removeMenuItem(TAB_SLOTS[slot]);
                }
                this.providerTabs.inflateMenu(R.menu.provider_tabs, this::onProviderTabClicked);
                this.menuSlots = TAB_SLOTS.length;
            }
            for (int slot = 0; slot < needed; slot++) {
                MenuItem item = this.providerTabs.findMenuItem(TAB_SLOTS[slot]);
                UsageProvider tabProvider = enabled.get(slot);
                item.setTitle(tabProvider.displayName);
                item.setIcon(ProvidersActivity.iconRes(tabProvider));
            }
            for (int slot = this.menuSlots - 1; slot >= needed; slot--) {
                this.providerTabs.removeMenuItem(TAB_SLOTS[slot]);
            }
            this.menuSlots = needed;
        }
        this.providerTabs.setSelectedItem(tabId(this.provider));
        this.providerTabs.setVisibility(ProviderTabs.showsBar(enabled.size())
                ? View.VISIBLE : View.GONE);
    }

    /** Tab slot showing {@code provider}; slots follow the order from Settings → Providers. */
    private int tabId(UsageProvider provider) {
        int index = this.tabProviders.indexOf(provider);
        return TAB_SLOTS[Math.max(0, Math.min(index, TAB_SLOTS.length - 1))];
    }

    private UsageProvider providerForTab(int itemId) {
        for (int slot = 0; slot < TAB_SLOTS.length && slot < this.tabProviders.size(); slot++) {
            if (TAB_SLOTS[slot] == itemId) {
                return this.tabProviders.get(slot);
            }
        }
        return null;
    }

    private void selectProvider(UsageProvider selected) {
        if (selected == this.provider) {
            return;
        }
        DiagnosticLog.info(this, "user", "provider_selected", "provider", selected.id);
        this.provider = selected;
        ProviderPreferences.setSelected(this, selected);
        if (this.swipeRefresh != null) {
            this.swipeRefresh.setRefreshing(false);
        }
        rebuild();
        refreshSelectedProviderIfStale();
    }

    /**
     * Claude usage is never fetched in the background (its usage route rate-limits hard), so the
     * tab refreshes itself when opened with data older than a few minutes.
     */
    private void refreshSelectedProviderIfStale() {
        if (this.provider == UsageProvider.CLAUDE_CODE
                && SecureTokenStore.isSignedIn(this, UsageProvider.CLAUDE_CODE)
                && AppPreferences.getRefreshOnLaunch(this)
                && ClaudeUsageApi.isStale(this)) {
            if (this.swipeRefresh != null) {
                this.swipeRefresh.setRefreshing(true);
            }
            refreshProvider(UsageProvider.CLAUDE_CODE, "tab");
        } else if (this.provider == UsageProvider.CURSOR
                && SecureTokenStore.isSignedIn(this, UsageProvider.CURSOR)
                && AppPreferences.getRefreshOnLaunch(this)
                && CursorUsageApi.isStale(this)) {
            if (this.swipeRefresh != null) {
                this.swipeRefresh.setRefreshing(true);
            }
            refreshProvider(UsageProvider.CURSOR, "tab");
        } else if (ProviderUsageApi.handles(this.provider)
                && SecureTokenStore.isSignedIn(this, this.provider)
                && AppPreferences.getRefreshOnLaunch(this)
                && ProviderUsageApi.isStale(this, this.provider)) {
            if (this.swipeRefresh != null) {
                this.swipeRefresh.setRefreshing(true);
            }
            refreshProvider(this.provider, "tab");
        }
    }

    /** The large title names the selected provider ("Codex", "Cursor"), matching iOS. */
    private void applyProviderTitle() {
        if (this.header == null) {
            return;
        }
        String title = this.provider.displayName;
        CharSequence current = this.header.getTitle();
        if (current == null || !title.contentEquals(current)) {
            this.header.setTitle(title);
        }
    }

    public void rebuild() {
        applyProviderTitle();
        if (this.content != null && this.provider != UsageProvider.CODEX) {
            this.content.removeAllViews();
            buildProviderDashboard(this.provider);
            return;
        }
        if (this.content != null) {
            this.content.removeAllViews();
            GitHubRelease update = UpdatePreferences.availableUpdate(this);
            if (update != null) {
                this.content.addView(buildUpdateCard(update));
                Ui.addSpacer(this.content, 20);
            }
            LinearLayout dashboard = buildUsageDashboard();
            if (dashboard.getChildCount() > 0) {
                this.content.addView(dashboard);
                Ui.addSpacer(this.content, 20);
            }
            boolean signedIn = SecureTokenStore.isSignedIn(this);
            if (!signedIn) {
                Button signIn = Ui.nativePrimaryButton(this,
                        ProviderPreferences.isSignInPending(this, UsageProvider.CODEX)
                                ? "Continue sign-in" : "Sign in with ChatGPT");
                signIn.setOnClickListener(view -> startOrContinueSignIn());
                this.content.addView(signIn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
                Ui.addSpacer(this.content, 20);
            }
            if (signedIn && dashboard.getChildCount() == 0) {
                TextView empty = Ui.text(this,
                        "No dashboard items are available. Refresh usage or choose items in "
                                + "Settings → Refresh & dashboard → Edit dashboard.",
                        14.0f, Ui.secondaryText(this.dark));
                empty.setGravity(Gravity.CENTER);
                this.content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }

    private LinearLayout buildUpdateCard(GitHubRelease release) {
        boolean returnToStable = UpdateChannel.isReturnToStable(release,
                UpdatePreferences.installedVersion(this));
        LinearLayout card = Ui.card(this, this.dark);
        TextView title = Ui.text(this, returnToStable
                        ? "Return to Codex Meter " + release.version
                        : "Codex Meter " + release.version + " is ready", 18,
                Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        TextView summary = Ui.text(this,
                returnToStable
                        ? "You are back on the stable channel. The stable APK installs in place "
                        + "over this alpha build after checksum verification."
                        : release.prerelease
                        ? "A signed alpha release is available. The APK will be checksum-verified "
                        + "before Android asks you to approve installation."
                        : "A signed GitHub release is available. The APK will be checksum-verified "
                        + "before Android asks you to approve installation.",
                13, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.setMargins(0, Ui.dp(this, 7), 0, Ui.dp(this, 14));
        card.addView(summary, summaryParams);
        Button update = Ui.nativePrimaryButton(this, "Review update");
        update.setOnClickListener(view -> startActivity(new Intent(this, UpdateActivity.class)
                .putExtra(UpdateActivity.EXTRA_VERSION, release.version)));
        card.addView(update, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        return card;
    }

    private void addHeader() {
        TextView textViewText = Ui.text(this, "Your Codex allowance at a glance.", 15.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(Ui.dp(this, 4.0f), Ui.dp(this, 4.0f), 0, Ui.dp(this, 2.0f));
        this.content.addView(textViewText, layoutParams);
    }

    private LinearLayout buildUsageDashboard() {
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        boolean signedIn = SecureTokenStore.isSignedIn(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        if (!signedIn) {
            return column;
        }
        Map<String, List<UsageLimit>> limitsByKey = new LinkedHashMap<>();
        List<String> available = new ArrayList<>();
        if (snapshot != null) {
            for (UsageLimit limit : snapshot.additionalLimits) {
                String key = DashboardSections.limitKey(limit);
                List<UsageLimit> group = limitsByKey.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    limitsByKey.put(key, group);
                }
                group.add(limit);
            }
            if (AppPreferences.showDashboardFiveHour(this) && snapshot.fiveHour != null) {
                available.add(DashboardSections.FIVE_HOUR);
            }
            if (AppPreferences.showDashboardWeekly(this) && snapshot.weekly != null) {
                available.add(DashboardSections.WEEKLY);
            }
            if (AppPreferences.showDashboardMonthly(this) && snapshot.monthly != null) {
                available.add(DashboardSections.MONTHLY);
            }
            // Accounts without workspace spend controls never see this card.
            if (AppPreferences.showDashboardSpendControl(this) && snapshot.spendControl != null) {
                available.add(DashboardSections.SPEND_CONTROL);
            }
            if (AppPreferences.showDashboardAdditionalLimits(this)) {
                for (String key : limitsByKey.keySet()) {
                    if (!AppPreferences.isDashboardSectionHidden(this, key)) {
                        available.add(key);
                    }
                }
            }
            // Zero, near-zero, and negative balances are always hidden regardless of settings.
            if (AppPreferences.showDashboardUsageCredits(this) && snapshot.usageCredits != null
                    && snapshot.usageCredits.shouldDisplay()) {
                available.add(DashboardSections.USAGE_CREDITS);
            }
            // Usage history only appears once at least one window can feed a burn chart.
            if (AppPreferences.showDashboardUsageHistory(this)
                    && snapshot.fetchedAtMillis > 0L
                    && (snapshot.fiveHour != null || snapshot.weekly != null
                            || snapshot.monthly != null)) {
                available.add(DashboardSections.USAGE_HISTORY);
            }
        }
        // Zero available resets always hide the card, even when the Edit dashboard switch is on.
        if (AppPreferences.showDashboardResetCredits(this) && shouldShowResetCreditsCard(snapshot)) {
            available.add(DashboardSections.RESET_CREDITS);
        }
        boolean inverted = false;
        for (String key : DashboardSections.resolveOrder(
                AppPreferences.getDashboardOrder(this), available)) {
            if (DashboardSections.FIVE_HOUR.equals(key)) {
                addDashboardCard(column, buildMetricCard(
                        "5-hour", snapshot, snapshot.fiveHour, inverted));
                inverted = !inverted;
            } else if (DashboardSections.WEEKLY.equals(key)) {
                addDashboardCard(column, buildMetricCard(
                        "Weekly", snapshot, snapshot.weekly, inverted));
                inverted = !inverted;
            } else if (DashboardSections.MONTHLY.equals(key)) {
                addDashboardCard(column, buildMetricCard(
                        "Monthly", snapshot, snapshot.monthly, inverted));
                inverted = !inverted;
            } else if (DashboardSections.SPEND_CONTROL.equals(key)) {
                addDashboardCard(column, buildSpendControlCard(snapshot));
            } else if (DashboardSections.USAGE_CREDITS.equals(key)) {
                addDashboardCard(column, buildUsageCreditsCard(snapshot.usageCredits));
            } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                addDashboardCard(column, buildUsageHistoryCard(UsageProvider.CODEX));
            } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                addDashboardCard(column, buildResetCreditsCard(UsageProvider.CODEX));
            } else {
                List<UsageLimit> group = limitsByKey.get(key);
                if (group == null) {
                    continue;
                }
                for (UsageLimit limit : group) {
                    if (limit.primary != null) {
                        addDashboardCard(column, buildMetricCard(
                                limitTitle(limit) + " · " + cadenceLabel(limit.primary),
                                snapshot, limit.primary, inverted));
                        inverted = !inverted;
                    }
                    if (limit.secondary != null) {
                        addDashboardCard(column, buildMetricCard(
                                limitTitle(limit) + " · " + cadenceLabel(limit.secondary),
                                snapshot, limit.secondary, inverted));
                        inverted = !inverted;
                    }
                }
            }
        }
        return column;
    }

    private void addDashboardCard(LinearLayout column, View card) {
        if (column.getChildCount() > 0) {
            Ui.addSpacer(column, 20);
        }
        column.addView(card);
    }

    private LinearLayout buildMetricCard(String label, UsageSnapshot snapshot, UsageWindow window,
            boolean invertedWave) {
        LinearLayout card = Ui.card(this, this.dark);
        card.setPadding(0, 0, 0, 0);
        card.setMinimumHeight(Ui.dp(this, 103.0f));
        long now = System.currentTimeMillis();
        String reset = UsageFormat.reset(this, window, WidgetOptions.RESET_RELATIVE,
                snapshot.fetchedAtMillis, now);
        UsagePace.Assessment pace = UsagePacePreferences.assess(this, this.provider, snapshot,
                window, now);
        UsageWaveView wave = new UsageWaveView(this);
        wave.setUsage(label, reset, UsageFormat.estimatedRemaining(pace),
                window.remainingPercent(),
                window.windowSeconds >= 86_400L
                        ? R.drawable.ic_oui_calendar_week : R.drawable.ic_oui_time,
                invertedWave, pace.accelerated);
        card.addView(wave, new LinearLayout.LayoutParams(-1, Ui.dp(this, 103.0f)));
        return card;
    }

    private LinearLayout buildUsageHistoryCard(UsageProvider target) {
        LinearLayout card = Ui.card(this, this.dark);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 12));
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(this, target);
        long now = System.currentTimeMillis();
        TextView title = Ui.text(this, "Usage history", 18, Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        card.addView(title, titleParams);
        TextView detail = Ui.text(this,
                "On-device burn trends improve estimates as samples accumulate.",
                12, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.setMargins(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        card.addView(detail, detailParams);

        // Windows still waiting for usage data would only render a blank "Waiting for usage
        // data" chart, so they are dropped from the card until the provider reports them.
        // Longer windows come first and the 5-hour chart sits at the bottom.
        boolean hasCharts = false;
        if (snapshot != null && snapshot.fetchedAtMillis > 0L) {
            hasCharts |= addBurnChart(card, target, snapshot, "Weekly", snapshot.weekly,
                    UsageHistory.WEEKLY, now);
            hasCharts |= addBurnChart(card, target, snapshot, "Monthly", snapshot.monthly,
                    UsageHistory.MONTHLY, now);
            hasCharts |= addBurnChart(card, target, snapshot, "5-hour", snapshot.fiveHour,
                    UsageHistory.FIVE_HOUR, now);
        }

        if (!hasCharts) {
            TextView waiting = Ui.text(this,
                    "Charts appear once " + target.vendorName
                            + " reports your 5-hour, weekly, or monthly usage windows.",
                    12, Ui.secondaryText(this.dark));
            LinearLayout.LayoutParams waitingParams = new LinearLayout.LayoutParams(-1, -2);
            waitingParams.setMargins(Ui.dp(this, 10), Ui.dp(this, 8),
                    Ui.dp(this, 10), Ui.dp(this, 8));
            card.addView(waiting, waitingParams);
        }

        Button open = Ui.button(this, "View history", false, this.dark);
        open.setOnClickListener(view -> UsageHistoryActivity.open(this, target));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        openParams.setMargins(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), 0);
        card.addView(open, openParams);
        return card;
    }

    private boolean addBurnChart(LinearLayout card, UsageProvider target,
            UsageSnapshot snapshot, String label, UsageWindow window, String historyKind,
            long now) {
        if (window == null) return false;
        UsageBurnChartView chart = new UsageBurnChartView(this);
        chart.setData(label, window,
                ProviderPreferences.loadUsageHistory(this, target, historyKind),
                snapshot.fetchedAtMillis,
                UsagePacePreferences.assess(this, target, snapshot, window, now));
        card.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 126)));
        return true;
    }

    private LinearLayout buildUsageCreditsCard(UsageCredits credits) {
        LinearLayout card = Ui.card(this, this.dark);
        TextView title = Ui.text(this, "Usage credits", 18, Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        card.addView(buildIconDetailRow(R.drawable.ic_oui_credit_card_outline,
                usageCreditBalance(credits), usageCreditsSummary(credits)));
        return card;
    }

    /**
     * Workspace spend-control card: the per-member monthly credit allocation OpenAI reports under
     * {@code spend_control.individual_limit}. Distinct from purchased usage credits and banked
     * reset credits, and only built when the snapshot carries one.
     */
    private LinearLayout buildSpendControlCard(UsageSnapshot snapshot) {
        SpendControl control = snapshot.spendControl;
        long now = System.currentTimeMillis();
        LinearLayout card = Ui.card(this, this.dark);
        LinearLayout header = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Monthly credit limit", 18, Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        String percentUsed = UsageFormat.spendControlPercentUsed(control);
        if (!percentUsed.isEmpty()) {
            TextView percent = Ui.text(this, percentUsed, 14.0f, Ui.secondaryText(this.dark));
            percent.setTypeface(Ui.mediumTypeface(this));
            LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(-2, -2);
            percentParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
            header.addView(percent, percentParams);
        }
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));
        card.addView(buildIconDetailRow(R.drawable.ic_oui_calendar_month,
                UsageFormat.spendControlUsage(this, control),
                UsageFormat.spendControlRemaining(this, control),
                control.reached ? Ui.danger(this.dark) : Ui.secondaryText(this.dark)));

        int remaining = control.effectiveRemainingPercent();
        if (remaining >= 0) {
            ProgressBar bar = Ui.progress(this, this.dark);
            bar.setProgress(remaining);
            if (control.reached) {
                bar.setProgressTintList(ColorStateList.valueOf(Ui.danger(this.dark)));
            }
            LinearLayout.LayoutParams barParams =
                    (LinearLayout.LayoutParams) bar.getLayoutParams();
            barParams.setMargins(0, Ui.dp(this, 14), 0, 0);
            card.addView(bar, barParams);
        }

        String footer = control.sourceLabel();
        if (control.showsResetCountdown()) {
            footer += " · " + UsageFormat.resetAt(this,
                    control.effectiveResetAtMillis(snapshot.fetchedAtMillis),
                    WidgetOptions.RESET_BOTH, now);
        }
        TextView note = Ui.text(this, footer, 12, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        card.addView(note, noteParams);
        return card;
    }

    /** Left-aligned icon + value + summary row used inside the credit dashboard cards. */
    private LinearLayout buildIconDetailRow(int icon, String value, String summary) {
        return buildIconDetailRow(icon, value, summary, Ui.secondaryText(this.dark));
    }

    private LinearLayout buildIconDetailRow(int icon, String value, String summary,
            int summaryColor) {
        LinearLayout row = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(Ui.mainText(this.dark)));
        row.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView valueText = Ui.text(this, value, 17.0f, Ui.mainText(this.dark));
        valueText.setTypeface(Ui.mediumTypeface(this));
        labels.addView(valueText);
        if (summary != null && !summary.isEmpty()) {
            TextView summaryText = Ui.text(this, summary, 13.0f, summaryColor);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-2, -2);
            summaryParams.setMargins(0, Ui.dp(this, 2), 0, 0);
            labels.addView(summaryText, summaryParams);
        }
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelParams.setMargins(Ui.dp(this, 16), 0, 0, 0);
        row.addView(labels, labelParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        row.setLayoutParams(rowParams);
        return row;
    }

    private static String usageCreditBalance(UsageCredits credits) {
        if (credits.unlimited) {
            return "Unlimited";
        }
        if (credits.balance.isEmpty()) {
            return credits.hasCredits ? "Credits available" : "No purchased credits";
        }
        try {
            BigDecimal amount = new BigDecimal(credits.balance.replace(",", ""));
            NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
            format.setMaximumFractionDigits(2);
            return format.format(amount) + " credits";
        } catch (NumberFormatException ignored) {
            return credits.balance;
        }
    }

    private static String usageCreditsSummary(UsageCredits credits) {
        if (credits.unlimited) {
            return "Usage-credit balance is not capped";
        }
        if (credits.balance.isEmpty() && !credits.hasCredits) {
            return "Purchase credits in ChatGPT Codex";
        }
        return "Purchased Codex usage-credit balance";
    }

    private static String cadenceLabel(UsageWindow window) {
        long seconds = window.windowSeconds;
        if (seconds >= 432_000L && seconds <= 777_600L) {
            return "Weekly";
        }
        if (seconds >= 2_419_200L && seconds <= 2_678_400L) {
            return "Monthly";
        }
        if (seconds >= 10_800L && seconds <= 28_800L) {
            long hours = Math.max(1L, Math.round(seconds / 3600.0d));
            return hours + "-hour";
        }
        if (seconds == 86_400L) {
            return "Daily";
        }
        if (seconds % 86_400L == 0L) {
            long days = seconds / 86_400L;
            return days + "-day";
        }
        if (seconds % 3_600L == 0L) {
            long hours = seconds / 3_600L;
            return hours + "-hour";
        }
        return "Usage";
    }

    private static String limitTitle(UsageLimit limit) {
        if (limit.limitReached) {
            return limit.displayName() + " (limit reached)";
        }
        if (!limit.allowed) {
            return limit.displayName() + " (unavailable)";
        }
        return limit.displayName();
    }

    private LinearLayout buildUsageCard() {
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        linearLayoutCard.setPadding(Ui.dp(this, 20.0f), Ui.dp(this, 20.0f), Ui.dp(this, 20.0f), Ui.dp(this, 10.0f));
        AuthTokens tokens = SecureTokenStore.load(this);
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        boolean signedIn = tokens != null;

        LinearLayout account = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.codex_profile_avatar);
        Ui.makeAvatar(avatar);
        account.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 44.0f), Ui.dp(this, 44.0f)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        String titleText = signedIn ? "ChatGPT account" : "Not connected";
        TextView title = Ui.text(this, titleText, 18.0f, Ui.mainText(this.dark));
        title.setSingleLine(true);
        identity.addView(title);
        TextView subtitle = Ui.text(this, signedIn && !tokens.email.isEmpty() ? tokens.email : (signedIn ? "Connected" : "Sign in to view your usage"), 14.0f, Ui.secondaryText(this.dark));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(subtitle);
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        identityParams.setMargins(Ui.dp(this, 20.0f), 0, Ui.dp(this, 10.0f), 0);
        account.addView(identity, identityParams);
        if (signedIn && snapshot != null) {
            String plan = UsageFormat.planLabel(snapshot.planType);
            TextView badge = Ui.text(this, plan.isEmpty() ? "Codex" : plan, 14.0f, Ui.mainText(this.dark));
            badge.setTypeface(Ui.mediumTypeface(this));
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(Ui.dp(this, 13.0f), Ui.dp(this, 6.0f), Ui.dp(this, 13.0f), Ui.dp(this, 6.0f));
            badge.setBackground(Ui.pillBackground(this, this.dark));
            account.addView(badge);
        }
        linearLayoutCard.addView(account, new LinearLayout.LayoutParams(-1, Ui.dp(this, 55.0f)));

        View divider = new View(this);
        divider.setBackgroundColor(Ui.divider(this.dark));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1.0f));
        dividerParams.setMargins(0, Ui.dp(this, 10.0f), 0, Ui.dp(this, 10.0f));
        linearLayoutCard.addView(divider, dividerParams);

        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        if (!signedIn) {
            Button button = Ui.button(this, AppPreferences.isOAuthPending(this) ? "Continue sign-in" : "Sign in with ChatGPT", true, this.dark);
            button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.3
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.startOrContinueSignIn();
                }
            });
            linearLayoutHorizontal.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f));
        } else {
            final Button button2 = Ui.button(this, "Refresh", true, this.dark);
            button2.setCompoundDrawables(null, null, null, null);
            button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.4
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.refreshNow(button2);
                }
            });
            linearLayoutHorizontal.addView(button2, new LinearLayout.LayoutParams(0, Ui.dp(this, 60.0f), 1.0f));
            Button button3 = Ui.button(this, "Sign out", false, this.dark);
            button3.setCompoundDrawables(null, null, null, null);
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(0, Ui.dp(this, 60.0f), 1.0f);
            layoutParams4.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
            linearLayoutHorizontal.addView(button3, layoutParams4);
            button3.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.5
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    MainActivity.this.confirmSignOut();
                }
            });
        }
        linearLayoutCard.addView(linearLayoutHorizontal, new LinearLayout.LayoutParams(-1, Ui.dp(this, 74.0f)));
        return linearLayoutCard;
    }

    private void addUsageRow(LinearLayout linearLayout, String str, UsageWindow usageWindow) {
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 80);
        linearLayoutHorizontal.addView(Ui.text(this, str, 13.0f, Ui.secondaryText(this.dark)), new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView textViewText = Ui.text(this, usageWindow == null ? "Unavailable" : usageWindow.remainingPercent() + "% left", 20.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutHorizontal.addView(textViewText);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 4.0f), 0, Ui.dp(this, 6.0f));
        linearLayout.addView(linearLayoutHorizontal, layoutParams);
        ProgressBar progressBarProgress = Ui.progress(this, this.dark);
        progressBarProgress.setProgress(usageWindow == null ? 0 : usageWindow.remainingPercent());
        linearLayout.addView(progressBarProgress);
        View viewText = Ui.text(this, usageWindow == null ? "Reset time unavailable" : UsageFormat.reset(this, usageWindow, "both", System.currentTimeMillis()), 11.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, Ui.dp(this, 5.0f), 0, Ui.dp(this, 13.0f));
        linearLayout.addView(viewText, layoutParams2);
    }

    /** The Reset credits card, shared by Codex reset credits and Claude Code saved resets. */
    private LinearLayout buildResetCreditsCard(UsageProvider target) {
        boolean claude = target == UsageProvider.CLAUDE_CODE;
        boolean signedIn = SecureTokenStore.isSignedIn(this, target);
        long now = System.currentTimeMillis();
        ClaudeSavedResets saved = claude ? ProviderPreferences.loadSavedResets(this, target)
                : null;
        ResetCreditsSnapshot credits = claude
                ? (saved == null ? null : saved.toResetCreditsSnapshot(now))
                : AppPreferences.loadResetCredits(this);
        int available = credits == null ? 0 : credits.availableCount;
        long nextExpiry = credits == null ? 0L : credits.nextExpiryMillis(now);
        // Claude only: Anthropic can hold a reset until you reach a usage limit.
        boolean usableNow = !claude || (saved != null && saved.usableGrant(now) != null);

        LinearLayout card = Ui.card(this, this.dark);
        TextView title = Ui.text(this, "Reset credits", 18, Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        card.addView(buildIconDetailRow(R.drawable.ic_oui_battery,
                resetCreditsTitle(signedIn, available),
                resetCreditsSummary(signedIn, available, nextExpiry, now)));

        if (signedIn) {
            card.setOnClickListener(view -> openResetCredits(target));
            Button button = Ui.nativePrimaryButton(this,
                    available > 0 ? "Use 1 reset" : "No resets available");
            button.setEnabled(available > 0 && usableNow);
            button.setOnClickListener(view -> openResetCredits(target));
            LinearLayout.LayoutParams buttonParams =
                    new LinearLayout.LayoutParams(-1, Ui.dp(this, 60.0f));
            buttonParams.setMargins(0, Ui.dp(this, 16.0f), 0, 0);
            card.addView(button, buttonParams);
        }
        return card;
    }

    private static String resetCreditsTitle(boolean signedIn, int available) {
        if (!signedIn) {
            return "Reset credits";
        }
        if (available <= 0) {
            return "No resets available";
        }
        if (available == 1) {
            return "1 reset available";
        }
        return available + " resets available";
    }

    private String resetCreditsSummary(boolean signedIn, int available, long nextExpiry,
            long now) {
        if (!signedIn) {
            return "Sign in to view reset credits";
        }
        if (nextExpiry > 0L) {
            return "Next expires " + UsageFormat.absolute(this, nextExpiry, now)
                    + " · " + UsageFormat.relative(nextExpiry, now);
        }
        if (available > 0) {
            return "Expiration details unavailable";
        }
        return "Earn credits from ChatGPT Codex";
    }

    private void openResetCredits(UsageProvider target) {
        Ui.startSecondaryActivity(this, ResetCreditActivity.intent(this, target));
    }

    /**
     * Prefer the detailed reset-credits cache; fall back to the usage-endpoint summary count.
     * Unknown inventory never surfaces an empty card.
     */
    private boolean shouldShowResetCreditsCard(UsageSnapshot snapshot) {
        ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(this);
        if (credits != null) {
            return credits.shouldDisplay();
        }
        return snapshot != null
                && ResetCreditsSnapshot.shouldDisplayCount(snapshot.resetCreditsAvailable);
    }

    private LinearLayout buildWidgetCard() {
        String str;
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        int length = AppWidgetManager.getInstance(this).getAppWidgetIds(new ComponentName(this, (Class<?>) CodexUsageWidget.class)).length + SamsungLockWidgetSupport.countAll(this);
        if (length == 0) {
            str = "Add Codex Meter widgets";
        } else {
            str = length + " widget" + (length == 1 ? "" : "s") + " active";
        }
        TextView textViewText = Ui.text(this, str, 16.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        View viewText = Ui.text(this, "Home and Galaxy lock-screen widgets use two battery-style dials for 5-hour and weekly usage remaining, with One UI Home handling the native frame and blur.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 7.0f), 0, Ui.dp(this, 15.0f));
        linearLayoutCard.addView(viewText, layoutParams);
        LinearLayout linearLayoutHorizontal = Ui.horizontal(this, 16);
        Button button = Ui.button(this, "Add widget", true, this.dark);
        button.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.7
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.requestPinWidget();
            }
        });
        linearLayoutHorizontal.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f));
        Button button2 = Ui.button(this, "Customize", false, this.dark);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 50.0f), 1.0f);
        layoutParams2.setMargins(Ui.dp(this, 10.0f), 0, 0, 0);
        linearLayoutHorizontal.addView(button2, layoutParams2);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.8
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                Ui.startSecondaryActivity(MainActivity.this, SettingsActivity.class);
            }
        });
        linearLayoutCard.addView(linearLayoutHorizontal);
        return linearLayoutCard;
    }

    private LinearLayout buildOperationCard() {
        LinearLayout linearLayoutCard = Ui.card(this, this.dark);
        TextView textViewText = Ui.text(this, "Automatic refresh every " + AppPreferences.getRefreshMinutes(this) + " minutes", 15.0f, Ui.mainText(this.dark));
        textViewText.setTypeface(Ui.mediumTypeface(this));
        linearLayoutCard.addView(textViewText);
        TextView textViewText2 = Ui.text(this, "Manual refreshes run immediately. Scheduled work follows Android battery and network policy, and another update is requested after the next known reset. Cached values remain visible offline.", 13.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, Ui.dp(this, 7.0f), 0, 0);
        linearLayoutCard.addView(textViewText2, layoutParams);
        String schedulerError = AppPreferences.getSchedulerError(this);
        if (!schedulerError.isEmpty()) {
            TextView textViewText3 = Ui.text(this, schedulerError, 12.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams2.setMargins(0, Ui.dp(this, 10.0f), 0, 0);
            linearLayoutCard.addView(textViewText3, layoutParams2);
        }
        return linearLayoutCard;
    }

    public void startOrContinueSignIn() {
        startOrContinueSignIn(UsageProvider.CODEX);
    }

    public void startOrContinueSignIn(UsageProvider target) {
        String str;
        DiagnosticLog.info(this, "user", "sign_in_requested",
                "provider", target.id,
                "already_signed_in", SecureTokenStore.isSignedIn(this, target));
        if (SecureTokenStore.isSignedIn(this, target)) {
            if (ProviderPreferences.isSignInPending(this, target)) {
                AppPreferences.setOAuthPending(this, false, "");
            }
            rebuild();
            return;
        }
        if (ProviderCatalog.get(target).usesSignInScreen()) {
            SignInActivity.start(this, target);
            return;
        }
        try {
            startForegroundService(new Intent(this, (Class<?>) OAuthService.class)
                    .setAction(OAuthService.ACTION_START)
                    .putExtra(OAuthService.EXTRA_PROVIDER, target.id));
            if (ProviderPreferences.isSignInPending(this, target)) {
                str = "Resuming secure " + target.vendorName + " sign-in…";
            } else {
                str = "Opening secure " + target.vendorName + " sign-in…";
            }
            Toast.makeText(this, str, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            DiagnosticLog.error(this, "auth", "sign_in_service_start_failed", e);
            AppPreferences.setOAuthPending(this, false, "");
            Toast.makeText(this, "Could not start sign-in: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    public void openAuthUrl(String str) {
        if (!str.equals(this.lastLaunchedAuthUrl) || hasWindowFocus()) {
            this.lastLaunchedAuthUrl = str;
            try {
                startActivity(new Intent("android.intent.action.VIEW", Uri.parse(str)));
            } catch (RuntimeException e) {
                Toast.makeText(this, "No browser is available to complete sign-in.", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void refreshNow(Button button) {
        DiagnosticLog.info(this, "user", "manual_refresh_requested", "source", "button");
        button.setEnabled(false);
        button.setText(R.string.refreshing);
        final Context applicationContext = getApplicationContext();
        this.executor.execute(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.9
            @Override // java.lang.Runnable
            public void run() {
                try {
                    RefreshScheduler.scheduleAtNextReset(applicationContext, UsageApi.refreshAndCache(applicationContext));
                    WidgetRenderer.updateAll(applicationContext);
                    MainActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.9.1
                        @Override // java.lang.Runnable
                        public void run() {
                            DiagnosticLog.info(applicationContext, "user",
                                    "manual_refresh_finished", "source", "button");
                            Toast.makeText(MainActivity.this, "Usage updated.", Toast.LENGTH_SHORT).show();
                            MainActivity.this.rebuild();
                        }
                    });
                } catch (Exception e) {
                    DiagnosticLog.error(applicationContext, "user", "manual_refresh_failed", e,
                            "source", "button");
                    AppPreferences.setLastError(applicationContext, MainActivity.safeMessage(e));
                    WidgetRenderer.updateAll(applicationContext);
                    MainActivity.this.runOnUiThread(new Runnable() { // from class: dev.bennett.codexmeter.MainActivity.9.2
                        @Override // java.lang.Runnable
                        public void run() {
                            Toast.makeText(MainActivity.this, MainActivity.safeMessage(e), Toast.LENGTH_LONG).show();
                            MainActivity.this.rebuild();
                        }
                    });
                }
            }
        });
    }

    private void refreshFromPull() {
        if (this.provider != UsageProvider.CODEX) {
            if (!SecureTokenStore.isSignedIn(this, this.provider)) {
                this.swipeRefresh.setRefreshing(false);
                Toast.makeText(this, this.provider.signInLabel() + " to refresh usage.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            refreshProvider(this.provider, "pull");
            return;
        }
        DiagnosticLog.info(this, "user", "manual_refresh_requested", "source", "pull");
        if (!SecureTokenStore.isSignedIn(this)) {
            DiagnosticLog.warn(this, "user", "manual_refresh_rejected",
                    "source", "pull", "reason", "signed_out");
            this.swipeRefresh.setRefreshing(false);
            Toast.makeText(this, "Sign in from Settings to refresh usage.", Toast.LENGTH_SHORT).show();
            Ui.startSecondaryActivity(this, SettingsActivity.class);
            return;
        }
        final Context applicationContext = getApplicationContext();
        this.executor.execute(() -> {
            try {
                RefreshScheduler.scheduleAtNextReset(applicationContext, UsageApi.refreshAndCache(applicationContext));
                WidgetRenderer.updateAll(applicationContext);
                runOnUiThread(() -> {
                    DiagnosticLog.info(applicationContext, "user",
                            "manual_refresh_finished", "source", "pull");
                    this.swipeRefresh.setRefreshing(false);
                    rebuild();
                });
            } catch (Exception e) {
                DiagnosticLog.error(applicationContext, "user", "manual_refresh_failed", e,
                        "source", "pull");
                AppPreferences.setLastError(applicationContext, safeMessage(e));
                WidgetRenderer.updateAll(applicationContext);
                runOnUiThread(() -> {
                    this.swipeRefresh.setRefreshing(false);
                    Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
                    rebuild();
                });
            }
        });
    }

    public void confirmSignOut() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Sign out?").setMessage("This removes encrypted ChatGPT tokens and cached usage from this device.").setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).setPositiveButton("Sign out", new DialogInterface.OnClickListener() { // from class: dev.bennett.codexmeter.MainActivity.10
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.signOut();
            }
        }).create();
        dialog.show();
    }

    public void signOut() {
        ProviderAccounts.signOut(this, UsageProvider.CODEX);
        rebuild();
    }

    /**
     * Dashboard for providers other than Codex. It reuses the Codex metric cards; widgets,
     * Now Bar, Wear, alerts, and burn history remain Codex-only for now.
     */
    private void buildProviderDashboard(UsageProvider target) {
        GitHubRelease update = UpdatePreferences.availableUpdate(this);
        if (update != null) {
            this.content.addView(buildUpdateCard(update));
            Ui.addSpacer(this.content, 20);
        }
        boolean signedIn = SecureTokenStore.isSignedIn(this, target);
        if (!signedIn) {
            this.content.addView(buildProviderIntroCard(target));
            Ui.addSpacer(this.content, 20);
            Button signIn = Ui.nativePrimaryButton(this,
                    ProviderPreferences.isSignInPending(this, target)
                            ? "Continue sign-in" : target.signInLabel());
            signIn.setOnClickListener(view -> startOrContinueSignIn(target));
            this.content.addView(signIn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
            return;
        }
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(this, target);
        String error = ProviderPreferences.getLastError(this, target);
        if (!error.isEmpty()) {
            TextView errorText = Ui.text(this, error, 13.0f, Ui.danger(this.dark));
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-1, -2);
            errorParams.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 14));
            this.content.addView(errorText, errorParams);
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        boolean inverted = false;
        boolean anyUsage = false;
        if (snapshot != null) {
            Map<String, UsageLimit> limitsByKey = new LinkedHashMap<>();
            for (UsageLimit limit : snapshot.additionalLimits) {
                limitsByKey.put(DashboardSections.limitKey(limit), limit);
            }
            List<String> available = new ArrayList<>();
            List<String> sections = new ArrayList<>(
                    DashboardSections.providerSections(snapshot, null));
            // Saved resets hide whenever none are left, like Codex reset credits.
            ClaudeSavedResets savedResets = target == UsageProvider.CLAUDE_CODE
                    ? ProviderPreferences.loadSavedResets(this, target) : null;
            if (savedResets != null && savedResets.shouldDisplay(System.currentTimeMillis())) {
                sections.add(DashboardSections.RESET_CREDITS);
            }
            for (String key : sections) {
                if (DashboardSections.USAGE_HISTORY.equals(key)
                        && (snapshot.fetchedAtMillis <= 0L || (snapshot.fiveHour == null
                                && snapshot.weekly == null && snapshot.monthly == null))) {
                    continue;
                }
                if (ProviderPreferences.isDashboardSectionVisible(this, target, key)) {
                    available.add(key);
                }
            }
            anyUsage = snapshot.fiveHour != null || snapshot.weekly != null
                    || snapshot.monthly != null || !snapshot.additionalLimits.isEmpty()
                    || (snapshot.usageCredits != null && snapshot.usageCredits.shouldDisplay());
            for (String key : DashboardSections.resolveOrder(
                    ProviderPreferences.getDashboardOrder(this, target), available)) {
                if (DashboardSections.FIVE_HOUR.equals(key)) {
                    addDashboardCard(column, buildMetricCard(cadenceLabel(snapshot.fiveHour),
                            snapshot, snapshot.fiveHour, inverted));
                    inverted = !inverted;
                } else if (DashboardSections.WEEKLY.equals(key)) {
                    addDashboardCard(column, buildMetricCard("Weekly",
                            snapshot, snapshot.weekly, inverted));
                    inverted = !inverted;
                } else if (DashboardSections.MONTHLY.equals(key)) {
                    addDashboardCard(column, buildMetricCard("Monthly",
                            snapshot, snapshot.monthly, inverted));
                    inverted = !inverted;
                } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                    addDashboardCard(column, buildUsageHistoryCard(target));
                } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                    addDashboardCard(column, buildResetCreditsCard(target));
                } else {
                    UsageLimit limit = limitsByKey.get(key);
                    if (limit == null) continue;
                    // Cursor's pools all share the billing cycle, so the name says enough.
                    boolean cadence = target != UsageProvider.CURSOR;
                    if (limit.primary != null) {
                        addDashboardCard(column, buildMetricCard(cadence
                                        ? limitTitle(limit) + " · " + cadenceLabel(limit.primary)
                                        : limit.displayName(),
                                snapshot, limit.primary, inverted));
                        inverted = !inverted;
                    }
                    if (limit.secondary != null) {
                        addDashboardCard(column, buildMetricCard(cadence
                                        ? limitTitle(limit) + " · " + cadenceLabel(limit.secondary)
                                        : limit.displayName(),
                                snapshot, limit.secondary, inverted));
                        inverted = !inverted;
                    }
                }
            }
        }
        if (target == UsageProvider.CLAUDE_CODE && ClaudeUsageParser.noFiveHourLimit(snapshot)) {
            TextView note = Ui.text(this, ClaudeUsageParser.NO_FIVE_HOUR_LIMIT_NOTE, 14.0f,
                    Ui.secondaryText(this.dark));
            note.setGravity(Gravity.CENTER);
            note.setPadding(0, Ui.dp(this, 4.0f), 0, Ui.dp(this, 8.0f));
            column.addView(note, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!anyUsage) {
            TextView empty = Ui.text(this, "Pull down to load your " + target.displayName
                    + " usage.", 14.0f, Ui.secondaryText(this.dark));
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, -2);
            emptyParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            column.addView(empty, emptyParams);
        }
        if (snapshot != null && snapshot.usageCredits != null
                && snapshot.usageCredits.shouldDisplay()) {
            addDashboardCard(column, buildProviderBalanceCard(target, snapshot.usageCredits));
        }
        this.content.addView(column);
    }

    /** Balance card for providers that report credits or a dollar balance. */
    private LinearLayout buildProviderBalanceCard(UsageProvider target, UsageCredits credits) {
        LinearLayout card = Ui.card(this, this.dark);
        TextView title = Ui.text(this, "Balance", 18, Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        card.addView(buildIconDetailRow(R.drawable.ic_oui_credit_card_outline,
                credits.unlimited ? "Unlimited" : credits.balance,
                "Credit balance on your " + target.vendorName + " account"));
        return card;
    }

    private LinearLayout buildProviderIntroCard(UsageProvider target) {
        LinearLayout card = Ui.card(this, this.dark);
        TextView title = Ui.text(this, target.displayName, 18, Ui.mainText(this.dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        String limits = target == UsageProvider.CURSOR
                ? " usage and when it refreshes, plus any extra spend on top of your plan."
                : target == UsageProvider.CLAUDE_CODE
                        ? " 5-hour and weekly limits, plus any model-only allocation your plan "
                                + "has."
                        : ": " + ProviderCatalog.get(target).meters.toLowerCase(Locale.ROOT) + ".";
        TextView summary = Ui.text(this, "See your " + target.displayName
                        + limits + " Sign in with the "
                        + target.accountName + " account you use in " + target.displayName
                        + ". Your tokens stay encrypted on this device.",
                13, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.setMargins(0, Ui.dp(this, 7), 0, 0);
        card.addView(summary, summaryParams);
        return card;
    }

    private void refreshProvider(UsageProvider target, String source) {
        DiagnosticLog.info(this, "user", "manual_refresh_requested", "source", source,
                "provider", target.id);
        final Context applicationContext = getApplicationContext();
        this.executor.execute(() -> {
            String failure = null;
            try {
                if (target == UsageProvider.CLAUDE_CODE) {
                    ClaudeUsageApi.refreshAndCache(applicationContext);
                } else if (target == UsageProvider.CURSOR) {
                    CursorUsageApi.refreshAndCache(applicationContext);
                } else if (ProviderUsageApi.handles(target)) {
                    ProviderUsageApi.refreshAndCache(applicationContext, target);
                }
                WidgetRenderer.updateAll(applicationContext);
            } catch (Exception e) {
                failure = safeMessage(e);
            }
            final String message = failure;
            runOnUiThread(() -> {
                if (this.swipeRefresh != null) {
                    this.swipeRefresh.setRefreshing(false);
                }
                if (message != null && !"tab".equals(source)) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
                rebuild();
            });
        });
    }

    public void requestPinWidget() {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        ComponentName componentName = new ComponentName(this, (Class<?>) CodexUsageWidget.class);
        if (appWidgetManager.isRequestPinAppWidgetSupported()) {
            appWidgetManager.requestPinAppWidget(componentName, null, null);
            Toast.makeText(this, "Choose a size and place the widget on your home screen.", Toast.LENGTH_LONG).show();
        } else {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add from your launcher").setMessage("Long-press an empty area of the home screen, open Widgets, then choose Codex Meter.").setPositiveButton("OK", (DialogInterface.OnClickListener) null).create();
            dialog.show();
        }
    }

    public static String safeMessage(Exception exc) {
        String message = exc.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "The operation failed.";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String safeMessage(RuntimeException runtimeException) {
        String message = runtimeException.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return runtimeException.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
