package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.PopupMenu;

/**
 * Makes a row of a list dismissible, for the One UI and Material 3 Expressive Priority tabs (Liquid
 * Glass uses Glassé's own swipe row and context menu): swipe it sideways past a third of its width,
 * or fling it, and it slides away; touch and hold it for a menu with Dismiss (and Dismiss all when
 * there is more than one).
 */
final class SwipeDismiss {
    private SwipeDismiss() {}

    /** Past this share of the row's width a released swipe dismisses. */
    private static final float DISMISS_SHARE = 0.33f;

    @SuppressLint("ClickableViewAccessibility")
    static void attach(View row, Runnable onDismiss, Runnable onDismissAll) {
        if (row == null || onDismiss == null) {
            return;
        }
        Runnable dismiss = () -> slideAway(row, row.getTranslationX() < 0 ? -1 : 1, onDismiss);
        row.setLongClickable(true);
        row.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            PopupMenu menu = new PopupMenu(v.getContext(), v, android.view.Gravity.END);
            menu.getMenu().add(0, 1, 0, "Dismiss");
            if (onDismissAll != null) {
                menu.getMenu().add(0, 2, 1, "Dismiss all");
            }
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    dismiss.run();
                } else {
                    onDismissAll.run();
                }
                return true;
            });
            menu.show();
            return true;
        });
        androidx.core.view.ViewCompat.addAccessibilityAction(row, "Dismiss", (view, args) -> {
            onDismiss.run();
            return true;
        });
        int slop = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
        float minFling = ViewConfiguration.get(row.getContext()).getScaledMinimumFlingVelocity() * 4f;
        row.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            boolean dragging;
            VelocityTracker velocity;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        velocity = VelocityTracker.obtain();
                        velocity.addMovement(e);
                        return false; // long press and taps still work
                    case MotionEvent.ACTION_MOVE: {
                        if (velocity != null) velocity.addMovement(e);
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            dragging = true;
                            v.cancelLongPress();
                            ViewParent parent = v.getParent();
                            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                        }
                        if (dragging) {
                            v.setTranslationX(dx);
                            v.setAlpha(1f - Math.min(1f, Math.abs(dx) / Math.max(1f, v.getWidth())));
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean was = dragging;
                        dragging = false;
                        float vx = 0f;
                        if (velocity != null) {
                            velocity.addMovement(e);
                            velocity.computeCurrentVelocity(1000);
                            vx = velocity.getXVelocity();
                            velocity.recycle();
                            velocity = null;
                        }
                        if (!was) {
                            return false;
                        }
                        float dx = v.getTranslationX();
                        boolean fling = Math.abs(vx) > minFling && Math.signum(vx) == Math.signum(dx);
                        if (e.getActionMasked() == MotionEvent.ACTION_UP
                                && (Math.abs(dx) > v.getWidth() * DISMISS_SHARE || fling)) {
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                            slideAway(v, dx < 0 ? -1 : 1, onDismiss);
                        } else {
                            v.animate().translationX(0f).alpha(1f).setDuration(180).start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private static void slideAway(View row, int direction, Runnable then) {
        row.animate().translationX(direction * (float) Math.max(row.getWidth(), 1)).alpha(0f)
                .setDuration(180).withEndAction(then).start();
    }
}
