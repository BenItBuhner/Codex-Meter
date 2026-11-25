package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.Configuration;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.lang.reflect.Field;

/**
 * Material 3 Expressive's switch. This build's appcompat is One UI's, whose SwitchCompat ignores
 * the switch's own size and lays every switch out at One UI's fixed width (sesl_switch_width,
 * 44 dp), which squeezed Material's 52 dp track. This sets that width back to Material's after the
 * constructor and after a configuration change, the two places One UI sets it.
 */
final class ExpressiveSwitch extends MaterialSwitch {
    /** Material 3's switch track width. */
    static final int WIDTH_DP = 52;
    private static final Field SWITCH_WIDTH = switchWidthField();

    ExpressiveSwitch(Context context) {
        super(context);
        fixWidth();
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        fixWidth();
    }

    private void fixWidth() {
        int width = Ui.dp(getContext(), WIDTH_DP);
        setSwitchMinWidth(width);
        if (SWITCH_WIDTH != null) {
            try {
                SWITCH_WIDTH.setInt(this, width);
            } catch (IllegalAccessException ignored) {
                // Stock appcompat has no fixed width: setSwitchMinWidth covers it.
            }
        }
        requestLayout();
    }

    private static Field switchWidthField() {
        try {
            Field field = androidx.appcompat.widget.SwitchCompat.class.getDeclaredField("mSwitchWidth");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException missing) {
            // Stock appcompat: the switch already sizes itself from its track.
            return null;
        }
    }
}
