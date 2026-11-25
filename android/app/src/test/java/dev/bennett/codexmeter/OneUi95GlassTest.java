package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * One UI 9.5's tab bar on Android 12+: its glass replaces Samsung's own view blur, so the bar is
 * inflated with that blur off (on Samsung phones it fills the bar's whole view and is shaped only by
 * the outline the glass takes away), and the bar stays clipped to an invisible pill the size of the
 * bar. One UI 9 keeps Samsung's blur.
 */
@RunWith(RobolectricTestRunner.class)
public final class OneUi95GlassTest {
    /** A bare host for the One UI 9 page shell. */
    public static final class Host extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
        }
    }

    private static BottomNavigationView openBar(String design) {
        Context app = RuntimeEnvironment.getApplication();
        AppPreferences.setDesign(app, design);
        Host host = ActivityController.of(new Host()).setup().get();
        OneUi9Page page = OneUi9Page.install(host, android.R.layout.simple_list_item_1, "Usage",
                false, true);
        new OneUi9ProviderBar(page, provider -> { }, () -> { });
        BottomNavigationView bar = page.root.findViewById(R.id.oneui9_provider_bar);
        assertNotNull(bar);
        bar.layout(100, 2000, 900, 2180);
        return bar;
    }

    private static boolean appliesSamsungBlur(View bar) {
        TypedArray a = bar.getContext().obtainStyledAttributes(
                R.style.OneUi95FloatingNavigation,
                new int[] {com.google.android.material.R.attr.seslBottomBarApplyBlur});
        try {
            return a.getBoolean(0, true);
        } finally {
            a.recycle();
        }
    }

    @Test
    public void oneUi95BarDropsSamsungBlurAndClipsToItsPill() {
        BottomNavigationView bar = openBar(AppPreferences.DESIGN_ONE_UI_9_5);
        assertFalse(appliesSamsungBlur(bar));
        assertNull(bar.getBackground());
        assertNull(bar.getBackgroundTintList());
        assertTrue(bar.getClipToOutline());

        Outline outline = new Outline();
        bar.getOutlineProvider().getOutline(bar, outline);
        Rect rect = new Rect();
        assertTrue(outline.getRect(rect));
        assertEquals(new Rect(0, 0, 800, 180), rect);
        assertEquals(90f, outline.getRadius(), 0.01f);
        assertEquals(0f, outline.getAlpha(), 0f);

        // The glass sits just below the bar in the page.
        ViewGroup root = (ViewGroup) bar.getParent();
        View below = root.getChildAt(root.indexOfChild(bar) - 1);
        assertTrue(below instanceof OneUi95Glass.Lens);
        assertTrue(bar.getElevation() > below.getElevation());
    }

    @Test
    public void oneUi9BarKeepsSamsungBlurAndBackground() {
        BottomNavigationView bar = openBar(AppPreferences.DESIGN_ONE_UI_9);
        assertNotNull(bar.getBackground());
        ViewGroup root = (ViewGroup) bar.getParent();
        for (int i = 0; i < root.getChildCount(); i++) {
            assertFalse(String.valueOf(root.getChildAt(i)),
                    root.getChildAt(i) instanceof OneUi95Glass.Lens);
        }
    }
}
