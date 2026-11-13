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
        // SF Symbols' gearshape and square.and.pencil, for the dashboard's Settings and Edit buttons
        stroke("gearshape", "M10.05 4.35L10.52 2.21A9.9 9.9 0 0 1 13.48 2.21L13.95 4.35A7.9 7.9 0 0 1 16.03 5.21L16.03 5.21L17.88 4.03A9.9 9.9 0 0 1 19.97 6.12L18.79 7.97A7.9 7.9 0 0 1 19.65 10.05L19.65 10.05L21.79 10.52A9.9 9.9 0 0 1 21.79 13.48L19.65 13.95A7.9 7.9 0 0 1 18.79 16.03L18.79 16.03L19.97 17.88A9.9 9.9 0 0 1 17.88 19.97L16.03 18.79A7.9 7.9 0 0 1 13.95 19.65L13.95 19.65L13.48 21.79A9.9 9.9 0 0 1 10.52 21.79L10.05 19.65A7.9 7.9 0 0 1 7.97 18.79L7.97 18.79L6.12 19.97A9.9 9.9 0 0 1 4.03 17.88L5.21 16.03A7.9 7.9 0 0 1 4.35 13.95L4.35 13.95L2.21 13.48A9.9 9.9 0 0 1 2.21 10.52L4.35 10.05A7.9 7.9 0 0 1 5.21 7.97L5.21 7.97L4.03 6.12A9.9 9.9 0 0 1 6.12 4.03L7.97 5.21A7.9 7.9 0 0 1 10.05 4.35ZM12 8.6a3.4 3.4 0 1 0 0 6.8a3.4 3.4 0 1 0 0-6.8Z", 1.65);
        stroke("squarePencil", "M11 4.8H6.3A2.3 2.3 0 0 0 4 7.1v10.6A2.3 2.3 0 0 0 6.3 20h10.6a2.3 2.3 0 0 0 2.3-2.3V13M18.1 3.6a1.6 1.6 0 0 1 2.3 2.3L12.6 13.7l-3.1.8.8-3.1Z", 1.65);
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
        // GitHub's mark, for About's GitHub button
        fill("github", "M12 .5C5.73.5.5 5.73.5 12c0 5.08 3.29 9.39 7.86 10.91.58.1.79-.25.79-.56v-1.97c-3.2.7-3.87-1.54-3.87-1.54-.52-1.33-1.28-1.69-1.28-1.69-1.04-.71.08-.7.08-.7 1.15.08 1.76 1.19 1.76 1.19 1.03 1.76 2.69 1.25 3.35.96.1-.74.4-1.25.73-1.54-2.55-.29-5.24-1.28-5.24-5.68 0-1.25.45-2.28 1.19-3.08-.12-.29-.52-1.46.11-3.04 0 0 .97-.31 3.17 1.18a11 11 0 0 1 5.77 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.58.23 2.75.11 3.04.74.8 1.19 1.83 1.19 3.08 0 4.41-2.69 5.38-5.25 5.67.41.36.78 1.06.78 2.14v3.17c0 .31.21.67.8.56C20.21 21.39 23.5 17.08 23.5 12 23.5 5.73 18.27.5 12 .5z");
        // the Priority tab's flag (res/drawable/ic_priority.xml)
        stroke("flag", "M5.5 21V4.5M5.5 4.8c2.2-1.3 4.3-1.3 6.5 0s4.3 1.3 6.5 0v8.7c-2.2 1.3-4.3 1.3-6.5 0s-4.3-1.3-6.5 0", 1.9);
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
