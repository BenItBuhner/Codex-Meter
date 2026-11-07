package androidx.preference;

import androidx.appcompat.util.SeslRoundedCorner;

/**
 * SESL's PreferenceFragmentCompat rounds the visible top and bottom edges of its list, a One UI 8
 * touch that boxes the list into a card where it meets the app bar. One UI 9 lets the list run
 * under the floating toolbar and fade instead, so the One UI 9 design turns those list corners
 * off. The field is package-private, which is why this lives in androidx.preference. Each row
 * group keeps its own rounded card.
 */
public final class PreferenceListCorners {
    private PreferenceListCorners() {
    }

    public static void drop(PreferenceFragmentCompat fragment) {
        if (fragment.mListRoundedCorner != null) {
            fragment.mListRoundedCorner.setRoundedCorners(SeslRoundedCorner.ROUNDED_CORNER_NONE);
        }
    }
}
