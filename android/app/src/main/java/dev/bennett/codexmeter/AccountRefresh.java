package dev.bennett.codexmeter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sequential refresh with per-account failure isolation and cancellation. */
final class AccountRefresh {
    interface Fetch { void refresh(String id) throws Exception; }

    static Map<String, Exception> run(List<String> ids, Fetch fetch) throws InterruptedException {
        Map<String, Exception> failures = new LinkedHashMap<>();
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try { fetch.refresh(id); }
            catch (InterruptedException exception) { throw exception; }
            catch (Exception exception) { failures.put(id, exception); }
        }
        return failures;
    }
}
