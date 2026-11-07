package dev.bennett.codexmeter;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * When to ask a status feed again. About every 5 minutes while the app is open, every 30 minutes
 * from background work (WorkManager and JobScheduler won't run more often than every 15 anyway),
 * with jitter so devices don't hit a feed together, exponential backoff after failures, and the
 * feed's Retry-After honoured when it sends one. Feeds are shared by several providers (Cursor and
 * Grok both live on status.x.ai), so callers key this by feed URL, not provider.
 */
public final class StatusPollPolicy {
    public static final long FOREGROUND_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    public static final long BACKGROUND_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30);
    public static final long MAX_BACKOFF_MS = TimeUnit.HOURS.toMillis(2);
    /** Up to this share of the interval is added at random. */
    public static final double JITTER_FRACTION = 0.2;

    private StatusPollPolicy() {}

    /**
     * Earliest time the feed may be asked again.
     *
     * @param lastAttemptMillis when the last request was sent (0 if never)
     * @param consecutiveFailures failed requests in a row since the last success
     * @param retryAfterUntilMillis the time a Retry-After header asked us to wait until, or 0
     * @param foreground whether the app is on screen
     * @param random01 a number in [0, 1) for jitter, passed in so tests are deterministic
     */
    public static long nextAllowedAt(long lastAttemptMillis, int consecutiveFailures,
            long retryAfterUntilMillis, boolean foreground, double random01) {
        if (lastAttemptMillis <= 0L) {
            return Math.max(0L, retryAfterUntilMillis);
        }
        long base = foreground ? FOREGROUND_INTERVAL_MS : BACKGROUND_INTERVAL_MS;
        long interval = base;
        if (consecutiveFailures > 0) {
            int shift = Math.min(consecutiveFailures, 6);
            interval = Math.min(MAX_BACKOFF_MS, Math.max(base, FOREGROUND_INTERVAL_MS << shift));
        }
        double r = random01 < 0 ? 0 : Math.min(random01, 0.999999);
        long jitter = (long) (interval * JITTER_FRACTION * r);
        return Math.max(lastAttemptMillis + interval + jitter, retryAfterUntilMillis);
    }

    public static boolean shouldFetch(long now, long lastAttemptMillis, int consecutiveFailures,
            long retryAfterUntilMillis, boolean foreground, double random01) {
        return now >= nextAllowedAt(lastAttemptMillis, consecutiveFailures, retryAfterUntilMillis,
                foreground, random01);
    }

    /**
     * Turns a Retry-After header (seconds, or an HTTP date) into an absolute time, or 0 when the
     * header is missing or unreadable. Capped at the longest backoff so a bad header can't silence
     * status for days.
     */
    public static long retryAfterUntil(String header, long now) {
        if (header == null) {
            return 0L;
        }
        String value = header.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        long until = 0L;
        try {
            long seconds = Long.parseLong(value);
            until = now + TimeUnit.SECONDS.toMillis(Math.max(0L, seconds));
        } catch (NumberFormatException notSeconds) {
            long at = ProviderStatus.parseRfc1123(value);
            if (at > 0L) {
                until = at;
            }
        }
        if (until <= 0L) {
            return 0L;
        }
        return Math.min(until, now + MAX_BACKOFF_MS);
    }

    /** A stable preferences key for a feed URL. */
    public static String feedKey(String url) {
        return url == null ? "" : url.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
