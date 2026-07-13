package dev.bennett.codexmeter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Checksum parsing and hashing for downloaded release APKs. */
public final class ReleaseIntegrity {
    private ReleaseIntegrity() {
    }

    public static String expectedSha256(String checksumFile, String fileName) {
        if (checksumFile == null || fileName == null || fileName.contains("/")
                || fileName.contains("\\") || fileName.trim().isEmpty()) {
            return "";
        }
        for (String rawLine : checksumFile.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.length() < 66) {
                continue;
            }
            String digest = line.substring(0, 64);
            if (!isSha256(digest)) {
                continue;
            }
            String listedName = line.substring(64).trim();
            if (listedName.startsWith("*")) {
                listedName = listedName.substring(1);
            }
            if (fileName.equals(listedName)) {
                return digest.toLowerCase(Locale.US);
            }
        }
        return "";
    }

    public static String sha256(File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Downloaded APK is missing.");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
