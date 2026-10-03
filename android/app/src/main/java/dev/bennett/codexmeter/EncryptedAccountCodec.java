package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Reuses the legacy AES-GCM envelope and Android Keystore key. */
final class EncryptedAccountCodec {
    static JSONObject encrypt(JSONObject document, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(document.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                .put("ct", Base64.getEncoder().encodeToString(ciphertext));
    }

    static JSONObject decrypt(JSONObject envelope, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128,
                Base64.getDecoder().decode(envelope.getString("iv"))));
        return new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(
                envelope.getString("ct"))), StandardCharsets.UTF_8));
    }
}
