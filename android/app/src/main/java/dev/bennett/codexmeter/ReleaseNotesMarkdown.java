package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the GitHub release-note Markdown subset used by Codex Meter into HTML that
 * {@code android.text.Html} can render inside TextViews.
 */
public final class ReleaseNotesMarkdown {
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|_(.+?)_");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern AUTOLINK = Pattern.compile(
            "(?<![\"'>])(https?://[\\w.-]+(?:/[\\w\\-./?%&=+#~:@,]*)?)");

    private ReleaseNotesMarkdown() {
    }

    public static String toHtml(String markdown) {
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        source = HTML_COMMENT.matcher(source).replaceAll("");
        source = source.trim();
        if (source.isEmpty()) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        String[] lines = source.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                index++;
                continue;
            }
            if (isHorizontalRule(trimmed)) {
                html.append("<hr>");
                index++;
                continue;
            }
            int headingLevel = headingLevel(trimmed);
            if (headingLevel > 0) {
                String text = trimmed.substring(headingLevel).trim();
                html.append("<p><b>").append(inline(text)).append("</b></p>");
                index++;
                continue;
            }
            if (isUnorderedItem(trimmed)) {
                html.append("<ul>");
                while (index < lines.length && isUnorderedItem(lines[index].trim())) {
                    html.append("<li>").append(inline(stripUnorderedMarker(lines[index].trim())))
                            .append("</li>");
                    index++;
                }
                html.append("</ul>");
                continue;
            }
            if (isOrderedItem(trimmed)) {
                html.append("<ol>");
                while (index < lines.length && isOrderedItem(lines[index].trim())) {
                    html.append("<li>").append(inline(stripOrderedMarker(lines[index].trim())))
                            .append("</li>");
                    index++;
                }
                html.append("</ol>");
                continue;
            }

            List<String> paragraph = new ArrayList<>();
            while (index < lines.length) {
                String candidate = lines[index].trim();
                if (candidate.isEmpty()
                        || isHorizontalRule(candidate)
                        || headingLevel(candidate) > 0
                        || isUnorderedItem(candidate)
                        || isOrderedItem(candidate)) {
                    break;
                }
                paragraph.add(candidate);
                index++;
            }
            html.append("<p>");
            for (int part = 0; part < paragraph.size(); part++) {
                if (part > 0) {
                    html.append("<br>");
                }
                html.append(inline(paragraph.get(part)));
            }
            html.append("</p>");
        }
        return html.toString();
    }

    private static String inline(String text) {
        String escaped = escapeHtml(text == null ? "" : text);
        escaped = replaceAll(LINK, escaped, matcher -> {
            String label = matcher.group(1);
            String url = unescapeBasicEntities(matcher.group(2));
            if (!isSafeUrl(url)) {
                return matcher.group(0);
            }
            return "<a href=\"" + escapeAttribute(url) + "\">" + label + "</a>";
        });
        escaped = replaceAll(INLINE_CODE, escaped, matcher ->
                "<code>" + matcher.group(1) + "</code>");
        escaped = replaceAll(BOLD, escaped, matcher -> {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return "<b>" + value + "</b>";
        });
        escaped = replaceAll(ITALIC, escaped, matcher -> {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return "<i>" + value + "</i>";
        });
        escaped = replaceAll(AUTOLINK, escaped, matcher -> {
            String displayed = matcher.group(1);
            String url = unescapeBasicEntities(displayed);
            if (!isSafeUrl(url) || urlContainsHtml(url)) {
                return displayed;
            }
            return "<a href=\"" + escapeAttribute(url) + "\">" + displayed + "</a>";
        });
        return escaped;
    }

    private static String unescapeBasicEntities(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private interface Replacer {
        String replace(Matcher matcher);
    }

    private static String replaceAll(Pattern pattern, String input, Replacer replacer) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacer.replace(matcher)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isSafeUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static boolean urlContainsHtml(String url) {
        return url.indexOf('<') >= 0 || url.indexOf('>') >= 0 || url.indexOf('"') >= 0;
    }

    private static int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6 || level >= trimmed.length() || trimmed.charAt(level) != ' ') {
            return 0;
        }
        return level;
    }

    private static boolean isHorizontalRule(String trimmed) {
        return "---".equals(trimmed) || "***".equals(trimmed) || "___".equals(trimmed)
                || "----".equals(trimmed) || "*****".equals(trimmed);
    }

    private static boolean isUnorderedItem(String trimmed) {
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ");
    }

    private static String stripUnorderedMarker(String trimmed) {
        return trimmed.substring(2).trim();
    }

    private static boolean isOrderedItem(String trimmed) {
        int index = 0;
        while (index < trimmed.length() && trimmed.charAt(index) >= '0'
                && trimmed.charAt(index) <= '9') {
            index++;
        }
        return index > 0 && index + 1 < trimmed.length()
                && trimmed.charAt(index) == '.'
                && trimmed.charAt(index + 1) == ' ';
    }

    private static String stripOrderedMarker(String trimmed) {
        int index = 0;
        while (index < trimmed.length() && trimmed.charAt(index) >= '0'
                && trimmed.charAt(index) <= '9') {
            index++;
        }
        return trimmed.substring(index + 2).trim();
    }

    private static String escapeHtml(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&':
                    builder.append("&amp;");
                    break;
                case '<':
                    builder.append("&lt;");
                    break;
                case '>':
                    builder.append("&gt;");
                    break;
                case '"':
                    builder.append("&quot;");
                    break;
                default:
                    builder.append(character);
                    break;
            }
        }
        return builder.toString();
    }

    private static String escapeAttribute(String value) {
        return escapeHtml(value).replace("'", "&#39;");
    }
}
