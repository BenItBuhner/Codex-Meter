package dev.bennett.codexmeter;

/** On-screen refreshes: one run per provider, joins instead of queueing, stuck runs time out. */
public final class RefreshTrackerSelfTest {
    public static void main(String[] args) {
        joinsInsteadOfQueueing();
        providersAreSeparate();
        stuckRunTimesOut();
        lateEndOfStuckRunIsIgnored();
        endTwiceIsHarmless();
        System.out.println("Refresh tracker: joins repeat pulls, per-provider runs, stuck runs time out.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void joinsInsteadOfQueueing() {
        RefreshTracker t = new RefreshTracker();
        RefreshTracker.Run a = t.begin("codex", 0);
        check(a != null, "first pull starts a refresh");
        check(t.begin("codex", 500) == null, "a second pull joins the running refresh");
        check(t.running("codex", 600), "spinner shows while running");
        check(t.end(a), "its own end finishes it");
        check(!t.running("codex", 700), "spinner hides after the end");
        check(t.begin("codex", 800) != null, "a new pull can start after the end");
    }

    private static void providersAreSeparate() {
        RefreshTracker t = new RefreshTracker();
        RefreshTracker.Run codex = t.begin("codex", 0);
        RefreshTracker.Run claude = t.begin("claude_code", 0);
        check(codex != null && claude != null, "each provider refreshes on its own");
        t.end(claude);
        check(t.running("codex", 10), "ending Claude leaves Codex's spinner up");
        check(!t.running("claude_code", 10), "Claude is idle");
    }

    private static void stuckRunTimesOut() {
        RefreshTracker t = new RefreshTracker();
        t.begin("codex", 0);
        check(t.phase("codex", RefreshTracker.STUCK_MS - 1) == RefreshTracker.Phase.RUNNING, "still running");
        check(t.phase("codex", RefreshTracker.STUCK_MS) == RefreshTracker.Phase.IDLE, "a hung refresh stops spinning");
        check(t.begin("codex", RefreshTracker.STUCK_MS) != null, "and a new pull can start");
    }

    private static void lateEndOfStuckRunIsIgnored() {
        RefreshTracker t = new RefreshTracker();
        RefreshTracker.Run stuck = t.begin("codex", 0);
        RefreshTracker.Run fresh = t.begin("codex", RefreshTracker.STUCK_MS + 1);
        check(!t.end(stuck), "the stuck run's late end does not end the new one");
        check(t.running("codex", RefreshTracker.STUCK_MS + 2), "the new run keeps its spinner");
        check(t.end(fresh), "the new run ends itself");
    }

    private static void endTwiceIsHarmless() {
        RefreshTracker t = new RefreshTracker();
        RefreshTracker.Run a = t.begin("codex", 0);
        check(t.end(a), "first end");
        RefreshTracker.Run b = t.begin("codex", 1);
        check(!t.end(a), "ending an old run again does nothing");
        check(t.running("codex", 2), "the newer run is untouched");
        check(!t.end(null), "null is ignored");
        t.end(b);
    }
}
