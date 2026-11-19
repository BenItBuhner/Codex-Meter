package dev.bennett.codexmeter;

import java.util.HashSet;
import java.util.Set;

/** The What's new page: shown once per big release, only to people who used the app before it. */
public final class WhatsNewSelfTest {
    public static void main(String[] args) {
        check(WhatsNew.shouldShow(null, true), "an upgrade that never saw it shows it");
        check(WhatsNew.shouldShow("2.8", true), "an older page seen still shows the new one");
        check(!WhatsNew.shouldShow(WhatsNew.RELEASE, true), "seen once, never again");
        check(!WhatsNew.shouldShow(null, false), "a new install sets up instead");
        check(!WhatsNew.shouldShow(WhatsNew.RELEASE, false), "a new install that finished setup");
        check(WhatsNew.TITLE.contains(WhatsNew.RELEASE), "the title names the release");

        Set<String> icons = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (WhatsNew.Feature feature : WhatsNew.FEATURES) {
            check(icons.add(feature.icon), "one feature per icon: " + feature.icon);
            check(titles.add(feature.title), "titles differ: " + feature.title);
            check(!feature.title.isEmpty() && !feature.title.endsWith("."), "title: " + feature.title);
            check(feature.body.endsWith(".") && feature.body.length() <= 110,
                    "one short sentence: " + feature.body);
        }
        check(WhatsNew.FEATURES.size() >= 8, "every new feature is listed");
        System.out.println("What's new shows once per release, to upgrades only, "
                + WhatsNew.FEATURES.size() + " features.");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
