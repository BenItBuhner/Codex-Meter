package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import dev.bennett.codexmeter.glass.GlassModel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import android.content.Intent;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * First-run setup in Material 3 Expressive: drawn by {@link ExpressivePages} rather than One UI's
 * page, with the same steps and the same saved choices.
 */
@RunWith(RobolectricTestRunner.class)
public final class OnboardingExpressiveTest {
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setDesign(app, DesignIds.MATERIAL_3_EXPRESSIVE);
    }

    @Test
    public void setupRunsOnExpressivePagesFromWelcomeToSignIn() {
        ActivityController<OnboardingActivity> controller =
                start();
        OnboardingActivity activity = controller.get();
        assertNull("no One UI page", activity.findViewById(R.id.dashboard_scroll));

        GlassModel.Page welcome = activity.expressivePage();
        assertEquals("Meet Codex Meter", welcome.title);
        row(welcome, "continue").onPress.run();

        GlassModel.Page providers = activity.expressivePage();
        assertEquals("Choose your providers", providers.title);
        assertFalse("nothing is preselected", row(providers, "continue").enabled);
        assertTrue(providers.actions.isEmpty());
        row(providers, UsageProvider.CODEX.id).onToggle.on(true);

        providers = activity.expressivePage();
        assertTrue(row(providers, "continue").enabled);
        assertTrue(row(providers, UsageProvider.CODEX.id).checked);
        assertEquals(1, providers.actions.size());
        row(providers, "continue").onPress.run();

        GlassModel.Page signIn = activity.expressivePage();
        assertEquals("Connect Codex", signIn.title);
        assertEquals(OnboardingFlow.FIRST_SIGN_IN_STEP, AppPreferences.getOnboardingStep(app));
        assertEquals(1, AppPreferences.getOnboardingProviders(app).size());
        row(signIn, "skip").onPress.run();

        GlassModel.Page done = activity.expressivePage();
        assertEquals("Setup complete", done.title);
        assertNotNull(row(done, UsageProvider.CODEX.id));
        controller.pause().stop().destroy();
    }

    @Test
    public void oneUiKeepsItsOwnPage() {
        AppPreferences.setDesign(app, DesignIds.ONE_UI_9);
        OnboardingActivity activity = start().get();
        assertNotNull(activity.findViewById(R.id.dashboard_scroll));
    }

    /** Built without the Robolectric class, whose annotations fail this build's -Xlint -Werror. */
    private ActivityController<OnboardingActivity> start() {
        return ActivityController.of(new OnboardingActivity(),
                new Intent(app, OnboardingActivity.class)).setup();
    }

    private static GlassModel.Row row(GlassModel.Page page, String key) {
        for (GlassModel.Section section : page.sections) {
            for (GlassModel.Row row : section.rows) {
                if (key.equals(row.key)) return row;
            }
        }
        throw new AssertionError("no row " + key + " on " + page.title);
    }
}
