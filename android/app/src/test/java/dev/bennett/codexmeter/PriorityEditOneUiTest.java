package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * Priority's Edit in the One UI designs: the editor lists Priority's sections (not a provider's
 * cards) in the tab's order with how many items each has, and its switches and order write the
 * same settings Liquid Glass's Edit mode does.
 */
@RunWith(RobolectricTestRunner.class)
public final class PriorityEditOneUiTest {
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setDesign(app, AppPreferences.DESIGN_ONE_UI_9);
    }

    private DashboardReorderActivity open(PriorityPage page) {
        Intent intent = DashboardReorderActivity.priority(app, page);
        ActivityController<DashboardReorderActivity> controller =
                ActivityController.of(new DashboardReorderActivity(), intent).setup();
        return controller.get();
    }

    private static RecyclerView recycler(View root) {
        if (root instanceof RecyclerView) {
            return (RecyclerView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                RecyclerView found = recycler(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<TextView> texts(View root, List<TextView> out) {
        if (root instanceof SwitchCompat) {
            return out; // a switch is a TextView too, with no text of its own here
        }
        if (root instanceof TextView) {
            out.add((TextView) root);
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                texts(group.getChildAt(i), out);
            }
        }
        return out;
    }

    private static SwitchCompat toggle(View root) {
        if (root instanceof SwitchCompat) {
            return (SwitchCompat) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                SwitchCompat found = toggle(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void listsPrioritySectionsInTheTabsOrderWithTheirCounts() {
        PriorityPrefs.setSectionOrder(app, Arrays.asList(
                "models", "used_up", "alerts", "news", "watch", "ready", "coming_up"));
        PriorityPage page = new PriorityPage();
        page.sizes.put("models", 3);
        page.sizes.put("used_up", 1);
        DashboardReorderActivity activity = open(page);

        RecyclerView list = recycler(activity.getWindow().getDecorView());
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, 1080, 4000);
        assertEquals(PriorityPage.SECTIONS.length, list.getAdapter().getItemCount());

        List<String> first = new ArrayList<>();
        for (TextView t : texts(list.getChildAt(0), new ArrayList<>())) {
            first.add(t.getText().toString());
        }
        assertEquals(Arrays.asList(PriorityPage.NEW_MODELS, "3 items now"), first);
        List<String> second = new ArrayList<>();
        for (TextView t : texts(list.getChildAt(1), new ArrayList<>())) {
            second.add(t.getText().toString());
        }
        assertEquals(Arrays.asList(PriorityPage.USED_UP, "1 item now"), second);
        // a section with nothing in it right now
        List<String> third = new ArrayList<>();
        for (TextView t : texts(list.getChildAt(2), new ArrayList<>())) {
            third.add(t.getText().toString());
        }
        assertEquals("Nothing right now", third.get(1));
    }

    @Test
    public void itsSwitchHidesTheSectionOnPriority() {
        PriorityPrefs.setSectionOrder(app, Arrays.asList(
                "news", "alerts", "watch", "used_up", "ready", "coming_up", "models"));
        DashboardReorderActivity activity = open(new PriorityPage());
        RecyclerView list = recycler(activity.getWindow().getDecorView());
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, 1080, 4000);

        SwitchCompat news = toggle(list.getChildAt(0));
        assertTrue(news.isChecked());
        news.performClick();
        assertFalse(PriorityPrefs.isSectionShown(app, "news"));
        // a provider's dashboard choices stay untouched
        assertTrue(ProviderPreferences.isDashboardSectionVisible(app, UsageProvider.CODEX, "news"));
        news.performClick();
        assertTrue(PriorityPrefs.isSectionShown(app, "news"));
    }

    @Test
    public void itemCountReadsLikeLiquidGlass() {
        assertEquals("Nothing right now", DashboardReorderActivity.itemCount(null));
        assertEquals("1 item now", DashboardReorderActivity.itemCount(1));
        assertEquals("4 items now", DashboardReorderActivity.itemCount(4));
    }
}
