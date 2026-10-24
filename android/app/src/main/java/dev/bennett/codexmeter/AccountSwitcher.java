package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;

/**
 * The dashboard's account button: an avatar for the active account that opens a list of every
 * account saved for this provider, with a row to add another.
 */
final class AccountSwitcher {
    interface Listener {
        void onSwitch(String accountId);

        void onAdd();

        void onRemove(String accountId);
    }

    private static final int[] AVATAR_COLORS = {
            Color.rgb(56, 125, 255), Color.rgb(0, 150, 136), Color.rgb(233, 101, 46),
            Color.rgb(142, 84, 233), Color.rgb(214, 51, 108), Color.rgb(76, 145, 64)};

    private AccountSwitcher() {
    }

    static int colorFor(String key) {
        int hash = key == null ? 0 : key.toLowerCase(java.util.Locale.ROOT).hashCode();
        return AVATAR_COLORS[Math.floorMod(hash, AVATAR_COLORS.length)];
    }

    static String initial(String label) {
        if (label == null) return "?";
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(Character.toUpperCase(c));
        }
        return "?";
    }

    /** Toolbar icon: the active account's initial on its color, with a count badge when > 1. */
    static Drawable toolbarIcon(Context context, String label, int accountCount, boolean dark) {
        AvatarDrawable icon = new AvatarDrawable(context, initial(label), colorFor(label), 26,
                accountCount > 1 ? accountCount : 0);
        icon.border.setColor(Ui.background(context, dark));
        return icon;
    }

    static void show(Context context, UsageProvider provider, boolean dark, Listener listener) {
        List<AccountVault.Account> accounts = AccountVault.list(context, provider);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4));
        AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < accounts.size(); i++) {
            AccountVault.Account account = accounts.get(i);
            String label = account.label(i);
            View row = row(context, dark,
                    new AvatarDrawable(context, initial(label), colorFor(label), 40, 0),
                    label, summary(account), account.active);
            if (!account.active) {
                row.setOnClickListener(v -> {
                    holder[0].dismiss();
                    listener.onSwitch(account.id);
                });
                row.setOnLongClickListener(v -> {
                    holder[0].dismiss();
                    new AlertDialog.Builder(context)
                            .setTitle("Remove " + label + "?")
                            .setMessage("Its saved sign-in and usage are removed from this "
                                    + "device. You can add it again later.")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Remove", (d, w) -> listener.onRemove(account.id))
                            .show();
                    return true;
                });
            } else {
                row.setOnClickListener(v -> holder[0].dismiss());
            }
            list.addView(row);
        }
        View add = row(context, dark, addIcon(context, dark), "Add another account",
                accounts.size() > 1 ? "Hold a saved account to remove it" : "", false);
        add.setOnClickListener(v -> {
            holder[0].dismiss();
            listener.onAdd();
        });
        list.addView(add);
        holder[0] = new AlertDialog.Builder(context)
                .setTitle(provider.displayName + " accounts")
                .setView(list)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0].show();
    }

    /** "Plus · 5-hour 63% left · Weekly 39% left" from the account's last refresh. */
    static String summary(AccountVault.Account account) {
        List<String> parts = new ArrayList<>();
        if (!account.plan.isEmpty()) parts.add(account.plan);
        UsageSnapshot s = account.snapshot;
        if (s != null) {
            addWindow(parts, s.fiveHour);
            addWindow(parts, s.weekly);
            addWindow(parts, s.monthly);
        }
        if (parts.isEmpty()) return account.active ? "Active" : "Saved";
        return TextUtils.join(" · ", parts);
    }

    private static void addWindow(List<String> parts, UsageWindow window) {
        if (window == null) return;
        String name = window.windowSeconds > 0 && window.windowSeconds <= 6L * 3600L ? "5-hour"
                : window.windowSeconds > 0 && window.windowSeconds <= 8L * 86400L ? "Weekly"
                : "Monthly";
        parts.add(name + " " + window.remainingPercent() + "% left");
    }

    static View row(Context context, boolean dark, Drawable icon, String title,
            String summary, boolean checked) {
        LinearLayout row = Ui.horizontal(context, Gravity.CENTER_VERTICAL);
        int h = Ui.dp(context, 24);
        row.setPadding(h, Ui.dp(context, 12), h, Ui.dp(context, 12));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple,
                true);
        row.setBackgroundResource(ripple.resourceId);
        ImageView avatar = new ImageView(context);
        avatar.setImageDrawable(icon);
        row.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(context, 40),
                Ui.dp(context, 40)));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(context, title, 16f, Ui.mainText(dark));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (checked) name.setTypeface(Ui.mediumTypeface(context));
        texts.addView(name);
        if (summary != null && !summary.isEmpty()) {
            TextView sub = Ui.text(context, summary, 13f, Ui.secondaryText(dark));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1f);
        textParams.setMargins(Ui.dp(context, 16), 0, Ui.dp(context, 8), 0);
        row.addView(texts, textParams);
        if (checked) {
            TextView mark = Ui.text(context, "✓", 20f, Ui.accent(context, dark));
            mark.setContentDescription("Active account");
            row.addView(mark);
        }
        return row;
    }

    static Drawable addIcon(Context context, boolean dark) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.controlSurface(context, dark));
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{circle,
                new GlyphDrawable(context, "+", Ui.mainText(dark), 24)});
    }

    /** A filled circle with a centered letter and an optional small count badge. */
    static final class AvatarDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String letter;
        private final int sizePx;
        private final int count;

        AvatarDrawable(Context context, String letter, int color, int sizeDp, int count) {
            this.letter = letter;
            this.sizePx = Ui.dp(context, sizeDp);
            this.count = count;
            fill.setColor(color);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            badge.setColor(Color.rgb(30, 30, 34));
            border.setColor(Color.WHITE);
            badgeText.setColor(Color.WHITE);
            badgeText.setTextAlign(Paint.Align.CENTER);
            badgeText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float size = Math.min(b.width(), b.height());
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = size / 2f;
            float inner = count > 0 ? r * 0.86f : r;
            canvas.drawCircle(cx, cy, inner, fill);
            text.setTextSize(inner * 1.05f);
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, text);
            if (count > 0) {
                float br = r * 0.42f;
                float bx = b.right - br;
                float by = b.bottom - br;
                canvas.drawCircle(bx, by, br + r * 0.1f, border);
                canvas.drawCircle(bx, by, br, badge);
                badgeText.setTextSize(br * 1.3f);
                Paint.FontMetrics bm = badgeText.getFontMetrics();
                canvas.drawText(String.valueOf(Math.min(count, 9)), bx,
                        by - (bm.ascent + bm.descent) / 2f, badgeText);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // Avatars keep their own colors; the toolbar tint must not flatten them.
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class GlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String glyph;
        private final int sizePx;

        GlyphDrawable(Context context, String glyph, int color, int sizeDp) {
            this.glyph = glyph;
            this.sizePx = Ui.dp(context, sizeDp);
            paint.setColor(color);
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setTextSize(Math.min(b.width(), b.height()) * 0.7f);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(glyph, b.exactCenterX(),
                    b.exactCenterY() - (fm.ascent + fm.descent) / 2f, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
