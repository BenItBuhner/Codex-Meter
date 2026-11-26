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
        // One UI 9.5 is its own saved design, and part of the One UI 9 family (same page shell).
        check(DesignIds.normalize("one_ui_9_5", false).equals(DesignIds.ONE_UI_9_5), "One UI 9.5 kept");
        check(DesignIds.normalize("one_ui_9_5", true).equals(DesignIds.ONE_UI_9_5), "One UI 9.5 beats the old switch");
        check(DesignIds.label("one_ui_9_5").equals("One UI 9.5"), "label One UI 9.5");
        // Builds without One UI 9.5 (alphas and stable while it is internal) read and save it as One UI 9.
        check(DesignIds.normalize("one_ui_9_5", false, false).equals(DesignIds.ONE_UI_9), "hidden 9.5 reads as 9");
        check(DesignIds.normalize("one_ui_9_5", false, true).equals(DesignIds.ONE_UI_9_5), "available 9.5 kept");
        check(DesignIds.forSave("one_ui_9_5", false).equals(DesignIds.ONE_UI_9), "hidden 9.5 saves as 9");
        check(DesignIds.normalize("liquid_glass", false, false).equals(DesignIds.LIQUID_GLASS), "others untouched");
        check(DesignIds.ONE_UI_9.equals(DesignIds.suggestFor("samsung", "samsung", 90500, false)), "9.5 phone gets 9 when hidden");
        check(DesignIds.isOneUi9Family("one_ui_9") && DesignIds.isOneUi9Family("one_ui_9_5")
                && !DesignIds.isOneUi9Family("classic") && !DesignIds.isOneUi9Family("liquid_glass"), "One UI 9 family");
        check(DesignIds.normalize("material_3_expressive", true).equals(DesignIds.MATERIAL_3_EXPRESSIVE), "expressive kept");
        check(DesignIds.isMaterialExpressive("material_3_expressive") && !DesignIds.isMaterialExpressive("classic"), "expressive id");
        check(DesignIds.label("material_3_expressive").equals("Material 3 Expressive"), "label expressive");
        // First-run suggestion: advisory, and never a One UI version guessed from Android's.
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("Google", "google", 0)), "Pixel");
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("Google", null, 0)), "Google maker");
        check(DesignIds.ONE_UI_9.equals(DesignIds.suggestFor("samsung", "samsung", 90000)), "One UI 9 reported");
        check(DesignIds.ONE_UI_9.equals(DesignIds.suggestFor("samsung", "samsung", 90100)), "One UI 9.1 reported");
        check(DesignIds.ONE_UI_9_5.equals(DesignIds.suggestFor("samsung", "samsung", 90500)), "One UI 9.5 reported");
        check(DesignIds.ONE_UI_9_5.equals(DesignIds.suggestFor("samsung", "samsung", 100000)), "later One UI gets 9.5");
        check(DesignIds.CLASSIC.equals(DesignIds.suggestFor("samsung", "samsung", 80500)), "One UI 8.5 reported");
        check(DesignIds.CLASSIC.equals(DesignIds.suggestFor("samsung", "samsung", 0)), "One UI unknown stays default");
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("OnePlus", "OnePlus", 0)), "OnePlus");
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("motorola", "motorola", 0)), "Motorola");
        check(DesignIds.MATERIAL_3_EXPRESSIVE.equals(DesignIds.suggestFor("Nothing", "Nothing", 0)), "Nothing");
        check(DesignIds.ONE_UI_9.equals(DesignIds.suggestFor("SAMSUNG", " Samsung ", 90000)), "Samsung casing");
        check(DesignIds.suggestFor(null, null, 0) == null, "nothing known");
        check(DesignIds.suggestFor("unknown", "", 0) == null, "unknown maker keeps default");
        System.out.println("Design ids move both former Liquid Glass looks to the one Liquid Glass.");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
