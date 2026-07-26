package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.TimeUnit;

/**
 * Stores a decaying attention score instead of an activity history. Recent opens and foreground
 * time influence automatic refresh without retaining analytics events.
 */
public final class RefreshEngagement {
    private static final long HALF_LIFE_MS = TimeUnit.HOURS.toMillis(6);
    private static final long OPEN_DEBOUNCE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long FOREGROUND_UNIT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String KEY_FOREGROUND_AT = "foreground_at";
    private static final String KEY_LAST_OPEN_AT = "last_open_at";
    private static final String KEY_SCORE = "score";
    private static final String KEY_SCORE_AT = "score_at";
    private static final String PREFS = "codex_meter_refresh_engagement_v1";

    private RefreshEngagement() {
    }

    public static synchronized void onForeground(Context context) {
        onForeground(context, System.currentTimeMillis());
    }

    static synchronized void onForeground(Context context, long nowMillis) {
        SharedPreferences prefs = prefs(context);
        double score = decayedScore(prefs, nowMillis);
        long lastOpen = prefs.getLong(KEY_LAST_OPEN_AT, 0L);
        SharedPreferences.Editor edit = prefs.edit();
        if (lastOpen <= 0L || nowMillis - lastOpen >= OPEN_DEBOUNCE_MS) {
            score += 1.0d;
            edit.putLong(KEY_LAST_OPEN_AT, nowMillis);
        }
        edit.putLong(KEY_FOREGROUND_AT, nowMillis)
                .putLong(KEY_SCORE_AT, nowMillis)
                .putLong(KEY_SCORE, Double.doubleToRawLongBits(score))
                .apply();
    }

    public static synchronized void onBackground(Context context) {
        onBackground(context, System.currentTimeMillis());
    }

    static synchronized void onBackground(Context context, long nowMillis) {
        SharedPreferences prefs = prefs(context);
        long foregroundAt = prefs.getLong(KEY_FOREGROUND_AT, 0L);
        double score = decayedScore(prefs, nowMillis);
        if (foregroundAt > 0L && nowMillis > foregroundAt) {
            score += Math.min(6.0d, (double) (nowMillis - foregroundAt) / FOREGROUND_UNIT_MS);
        }
        prefs.edit()
                .remove(KEY_FOREGROUND_AT)
                .putLong(KEY_SCORE_AT, nowMillis)
                .putLong(KEY_SCORE, Double.doubleToRawLongBits(score))
                .apply();
    }

    public static synchronized double score(Context context, long nowMillis) {
        SharedPreferences prefs = prefs(context);
        double score = decayedScore(prefs, nowMillis);
        long foregroundAt = prefs.getLong(KEY_FOREGROUND_AT, 0L);
        if (foregroundAt > 0L && nowMillis > foregroundAt) {
            score += Math.min(6.0d, (double) (nowMillis - foregroundAt) / FOREGROUND_UNIT_MS);
        }
        return Math.max(0.0d, score);
    }

    private static double decayedScore(SharedPreferences prefs, long nowMillis) {
        double stored = Double.longBitsToDouble(prefs.getLong(
                KEY_SCORE, Double.doubleToRawLongBits(0.0d)));
        long scoreAt = prefs.getLong(KEY_SCORE_AT, nowMillis);
        long elapsed = Math.max(0L, nowMillis - scoreAt);
        if (!Double.isFinite(stored) || stored <= 0.0d) return 0.0d;
        return stored * Math.pow(0.5d, (double) elapsed / HALF_LIFE_MS);
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
