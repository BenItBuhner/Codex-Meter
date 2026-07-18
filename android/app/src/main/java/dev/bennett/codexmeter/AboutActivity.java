package dev.bennett.codexmeter;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import dev.oneuiproject.oneui.utils.EdgeToEdge;

public final class AboutActivity extends AppCompatActivity {
    private static final int MENU_GITHUB = 8201;
    private static final int MENU_APP_INFO = 8202;
    private boolean dark;

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        dark = Ui.isDark(this);
        if (!Ui.isOneUi(this)) {
            Ui.Page page = Ui.installPage(this, "About", true);
            page.content.addView(buildMaterialHero());
            buildSections(page.content);
            return;
        }
        EdgeToEdge.apply(this, () -> new kotlin.Pair<>(dark, dark));
        setContentView(R.layout.activity_about);
        applySystemBarInsets();
        setupToolbar();
        setupCollapsingContent();
        ((TextView) findViewById(R.id.about_header_version)).setText(getString(R.string.about_version, Ui.versionName(this)));
        build(findViewById(R.id.about_content));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_GITHUB, 0, "GitHub")
                .setIcon(R.drawable.ic_github_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_APP_INFO, 1, "App info")
                .setIcon(R.drawable.ic_oui_info_outline)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        if (item.getItemId() == MENU_GITHUB) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(GitHubReleaseSource.REPOSITORY_URL)));
            return true;
        }
        if (item.getItemId() == MENU_APP_INFO) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void build(LinearLayout content) {
        content.addView(buildAppCard());
        buildSections(content);
    }

    private void buildSections(LinearLayout content) {
        content.addView(sectionTitle("Credits"));
        RoundedLinearLayout credits = Ui.cardGroup(this, dark);
        credits.addView(personRow("BenIt Buhner", "App creator and AI geek", R.drawable.benit_github_avatar, false,
                "https://github.com/BenItBuhner"));
        credits.addView(personRow("That Josh Guy", "App and Icon designer", R.drawable.codex_profile_avatar, true,
                "https://tjg.gg"));
        content.addView(credits);

        content.addView(sectionTitle("Dependencies"));
        RoundedLinearLayout dependencies = Ui.cardGroup(this, dark);
        CardItemView oneUi = Ui.actionRow(this, "One UI Design Library",
                "The original Android design base, preserved as the default.",
                R.drawable.ic_oui_theme,
                view -> openUrl("https://github.com/tribalfs/oneui-design"));
        dependencies.addView(oneUi);
        CardItemView openAi = Ui.actionRow(this, "OpenAI API", "This app would be pretty useless without it.", R.drawable.ic_openai_figma,
                view -> openUrl("https://openai.com"));
        openAi.setIconSize(Ui.dp(this, 24));
        openAi.setShowTopDivider(true);
        dependencies.addView(openAi);
        content.addView(dependencies);
    }

    private LinearLayout buildMaterialHero() {
        LinearLayout hero = Ui.card(this, dark);
        hero.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        hero.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView title = Ui.title(this, "Codex Meter", dark);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(this, 20), 0, 0);
        hero.addView(title, titleParams);
        TextView subtitle = Ui.text(this,
                getString(R.string.about_version, Ui.versionName(this))
                        + " · Material 3 Expressive",
                14, Ui.secondaryText(dark));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        hero.addView(subtitle, subtitleParams);
        return hero;
    }

    private LinearLayout buildAppCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(this, "Codex Meter", 18, Ui.mainText(dark)));
        text.addView(Ui.text(this, getString(R.string.about_version, Ui.versionName(this)), 14, Ui.secondaryText(dark)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(Ui.dp(this, 20), 0, 0, 0);
        card.addView(text, params);
        return card;
    }

    private CardItemView personRow(String title, String summary, int avatar, boolean divider, String url) {
        CardItemView row = Ui.actionRow(this, title, summary, avatar, view -> openUrl(url));
        row.setIconSize(Ui.dp(this, 44));
        Ui.makeAvatar(row.getIconImageView());
        row.setShowTopDivider(divider);
        return row;
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.about_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        AppBarLayout appBar = findViewById(R.id.about_app_bar);
        appBar.setBackgroundColor(Color.TRANSPARENT);
        appBar.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        appBar.setElevation(0);
        appBar.setStateListAnimator(null);
        CollapsingToolbarLayout collapsing = findViewById(R.id.about_collapsing_toolbar);
        collapsing.setBackgroundColor(Color.TRANSPARENT);
        collapsing.setContentScrim(new ColorDrawable(Color.TRANSPARENT));
        collapsing.setStatusBarScrim(new ColorDrawable(Color.TRANSPARENT));
        toolbar.setBackgroundColor(Color.TRANSPARENT);
        toolbar.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        toolbar.setElevation(0);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.about_root);
        AppBarLayout appBar = findViewById(R.id.about_app_bar);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, 0, bars.right, 0);
            appBar.setPadding(0, bars.top, 0, 0);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setupCollapsingContent() {
        View content = findViewById(R.id.about_content);
        View hint = findViewById(R.id.about_swipe_hint);
        View fade = findViewById(R.id.about_gradient_fade);
        content.setAlpha(0);
        ((AppBarLayout) findViewById(R.id.about_app_bar)).addOnOffsetChangedListener((appBar, offset) -> {
            float range = Math.max(1, appBar.getTotalScrollRange());
            float progress = Math.abs(offset) / range;
            content.setAlpha(clamp((progress - 0.25f) / 0.55f));
            fade.setAlpha(clamp(progress * 2f));
            hint.setAlpha(clamp(1f - progress * 2f));
            hint.setVisibility(hint.getAlpha() == 0 ? View.INVISIBLE : View.VISIBLE);
        });
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private TextView sectionTitle(String title) {
        TextView label = Ui.text(this, title, 14, Ui.secondaryText(dark));
        label.setTypeface(Ui.mediumTypeface(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Ui.dp(this, 20), Ui.dp(this, 20), 0, Ui.dp(this, 10));
        label.setLayoutParams(params);
        return label;
    }
}
