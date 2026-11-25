package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Widgets take Material 3 Expressive's colors in that design, and keep their own elsewhere. */
@RunWith(RobolectricTestRunner.class)
public final class WidgetMaterialTest {
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    @Test
    public void expressiveWidgetsUseMaterialPrimaryAndSecondaryContainer() {
        AppPreferences.setDesign(app, DesignIds.MATERIAL_3_EXPRESSIVE);
        for (boolean dark : new boolean[] {false, true}) {
            assertEquals(WidgetMaterial.primary(app, dark),
                    WidgetGraphics.accentColor(app, WidgetOptions.ACCENT_APP, dark));
            assertEquals(WidgetMaterial.track(app, dark), WidgetGraphics.trackColor(app, dark));
            // a chosen accent stays the user's choice
            assertEquals(WidgetGraphics.accentColor(WidgetOptions.ACCENT_ROSE, dark),
                    WidgetGraphics.accentColor(app, WidgetOptions.ACCENT_ROSE, dark));
        }
        assertNotEquals(WidgetMaterial.surface(app, false), WidgetMaterial.surface(app, true));
    }

    @Test
    public void otherDesignsKeepTheirWidgetColors() {
        AppPreferences.setDesign(app, DesignIds.ONE_UI_9);
        assertEquals(Ui.accent(app, false), WidgetGraphics.accentColor(app, WidgetOptions.ACCENT_APP, false));
        assertEquals(WidgetGraphics.trackColor(false), WidgetGraphics.trackColor(app, false));
    }
}
