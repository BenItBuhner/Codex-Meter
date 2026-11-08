package dev.bennett.codexmeter;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Pull to refresh for the Material 3 Expressive dashboard: pulling down at the top brings in the
 * contained loading indicator ({@code expressive_pull_indicator}), which follows the finger,
 * grows into place and starts a refresh when let go far enough. It stays while refreshing.
 */
public final class ExpressivePull extends FrameLayout {
    interface Listener {
        void onPullRefresh();
    }

    private final int slop;
    private final float trigger;
    private final float max;
    private View target;
    private View indicator;
    private Listener listener;
    private float downY;
    private boolean dragging;
    private boolean refreshing;
    private float offset;

    public ExpressivePull(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        float d = context.getResources().getDisplayMetrics().density;
        this.trigger = 96 * d;
        this.max = 140 * d;
    }

    void setup(View scroll, View indicator, Listener listener) {
        this.target = scroll;
        this.indicator = indicator;
        this.listener = listener;
        indicator.setAlpha(0f);
        indicator.setScaleX(0.4f);
        indicator.setScaleY(0.4f);
        indicator.setVisibility(VISIBLE);
    }

    void setRefreshing(boolean on) {
        this.refreshing = on;
        if (this.indicator == null) {
            return;
        }
        if (on) {
            settle(this.trigger * 0.75f, 1f);
        } else if (!this.dragging) {
            settle(0f, 0f);
        }
    }

    private boolean atTop() {
        return this.target != null && !this.target.canScrollVertically(-1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (this.indicator == null || !isEnabled() || this.refreshing) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                this.downY = ev.getY();
                this.dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!this.dragging && atTop() && ev.getY() - this.downY > this.slop) {
                    this.dragging = true;
                    this.downY = ev.getY();
                }
                break;
            default:
                break;
        }
        return this.dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!this.dragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                // Resistance grows as the pull goes on.
                float raw = Math.max(0f, ev.getY() - this.downY);
                this.offset = this.max * (1f - (float) Math.exp(-raw / this.max));
                place(this.offset, Math.min(1f, this.offset / this.trigger));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                this.dragging = false;
                if (ev.getActionMasked() == MotionEvent.ACTION_UP && this.offset >= this.trigger * 0.8f) {
                    android.util.Log.i("CodexGlass", "pull to refresh");
                    this.refreshing = true;
                    settle(this.trigger * 0.75f, 1f);
                    if (this.listener != null) {
                        this.listener.onPullRefresh();
                    }
                } else {
                    settle(0f, 0f);
                }
                this.offset = 0f;
                return true;
            default:
                return true;
        }
    }

    private void place(float y, float progress) {
        this.indicator.animate().cancel();
        this.indicator.setTranslationY(y);
        this.indicator.setAlpha(progress);
        float s = 0.4f + 0.6f * progress;
        this.indicator.setScaleX(s);
        this.indicator.setScaleY(s);
        this.indicator.setRotation(progress * 180f);
    }

    private void settle(float y, float progress) {
        float s = 0.4f + 0.6f * progress;
        this.indicator.animate().translationY(y).alpha(progress).scaleX(s).scaleY(s)
                .setDuration(ReducedMotion.isRequested(getContext()) ? 0 : 220).start();
    }
}
