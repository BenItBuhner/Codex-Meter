package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Dismissing on the Priority tab must stick: the row is gone the next time the page is built, on
 * every design, and only the list it was dismissed from is cleared.
 */
@RunWith(RobolectricTestRunner.class)
public final class PriorityPageDismissTest {
    private Context app;
    private ProviderEvents.Event refill;
    private ProviderEvents.Event added;

    @Before
    public void seed() {
        app = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        refill = ProviderEvents.surpriseRefill(UsageProvider.CODEX, Collections.singletonList("Weekly"), now - 60_000L);
        added = ProviderEvents.newResetCredits(UsageProvider.CODEX, 0, 2, now - 30_000L);
        ProviderAlerts.remember(app, refill);
        ProviderAlerts.remember(app, added);
        assertEquals(2, ProviderAlerts.recent(app).size());
    }

    @Test
    public void dismissingOneEventRemovesOnlyThatEvent() {
        PriorityPage.dismiss(app, note(refill));
        List<String> left = keys(ProviderAlerts.recent(app));
        assertFalse("dismissed event is gone", left.contains(refill.key()));
        assertTrue("the other event stays", left.contains(added.key()));
    }

    @Test
    public void dismissAllClearsEveryEventInTheList() {
        PriorityPage.dismissAll(app, Arrays.asList(note(refill), note(added)));
        assertTrue(ProviderAlerts.recent(app).isEmpty());
    }

    @Test
    public void dismissingAModelIsSavedAndLeavesEventsAlone() {
        ModelDiscovery.State state = new ModelDiscovery.State();
        ModelDiscovery.Entry model = new ModelDiscovery.Entry(ModelDiscovery.Source.OPENROUTER, "openai/gpt-6");
        model.name = "GPT-6";
        model.kind = ModelDiscovery.Kind.NEW;
        state.entries.put(model.key(), model);
        ModelDiscoveryStore.seedForDemo(app, state);

        PriorityPage.dismissAll(app, Collections.singletonList(
                new PriorityNote(UsageProvider.CODEX, "GPT-6", "OpenAI model", null, model)));

        assertTrue("model dismissal persisted", ModelDiscoveryStore.load(app).dismissed.contains(model.key()));
        assertEquals("events untouched", 2, ProviderAlerts.recent(app).size());
    }

    @Test
    public void nullAndUndismissibleNotesChangeNothing() {
        PriorityPage.dismiss(app, null);
        PriorityPage.dismissAll(app, Collections.singletonList(new PriorityNote(UsageProvider.CODEX, "Weekly", "40%")));
        assertEquals(2, ProviderAlerts.recent(app).size());
    }

    private static PriorityNote note(ProviderEvents.Event e) {
        return new PriorityNote(UsageProvider.CODEX, e.title, e.detail, e.key());
    }

    private static List<String> keys(List<ProviderEvents.Event> events) {
        List<String> out = new ArrayList<>();
        for (ProviderEvents.Event e : events) out.add(e.key());
        return out;
    }
}
