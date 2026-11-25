package dev.bennett.codexmeter;

import com.benitbuhner.glasse.core.Clock;
import com.benitbuhner.glasse.core.Ticker;
import com.benitbuhner.glasse.core.Timers;
import com.benitbuhner.glasse.ui.Meter;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * Keeps Glassé's frame loop with the glass screen that is in front.
 *
 * <p>Glassé has one animation loop for the whole process ({@code Ticker}, {@code Timers}), and
 * each GlasseHost points its hooks at itself when it starts and clears them when it is destroyed.
 * With a second glass activity on top (sign-in, widget settings, What's new), the one below lost
 * its hooks twice: the new host took them, and destroying that host cleared them, after the one
 * below had already resumed. From then on nothing woke its frames, so the page under it (Settings
 * › Providers, mostly) sat frozen until the app was closed.
 *
 * <p>Each glass activity now remembers its own hooks right after its host starts ({@link #of}),
 * takes them back whenever it resumes ({@link #claim}), and hands them back to whichever activity
 * is in front once its own host is gone ({@link #release}).
 */
final class GlassLoop {
    /** The loop of the glass activity that resumed last: the one in front. */
    private static GlassLoop front;

    private final Function0<Unit> wake;
    private final Function1<? super Double, Unit> schedule;
    private final Function1<? super Boolean, Unit> meter;

    private GlassLoop() {
        this.wake = Ticker.onWake;
        this.schedule = Timers.onSchedule;
        this.meter = Meter.onToggle;
    }

    /** The hooks a host just installed; call right after {@code GlasseHost.start}. */
    static GlassLoop of() {
        GlassLoop loop = new GlassLoop();
        front = loop;
        return loop;
    }

    /** The activity came to the front: its host drives the loop again. */
    void claim() {
        front = this;
        install();
    }

    /** The activity's host was destroyed (call after {@code GlasseHost.onDestroy}). */
    void release() {
        if (front == this) {
            front = null;
            return;
        }
        if (front != null) {
            front.install();
        }
    }

    private void install() {
        if (this.wake == null) {
            return;
        }
        Ticker.onWake = this.wake;
        Timers.onSchedule = this.schedule;
        Meter.onToggle = this.meter;
        // Timers set while another host held the loop were posted to that host's handler, which
        // dropped them: this host posts the next one. A pending animation gets its frames again.
        Double next = Timers.nextDue();
        if (next != null && this.schedule != null) {
            this.schedule.invoke(Math.max(0.0, next - Clock.now()));
        }
        if (Ticker.active()) {
            this.wake.invoke();
        }
    }

    /** Test hook: the loop in front. */
    static boolean inFront(GlassLoop loop) {
        return front == loop;
    }
}
