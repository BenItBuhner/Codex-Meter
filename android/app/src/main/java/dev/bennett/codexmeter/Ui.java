package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SeslProgressBar;
import androidx.appcompat.widget.Toolbar;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.ktx.ActivityKt;
import dev.oneuiproject.oneui.popover.PopOverOptions;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import dev.oneuiproject.oneui.widget.Separator;

/* JADX INFO: loaded from: classes.dex */
public final class Ui {
    public static final class Page {
        public final ToolbarLayout toolbar;
        public final Toolbar materialToolbar;
        public final LinearLayout content;
        private final AppCompatActivity activity;

        private Page(AppCompatActivity activity, ToolbarLayout toolbar, Toolbar materialToolbar,
                LinearLayout content) {
            this.activity = activity;
            this.toolbar = toolbar;
            this.materialToolbar = materialToolbar;
            this.content = content;
        }

        public void setTitle(String title) {
            if (toolbar != null) {
                toolbar.setTitle(title);
            } else if (materialToolbar != null) {
                materialToolbar.setTitle(title);
            }
        }

        public void setShowNavigationButtonAsBack(boolean show) {
            if (toolbar != null) {
                toolbar.setShowNavigationButtonAsBack(show);
            } else if (materialToolbar != null) {
                materialToolbar.setNavigationIcon(show
                        ? activity.getDrawable(R.drawable.ic_oui_back) : null);
                if (materialToolbar.getNavigationIcon() != null) {
                    materialToolbar.getNavigationIcon().setTint(mainText(activity, isDark(activity)));
                }
                materialToolbar.setNavigationOnClickListener(show ? view ->
                        activity.getOnBackPressedDispatcher().onBackPressed() : null);
            }
        }
    }

    public static final class ConfigPage {
        public final ToolbarLayout toolbar;
        public final Toolbar materialToolbar;
        public final LinearLayout content;
        public final FrameLayout preview;
        public final TextView cancel;
        public final TextView save;

        private ConfigPage(ToolbarLayout toolbar, Toolbar materialToolbar, LinearLayout content,
                FrameLayout preview, TextView cancel, TextView save) {
            this.toolbar = toolbar;
            this.materialToolbar = materialToolbar;
            this.content = content;
            this.preview = preview;
            this.cancel = cancel;
            this.save = save;
        }
    }

    private Ui() {
    }

    public static void applySelectedTheme(Activity activity) {
        String appTheme = AppPreferences.getAppTheme(activity);
        if (activity instanceof AppCompatActivity) {
            int mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (WidgetOptions.THEME_DARK.equals(appTheme)) {
                mode = AppCompatDelegate.MODE_NIGHT_YES;
            } else if (WidgetOptions.THEME_LIGHT.equals(appTheme)) {
                mode = AppCompatDelegate.MODE_NIGHT_NO;
            }
            ((AppCompatActivity) activity).getDelegate().setLocalNightMode(mode);
        }
        activity.setTheme(isOneUi(activity) ? R.style.AppTheme : R.style.AppThemeMaterial);
    }

    public static Page installPage(AppCompatActivity activity, String title, boolean back) {
        ViewGroup parent = activity.findViewById(android.R.id.content);
        boolean oneUi = isOneUi(activity);
        View root = LayoutInflater.from(activity).inflate(oneUi
                ? R.layout.activity_oneui_dashboard : R.layout.activity_material_dashboard,
                parent, false);
        ToolbarLayout toolbar = oneUi ? root.findViewById(R.id.toolbar_layout) : null;
        Toolbar materialToolbar = oneUi ? null : root.findViewById(R.id.material_toolbar);
        LinearLayout content = root.findViewById(R.id.dashboard_content);
        if (oneUi) {
            configureReachToolbar(toolbar, title, back);
        } else {
            configureMaterialToolbar(activity, materialToolbar, title, back);
            styleMaterialRoot(activity, root);
        }
        activity.setContentView(root);
        if (!oneUi) {
            configureSystemBars(activity, root, isDark(activity));
        }
        return new Page(activity, toolbar, materialToolbar, content);
    }

    public static void configureReachToolbar(ToolbarLayout toolbar, String title, boolean back) {
        toolbar.setTitle(title);
        toolbar.setShowNavigationButtonAsBack(back);
        // Force SESL to recalculate its responsive app-bar height after XML inflation.
        toolbar.setExpandable(false);
        toolbar.setExpandable(true);
        toolbar.setExpanded(true, false);
    }

