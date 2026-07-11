package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

/* JADX INFO: loaded from: classes.dex */
public final class Ui {
    private Ui() {
    }

    public static void applySelectedTheme(Activity activity) {
        String appTheme = AppPreferences.getAppTheme(activity);
        if (!WidgetOptions.THEME_DARK.equals(appTheme)) {
            if (!WidgetOptions.THEME_LIGHT.equals(appTheme)) {
                activity.setTheme(R.style.AppTheme);
                return;
            } else {
                activity.setTheme(R.style.AppThemeLight);
                return;
            }
        }
        activity.setTheme(R.style.AppThemeDark);
    }

    public static boolean isDark(Context context) {
        String appTheme = AppPreferences.getAppTheme(context);
        if (WidgetOptions.THEME_DARK.equals(appTheme)) {
            return true;
        }
        return !WidgetOptions.THEME_LIGHT.equals(appTheme) && (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    public static boolean isOneUi(Context context) {
        return WidgetOptions.SURFACE_ONE_UI.equals(AppPreferences.getAppStyle(context));
    }

    public static int pageHorizontalPadding(Context context) {
        return dp(context, isOneUi(context) ? 24.0f : 20.0f);
    }

    public static int pageTopPadding(Context context) {
        return dp(context, isOneUi(context) ? 42.0f : 20.0f);
    }

    public static int background(Context context, boolean z) {
        if (isOneUi(context)) {
            return z ? Color.rgb(5, 6, 8) : Color.rgb(244, 244, 247);
        }
        return systemColor(context, z ? "system_neutral1_900" : "system_neutral1_10", z ? Color.rgb(18, 18, 22) : Color.rgb(249, 247, 251));
    }

    public static int background(boolean z) {
        return z ? Color.rgb(18, 18, 22) : Color.rgb(249, 247, 251);
    }

    public static int cardColor(Context context, boolean z) {
        if (!isOneUi(context)) {
            return systemColor(context, z ? "system_neutral1_800" : "system_neutral1_50", z ? Color.rgb(31, 30, 36) : Color.rgb(242, 239, 246));
        }
        if (z) {
            return Color.rgb(22, 24, 28);
        }
        return -1;
    }

    public static int card(boolean z) {
        return z ? Color.rgb(31, 30, 36) : Color.rgb(242, 239, 246);
    }

    public static int controlSurface(Context context, boolean z) {
        if (isOneUi(context)) {
            return z ? Color.rgb(42, 44, 50) : Color.rgb(234, 235, 239);
        }
        return systemColor(context, z ? "system_neutral1_700" : "system_neutral1_100", z ? Color.rgb(46, 44, 52) : Color.rgb(232, 228, 236));
    }

    public static int mainText(boolean z) {
        return z ? Color.rgb(248, 248, 250) : Color.rgb(24, 25, 28);
    }

    public static int secondaryText(boolean z) {
        return z ? Color.rgb(183, 186, 194) : Color.rgb(93, 96, 104);
    }

    public static int divider(boolean z) {
        return z ? Color.rgb(55, 58, 64) : Color.rgb(226, 227, 231);
    }

    public static int accent(Context context, boolean z) {
        if (isOneUi(context)) {
            return z ? Color.rgb(102, 164, 255) : Color.rgb(0, 105, 232);
        }
        return systemColor(context, z ? "system_accent1_200" : "system_accent1_600", z ? Color.rgb(117, 220, 179) : Color.rgb(0, 113, 83));
    }

    public static int accent(boolean z) {
        return z ? Color.rgb(117, 220, 179) : Color.rgb(0, 113, 83);
    }

    public static int onAccent(Context context, boolean z) {
        if (isOneUi(context) || !z) {
            return -1;
        }
        return Color.rgb(0, 55, 39);
    }

    public static int danger(boolean z) {
        return z ? Color.rgb(255, 177, 173) : Color.rgb(190, 35, 43);
    }

    public static int dp(Context context, float f) {
        return Math.round(context.getResources().getDisplayMetrics().density * f);
    }

    public static Typeface regularTypeface(Context context) {
        return Typeface.create(isOneUi(context) ? "sec" : "sans-serif", 0);
    }

    public static Typeface mediumTypeface(Context context) {
        return Typeface.create(isOneUi(context) ? "sec" : "sans-serif-medium", 1);
    }

    public static TextView text(Context context, String str, float f, int i) {
        TextView textView = new TextView(context);
        textView.setText(str);
        textView.setTextSize(f);
        textView.setTextColor(i);
        textView.setTypeface(regularTypeface(context));
        textView.setLineSpacing(0.0f, isOneUi(context) ? 1.12f : 1.14f);
        textView.setIncludeFontPadding(false);
        return textView;
    }

    public static TextView title(Context context, String str, boolean z) {
        TextView textViewText = text(context, str, isOneUi(context) ? 42.0f : 36.0f, mainText(z));
        textViewText.setTypeface(mediumTypeface(context));
        textViewText.setLetterSpacing(isOneUi(context) ? -0.025f : -0.02f);
        return textViewText;
    }

    public static TextView sectionTitle(Context context, String str, boolean z) {
        boolean zIsOneUi = isOneUi(context);
        TextView textViewText = text(context, str, zIsOneUi ? 13.0f : 15.0f, zIsOneUi ? accent(context, z) : mainText(z));
        textViewText.setTypeface(mediumTypeface(context));
        if (zIsOneUi) {
            textViewText.setLetterSpacing(0.01f);
        }
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(zIsOneUi ? dp(context, 4.0f) : 0, dp(context, zIsOneUi ? 30.0f : 28.0f), 0, dp(context, zIsOneUi ? 10.0f : 11.0f));
        textViewText.setLayoutParams(layoutParams);
        return textViewText;
    }

    public static LinearLayout card(Context context, boolean z) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(1);
        boolean zIsOneUi = isOneUi(context);
        int i = zIsOneUi ? 22 : 20;
        int i2 = zIsOneUi ? 20 : 19;
        linearLayout.setPadding(dp(context, i), dp(context, i2), dp(context, i), dp(context, i2));
        linearLayout.setBackground(shape(cardColor(context, z), dp(context, zIsOneUi ? 26.0f : 28.0f)));
        linearLayout.setElevation(0.0f);
        linearLayout.setClipToOutline(true);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return linearLayout;
    }

    public static Button button(Context context, String str, boolean z, boolean z2) {
        int iArgb;
        boolean zIsOneUi = isOneUi(context);
        Button button = new Button(context);
        button.setText(str);
        button.setAllCaps(false);
        button.setTextSize(zIsOneUi ? 15.0f : 14.0f);
        button.setTypeface(mediumTypeface(context));
        button.setGravity(17);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(context, zIsOneUi ? 52.0f : 52.0f));
        button.setMinWidth(0);
        button.setPadding(dp(context, zIsOneUi ? 18.0f : 20.0f), dp(context, 7.0f), dp(context, zIsOneUi ? 18.0f : 20.0f), dp(context, 7.0f));
        int iDp = dp(context, zIsOneUi ? 19.0f : 26.0f);
        int iAccent = z ? accent(context, z2) : controlSurface(context, z2);
        int iOnAccent = z ? onAccent(context, z2) : mainText(z2);
        GradientDrawable gradientDrawableShape = shape(iAccent, iDp);
        if (z) {
            iArgb = Color.argb(42, 255, 255, 255);
        } else {
            iArgb = Color.argb(z2 ? 44 : 28, Color.red(mainText(z2)), Color.green(mainText(z2)), Color.blue(mainText(z2)));
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(iArgb), gradientDrawableShape, null));
        button.setTextColor(iOnAccent);
        button.setElevation(0.0f);
        button.setStateListAnimator(null);
        return button;
    }

    public static Button topAction(Context context, String str, boolean z) {
        boolean zIsOneUi = isOneUi(context);
        Button button = new Button(context);
        button.setText(str);
        button.setAllCaps(false);
        button.setTextSize(zIsOneUi ? 15.0f : 14.0f);
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
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(z ? 42 : 28, Color.red(mainText(z)), Color.green(mainText(z)), Color.blue(mainText(z)))), shape(zIsOneUi ? controlSurface(context, z) : cardColor(context, z), dp(context, 24.0f)), null));
        button.setElevation(0.0f);
        button.setStateListAnimator(null);
        return button;
    }

    public static Button backAction(Context context, boolean z) {
        Button button = topAction(context, "‹", z);
        button.setTextSize(isOneUi(context) ? 32.0f : 28.0f);
        button.setTypeface(regularTypeface(context));
        button.setPadding(0, 0, 0, dp(context, 3.0f));
        return button;
    }

    public static ProgressBar progress(Context context, boolean z) {
        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(accent(context, z)));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(controlSurface(context, z)));
        progressBar.setIndeterminate(false);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(context, isOneUi(context) ? 7.0f : 8.0f)));
        return progressBar;
    }

    public static Spinner spinner(final Context context, String[] strArr, final boolean z) {
        final boolean zIsOneUi = isOneUi(context);
        Spinner spinner = new Spinner(context, 1);
        spinner.setAdapter((SpinnerAdapter) new ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, strArr) { // from class: dev.bennett.codexmeter.Ui.1
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
            spinner.setBackground(shape(controlSurface(context, z), dp(context, 20.0f)));
            spinner.setPopupBackgroundDrawable(shape(cardColor(context, z), dp(context, 18.0f)));
            spinner.setPadding(dp(context, 2.0f), 0, dp(context, 2.0f), 0);
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
        CheckBox checkBox = new CheckBox(context);
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
        window.setStatusBarColor(Build.VERSION.SDK_INT >= 30 ? 0 : iBackground);
        if (Build.VERSION.SDK_INT >= 30) {
            iBackground = 0;
        }
        window.setNavigationBarColor(iBackground);
        if (Build.VERSION.SDK_INT >= 30) {
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

    public static LinearLayout horizontal(Context context, int i) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(i);
        return linearLayout;
    }

    public static void addSpacer(LinearLayout linearLayout, int i) {
        linearLayout.addView(new View(linearLayout.getContext()), new LinearLayout.LayoutParams(1, dp(linearLayout.getContext(), i)));
    }

    private static GradientDrawable shape(int i, int i2) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(i2);
        return gradientDrawable;
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
