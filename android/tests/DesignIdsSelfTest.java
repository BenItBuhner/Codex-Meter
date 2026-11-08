package dev.bennett.codexmeter;

/** App design ids: both former Liquid Glass looks read as the one Liquid Glass, unknown ones fall back. */
public final class DesignIdsSelfTest {
    public static void main(String[] args) {
        // Liquid Glass is one design: the two former looks (iOS 26- and 27-inspired) both read as it.
        check(DesignIds.normalize("liquid_glass", false).equals(DesignIds.LIQUID_GLASS), "Liquid Glass kept");
        check(DesignIds.normalize("liquid_glass_ios_26", false).equals(DesignIds.LIQUID_GLASS), "iOS 26 look moves to Liquid Glass");
        check(DesignIds.normalize("liquid_glass_ios_27", false).equals(DesignIds.LIQUID_GLASS), "iOS 27 look moves to Liquid Glass");
        check(DesignIds.forSave("liquid_glass_ios_26").equals(DesignIds.LIQUID_GLASS), "former look saves as Liquid Glass");
        check(DesignIds.normalize("liquid_glass_ios_26", true).equals(DesignIds.LIQUID_GLASS), "saved design beats the old switch");
        check(DesignIds.normalize(null, true).equals(DesignIds.ONE_UI_9), "old One UI 9 switch");
        check(DesignIds.normalize(null, false).equals(DesignIds.CLASSIC), "nothing saved is classic");
        check(DesignIds.normalize("bogus", false).equals(DesignIds.CLASSIC), "unknown is classic");
        check(DesignIds.forSave(null).equals(DesignIds.CLASSIC), "null saves classic");
        check(DesignIds.isLiquidGlass("liquid_glass") && DesignIds.isLiquidGlass("liquid_glass_ios_26")
                && DesignIds.isLiquidGlass("liquid_glass_ios_27") && !DesignIds.isLiquidGlass("one_ui_9"), "glass family");
        check(DesignIds.label("liquid_glass").equals("Liquid Glass"), "label");
        check(DesignIds.label("liquid_glass_ios_26").equals("Liquid Glass")
                && DesignIds.label("liquid_glass_ios_27").equals("Liquid Glass"), "former looks label as Liquid Glass");
        check(DesignIds.label("classic").equals("One UI 8.5"), "label classic");
        check(DesignIds.normalize("material_3_expressive", true).equals(DesignIds.MATERIAL_3_EXPRESSIVE), "expressive kept");
        check(DesignIds.isMaterialExpressive("material_3_expressive") && !DesignIds.isMaterialExpressive("classic"), "expressive id");
        check(DesignIds.label("material_3_expressive").equals("Material 3 Expressive"), "label expressive");
        // First-run suggestion: advisory, and never a One UI version guessed from Android's.
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("Google", "google", 0)), "Pixel");
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("Google", null, 0)), "Google maker");
        check(DesignIds.ONE_UI_9.equals(DesignIds.suggestFor("samsung", "samsung", 90000)), "One UI 9 reported");
        check(DesignIds.ONE_UI_9.equals(DesignIds.suggestFor("samsung", "samsung", 90100)), "One UI 9.1 reported");
        check(DesignIds.CLASSIC.equals(DesignIds.suggestFor("samsung", "samsung", 80500)), "One UI 8.5 reported");
        check(DesignIds.CLASSIC.equals(DesignIds.suggestFor("samsung", "samsung", 0)), "One UI unknown stays default");
        check(DesignIds.suggestFor("OnePlus", "OnePlus", 0) == null, "others keep default");
        check(DesignIds.suggestFor(null, null, 0) == null, "nothing known");
        System.out.println("Design ids move both former Liquid Glass looks to the one Liquid Glass.");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
