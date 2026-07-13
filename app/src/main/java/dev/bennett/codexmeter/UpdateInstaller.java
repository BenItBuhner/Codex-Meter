package dev.bennett.codexmeter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

/** Downloads, authenticates, and submits one release APK to Android's package installer. */
public final class UpdateInstaller {
    private static final long MAX_APK_BYTES = 150L * 1024L * 1024L;
    private static final int MAX_CHECKSUM_BYTES = 64 * 1024;

    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    public static final class PreparedUpdate {
        public final File apk;
        public final String versionName;
        public final long versionCode;

        PreparedUpdate(File apk, String versionName, long versionCode) {
            this.apk = apk;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }

    public static final class DowngradeNotSupportedException extends Exception {
        DowngradeNotSupportedException(String message) {
            super(message);
        }
    }

    private UpdateInstaller() {
    }

    public static PreparedUpdate prepare(Context context, GitHubRelease release,
            ProgressListener listener) throws Exception {
        if (release == null) {
            throw new IllegalArgumentException("No GitHub release was selected.");
        }
        if (release.apkSize <= 0L || release.apkSize > MAX_APK_BYTES) {
            throw new IllegalStateException("The release APK has an unsafe file size.");
        }
        String checksumFile = downloadText(release.checksumUrl, MAX_CHECKSUM_BYTES);
        String expected = ReleaseIntegrity.expectedSha256(checksumFile, release.apkName);
        if (expected.isEmpty()) {
            throw new SecurityException("The release checksum does not list "
                    + release.apkName + ".");
        }
        File directory = new File(context.getCacheDir(), "verified-updates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not prepare update storage.");
        }
        File partial = new File(directory, release.apkName + ".part");
        File apk = new File(directory, release.apkName);
        deleteQuietly(partial);
        deleteQuietly(apk);
        try {
            downloadFile(release.apkUrl, partial, release.apkSize, listener);
            if (partial.length() != release.apkSize) {
                throw new SecurityException("The APK size does not match the GitHub release.");
            }
            String actual = ReleaseIntegrity.sha256(partial);
            if (!MessageDigestSupport.constantTimeEquals(expected, actual)) {
                throw new SecurityException("The downloaded APK failed SHA-256 verification.");
            }
            if (!partial.renameTo(apk)) {
                copy(partial, apk);
                deleteQuietly(partial);
            }
            return verifyPackage(context, release, apk);
        } catch (Exception exception) {
            deleteQuietly(partial);
            deleteQuietly(apk);
            throw exception;
        }
    }

    public static int commit(Context context, PreparedUpdate update) throws Exception {
        if (update == null || update.apk == null || !update.apk.isFile()) {
            throw new IllegalArgumentException("The verified update APK is missing.");
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(update.apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            try (InputStream input = new FileInputStream(update.apk);
                    OutputStream output = session.openWrite("base.apk", 0L, update.apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }
            Intent result = new Intent(context, UpdateInstallReceiver.class)
                    .setAction(AppConstants.ACTION_INSTALL_STATUS)
                    .putExtra(UpdateInstallReceiver.EXTRA_VERSION, update.versionName);
            PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            IntentSender sender = callback.getIntentSender();
            session.commit(sender);
            return sessionId;
        } catch (Exception exception) {
            try {
                installer.abandonSession(sessionId);
            } catch (RuntimeException ignored) {
            }
            throw exception;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private static PreparedUpdate verifyPackage(Context context, GitHubRelease release, File apk)
            throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) {
            throw new SecurityException("Android could not read the downloaded APK.");
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("The APK package name is not Codex Meter.");
        }
        ReleaseVersion expected = ReleaseVersion.parse(release.version);
        ReleaseVersion actual = ReleaseVersion.parse(archive.versionName);
        if (expected == null || actual == null || expected.compareTo(actual) != 0) {
            throw new SecurityException("The APK version does not match the selected release.");
        }
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (!sameSigners(signatures(installed), signatures(archive))) {
            throw new SecurityException(
                    "The APK signing certificate does not match this installation.");
        }
        long installedCode = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;
        long archiveCode = Build.VERSION.SDK_INT >= 28
                ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveCode < installedCode) {
            throw new DowngradeNotSupportedException(
                    "Android cannot install an older version over the current app.");
        }
        return new PreparedUpdate(apk, archive.versionName, archiveCode);
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static boolean sameSigners(Signature[] installed, Signature[] archive) {
        Set<String> installedValues = signatureValues(installed);
        Set<String> archiveValues = signatureValues(archive);
        if (installedValues.isEmpty() || archiveValues.isEmpty()) {
            return false;
        }
        for (String signer : archiveValues) {
            if (installedValues.contains(signer)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> signatureValues(Signature[] signatures) {
        HashSet<String> result = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                if (signature != null) {
                    result.add(signature.toCharsString());
                }
            }
        }
        return result;
    }

    private static String downloadText(String url, int limit) throws Exception {
        HttpsURLConnection connection = open(url);
        try {
            requireOk(connection);
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new SecurityException("The checksum file is unexpectedly large.");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadFile(String url, File destination, long expected,
            ProgressListener listener) throws Exception {
        HttpsURLConnection connection = open(url);
        try {
            requireOk(connection);
            long declared = connection.getContentLengthLong();
            if (declared > MAX_APK_BYTES || (declared > 0L && declared != expected)) {
                throw new SecurityException("The APK download size changed unexpectedly.");
            }
            try (InputStream input = connection.getInputStream();
                    OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Update download canceled.");
                    }
                    total += read;
                    if (total > expected || total > MAX_APK_BYTES) {
                        throw new SecurityException("The APK download exceeded its expected size.");
                    }
                    output.write(buffer, 0, read);
                    if (listener != null) {
                        listener.onProgress(total, expected);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !allowedHost(url.getHost())) {
            throw new SecurityException("The release download URL is not trusted.");
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", AppConstants.userAgent());
        return connection;
    }

    private static void requireOk(HttpsURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        URL finalUrl = connection.getURL();
        if (!"https".equalsIgnoreCase(finalUrl.getProtocol()) || !allowedHost(finalUrl.getHost())) {
            throw new SecurityException("GitHub redirected the download to an untrusted host.");
        }
        if (status != HttpsURLConnection.HTTP_OK) {
            throw new IllegalStateException("GitHub download failed with HTTP " + status + ".");
        }
    }

    private static boolean allowedHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.US);
        return "github.com".equals(normalized)
                || normalized.endsWith(".github.com")
                || "githubusercontent.com".equals(normalized)
                || normalized.endsWith(".githubusercontent.com");
    }

    private static void copy(File source, File destination) throws Exception {
        try (InputStream input = new FileInputStream(source);
                OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static final class MessageDigestSupport {
        static boolean constantTimeEquals(String left, String right) {
            if (left == null || right == null) {
                return false;
            }
            return java.security.MessageDigest.isEqual(
                    left.toLowerCase(Locale.US).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    right.toLowerCase(Locale.US).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }
}
