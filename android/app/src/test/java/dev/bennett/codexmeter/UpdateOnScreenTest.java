package dev.bennett.codexmeter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/** The update notification stays quiet only while the screen in front shows the update. */
@RunWith(RobolectricTestRunner.class)
public final class UpdateOnScreenTest {
    private final Activity first = Robolectric.buildActivity(Activity.class).setup().get();
    private final Activity second = Robolectric.buildActivity(Activity.class).setup().get();

    @After
    public void tearDown() {
        UpdateNotificationManager.screenPaused(first);
        UpdateNotificationManager.screenPaused(second);
    }

    @Test
    public void nothingInFrontMeansNotify() {
        assertFalse(UpdateNotificationManager.updateOnScreen());
    }

    @Test
    public void followsWhatTheFrontScreenShowsNow() {
        AtomicBoolean onPriority = new AtomicBoolean(true);
        UpdateNotificationManager.screenResumed(first, onPriority::get);
        assertTrue(UpdateNotificationManager.updateOnScreen());
        onPriority.set(false); // switched to a provider tab
        assertFalse(UpdateNotificationManager.updateOnScreen());
    }

    @Test
    public void pausingTheFrontScreenEndsIt() {
        UpdateNotificationManager.screenResumed(first, () -> true);
        UpdateNotificationManager.screenPaused(first);
        assertFalse(UpdateNotificationManager.updateOnScreen());
    }

    @Test
    public void aLatePauseOfAnOlderScreenKeepsTheNewOne() {
        UpdateNotificationManager.screenResumed(first, () -> false);
        UpdateNotificationManager.screenResumed(second, () -> true);
        UpdateNotificationManager.screenPaused(first);
        assertTrue(UpdateNotificationManager.updateOnScreen());
    }

    @Test
    public void aFinishingScreenNoLongerCounts() {
        UpdateNotificationManager.screenResumed(first, () -> true);
        first.finish();
        assertFalse(UpdateNotificationManager.updateOnScreen());
    }
}
