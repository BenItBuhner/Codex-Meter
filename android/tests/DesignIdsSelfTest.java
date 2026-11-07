package dev.bennett.codexmeter;

/** App design ids: legacy values keep their look, unknown ones fall back, labels say "inspired". */
public final class DesignIdsSelfTest {
    public static void main(String[] args) {
        // The single Liquid Glass id saved before the split stays on the iOS 26-inspired look.
        check(DesignIds.normalize("liquid_glass", false).equals(DesignIds.LIQUID_GLASS_IOS_26), "legacy glass reads as iOS 26");
        check(DesignIds.forSave("liquid_glass").equals(DesignIds.LIQUID_GLASS_IOS_26), "legacy glass saves as iOS 26");
        check(DesignIds.normalize("liquid_glass_ios_27", false).equals(DesignIds.LIQUID_GLASS_IOS_27), "iOS 27 kept");
        check(DesignIds.normalize("liquid_glass_ios_26", true).equals(DesignIds.LIQUID_GLASS_IOS_26), "saved design beats the old switch");
        check(DesignIds.normalize(null, true).equals(DesignIds.ONE_UI_9), "old One UI 9 switch");
        check(DesignIds.normalize(null, false).equals(DesignIds.CLASSIC), "nothing saved is classic");
        check(DesignIds.normalize("bogus", false).equals(DesignIds.CLASSIC), "unknown is classic");
        check(DesignIds.forSave(null).equals(DesignIds.CLASSIC), "null saves classic");
        check(DesignIds.isLiquidGlass("liquid_glass") && DesignIds.isLiquidGlass("liquid_glass_ios_26")
                && DesignIds.isLiquidGlass("liquid_glass_ios_27") && !DesignIds.isLiquidGlass("one_ui_9"), "glass family");
        check(DesignIds.isIos27("liquid_glass_ios_27") && !DesignIds.isIos27("liquid_glass"), "iOS 27 only when chosen");
        check(DesignIds.label("liquid_glass").equals("Liquid Glass (iOS 26-inspired)"), "label 26");
        check(DesignIds.label("liquid_glass_ios_27").equals("Liquid Glass (iOS 27-inspired)"), "label 27");
        check(DesignIds.label("classic").equals("One UI 8.5"), "label classic");
        System.out.println("Design ids keep legacy Liquid Glass on the iOS 26-inspired look.");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
