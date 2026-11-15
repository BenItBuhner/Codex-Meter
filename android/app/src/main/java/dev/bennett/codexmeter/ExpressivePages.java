package dev.bennett.codexmeter;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import dev.bennett.codexmeter.glass.GlassModel;

/**
 * Material 3 Expressive for the pages beyond the dashboard and Settings: usage history, reset
 * credits, updates, release history, About, Dashboard tabs, Edit dashboard and lock screen widgets.
 * Until now these opened their One UI activities in the Expressive design. Now those activities hand
 * their intent to {@link ExpressivePageActivity} ({@link #redirect}), which runs the same page code
 * Liquid Glass runs ({@link GlassScreens}), drawn with Material views by {@link ExpressiveSettings}.
 */
final class ExpressivePages implements GlassScreens.Host {
    private final AppCompatActivity activity;
    private final GlassScreens screens;
    private final String id;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExpressiveSettings view;
    private boolean pending;

    private ExpressivePages(AppCompatActivity activity, String id) {
        this.activity = activity;
        this.id = id;
        this.screens = new GlassScreens(activity, this);
    }

    /**
     * In Material 3 Expressive, reopens {@code from} (one of the activities above) as its Expressive
     * page and finishes it; call right after {@code super.onCreate}. True when it did, and the
     * activity must return without building its One UI views.
     */
    static boolean redirect(AppCompatActivity from) {
        if (!AppPreferences.isMaterialExpressiveDesign(from)
                || GlassScreens.pageFor(from.getIntent()) == null) {
            return false;
        }
        from.startActivity(new Intent(from, ExpressivePageActivity.class)
                .putExtra(ExpressivePageActivity.EXTRA_INTENT, new Intent(from.getIntent())));
        from.finish();
        from.overridePendingTransition(0, 0);
        return true;
    }

    /** Shows the page {@code intent} opens in {@code host}; false when it opens none. */
    static boolean show(AppCompatActivity host, Intent intent) {
        return showPage(host, GlassScreens.pageFor(intent), intent);
    }

    /**
     * Shows page {@code id} in {@code host} itself, for the widget settings the launcher opens:
     * their result has to come from the activity it started.
     */
    static boolean showPage(AppCompatActivity host, String id, Intent intent) {
        return attach(host, id, intent) != null;
    }

    /**
     * Shows page {@code id} in {@code host} and returns it, for pages their activity builds (Add
     * account, sign-in), which call {@link #changed} when they change; null when there is no such page.
     */
    static ExpressivePages attach(AppCompatActivity host, String id, Intent intent) {
        if (id == null) {
            return null;
        }
        ExpressivePages pages = new ExpressivePages(host, id);
        if (pages.screens.screen(id) == null) {
            return null;
        }
        pages.show(intent);
        return pages;
    }

    private void show(Intent intent) {
        GlassScreens.Screen screen = this.screens.screen(this.id);
        if (screen == null) {
            return;
        }
        this.screens.opened(this.id, intent);
        GlassModel.Page first = screen.page();
        this.view = new ExpressiveSettings(this.activity, first == null ? "" : first.title,
                new ExpressiveSettings.Model() {
                    @Override
                    public GlassModel.Page page() {
                        return screen.page();
                    }

                    @Override
                    public void toggle(String key, boolean on) {
                    }

                    @Override
                    public void choose(String key, int index) {
                    }

                    @Override
                    public void press(String key) {
                    }
                });
        screen.shown();
        this.view.render();
        this.activity.getLifecycle().addObserver((LifecycleEventObserver) (owner, event) -> {
            if (event == Lifecycle.Event.ON_RESUME) {
                screen.resumed();
                changed();
            } else if (event == Lifecycle.Event.ON_DESTROY) {
                this.screens.dispose();
            }
        });
    }

    @Override
    public void changed() {
        // Pages report changes in bursts (a download's progress, a list rebuilt); draw once per frame.
        if (this.pending || this.view == null) {
            return;
        }
        this.pending = true;
        this.ui.post(() -> {
            this.pending = false;
            if (!this.activity.isFinishing() && !this.activity.isDestroyed()) {
                this.view.render();
            }
        });
    }

    @Override
    public void openPage(String page, Intent intent) {
        Intent open = intent != null ? intent : GlassScreens.intentFor(this.activity, page);
        if (open != null) {
            Ui.startSecondaryActivity(this.activity, open);
        }
    }

    @Override
    public void start(Intent intent) {
        this.activity.startActivity(intent);
    }

    @Override
    public void pop() {
        this.activity.finish();
    }

    @Override
    public void post(Runnable action) {
        this.ui.post(action);
    }
}
