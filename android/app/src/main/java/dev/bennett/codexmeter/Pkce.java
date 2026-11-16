package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** A PKCE verifier, its S256 challenge, and an OAuth state value, all fresh and random. */
public final class Pkce {
    public final String challenge;
    public final String state;
    public final String verifier;

    private Pkce(String verifier, String challenge, String state) {
        this.verifier = verifier;
        this.challenge = challenge;
        this.state = state;
    }

    public static Pkce generate() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] verifierBytes = new byte[64];
        byte[] stateBytes = new byte[32];
        random.nextBytes(verifierBytes);
        random.nextBytes(stateBytes);
        String verifier = base64Url(verifierBytes);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, base64Url(digest), base64Url(stateBytes));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
