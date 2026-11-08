package dev.bennett.codexmeter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Debug builds only: finds what blocks the main thread. A background thread pings the main
 * looper every 20 ms; while a ping goes unanswered for over {@link #STALL_MS}, it samples the main
 * thread's stack every 16 ms and logs each sample ("MainThreadWatch" tag), then logs the stall's
 * length. The design gallery's perf scenes read these lines to name the code that froze a frame.
 * Started by the manifest (a provider runs before any activity), so every flow is covered.
 */
public final class MainThreadWatch extends ContentProvider {
    static final String TAG = "MainThreadWatch";
    static final long STALL_MS = 48;

    @Override
    public boolean onCreate() {
        Handler main = new Handler(Looper.getMainLooper());
        Thread mainThread = Looper.getMainLooper().getThread();
        Thread watch = new Thread(() -> {
            final long[] answered = {SystemClock.uptimeMillis()};
            while (true) {
                long sent = SystemClock.uptimeMillis();
                main.postAtFrontOfQueue(() -> answered[0] = SystemClock.uptimeMillis());
                long stallStart = -1;
                int sample = 0;
                while (true) {
                    SystemClock.sleep(16);
                    if (answered[0] >= sent) {
                        break;
                    }
                    long waited = SystemClock.uptimeMillis() - sent;
                    if (waited >= STALL_MS) {
                        if (stallStart < 0) {
                            stallStart = sent;
                        }
                        StringBuilder b = new StringBuilder("sample ").append(sample++).append(" at ")
                                .append(waited).append("ms");
                        for (StackTraceElement e : mainThread.getStackTrace()) {
                            b.append("\n  at ").append(e);
                        }
                        Log.w(TAG, b.toString());
                    }
                }
                if (stallStart >= 0) {
                    Log.w(TAG, "stall " + (answered[0] - stallStart) + "ms");
                }
                SystemClock.sleep(20);
            }
        }, TAG);
        watch.setDaemon(true);
        watch.start();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
