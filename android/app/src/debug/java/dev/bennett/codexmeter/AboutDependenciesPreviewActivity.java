package dev.bennett.codexmeter;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/**
 * Debug-only preview of About dependency rows for visual checks without the
 * collapsing About chrome. Pass {@code force_dark=true|false} to pin theme.
 */
public final class AboutDependenciesPreviewActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle bundle) {
        if (getIntent().hasExtra("force_dark")) {
            boolean forceDark = getIntent().getBooleanExtra("force_dark", true);
            getDelegate().setLocalNightMode(forceDark
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
            AppPreferences.setAppTheme(this,
                    forceDark ? WidgetOptions.THEME_DARK : WidgetOptions.THEME_LIGHT);
        }
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        boolean dark = Ui.isDark(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(dark ? 0xFF000000 : 0xFFFCFCFF);

        RoundedLinearLayout dependencies = Ui.cardGroup(this, dark);
        CardItemView oneUi = Ui.actionRow(this, "One UI Design Library",
                "The library that makes this app so pretty.", R.drawable.ic_oui_theme,
                null);
        dependencies.addView(oneUi);
        CardItemView openAi = Ui.actionRow(this, "OpenAI API",
                "This app would be pretty useless without it.", R.drawable.ic_openai,
                null);
        openAi.setShowTopDivider(true);
        dependencies.addView(openAi);
        root.addView(dependencies);
        scroll.addView(root);
        setContentView(scroll);
    }
}
