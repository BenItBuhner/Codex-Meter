package dev.bennett.codexmeter;

import java.util.HashMap;
import java.util.Map;

/**
 * Which on-screen usage refreshes are running, one per provider. Each provider is either idle or
 * running. A refresh can begin only from idle, so repeated pulls join the refresh already running
 * instead of queueing another one, and only its own end returns it to idle. The spinner shown for
 * a tab is exactly "this tab's provider is running".
 *
 * <p>A refresh that never reports back (a hung request) counts as idle after {@link #STUCK_MS}, so
 * the spinner cannot stay up forever and a new pull can start again. A late end from that stuck run
 * is ignored when a newer run has already begun.
 *
 * <p>Pure Java so it can be tested off-device. Not thread-safe: the activity uses it on the main
 * thread only.
 */
final class RefreshTracker {
    /** How long a refresh may run before it is treated as stuck. Longer than any request timeout. */
    static final long STUCK_MS = 90_000L;

    enum Phase { IDLE, RUNNING }

    /** A started refresh. Pass it back to {@link #end} so a stale run can't end a newer one. */
    static final class Run {
        final String key;
        final long id;

        Run(String key, long id) {
            this.key = key;
            this.id = id;
        }
    }

    private static final class Slot {
        long id;
        long startedAt;
        boolean running;
    }

    private final Map<String, Slot> slots = new HashMap<>();
    private long nextId = 1;

    /** Starts a refresh for {@code key}, or returns null when one is already running. */
    Run begin(String key, long now) {
        Slot s = this.slots.get(key);
        if (s == null) {
            s = new Slot();
            this.slots.put(key, s);
        }
        if (s.running && now - s.startedAt < STUCK_MS) {
            return null;
        }
        s.running = true;
        s.startedAt = now;
        s.id = this.nextId++;
        return new Run(key, s.id);
    }

    /** Ends {@code run}. Returns false when a newer run had replaced it (nothing changes). */
    boolean end(Run run) {
        if (run == null) {
            return false;
        }
        Slot s = this.slots.get(run.key);
        if (s == null || s.id != run.id) {
            return false;
        }
        s.running = false;
        return true;
    }

    Phase phase(String key, long now) {
        Slot s = this.slots.get(key);
        return s != null && s.running && now - s.startedAt < STUCK_MS ? Phase.RUNNING : Phase.IDLE;
    }

    boolean running(String key, long now) {
        return phase(key, now) == Phase.RUNNING;
    }

    /** True while any provider is refreshing (the Priority tab's spinner, which covers them all). */
    boolean anyRunning(long now) {
        for (String key : this.slots.keySet()) {
            if (running(key, now)) {
                return true;
            }
        }
        return false;
    }
}
