package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;

/**
 * Fetches and caches official status feeds. Requests carry no account data: no tokens, cookies or
 * identifiers, only the app's user agent. Each feed is fetched at most as often as
 * {@link StatusPollPolicy} allows, with If-None-Match when the feed sent an ETag, and the last
 * good body is kept so a 304 or a failure doesn't lose what we knew.
 *
 * <p>Status is only fetched while the dashboard is on screen; nothing in the background asks for
 * it, because nothing in the background shows it yet.
 */
public final class ProviderStatusStore {
    private static final String PREFS = "provider_status";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private ProviderStatusStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The last status worked out for {@code provider}, or null when none has been fetched. */
    public static ProviderStatus load(Context context, UsageProvider provider) {
        if (ProviderStatusSources.forProvider(provider) == null) {
            return null;
        }
        String raw = prefs(context).getString("status_" + provider.id, null);
        if (raw == null) {
            return null;
        }
        try {
            return ProviderStatus.fromJson(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetches {@code provider}'s feed if the poll policy allows, on the calling thread (call it
     * off the main thread). Returns true when the stored status changed.
     */
    public static boolean refreshIfDue(Context context, UsageProvider provider, boolean foreground) {
        ProviderStatusSources.Source source = ProviderStatusSources.forProvider(provider);
        if (source == null) {
            return false;
        }
        SharedPreferences p = prefs(context);
        String key = StatusPollPolicy.feedKey(source.feedUrl);
        long now = System.currentTimeMillis();
        long last = p.getLong(key + "_attempt", 0L);
        int failures = p.getInt(key + "_failures", 0);
        long retryAfter = p.getLong(key + "_retry_after", 0L);
        if (!StatusPollPolicy.shouldFetch(now, last, failures, retryAfter, foreground,
                ThreadLocalRandom.current().nextDouble())) {
            // The feed may have been fetched for another provider that shares it.
            return reparse(context, provider, p.getString(key + "_body", null),
                    p.getLong(key + "_fetched", now));
        }
        p.edit().putLong(key + "_attempt", now).apply();
        String body = null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(source.feedUrl).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", source.format == ProviderStatusSources.Format.RSS
                    ? "application/rss+xml, application/xml" : "application/json");
            connection.setRequestProperty("User-Agent", AppConstants.userAgent());
            String etag = p.getString(key + "_etag", null);
            String cached = p.getString(key + "_body", null);
            if (etag != null && cached != null) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int status = connection.getResponseCode();
            long until = StatusPollPolicy.retryAfterUntil(connection.getHeaderField("Retry-After"), now);
            SharedPreferences.Editor edit = p.edit().putLong(key + "_retry_after", until);
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                body = cached;
                edit.putInt(key + "_failures", 0).putLong(key + "_fetched", now);
            } else if (status >= 200 && status < 300) {
                body = read(connection.getInputStream());
                edit.putInt(key + "_failures", 0).putString(key + "_body", body)
                        .putLong(key + "_fetched", now);
                String newEtag = connection.getHeaderField("ETag");
                if (newEtag != null) {
                    edit.putString(key + "_etag", newEtag);
                } else {
                    edit.remove(key + "_etag");
                }
            } else {
                edit.putInt(key + "_failures", failures + 1);
            }
            edit.apply();
            DiagnosticLog.info(context, "status", "feed_finished", "url",
                    DiagnosticSanitizer.safeUrl(source.feedUrl), "status", status);
        } catch (Exception e) {
            p.edit().putInt(key + "_failures", failures + 1).apply();
            DiagnosticLog.warn(context, "status", "feed_failed", "url",
                    DiagnosticSanitizer.safeUrl(source.feedUrl), "error", e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        if (body == null) {
            // A failed request is our connection problem, not an incident: keep the last known
            // status until it is too old to trust, then say "unknown".
            ProviderStatus previous = load(context, provider);
            if (previous != null && now - previous.checkedAtMillis < StatusPollPolicy.MAX_BACKOFF_MS) {
                return false;
            }
            return store(context, provider, ProviderStatus.unknown(provider.id, source.pageUrl, now));
        }
        return reparse(context, provider, body, now);
    }

    /**
     * Debug builds' demo only: stores {@code body} as {@code provider}'s feed, fetched now, so the
     * dashboard shows it without a network request for the next poll interval.
     */
    static void seedFeedForDemo(Context context, UsageProvider provider, String body) {
        ProviderStatusSources.Source source = ProviderStatusSources.forProvider(provider);
        if (source == null) {
            return;
        }
        String key = StatusPollPolicy.feedKey(source.feedUrl);
        long now = System.currentTimeMillis();
        prefs(context).edit().putString(key + "_body", body).putLong(key + "_fetched", now)
                .putLong(key + "_attempt", now).putInt(key + "_failures", 0).commit();
        reparse(context, provider, body, now);
    }

    /** {@code checkedAt}: when the feed body was last fetched, which the card shows. */
    private static boolean reparse(Context context, UsageProvider provider, String body, long checkedAt) {
        if (body == null) {
            return false;
        }
        ProviderStatus status = ProviderStatusSources.parse(provider, body, checkedAt);
        return status != null && store(context, provider, status);
    }

    private static boolean store(Context context, UsageProvider provider, ProviderStatus status) {
        ProviderStatus previous = load(context, provider);
        prefs(context).edit().putString("status_" + provider.id, status.toJson().toString()).apply();
        return previous == null || previous.state != status.state
                || !previous.headline(provider.displayName).equals(status.headline(provider.displayName))
                || !previous.detail().equals(status.detail());
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new java.io.IOException("status feed too large");
                }
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
