package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import dev.bennett.codexmeter.glass.GlassModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;

/**
 * Material 3 Expressive's grouped lists ({@link ExpressiveRows}): one segmented group per section
 * (large corners only at its two ends), full-width switches, and rows that move by their drag
 * handle or by TalkBack's Move up and Move down.
 */
@RunWith(RobolectricTestRunner.class)
public final class ExpressiveRowsTest {
    private Context ctx;
    private LinearLayout list;
    private final List<int[]> moves = new ArrayList<>();

    @Before
    public void setUp() {
        ctx = ExpressiveDashboard.themed(RuntimeEnvironment.getApplication());
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
    }

    private ExpressiveRows rows() {
        return new ExpressiveRows(ctx, false, null, () -> { });
    }

    private GlassModel.Section section(int n, boolean movable) {
        GlassModel.Section section = new GlassModel.Section("Providers");
        for (int i = 0; i < n; i++) {
            GlassModel.Row row = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, "row" + i);
            row.title = "Row " + i;
            row.onToggle = on -> { };
            section.rows.add(row);
        }
        if (movable) {
            section.onMove = (from, to) -> moves.add(new int[] {from, to});
        }
        return section;
    }

    /** The row cards in the list, in order (the label and footer are text views). */
    private List<MaterialCardView> cards() {
        List<MaterialCardView> out = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i) instanceof MaterialCardView) {
                out.add((MaterialCardView) list.getChildAt(i));
            }
        }
        return out;
    }

    private float dp(float v) {
        return Ui.dp(ctx, v);
    }

    private static float top(MaterialCardView card) {
        ShapeAppearanceModel shape = card.getShapeAppearanceModel();
        return shape.getTopLeftCornerSize().getCornerSize(new android.graphics.RectF(0, 0, 1000, 1000));
    }

    private static float bottom(MaterialCardView card) {
        ShapeAppearanceModel shape = card.getShapeAppearanceModel();
        return shape.getBottomLeftCornerSize().getCornerSize(new android.graphics.RectF(0, 0, 1000, 1000));
    }

    @Test
    public void aSectionIsOneGroupWithLargeCornersOnlyAtItsEnds() {
        rows().section(list, section(3, false), new ExpressiveCards(ctx, false, list));
        List<MaterialCardView> cards = cards();
        assertEquals(3, cards.size());
        assertEquals(dp(ExpressiveRows.OUTER), top(cards.get(0)), 0.5f);
        assertEquals(dp(ExpressiveRows.INNER), bottom(cards.get(0)), 0.5f);
        assertEquals(dp(ExpressiveRows.INNER), top(cards.get(1)), 0.5f);
        assertEquals(dp(ExpressiveRows.INNER), bottom(cards.get(1)), 0.5f);
        assertEquals(dp(ExpressiveRows.INNER), top(cards.get(2)), 0.5f);
        assertEquals(dp(ExpressiveRows.OUTER), bottom(cards.get(2)), 0.5f);
    }

    @Test
    public void aCardJoinsTheRowsOfItsSection() {
        GlassModel.Section section = section(2, false);
        GlassModel.Card card = new GlassModel.Card(GlassModel.Card.Kind.MESSAGE);
        card.title = "A note above the rows";
        section.cards.add(card);
        rows().section(list, section, new ExpressiveCards(ctx, false, list));
        List<MaterialCardView> cards = cards();
        assertEquals(3, cards.size());
        assertEquals("the card's bottom meets the first row", dp(ExpressiveRows.INNER), bottom(cards.get(0)), 0.5f);
        assertEquals("so the first row's top is an inner corner", dp(ExpressiveRows.INNER), top(cards.get(1)), 0.5f);
        assertEquals(dp(ExpressiveRows.OUTER), bottom(cards.get(2)), 0.5f);
    }

    @Test
    public void switchesKeepMaterialsWidth() {
        rows().section(list, section(1, false), new ExpressiveCards(ctx, false, list));
        MaterialSwitch sw = find(list, MaterialSwitch.class);
        assertNotNull(sw);
        sw.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue("switch is " + sw.getMeasuredWidth() + " px wide", sw.getMeasuredWidth() >= dp(52) - 1);
    }

    @Test
    public void reorderableRowsHaveHandlesAndTalkBackMoves() {
        rows().section(list, section(3, true), new ExpressiveCards(ctx, false, list));
        List<MaterialCardView> cards = cards();
        for (MaterialCardView card : cards) {
            assertNotNull("drag handle", card.findViewWithTag("drag_handle"));
        }
        assertNull("the first row can't move up", action(cards.get(0), "Move up"));
        assertNull("the last row can't move down", action(cards.get(2), "Move down"));
        cards.get(1).performAccessibilityAction(action(cards.get(1), "Move down").getId(), null);
        assertEquals(1, moves.size());
        assertEquals(1, moves.get(0)[0]);
        assertEquals(2, moves.get(0)[1]);
    }

    @Test
    public void draggingAHandleMovesItsRow() {
        rows().section(list, section(4, true), new ExpressiveCards(ctx, false, list));
        int width = (int) dp(360);
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, width, list.getMeasuredHeight());
        List<MaterialCardView> cards = cards();
        View handle = cards.get(0).findViewWithTag("drag_handle");
        float step = cards.get(1).getTop() - cards.get(0).getTop();
        assertTrue(step > 0);

        long t = SystemClock.uptimeMillis();
        handle.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10, 10, 0));
        // down a little over two rows: the row's centre passes the next two rows' centres
        for (int k = 1; k <= 10; k++) {
            handle.dispatchTouchEvent(MotionEvent.obtain(t, t + k * 16, MotionEvent.ACTION_MOVE,
                    10, 10 + step * 2.2f * k / 10, 0));
        }
        handle.dispatchTouchEvent(MotionEvent.obtain(t, t + 200, MotionEvent.ACTION_UP,
                10, 10 + step * 2.2f, 0));
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper(Duration.ofSeconds(1).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, moves.size());
        assertEquals(0, moves.get(0)[0]);
        assertEquals(2, moves.get(0)[1]);
    }

    private static AccessibilityNodeInfo.AccessibilityAction action(View view, String label) {
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        for (AccessibilityNodeInfo.AccessibilityAction a : info.getActionList()) {
            if (label.contentEquals(a.getLabel() == null ? "" : a.getLabel())) {
                return a;
            }
        }
        return null;
    }

    private static <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
