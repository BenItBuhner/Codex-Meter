package dev.bennett.codexmeter;

/**
 * What the sign-in screen is doing, and the only ways that can change. Browser and device-code
 * sign-ins wait on the sign-in service; paste and key sign-ins connect on the screen's own thread.
 */
final class SignInFlow {
    enum Phase { IDLE, WAITING_FOR_SERVICE, CONNECTING, CONNECTED }

    enum Event {
        /** The screen reopened while a service sign-in was still pending. */
        RESUME_PENDING,
        /** The screen asked the service to start, or to resend its code or link. */
        SERVICE_STARTED,
        SERVICE_FAILED_TO_START,
        SERVICE_SUCCEEDED,
        SERVICE_FAILED,
        CANCELLED,
        /** A paste or key sign-in began checking its input. */
        CONNECT,
        CONNECT_SUCCEEDED,
        CONNECT_FAILED,
    }

    private Phase phase = Phase.IDLE;

    Phase phase() {
        return phase;
    }

    boolean waitingForService() {
        return phase == Phase.WAITING_FOR_SERVICE;
    }

    boolean connecting() {
        return phase == Phase.CONNECTING;
    }

    /** Applies {@code event}; returns false, changing nothing, when it doesn't apply now. */
    boolean on(Event event) {
        Phase next = next(phase, event);
        if (next == null) return false;
        phase = next;
        return true;
    }

    private static Phase next(Phase phase, Event event) {
        if (phase == Phase.CONNECTED) return null;
        switch (event) {
            case RESUME_PENDING:
                return phase == Phase.IDLE ? Phase.WAITING_FOR_SERVICE : null;
            case SERVICE_STARTED:
                return phase == Phase.CONNECTING ? null : Phase.WAITING_FOR_SERVICE;
            case SERVICE_FAILED_TO_START:
            case CANCELLED:
                return phase == Phase.CONNECTING ? null : Phase.IDLE;
            case SERVICE_SUCCEEDED:
            case CONNECT_SUCCEEDED:
                return event == Event.CONNECT_SUCCEEDED && phase != Phase.CONNECTING ? null : Phase.CONNECTED;
            case SERVICE_FAILED:
                // A paste or key check that is running keeps going; the service's failure is its own.
                return phase == Phase.CONNECTING ? Phase.CONNECTING : Phase.IDLE;
            case CONNECT:
                return phase == Phase.CONNECTING ? null : Phase.CONNECTING;
            case CONNECT_FAILED:
                return phase == Phase.CONNECTING ? Phase.IDLE : null;
            default:
                return null;
        }
    }
}
