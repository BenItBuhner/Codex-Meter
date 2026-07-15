package dev.bennett.codexmeter;

/** Signals credentials that cannot recover without a new X authorization grant. */
final class XAuthorizationException extends Exception {
    XAuthorizationException(String message) {
        super(message);
    }
}
