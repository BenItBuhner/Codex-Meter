package dev.bennett.codexmeter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The last parse of each saved JSON value, reused while the saved text is unchanged. Pages such as
 * Priority read every provider's snapshot, usage history, status and model speeds on each rebuild
 * (a dismiss, a refresh, a tab change); parsing them all again on the main thread froze the page
 * for a moment. SharedPreferences hands back the same String until the value is written again, so
 * the check is usually a reference compare. Cached values must be immutable: every type stored here
 * has final fields and unmodifiable lists.
 */
final class ParsedCache {
    private ParsedCache() {}

    interface Parser<T> {
        T parse(String raw) throws Exception;
    }

    private static final ConcurrentHashMap<String, Object[]> CACHE = new ConcurrentHashMap<>();

    /**
     * {@code parser}'s result for {@code raw}, parsed again only when the text saved under
     * {@code key} changed. {@code key} names the value across stores ("status/claude_code"). A parse
     * that throws is not cached, so the caller's fallback runs each time, as before.
     */
    @SuppressWarnings("unchecked")
    static <T> T get(String key, String raw, Parser<T> parser) throws Exception {
        Object[] hit = CACHE.get(key);
        if (hit != null && (hit[0] == raw || hit[0].equals(raw))) {
            return (T) hit[1];
        }
        T parsed = parser.parse(raw);
        CACHE.put(key, new Object[] {raw, parsed});
        return parsed;
    }
}
