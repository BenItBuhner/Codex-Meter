package dev.bennett.codexmeter;

/**
 * The sign-in service's attempts as an explicit state machine. At most one attempt is current.
 * Each attempt moves RUNNING to CANCELLED or FINISHED exactly once and never comes back, so a flow
 * thread left over from a cancelled attempt keeps stopping after a newer attempt starts, and can't
 * finish or report for it.
 */
final class SignInAttempts {
    enum State { RUNNING, CANCELLED, FINISHED }

    static final class Attempt {
        final UsageProvider provider;
        private volatile State state = State.RUNNING;

        Attempt(UsageProvider provider) {
            this.provider = provider;
        }

        State state() {
            return state;
        }
    }

    private Attempt current;

    /** Starts an attempt for {@code provider}, or returns null while another one is current. */
    synchronized Attempt begin(UsageProvider provider) {
        if (current != null) return null;
        current = new Attempt(provider);
        return current;
    }

    synchronized Attempt current() {
        return current;
    }

    /** Cancels the current attempt, if any; its thread sees {@link #shouldStop} from now on. */
    synchronized void cancel() {
        if (current != null) {
            current.state = State.CANCELLED;
            current = null;
        }
    }

    /**
     * Ends {@code own} (the caller's attempt, or null from the main thread, which ends whatever is
     * current). Returns false, and changes nothing, when {@code own} is no longer current.
     */
    synchronized boolean finish(Attempt own) {
        if (own != null && own != current) return false;
        if (current != null && current.state == State.RUNNING) current.state = State.FINISHED;
        current = null;
        return true;
    }

    /** True when {@code own} (or, from the main thread, the service) should stop working. */
    synchronized boolean shouldStop(Attempt own) {
        return own == null ? current == null : own.state != State.RUNNING || own != current;
    }

    /** True when {@code own} was replaced, so it must not touch service state or report results. */
    synchronized boolean superseded(Attempt own) {
        return own != null && own != current;
    }
}
