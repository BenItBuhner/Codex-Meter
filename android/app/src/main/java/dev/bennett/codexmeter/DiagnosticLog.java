package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in, privacy-filtered, rotating JSONL diagnostics stored only in app-private storage. */
public final class DiagnosticLog {
    private static final String DIRECTORY = "diagnostic-logs";
    private static final String CURRENT_FILE = "events.jsonl";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_SESSION = "session_id";
    private static final String PREFERENCES = "codex_meter_diagnostic_log_v1";
    private static final int MAX_ARCHIVES = 2;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final Object FILE_LOCK = new Object();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static volatile Context applicationContext;

    public static final class Stats {
        public final long bytes;
        public final int files;

        Stats(long bytes, int files) {
            this.bytes = bytes;
            this.files = files;
        }

        public boolean hasLogs() {
            return bytes > 0L;
        }
    }

    private DiagnosticLog() {
    }

    public static void install(Context context) {
        Context app = appContext(context);
        if (app != null) {
            applicationContext = app;
        }
        if (app == null || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        if (isEnabled(app)) {
            preferences(app).edit().putString(PREF_SESSION, UUID.randomUUID().toString()).apply();
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            error(app, "process", "uncaught_exception", throwable,
                    "crashed_thread", thread == null ? "" : thread.getName());
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    public static boolean isEnabled(Context context) {
        Context app = appContext(context);
        return app != null && preferences(app).getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        Context app = appContext(context);
        if (app == null) {
            return;
        }
        install(app);
        boolean wasEnabled = isEnabled(app);
        if (wasEnabled == enabled) {
            return;
        }
        if (enabled) {
            String session = UUID.randomUUID().toString();
            preferences(app).edit()
                    .putString(PREF_SESSION, session)
                    .putBoolean(PREF_ENABLED, true)
                    .apply();
            info(app, "diagnostics", "tracing_enabled",
                    "app_version", appVersion(app),
                    "android_sdk", Build.VERSION.SDK_INT,
                    "device", Build.MANUFACTURER + " " + Build.MODEL);
        } else {
            info(app, "diagnostics", "tracing_disabled");
            preferences(app).edit().putBoolean(PREF_ENABLED, false).apply();
        }
    }

    public static void info(Context context, String category, String event, Object... fields) {
        write(context, "info", category, event, null, fields);
    }

    public static void warn(Context context, String category, String event, Object... fields) {
        write(context, "warn", category, event, null, fields);
    }

    public static void error(Context context, String category, String event, Throwable error,
            Object... fields) {
        write(context, "error", category, event, error, fields);
    }

    public static void info(String category, String event, Object... fields) {
        info(applicationContext, category, event, fields);
    }

    public static void warn(String category, String event, Object... fields) {
        warn(applicationContext, category, event, fields);
    }

    public static void error(String category, String event, Throwable error, Object... fields) {
        error(applicationContext, category, event, error, fields);
    }

    public static Stats stats(Context context) {
        Context app = appContext(context);
        if (app == null) {
            return new Stats(0L, 0);
        }
        synchronized (FILE_LOCK) {
            long bytes = 0L;
            int files = 0;
            for (File file : orderedFiles(app)) {
                if (file.isFile() && file.length() > 0L) {
                    bytes += file.length();
                    files++;
                }
            }
            return new Stats(bytes, files);
        }
    }

    public static void clear(Context context) {
        Context app = appContext(context);
        if (app == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            for (File file : orderedFiles(app)) {
                if (file.exists()) {
                    file.delete();
                }
            }
        }
    }

    public static void export(Context context, Uri destination) throws Exception {
        Context app = appContext(context);
        if (app == null || destination == null) {
            throw new IllegalArgumentException("A diagnostic log destination is required.");
        }
        info(app, "diagnostics", "export_started");
        synchronized (FILE_LOCK) {
            try (OutputStream raw = app.getContentResolver().openOutputStream(destination, "wt")) {
                if (raw == null) {
                    throw new IllegalStateException("Android could not open the export file.");
                }
                try (BufferedOutputStream output = new BufferedOutputStream(raw)) {
                    byte[] buffer = new byte[16 * 1024];
                    for (File file : orderedFiles(app)) {
                        if (!file.isFile() || file.length() == 0L) {
                            continue;
                        }
                        try (BufferedInputStream input =
                                new BufferedInputStream(new FileInputStream(file))) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                }
            }
        }
        info(app, "diagnostics", "export_finished");
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return Math.max(1L, bytes / 1024L) + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void write(Context context, String level, String category, String event,
            Throwable error, Object... fields) {
        Context app = appContext(context);
        if (app == null || !isEnabled(app)) {
            return;
        }
        try {
            JSONObject record = new JSONObject();
            record.put("timestamp", Instant.now().toString());
            record.put("elapsed_ms", SystemClock.elapsedRealtime());
            record.put("sequence", SEQUENCE.incrementAndGet());
            record.put("session_id", preferences(app).getString(PREF_SESSION, ""));
            record.put("level", safe(level));
            record.put("category", safe(category));
            record.put("event", safe(event));
            record.put("thread", safe(Thread.currentThread().getName()));
            JSONObject details = details(fields);
            if (details.length() > 0) {
                record.put("details", details);
            }
            if ("network".equals(category)) {
                record.put("connectivity", connectivity(app));
            }
            if (error != null) {
                record.put("error", errorDetails(error));
            }
            append(app, record.toString() + "\n");
        } catch (Exception ignored) {
            // Diagnostics must never break the operation being diagnosed.
        }
    }

    private static JSONObject connectivity(Context app) throws Exception {
        JSONObject result = new JSONObject();
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                result.put("available", false);
                return result;
            }
            NetworkCapabilities capabilities =
                    manager.getNetworkCapabilities(manager.getActiveNetwork());
            result.put("available", capabilities != null);
            result.put("metered", manager.isActiveNetworkMetered());
            if (capabilities != null) {
                result.put("internet",
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                result.put("validated",
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                result.put("wifi",
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                result.put("cellular",
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                result.put("ethernet",
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                result.put("vpn",
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            }
        } catch (RuntimeException exception) {
            result.put("available", false);
            result.put("read_error", safe(exception.getClass().getSimpleName()));
        }
        return result;
    }

    private static JSONObject details(Object... fields) throws Exception {
        JSONObject result = new JSONObject();
        if (fields == null) {
            return result;
        }
        for (int index = 0; index + 1 < fields.length; index += 2) {
            String key = safe(String.valueOf(fields[index]));
            Object value = fields[index + 1];
            if (value == null) {
                result.put(key, JSONObject.NULL);
            } else if (value instanceof Number || value instanceof Boolean) {
                result.put(key, value);
            } else {
                result.put(key, safe(String.valueOf(value)));
            }
        }
        return result;
    }

    private static JSONObject errorDetails(Throwable throwable) throws Exception {
        JSONObject result = new JSONObject();
        result.put("type", throwable.getClass().getName());
        result.put("message", safe(throwable.getMessage()));
        JSONArray stack = new JSONArray();
        StackTraceElement[] elements = throwable.getStackTrace();
        for (int index = 0; index < elements.length && index < 20; index++) {
            stack.put(safe(elements[index].toString()));
        }
        result.put("stack", stack);
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            result.put("cause_type", cause.getClass().getName());
            result.put("cause_message", safe(cause.getMessage()));
        }
        return result;
    }

    private static void append(Context app, String line) throws Exception {
        byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
        synchronized (FILE_LOCK) {
            File directory = directory(app);
            if (!directory.exists() && !directory.mkdirs()) {
                return;
            }
            File current = new File(directory, CURRENT_FILE);
            if (current.length() + encoded.length > MAX_FILE_BYTES) {
                rotate(directory);
            }
            try (FileOutputStream output = new FileOutputStream(current, true);
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                writer.write(line);
            }
        }
    }

    private static void rotate(File directory) {
        File oldest = archive(directory, MAX_ARCHIVES);
        if (oldest.exists()) {
            oldest.delete();
        }
        for (int index = MAX_ARCHIVES - 1; index >= 1; index--) {
            File source = archive(directory, index);
            if (source.exists()) {
                source.renameTo(archive(directory, index + 1));
            }
        }
        File current = new File(directory, CURRENT_FILE);
        if (current.exists()) {
            current.renameTo(archive(directory, 1));
        }
    }

    private static File[] orderedFiles(Context app) {
        File directory = directory(app);
        return new File[] {
                archive(directory, 2),
                archive(directory, 1),
                new File(directory, CURRENT_FILE)
        };
    }

    private static File archive(File directory, int index) {
        return new File(directory, "events." + index + ".jsonl");
    }

    private static File directory(Context app) {
        return new File(app.getFilesDir(), DIRECTORY);
    }

    private static SharedPreferences preferences(Context app) {
        return app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return applicationContext;
        }
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static String safe(String value) {
        return DiagnosticSanitizer.redact(value == null ? "" : value);
    }

    private static String appVersion(Context app) {
        try {
            String version = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
            return version == null ? "" : version;
        } catch (Exception ignored) {
            return "";
        }
    }
}
