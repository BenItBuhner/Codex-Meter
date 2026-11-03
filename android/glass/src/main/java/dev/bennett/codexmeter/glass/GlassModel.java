package dev.bennett.codexmeter.glass;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Liquid Glass screens show, as plain data. The app fills these in from its own state
 * (usage snapshots, settings); the screens in {@link CodexGlass} lay them out with Glassé's kit.
 */
public final class GlassModel {
    private GlassModel() {}

    /** A provider tab in the dashboard's tab bar. */
    public static final class Tab {
        public final String id;
        public final String label;
        public final String icon;

        public Tab(String id, String label, String icon) {
            this.id = id;
            this.label = label;
            this.icon = icon;
        }
    }

    /** One block on the dashboard. */
    public static final class Card {
        public enum Kind { METER, BALANCE, LINK, HISTORY, UPDATE, SIGN_IN, MESSAGE, ERROR }

        public final Kind kind;
        public String title;
        public String value;
        public String summary;
        public String detail;
        public String icon;
        public String color;
        /** METER: the share left, 0 to 1. */
        public double remaining;
        /** METER: usage is running faster than the window allows. */
        public boolean fast;
        /** HISTORY: bar heights, 0 to 1, oldest first. */
        public double[] bars;
        /** HISTORY: labels under the first and last bar. */
        public String startLabel;
        public String endLabel;
        /** UPDATE and SIGN_IN: the button. */
        public String button;
        public Runnable onPress;

        public Card(Kind kind) {
            this.kind = kind;
        }
    }

    /** A provider's dashboard: its cards in order, and the line under the title. */
    public static final class Dashboard {
        public String title = "";
        public String subtitle;
        public final List<Card> cards = new ArrayList<>();
    }

    /** A settings row. */
    public static final class Row {
        public enum Kind {
            /** opens another settings page (link) or runs press() */
            LINK,
            /** a switch */
            SWITCH,
            /** a row with a switch that also opens its page */
            SWITCH_LINK,
            /** picks one of {@link #entries}, on a page of its own */
            CHOICE,
            /** a segmented control across the row (a few short options) */
            SEGMENTED,
            /** a button-like row that runs press() */
            ACTION,
            /** text only */
            INFO
        }

        public final Kind kind;
        public final String key;
        public String title = "";
        public String summary;
        public String value;
        public String icon;
        public String color;
        public boolean checked;
        public boolean enabled = true;
        public boolean destructive;
        public String[] entries;
        public int selected = -1;
        /** LINK and SWITCH_LINK: the page this row opens, when it is a settings page. */
        public String link;

        public Row(Kind kind, String key) {
            this.kind = kind;
            this.key = key;
        }
    }

    /** A group of rows under an optional header, with an optional footer. */
    public static final class Section {
        public String header;
        public String footer;
        public final List<Row> rows = new ArrayList<>();

        public Section(String header) {
            this.header = header;
        }
    }

    /** A settings page. */
    public static final class Page {
        public final String id;
        public String title;
        public final List<Section> sections = new ArrayList<>();

        public Page(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** An alert (no items) or a sheet (items to pick from). */
    public static final class Dialog {
        public static final String CANCEL = "cancel";
        public static final String DEFAULT = "default";
        public static final String PROMINENT = "prominent";
        public static final String DESTRUCTIVE = "destructive";

        public static final class Button {
            public final String label;
            public final String style;
            public final Runnable onPress;

            public Button(String label, String style, Runnable onPress) {
                this.label = label;
                this.style = style;
                this.onPress = onPress;
            }
        }

        public interface OnItem {
            void pick(int index, boolean checked);
        }

        public String title = "";
        public String message;
        public final List<Button> buttons = new ArrayList<>();
        public String[] items;
        /** single choice: the picked item; -1 for none */
        public int selected = -1;
        /** multiple choice: each item's switch */
        public boolean[] checked;
        public OnItem onItem;
        /** runs when the dialog closes, however it closes */
        public Runnable onClose;
    }
}
