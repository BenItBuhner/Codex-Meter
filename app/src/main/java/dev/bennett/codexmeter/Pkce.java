package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/* JADX INFO: loaded from: classes.dex */
public final class Pkce {
    public final String challenge;
    public final String state;
    public final String verifier;

    private Pkce(String str, String str2, String str3) {
        this.verifier = str;
        this.challenge = str2;
        this.state = str3;
    }

    public static Pkce generate() throws Exception {
        SecureRandom secureRandom = new SecureRandom();
        byte[] bArr = new byte[64];
        byte[] bArr2 = new byte[32];
        secureRandom.nextBytes(bArr);
        secureRandom.nextBytes(bArr2);
        String strBase64Url = base64Url(bArr);
        return new Pkce(strBase64Url, base64Url(MessageDigest.getInstance("SHA-256").digest(strBase64Url.getBytes(StandardCharsets.US_ASCII))), base64Url(bArr2));
    }

    private static String base64Url(byte[] bArr) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bArr);
    }
}
