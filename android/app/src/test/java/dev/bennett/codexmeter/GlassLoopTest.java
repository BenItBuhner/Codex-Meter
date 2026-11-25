package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.core.Ticker;
import com.benitbuhner.glasse.core.Timers;
import com.benitbuhner.glasse.ui.Meter;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.junit.After;
import org.junit.Test;

/**
 * Glassé's frame loop stays with the glass activity in front. Bennett's case: Settings › Providers,
 * then a second glass activity on top (Add account used to be one, sign-in still is), then back.
 * The activity on top took the loop's hooks and cleared them when it was destroyed, after the
 * activity below had already resumed, so Providers never drew another frame.
 */
public class GlassLoopTest {
    private final List<String> woke = new ArrayList<>();
    private final List<String> scheduled = new ArrayList<>();

    @After
    public void clear() {
        Ticker.onWake = null;
        Timers.onSchedule = null;
        Meter.onToggle = null;
        Timers.reset();
    }

    /** What GlasseHost.start does: point the loop's hooks at this host. */
    private GlassLoop start(String name) {
        Function0<Unit> wake = () -> {
            woke.add(name);
            return Unit.INSTANCE;
        };
        Function1<Double, Unit> schedule = ms -> {
            scheduled.add(name);
            return Unit.INSTANCE;
        };
        Function1<Boolean, Unit> meter = on -> Unit.INSTANCE;
        Ticker.onWake = wake;
        Timers.onSchedule = schedule;
        Meter.onToggle = meter;
        return GlassLoop.of();
    }

    /** What GlasseHost.onDestroy does: clear the hooks, whoever holds them. */
    private static void destroyHost() {
        Ticker.onWake = null;
        Timers.onSchedule = null;
        Meter.onToggle = null;
    }

    @Test
    public void backFromSecondGlassActivityKeepsTheLoopBelow() {
        GlassLoop main = start("main");
        main.claim();
        GlassLoop signIn = start("signin");
        signIn.claim();

        // Back: the activity below resumes first, then the one on top is destroyed.
        main.claim();
        destroyHost();
        signIn.release();

        assertTrue(GlassLoop.inFront(main));
        woke.clear();
        Ticker.onWake.invoke();
        assertEquals(List.of("main"), woke);
        scheduled.clear();
        Timers.set(10.0, () -> Unit.INSTANCE);
        assertEquals(List.of("main"), scheduled);
    }

    @Test
    public void timersPostedToTheGoneHostArePostedAgain() {
        GlassLoop main = start("main");
        GlassLoop top = start("top");
        Timers.set(250.0, () -> Unit.INSTANCE); // posted to top's handler, which onDestroy drops
        scheduled.clear();
        main.claim();
        assertEquals(List.of("main"), scheduled);
        destroyHost();
        top.release();
        assertEquals("main", scheduled.get(scheduled.size() - 1));
    }

    @Test
    public void lastActivityGoneLeavesNoHooks() {
        GlassLoop only = start("only");
        only.claim();
        destroyHost();
        only.release();
        assertNull(Ticker.onWake);
        assertNull(Timers.onSchedule);
    }
}
