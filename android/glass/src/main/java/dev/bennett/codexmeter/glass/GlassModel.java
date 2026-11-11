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
        public enum Kind {
            METER, BALANCE, LINK, HISTORY, UPDATE, SIGN_IN, MESSAGE, ERROR,
            /** a usage burn chart ({@link #chart}) */
            CHART,
            /** a title over label and value lines ({@link #stats}), with a note under them */
            STATS,
            /** a paragraph of secondary text in a card */
            NOTE,
            /** a section label ({@link #title}) over the cards that follow it */
            HEADING
        }

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
        /** METER: the pace line under the bar, label and value ("At this pace", "lasts 3d 4h"); null for none. */
        public String paceLabel;
        public String pace;
        /** HISTORY: bar heights, 0 to 1, oldest first. */
        public double[] bars;
        /** HISTORY: labels under the first and last bar. */
        public String startLabel;
        public String endLabel;
        /** UPDATE and SIGN_IN: the button. */
        public String button;
        public Runnable onPress;
        /** UPDATE: the button is shown but does nothing (work in progress). */
        public boolean buttonEnabled = true;
        /** UPDATE: a progress bar under the button, 0 to 1; negative hides it. */
        public double progress = -1;
        /** UPDATE: the summary is a warning. */
        public boolean warning;
        /** CHART: the chart. */
        public Chart chart;
        /** STATS: label and value pairs. */
        public final List<String[]> stats = new ArrayList<>();
        /**
         * STATS: per row, what dismissing it does (a swipe left, or touch and hold for a menu); a
         * null entry, or a list shorter than {@link #stats}, leaves that row fixed.
         */
        public final List<Runnable> statDismiss = new ArrayList<>();
        /** STATS: "Dismiss all" in a row's hold menu; null for none. */
        public Runnable dismissAll;

        public Card(Kind kind) {
            this.kind = kind;
        }
    }

    /**
     * A usage window's burn chart: this window's line, faint earlier windows on the same axis,
     * the even-pace diagonal and the projection. x runs 0 to 1 across the window, y is % used.
     */
    public static final class Chart {
        public String label = "";
        public String sampleLabel = "";
        /** this window: x, y pairs */
        public double[] current = new double[0];
        /** earlier windows, oldest first: x, y pairs each */
        public final List<double[]> previous = new ArrayList<>();
        /** the projection from the last point: x0, y0, x1, y1; null for none */
        public double[] projection;
        public boolean projectionWarning;
        /** what's under the finger: window -1 is this one, else an index into previous */
        public Scrub scrub;
        /** rows under the chart that pick a window: title, subtitle, window index (-1 current) */
        public final List<Object[]> windows = new ArrayList<>();
        public String hint;
        public String emptyText;
        /** Show a legend of the lines under the chart. */
        public boolean legend;
    }

    public interface Scrub {
        /** The label for {@code ratio} (0 to 1) across window {@code window}, or null off the line. */
        String at(int window, double ratio);

        /** The % used at {@code ratio} across window {@code window}, or -1. */
        double percent(int window, double ratio);
    }

    public interface OnToggle {
        void on(boolean value);
    }

    public interface OnText {
        void on(String text);
    }

    public interface OnPick {
        void on(int index);
    }

    public interface OnSlide {
        void on(double value);
    }

    public interface Mover {
        void move(int from, int to);
    }

    /** A button in a page's nav bar. */
    public static final class Action {
        public final String label;
        public final String icon;
        public final boolean prominent;
        public final Runnable onPress;

        public Action(String label, String icon, boolean prominent, Runnable onPress) {
            this.label = label;
            this.icon = icon;
            this.prominent = prominent;
            this.onPress = onPress;
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
            INFO,
            /** a full-width filled button ({@link #onPress}) */
            BUTTON,
            /** a slider, 0 to 1 ({@link #fraction}, {@link #onSlide}) */
            SLIDER,
            /** a picture ({@link #image}) across the row, {@link #imageHeight} tall */
            IMAGE,
            /** a paragraph of text in the list */
            TEXT,
            /** one option of several, with a check when {@link #checked} ({@link #onPress} picks it) */
            RADIO,
            /** a text field: {@link #value} as typed, {@link #hint} when empty, {@link #onText} on each change;
             *  {@link #onPress}, when set, is a Paste button at its end */
            FIELD,
            /** a large centered code or number ({@link #value}), {@link #title} above it, {@link #summary} under */
            CODE
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
        /** A picture at the start of the row (PNG bytes), or the IMAGE row's picture. */
        public byte[] image;
        public boolean roundImage;
        public double imageSize = 30;
        public double imageHeight = 180;
        /** A provider mark or other icon drawn in the text color, without a colored tile. */
        public boolean plainIcon;
        /** SLIDER: the position, 0 to 1. */
        public double fraction;
        /** SLIDER: the number of stops (2 or more), or 0 for a smooth slider. */
        public int steps;
        /** A small capsule after the title ("Experimental"). */
        public String tag;
        /** FIELD: the placeholder. */
        public String hint;
        /** FIELD: draws dots in place of the text. */
        public boolean secret;
        public OnText onText;
        /** Handlers for rows that are not settings preferences; null falls back to the source. */
        public Runnable onPress;
        public OnToggle onToggle;
        public OnPick onChoose;
        public OnSlide onSlide;

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
        /** Cards shown in place of the rows (pages that reuse the dashboard's blocks). */
        public final List<Card> cards = new ArrayList<>();
        /** Rows get drag handles; a drop calls this with the old and new positions. */
        public Mover onMove;

        public Section(String header) {
            this.header = header;
        }
    }

    /** A settings page. */
    public static final class Page {
        public final String id;
        public String title;
        public final List<Section> sections = new ArrayList<>();
        /** Buttons at the end of the nav bar. */
        public final List<Action> actions = new ArrayList<>();
        /** Text under the large title. */
        public String intro;

        public Page(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** A number with − and + buttons over a unit picker. */
    public static final class Amount {
        public int value = 1;
        public int min = 1;
        public int max = 999;
        public String[] units = new String[0];
        public int unit;
        /** under the picker; red when {@link #error} */
        public String note;
        public boolean error;
        public OnPick onValue;
        public OnPick onUnit;
    }

    /** An alert (no items) or a sheet (items or an amount to pick). */
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
        /** an amount and its unit to pick (a reminder time, say), in a sheet; null for none */
        public Amount amount;
        /** runs when the dialog closes, however it closes */
        public Runnable onClose;
    }
}
