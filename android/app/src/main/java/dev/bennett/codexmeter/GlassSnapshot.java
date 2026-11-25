package dev.bennett.codexmeter;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Keeps a Liquid Glass page visible while its activity sits behind another one.
 *
 * <p>Glassé draws into a GL surface, and a paused activity's surface goes black once the system
 * takes it away. Android's predictive back shows the activity underneath while the finger moves, so
 * swiping back from sign-in or What's new showed a black page where Settings › Providers should be.
 * When the activity pauses, the last frame is copied into a picture laid over the surface; when it
 * is back in front and drawing again, the picture goes.
 */
final class GlassSnapshot {
    /** How long the copy stays after resuming: a few frames, until the surface draws again. */
    private static final long UNCOVER_MS = 150;

    private final ViewGroup root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView cover;
    private boolean resumed = true;

    GlassSnapshot(ViewGroup root) {
        this.root = root;
    }

    void paused() {
        resumed = false;
        SurfaceView surface = find(root);
        if (surface == null || surface.getWidth() == 0 || surface.getHeight() == 0
                || !surface.getHolder().getSurface().isValid()) {
            return;
        }
        Bitmap frame;
        try {
            frame = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return;
        }
        try {
            PixelCopy.request(surface, frame, result -> {
                if (result == PixelCopy.SUCCESS && !resumed) {
                    show(frame);
                } else {
                    frame.recycle();
                }
            }, ui);
        } catch (IllegalArgumentException gone) {
            frame.recycle();
        }
    }

    void resumed() {
        resumed = true;
        if (cover != null && cover.getVisibility() == View.VISIBLE) {
            ui.removeCallbacksAndMessages(null);
            ui.postDelayed(this::hide, UNCOVER_MS);
        }
    }

    void destroyed() {
        ui.removeCallbacksAndMessages(null);
        resumed = true;
        hide();
    }

    private void show(Bitmap frame) {
        if (cover == null) {
            cover = new ImageView(root.getContext());
            cover.setScaleType(ImageView.ScaleType.FIT_XY);
            cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        }
        cover.setImageBitmap(frame);
        cover.setVisibility(View.VISIBLE);
    }

    private void hide() {
        // Paused again before the copy left: it stays (the page is behind another one again).
        if (cover == null || !resumed) {
            return;
        }
        cover.setVisibility(View.GONE);
        cover.setImageDrawable(null);
    }

    private static SurfaceView find(View view) {
        if (view instanceof SurfaceView) {
            return (SurfaceView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = find(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