    public static void startSecondaryActivity(Activity activity, Class<? extends Activity> activityClass) {
        Intent intent = new Intent(activity, activityClass);
        boolean largeOneUiDevice = Build.MANUFACTURER != null
                && "samsung".equalsIgnoreCase(Build.MANUFACTURER)
                && activity.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        if (largeOneUiDevice && isOneUi(activity)) {
            ActivityKt.startPopOverActivity(
                    activity,
                    intent,
                    PopOverOptions.Companion.centerRightAnchored(activity));
        } else {
            activity.startActivity(intent);
        }
    }

    public static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    public static ConfigPage installConfigPage(AppCompatActivity activity, String title) {
        ViewGroup parent = activity.findViewById(android.R.id.content);
        boolean oneUi = isOneUi(activity);
        View root = LayoutInflater.from(activity).inflate(oneUi
                ? R.layout.activity_widget_settings : R.layout.activity_material_widget_settings,
                parent, false);
        ToolbarLayout toolbar = oneUi ? root.findViewById(R.id.widget_settings_root) : null;
        Toolbar materialToolbar = oneUi ? null : root.findViewById(R.id.material_toolbar);
        LinearLayout content = root.findViewById(R.id.widget_settings_content);
        FrameLayout preview = root.findViewById(R.id.widget_preview_container);
        TextView cancel = root.findViewById(R.id.config_cancel);
        TextView save = root.findViewById(R.id.config_save);
        if (oneUi) {
            toolbar.setTitle(title);
            toolbar.setShowNavigationButtonAsBack(true);
        } else {
            configureMaterialToolbar(activity, materialToolbar, title, true);
            styleMaterialRoot(activity, root);
            boolean dark = isDark(activity);
            cancel.setTextColor(mainText(activity, dark));
            cancel.setTypeface(emphasizedTypeface(activity));
            save.setTextColor(onAccent(activity, dark));
            save.setTypeface(emphasizedTypeface(activity));
            save.setTextSize(16f);
            save.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 10));
            save.setBackground(shape(accent(activity, dark), dp(activity, 999)));
        }
        activity.setContentView(root);
        configureSystemBars(activity, root, isDark(activity));
        return new ConfigPage(toolbar, materialToolbar, content, preview, cancel, save);
    }

    public static boolean isDark(Context context) {
        String appTheme = AppPreferences.getAppTheme(context);
        if (WidgetOptions.THEME_DARK.equals(appTheme)) {
            return true;
        }
        return !WidgetOptions.THEME_LIGHT.equals(appTheme) && (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    public static boolean isOneUi(Context context) {
        return !AppDesignStyle.isMaterialExpressive(AppPreferences.getAppStyle(context));
    }

    public static int pageHorizontalPadding(Context context) {
        return dp(context, isOneUi(context) ? 24.0f : 16.0f);
    }

    public static int pageTopPadding(Context context) {
        return dp(context, 8.0f);
    }

    public static int background(Context context, boolean z) {
        if (isOneUi(context)) {
            return z ? Color.rgb(5, 6, 8) : Color.rgb(241, 241, 243);
        }
        // M3 surface — keep the canvas calm so containers can scream.
        return systemColor(context, z ? "system_neutral1_900" : "system_neutral1_10",
                z ? Color.rgb(20, 18, 24) : Color.rgb(254, 247, 255));
    }

    public static int background(boolean z) {
        return z ? Color.rgb(20, 18, 24) : Color.rgb(254, 247, 255);
    }

    public static int cardColor(Context context, boolean z) {
        if (!isOneUi(context)) {
            // surface-container-high — stronger lift than plain surface
            return systemColor(context, z ? "system_neutral1_800" : "system_neutral1_100",
                    z ? Color.rgb(36, 34, 40) : Color.rgb(245, 235, 247));
        }
        if (z) {
            return Color.rgb(22, 24, 28);
        }
        return Color.rgb(252, 252, 255);
    }

    public static int card(boolean z) {
        return z ? Color.rgb(36, 34, 40) : Color.rgb(245, 235, 247);
    }

    public static int controlSurface(Context context, boolean z) {
        if (isOneUi(context)) {
            return z ? Color.rgb(42, 44, 50) : Color.rgb(238, 238, 241);
        }
        return systemColor(context, z ? "system_neutral1_700" : "system_neutral2_100",
                z ? Color.rgb(54, 50, 60) : Color.rgb(236, 230, 240));
    }

    public static int mainText(boolean z) {
        return z ? Color.rgb(248, 248, 250) : Color.BLACK;
    }

    public static int mainText(Context context, boolean z) {
        if (isOneUi(context)) {
            return mainText(z);
        }
        return materialOnSurface(context, z);
    }

    public static int secondaryText(boolean z) {
        return z ? Color.rgb(183, 186, 194) : Color.rgb(132, 132, 135);
    }

    public static int secondaryText(Context context, boolean z) {
        if (isOneUi(context)) {
            return secondaryText(z);
        }
        return systemColor(context, z ? "system_neutral2_200" : "system_neutral2_600",
                z ? Color.rgb(202, 196, 208) : Color.rgb(89, 82, 96));
    }

    public static int divider(boolean z) {
        return z ? Color.rgb(55, 58, 64) : Color.rgb(228, 228, 228);
    }

    public static int accent(Context context, boolean z) {
        if (isOneUi(context)) {
            return z ? Color.rgb(92, 169, 255) : Color.rgb(56, 122, 255);
        }
        // Primary — punchy, not muted mint leftovers from the old Material pass.
        return systemColor(context, z ? "system_accent1_200" : "system_accent1_600",
                z ? Color.rgb(208, 188, 255) : Color.rgb(103, 80, 164));
    }

    public static int desaturatedAccent(Context context, boolean dark) {
        float[] hsv = new float[3];
        Color.colorToHSV(accent(context, dark), hsv);
        hsv[1] *= 0.72f;
        return Color.HSVToColor(hsv);
    }

    public static int accent(boolean z) {
        return z ? Color.rgb(117, 220, 179) : Color.rgb(0, 113, 83);
    }

    public static int onAccent(Context context, boolean z) {
        if (isOneUi(context)) {
            return Color.luminance(accent(context, z)) > 0.55f ? Color.BLACK : Color.WHITE;
        }
        return systemColor(context, z ? "system_accent1_800" : "system_accent1_0",
                z ? Color.rgb(56, 30, 114) : Color.WHITE);
    }

    public static int primaryContainer(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent1_700" : "system_accent1_100",
                dark ? Color.rgb(79, 55, 139) : Color.rgb(234, 221, 255));
    }

    public static int onPrimaryContainer(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent1_100" : "system_accent1_900",
                dark ? Color.rgb(234, 221, 255) : Color.rgb(33, 0, 93));
    }

    public static int secondaryContainer(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent2_700" : "system_accent2_100",
                dark ? Color.rgb(81, 69, 104) : Color.rgb(232, 222, 248));
    }

    public static int onSecondaryContainer(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent2_100" : "system_accent2_900",
                dark ? Color.rgb(232, 222, 248) : Color.rgb(33, 24, 50));
    }

    public static int tertiaryContainer(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent3_700" : "system_accent3_100",
                dark ? Color.rgb(99, 59, 72) : Color.rgb(255, 216, 228));
    }

    public static int onTertiaryContainer(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent3_100" : "system_accent3_900",
                dark ? Color.rgb(255, 216, 228) : Color.rgb(49, 17, 29));
    }

    public static int danger(boolean z) {
        return z ? Color.rgb(255, 177, 173) : Color.rgb(190, 35, 43);
    }

    public static int dp(Context context, float f) {
        return Math.round(context.getResources().getDisplayMetrics().density * f);
    }

    public static Typeface regularTypeface(Context context) {
        return Typeface.create(isOneUi(context) ? "sec" : "sans-serif", Typeface.NORMAL);
    }

    public static Typeface mediumTypeface(Context context) {
        return Typeface.create(isOneUi(context) ? "sec" : "sans-serif-medium", Typeface.BOLD);
    }

    /** Emphasized / display weight — the M3E hierarchy hammer. */
    public static Typeface emphasizedTypeface(Context context) {
        if (isOneUi(context)) {
            return Typeface.create("sec", Typeface.BOLD);
        }
        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        if (black != null) {
            return black;
        }
        return Typeface.create("sans-serif", Typeface.BOLD);
    }

    public static TextView text(Context context, String str, float f, int i) {
        TextView textView = new TextView(context);
        textView.setText(str);
        textView.setTextSize(f);
        textView.setTextColor(i);
        textView.setTypeface(regularTypeface(context));
        textView.setLineSpacing(0.0f, isOneUi(context) ? 1.12f : 1.1f);
        textView.setIncludeFontPadding(false);
        return textView;
    }

    public static TextView title(Context context, String str, boolean z) {
        boolean oneUi = isOneUi(context);
        TextView textViewText = text(context, str, oneUi ? 42.0f : 40.0f, mainText(context, z));
        textViewText.setTypeface(oneUi ? mediumTypeface(context) : emphasizedTypeface(context));
        textViewText.setLetterSpacing(oneUi ? -0.025f : -0.03f);
        return textViewText;
    }

    public static TextView sectionTitle(Context context, String str, boolean z) {
        boolean zIsOneUi = isOneUi(context);
        TextView textViewText = text(context, str, zIsOneUi ? 13.0f : 16.0f,
                zIsOneUi ? accent(context, z) : mainText(context, z));
        textViewText.setTypeface(zIsOneUi ? mediumTypeface(context) : emphasizedTypeface(context));
        if (zIsOneUi) {
            textViewText.setLetterSpacing(0.01f);
        } else {
            textViewText.setLetterSpacing(-0.01f);
        }
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(zIsOneUi ? dp(context, 4.0f) : 0,
                dp(context, zIsOneUi ? 30.0f : 24.0f), 0, dp(context, zIsOneUi ? 10.0f : 12.0f));
        textViewText.setLayoutParams(layoutParams);
        return textViewText;
    }

    public static LinearLayout card(Context context, boolean z) {
        boolean zIsOneUi = isOneUi(context);
        // SESL RoundedLinearLayout paints its own One UI corners — never use it for M3E shapes.
        LinearLayout linearLayout = zIsOneUi ? new RoundedLinearLayout(context) : new LinearLayout(context);
        linearLayout.setOrientation(1);
        int i = zIsOneUi ? 22 : 24;
        int i2 = zIsOneUi ? 20 : 22;
        linearLayout.setPadding(dp(context, i), dp(context, i2), dp(context, i), dp(context, i2));
        linearLayout.setBackground(zIsOneUi
                ? shape(cardColor(context, z), dp(context, 28.0f))
                : expressiveShape(cardColor(context, z), expressiveRadii(context, 0)));
        linearLayout.setElevation(0.0f);
        linearLayout.setClipToOutline(true);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return linearLayout;
    }

    public static LinearLayout expressiveCard(Context context, boolean dark, int fillColor, int tone) {
        // Plain LinearLayout so GradientDrawable cornerRadii (incl. asymmetric) actually win.
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 24.0f), dp(context, 22.0f), dp(context, 24.0f),
                dp(context, 22.0f));
        card.setBackground(expressiveShape(fillColor, expressiveRadii(context, tone)));
        card.setElevation(0.0f);
        card.setClipToOutline(true);
        card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    public static RoundedLinearLayout seslCard(Context context, boolean dark) {
        RoundedLinearLayout card = new RoundedLinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18.0f), dp(context, 14.0f), dp(context, 18.0f), dp(context, 14.0f));
        boolean oneUi = isOneUi(context);
        card.setBackground(oneUi
                ? shape(cardColor(context, dark), dp(context, 28.0f))
                : expressiveShape(cardColor(context, dark), expressiveRadii(context, 0)));
        card.setClipToOutline(true);
        card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    public static RoundedLinearLayout cardGroup(Context context, boolean dark) {
        RoundedLinearLayout group = new RoundedLinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        boolean oneUi = isOneUi(context);
        group.setBackground(oneUi
                ? shape(cardColor(context, dark), dp(context, 28.0f))
                : expressiveShape(cardColor(context, dark), expressiveRadii(context, 1)));
        group.setClipToOutline(true);
        group.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    public static RoundedLinearLayout seslRowCard(Context context, boolean dark) {
        RoundedLinearLayout card = new RoundedLinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        boolean oneUi = isOneUi(context);
        card.setBackground(oneUi
                ? shape(cardColor(context, dark), dp(context, 28.0f))
                : expressiveShape(cardColor(context, dark), expressiveRadii(context, 2)));
        card.setClipToOutline(true);
        card.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    public static Separator separator(Context context, String title) {
        Separator separator = new Separator(context);
        separator.setText(title);
        return separator;
    }

    public static Button button(Context context, String str, boolean z, boolean z2) {
        int iArgb;
        boolean zIsOneUi = isOneUi(context);
        Button button = new AppCompatButton(context);
        button.setText(str);
        button.setAllCaps(false);
        button.setTextSize(zIsOneUi ? 18.0f : 17.0f);
        button.setTypeface(zIsOneUi ? mediumTypeface(context) : emphasizedTypeface(context));
        button.setGravity(17);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setLetterSpacing(zIsOneUi ? 0f : 0.01f);
        button.setMinHeight(dp(context, zIsOneUi ? 52.0f : 60.0f));
        button.setMinWidth(0);
        button.setPadding(dp(context, zIsOneUi ? 18.0f : 28.0f), dp(context, zIsOneUi ? 7.0f : 14.0f),
                dp(context, zIsOneUi ? 18.0f : 28.0f), dp(context, zIsOneUi ? 7.0f : 14.0f));
        // M3E XL buttons stay fully rounded; tonal buttons use a slightly squared full radius.
        int iDp = dp(context, zIsOneUi ? 999.0f : (z ? 999.0f : 28.0f));
        int iAccent = z ? accent(context, z2)
                : (zIsOneUi ? controlSurface(context, z2) : secondaryContainer(context, z2));
        int iOnAccent = z ? onAccent(context, z2)
                : (zIsOneUi ? mainText(z2) : onSecondaryContainer(context, z2));
        GradientDrawable gradientDrawableShape = shape(iAccent, iDp);
        if (z) {
            iArgb = Color.argb(42, 255, 255, 255);
        } else {
            iArgb = Color.argb(z2 ? 44 : 28, Color.red(mainText(context, z2)),
                    Color.green(mainText(context, z2)), Color.blue(mainText(context, z2)));
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(iArgb), gradientDrawableShape, null));
        button.setTextColor(iOnAccent);
        int icon = buttonIcon(str);
        if (icon != 0) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(context, zIsOneUi ? 8.0f : 10.0f));
            button.setCompoundDrawableTintList(ColorStateList.valueOf(iOnAccent));
        }
        button.setElevation(0.0f);
        button.setStateListAnimator(null);
        return button;
    }

    public static Button nativePrimaryButton(Context context, String text) {
        if (!isOneUi(context)) {
            Button material = button(context, text, true, isDark(context));
            material.setMinHeight(dp(context, 64.0f));
            material.setTextSize(18.0f);
            return material;
        }
        Button button = (Button) LayoutInflater.from(context).inflate(R.layout.view_oneui_primary_button, null, false);
        button.setText(text);
        boolean dark = isDark(context);
        int accent = accent(context, dark);
        int onAccent = onAccent(context, dark);
        int disabledAccent = Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent));
        int disabledText = Color.argb(150, Color.red(onAccent), Color.green(onAccent), Color.blue(onAccent));
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{disabledAccent, accent}));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{disabledText, onAccent}));
        return button;
    }

    public static Button topAction(Context context, String str, boolean z) {
        boolean zIsOneUi = isOneUi(context);
        Button button = new AppCompatButton(context);
        button.setText(str);
        button.setAllCaps(false);
        button.setTextSize(18.0f);
        button.setTextColor(mainText(z));
        button.setTypeface(mediumTypeface(context));
        button.setGravity(17);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, zIsOneUi ? 18.0f : 16.0f), 0, dp(context, zIsOneUi ? 18.0f : 16.0f), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(z ? 42 : 28, Color.red(mainText(z)), Color.green(mainText(z)), Color.blue(mainText(z)))), shape(zIsOneUi ? controlSurface(context, z) : cardColor(context, z), dp(context, 999.0f)), null));
        button.setElevation(0.0f);
        button.setStateListAnimator(null);
        return button;
    }

    public static GradientDrawable pillBackground(Context context, boolean dark) {
        return shape(Color.argb(dark ? 36 : 16, 0, 0, 0), dp(context, 8.0f));
    }

    public static void makeAvatar(ImageView imageView) {
        GradientDrawable outline = new GradientDrawable();
        outline.setShape(GradientDrawable.OVAL);
        outline.setColor(Color.TRANSPARENT);
        imageView.setBackground(outline);
        imageView.setClipToOutline(true);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    public static Button backAction(Context context, boolean z) {
        Button button = topAction(context, "", z);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_oui_back, 0, 0, 0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(mainText(z)));
        button.setPadding(dp(context, 12.0f), 0, dp(context, 12.0f), 0);
        return button;
    }

    private static int buttonIcon(String label) {
        String value = label == null ? "" : label.toLowerCase();
        if (value.contains("refresh")) return R.drawable.ic_oui_refresh;
        if (value.contains("sign in")) return R.drawable.ic_oui_samsung_account;
        if (value.contains("sign out")) return R.drawable.ic_oui_app_closed;
        if (value.contains("add widget")) return R.drawable.ic_oui_add_home;
        if (value.contains("customize") || value.contains("settings")) return R.drawable.ic_oui_settings;
        if (value.contains("save")) return R.drawable.ic_oui_save;
        if (value.contains("cancel") || value.contains("discard")) return R.drawable.ic_oui_close;
        if (value.contains("reset")) return R.drawable.ic_oui_battery;
        if (value.contains("alarm")) return R.drawable.ic_oui_alarm;
        return 0;
    }

    public static ProgressBar progress(Context context, boolean z) {
        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        boolean oneUi = isOneUi(context);
        if (oneUi) {
            progressBar.setProgressTintList(ColorStateList.valueOf(accent(context, z)));
            progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(controlSurface(context, z)));
            progressBar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(context, 7.0f)));
            return progressBar;
        }
        // Fat M3E track — glanceable from across the room.
        int height = dp(context, 16.0f);
        GradientDrawable track = shape(controlSurface(context, z), height);
        GradientDrawable fill = shape(accent(context, z), height);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(layer);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        return progressBar;
    }

    /** One UI circular indeterminate spinner, centered below the rounded content corners. */
    public static SeslProgressBar indeterminateLoading(Context context) {
        return indeterminateLoading(context, "Loading");
    }

    /**
     * One UI circular indeterminate spinner with an accessibility label.
     * Keeps the page free of clipped loading text while still announcing status to TalkBack.
     */
    public static SeslProgressBar indeterminateLoading(Context context, String description) {
        SeslProgressBar loading = new SeslProgressBar(context);
        loading.setIndeterminate(true);
        if (description != null && !description.isEmpty()) {
            loading.setContentDescription(description);
            loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        // Keep clear of ToolbarLayout's rounded top corners so the spinner is not clipped.
        params.topMargin = dp(context, 48.0f);
        loading.setLayoutParams(params);
        return loading;
    }

    public static Spinner spinner(final Context context, String[] strArr, final boolean z) {
        final boolean zIsOneUi = isOneUi(context);
        Spinner spinner = new AppCompatSpinner(context, 1);
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, strArr) { // from class: dev.bennett.codexmeter.Ui.1
            @Override // android.widget.ArrayAdapter, android.widget.Adapter
            public View getView(int i, View view, ViewGroup viewGroup) {
                return style((TextView) super.getView(i, view, viewGroup), false);
            }

            @Override // android.widget.ArrayAdapter, android.widget.BaseAdapter, android.widget.SpinnerAdapter
            public View getDropDownView(int i, View view, ViewGroup viewGroup) {
                return style((TextView) super.getDropDownView(i, view, viewGroup), true);
            }

            private TextView style(TextView textView, boolean z2) {
                textView.setTextColor((z2 || !zIsOneUi) ? Ui.mainText(z) : Ui.accent(context, z));
                textView.setTextSize(zIsOneUi ? 14.0f : 15.0f);
                textView.setTypeface(zIsOneUi ? Ui.mediumTypeface(context) : Ui.regularTypeface(context));
                textView.setIncludeFontPadding(false);
                textView.setGravity(((!zIsOneUi || z2) ? 8388611 : 8388613) | 16);
                textView.setPadding(Ui.dp(context, z2 ? 16.0f : 10.0f), Ui.dp(context, 12.0f), Ui.dp(context, z2 ? 16.0f : 10.0f), Ui.dp(context, 12.0f));
                if (z2) {
                    textView.setBackgroundColor(Ui.cardColor(context, z));
                } else {
                    textView.setBackgroundColor(0);
                }
                return textView;
            }
        });
        if (zIsOneUi) {
            spinner.setBackground(new ColorDrawable(0));
            spinner.setPopupBackgroundDrawable(shape(cardColor(context, z), dp(context, 18.0f)));
            spinner.setPadding(0, 0, 0, 0);
        } else {
            spinner.setBackground(shape(secondaryContainer(context, z), dp(context, 28.0f)));
            spinner.setPopupBackgroundDrawable(expressiveShape(cardColor(context, z),
                    expressiveRadii(context, 0)));
            spinner.setPadding(dp(context, 8.0f), 0, dp(context, 8.0f), 0);
        }
        spinner.setElevation(0.0f);
        return spinner;
    }

    public static void addLabeledSpinner(LinearLayout linearLayout, String str, Spinner spinner, boolean z) {
        Context context = linearLayout.getContext();
        if (isOneUi(context)) {
            LinearLayout linearLayoutHorizontal = horizontal(context, 16);
            linearLayoutHorizontal.setMinimumHeight(dp(context, 58.0f));
            TextView textViewText = text(context, str, 15.0f, mainText(z));
            textViewText.setTypeface(regularTypeface(context));
            linearLayoutHorizontal.addView(textViewText, new LinearLayout.LayoutParams(0, -2, 1.0f));
            linearLayoutHorizontal.addView(spinner, new LinearLayout.LayoutParams(dp(context, 168.0f), dp(context, 54.0f)));
            linearLayout.addView(linearLayoutHorizontal, new LinearLayout.LayoutParams(-1, -2));
            View view = new View(context);
            view.setBackgroundColor(divider(z));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, 1);
            layoutParams.setMargins(0, 0, 0, 0);
            linearLayout.addView(view, layoutParams);
            return;
        }
        TextView textViewText2 = text(context, str, 13.0f, secondaryText(z));
        textViewText2.setTypeface(mediumTypeface(context));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, dp(context, 17.0f), 0, dp(context, 8.0f));
        linearLayout.addView(textViewText2, layoutParams2);
        linearLayout.addView(spinner, new LinearLayout.LayoutParams(-1, dp(context, 54.0f)));
    }

    public static CheckBox checkbox(Context context, String str, boolean z, boolean z2) {
        CheckBox checkBox = new AppCompatCheckBox(context);
        checkBox.setText(str);
        checkBox.setChecked(z);
        checkBox.setTextSize(isOneUi(context) ? 15.0f : 14.0f);
        checkBox.setTextColor(mainText(z2));
        checkBox.setTypeface(regularTypeface(context));
        checkBox.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{accent(context, z2), secondaryText(z2)}));
        checkBox.setMinHeight(dp(context, isOneUi(context) ? 52.0f : 48.0f));
        checkBox.setPadding(0, dp(context, 4.0f), 0, dp(context, 4.0f));
        return checkBox;
    }

    public static void configureSystemBars(Activity activity, View view, boolean z) {
        Window window = activity.getWindow();
        int iBackground = background(activity, z);
        // Keep the bars tied to the selected app theme. Transparent bars can briefly expose the
        // previous activity window colour during a light/dark recreation on recent One UI builds.
        window.setStatusBarColor(iBackground);
        window.setNavigationBarColor(iBackground);
        window.setBackgroundDrawable(new ColorDrawable(iBackground));
        window.getDecorView().setBackgroundColor(iBackground);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setNavigationBarContrastEnforced(false);
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController insetsController = window.getInsetsController();
            if (insetsController != null) {
                insetsController.setSystemBarsAppearance(z ? 0 : 24, 24);
            }
            final int paddingLeft = view.getPaddingLeft();
            final int paddingTop = view.getPaddingTop();
            final int paddingRight = view.getPaddingRight();
            final int paddingBottom = view.getPaddingBottom();
            view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() { // from class: dev.bennett.codexmeter.Ui.2
                @Override // android.view.View.OnApplyWindowInsetsListener
                public WindowInsets onApplyWindowInsets(View view2, WindowInsets windowInsets) {
                    Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                    view2.setPadding(paddingLeft + insets.left, paddingTop + insets.top, paddingRight + insets.right, insets.bottom + paddingBottom);
                    return windowInsets;
                }
            });
            return;
        }
        int i = 256;
        if (!z) {
            i = 8464;
        }
        window.getDecorView().setSystemUiVisibility(i);
    }

    public static void configureMaterialToolbar(AppCompatActivity activity, Toolbar toolbar,
            String title, boolean back) {
        if (toolbar == null) {
            return;
        }
        boolean dark = isDark(activity);
        toolbar.setTitle(title);
        toolbar.setTitleTextAppearance(activity, R.style.MaterialToolbarTitle);
        // Appearance can clobber color — stamp on-surface after.
        toolbar.setTitleTextColor(mainText(activity, dark));
        toolbar.setSubtitleTextColor(secondaryText(activity, dark));
        toolbar.setBackgroundColor(background(activity, dark));
        toolbar.setNavigationIcon(back ? activity.getDrawable(R.drawable.ic_oui_back) : null);
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().setTint(mainText(activity, dark));
        }
        toolbar.setNavigationOnClickListener(back ? view ->
                activity.getOnBackPressedDispatcher().onBackPressed() : null);
        activity.setSupportActionBar(toolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
    }

    public static void styleMaterialRoot(Context context, View root) {
        boolean dark = isDark(context);
        root.setBackgroundColor(background(context, dark));
        View content = root.findViewById(R.id.dashboard_content);
        if (content != null) {
            content.setBackgroundColor(background(context, dark));
        }
    }

    public static int materialAccent(Context context, boolean dark) {
        return systemColor(context, dark ? "system_accent1_200" : "system_accent1_600",
                dark ? Color.rgb(208, 188, 255) : Color.rgb(103, 80, 164));
    }

    public static int materialSurface(Context context, boolean dark) {
        return systemColor(context, dark ? "system_neutral1_800" : "system_neutral1_50",
                dark ? Color.rgb(36, 34, 40) : Color.rgb(245, 235, 247));
    }

    public static int materialOnSurface(Context context, boolean dark) {
        return systemColor(context, dark ? "system_neutral1_50" : "system_neutral1_900",
                dark ? Color.rgb(230, 224, 233) : Color.rgb(29, 27, 32));
    }

    public static LinearLayout horizontal(Context context, int i) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(i);
        return linearLayout;
    }

    public static void addSpacer(LinearLayout linearLayout, int i) {
        linearLayout.addView(new View(linearLayout.getContext()), new LinearLayout.LayoutParams(1, dp(linearLayout.getContext(), i)));
    }

    public static CardItemView actionRow(Context context, String title, String summary, int icon, View.OnClickListener listener) {
        CardItemView row = new CardItemView(context);
        row.setTitle(title);
        row.setSummary(summary);
        if (icon != 0) {
            row.setIcon(context.getDrawable(icon));
        }
        row.setShowTopDivider(false);
        row.setShowBottomDivider(false);
        if (listener != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(listener);
        }
        return row;
    }

    private static GradientDrawable shape(int i, int i2) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(i2);
        return gradientDrawable;
    }

    public static GradientDrawable expressiveShape(int color, float[] radii) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(radii);
        return drawable;
    }

    /**
     * M3E shape scale: larger / asymmetric corners so containers feel intentional, not bland pills.
     * tone 0 = XL uniform, 1 = left-heavy, 2 = right-heavy.
     */
    public static float[] expressiveRadii(Context context, int tone) {
        // extraLargeIncreased-ish + hard asymmetry so shapes read as intentional M3E, not 28dp mush.
        float xl = dp(context, 48.0f);
        float lg = dp(context, 24.0f);
        float sm = dp(context, 12.0f);
        if (tone == 1) {
            return new float[]{xl, xl, sm, sm, xl, xl, lg, lg};
        }
        if (tone == 2) {
            return new float[]{sm, sm, xl, xl, lg, lg, xl, xl};
        }
        return new float[]{xl, xl, xl, xl, xl, xl, xl, xl};
    }

    private static int systemColor(Context context, String str, int i) {
        int identifier;
        if (Build.VERSION.SDK_INT >= 31 && (identifier = context.getResources().getIdentifier(str, "color", "android")) != 0) {
            try {
                return context.getColor(identifier);
            } catch (RuntimeException e) {
                return i;
            }
        }
        return i;
    }
}
