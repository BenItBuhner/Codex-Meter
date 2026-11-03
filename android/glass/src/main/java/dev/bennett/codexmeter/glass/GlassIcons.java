package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.ui.Icons;

/**
 * The icons Codex Meter needs beyond Glassé's built-in set, in the same style (24 x 24, rounded
 * strokes like SF Symbols). Registered once; every kit component then takes them by name.
 */
public final class GlassIcons {
    private GlassIcons() {}

    private static boolean done;

    public static synchronized void register() {
        if (done) {
            return;
        }
        done = true;
        stroke("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4v4.5H15", 2.1);
        stroke("clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5.2l3.4 2", 2.0);
        stroke("calendar", "M5.5 5h13A2 2 0 0 1 20.5 7v11.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3.5 9.5h17M8 3v4M16 3v4", 1.9);
        stroke("creditcard", "M4.5 5.5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2zM2.5 10h19M6 15h4", 1.9);
        stroke("chart", "M5 20v-6M10 20V9M15 20v-8M20 20V5", 2.5);
        stroke("waveform", "M3 12h3.5l2.5-6.5 4.5 13 3-9 1.5 2.5H21", 2.0);
        stroke("lock", "M7.5 10.5V8a4.5 4.5 0 0 1 9 0v2.5M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-7a1.5 1.5 0 0 1 1.5-1.5z", 1.9);
        stroke("arrows", "M7 4v15.5M3.5 16 7 19.5 10.5 16M17 20V4.5M13.5 8 17 4.5 20.5 8", 2.0);
        stroke("history", "M3.8 12a8.2 8.2 0 1 0 2.4-5.8M4 3.5v4.4h4.4M12 7.5V12l3.2 2", 2.0);
        stroke("tabs", "M4 5.5h16a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 17V7A1.5 1.5 0 0 1 4 5.5zM2.5 14.5h19M9 14.5v4M15 14.5v4", 1.8);
        stroke("doc", "M7 3.5h7l4.5 4.5v11a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5zM14 3.5V8h4.5M9 12.5h6M9 16h6", 1.8);
        stroke("ladybug", "M12 8.5a4.5 5.5 0 0 1 4.5 5.5v1a4.5 4.5 0 0 1-9 0v-1A4.5 5.5 0 0 1 12 8.5zM9 7.5a3 3 0 0 1 6 0M12 8.5V20M4 12h3.5M16.5 12H20M4.5 18l3.3-1.5M19.5 18l-3.3-1.5M5 6.5l3 2.5M19 6.5l-3 2.5", 1.7);
        stroke("phone", "M8 2.5h8A1.5 1.5 0 0 1 17.5 4v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5zM10.5 18.5h3", 1.8);
        stroke("paintbrush", "M14.5 4.5l5 5-7.8 7.8-5-5zM6.7 12.3c-2.6.4-3.2 2.4-3.2 4.2 0 1.5-.7 2.3-1.5 2.5 1.4 1.3 6.2 2 8-.8.7-1 .7-2.2.2-3", 1.8);
        fill("bolt", "M13.4 2.2 4.6 13.4c-.3.4 0 .9.5.9H11l-1.1 7.2c-.1.5.6.8.9.4l8.8-11.2c.3-.4 0-.9-.5-.9H13l1.3-7.2c.1-.5-.6-.8-.9-.4z");
        fill("ellipsisCircle", "M12 2.5a9.5 9.5 0 1 1 0 19 9.5 9.5 0 0 1 0-19zM7.5 10.6a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8zm4.5 0a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8zm4.5 0a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z");
        stroke("person2", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 20.5c.6-3.9 3.6-6 7.5-6s6.9 2.1 7.5 6", 1.9);
        stroke("signout", "M14 4.5H6.5A1.5 1.5 0 0 0 5 6v12a1.5 1.5 0 0 0 1.5 1.5H14M10.5 12H21M17.5 8.5 21 12l-3.5 3.5", 1.9);
        stroke("widget", "M5 4.5h5A1.5 1.5 0 0 1 11.5 6v5A1.5 1.5 0 0 1 10 12.5H5A1.5 1.5 0 0 1 3.5 11V6A1.5 1.5 0 0 1 5 4.5zM14 4.5h5A1.5 1.5 0 0 1 20.5 6v14A1.5 1.5 0 0 1 19 21.5h-5a1.5 1.5 0 0 1-1.5-1.5V6A1.5 1.5 0 0 1 14 4.5zM5 15.5h5a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 20v-3A1.5 1.5 0 0 1 5 15.5z", 1.8);
    }

    /** A provider's logo from its vector drawable's path (24 x 24, filled). */
    public static void provider(String id, String pathData) {
        Icons.register("provider_" + id, pathData, 0.0, "F");
    }

    private static void stroke(String name, String d, double width) {
        Icons.register(name, d, width, "S");
    }

    private static void fill(String name, String d) {
        Icons.register(name, d, 0.0, "F");
    }
}
