/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.lyrics;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java part of the LRCLIB lyrics: no Android classes, so it is unit tested on the JVM.
 */
public final class LrcLibCore {
    public static final String BASE_URL = "https://lrclib.net/api/";

    /** Format names of the app's lyrics format enum (TEXT, LRC, RICH_JSON). */
    public static final String FORMAT_LRC = "LRC";
    public static final String FORMAT_TEXT = "TEXT";

    /** Maximum duration difference, in seconds, accepted for a search result. */
    static final int SEARCH_DURATION_TOLERANCE = 3;

    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private LrcLibCore() {
    }

    // region URLs

    public static String encode(String value) {
        try {
            // URLEncoder produces form encoding; LRCLIB expects %20 for spaces.
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** /api/get with exact artist, title and duration (LRCLIB matches duration within ±2 s). */
    public static String buildGetUrl(String artist, String title, long durationSeconds) {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("get?artist_name=").append(encode(artist))
                .append("&track_name=").append(encode(title));
        if (durationSeconds > 0) {
            url.append("&duration=").append(durationSeconds);
        }
        return url.toString();
    }

    public static String buildSearchUrl(String artist, String title) {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("search?track_name=").append(encode(title));
        if (artist != null && !artist.isEmpty()) {
            url.append("&artist_name=").append(encode(artist));
        }
        return url.toString();
    }

    /** Duration in whole seconds, rounded, from the app's duration in milliseconds. */
    public static long secondsFromMillis(long durationMs) {
        return durationMs <= 0 ? 0 : (durationMs + 500) / 1000;
    }

    /** Artist queries to try: all artists joined as LRCLIB usually stores them, then the first. */
    public static List<String> artistCandidates(List<String> artists) {
        List<String> result = new ArrayList<>();
        if (artists == null || artists.isEmpty()) {
            return result;
        }
        String joined = join(artists, ", ");
        if (!joined.isEmpty()) {
            result.add(joined);
        }
        String first = artists.get(0) == null ? "" : artists.get(0).trim();
        if (!first.isEmpty() && !result.contains(first)) {
            result.add(first);
        }
        return result;
    }

    static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(trimmed);
        }
        return builder.toString();
    }

    // endregion

    // region Lyrics selection

    /**
     * Choose the lyrics for the requested format from one LRCLIB record.
     *
     * Synced lyrics are preferred and returned as LRC. If only plain lyrics exist they are
     * returned as TEXT. When the app asked for TEXT, synced lyrics are stripped of timestamps
     * if no plain lyrics are available.
     *
     * @return {formatName, content}, or null if the record has no usable lyrics.
     */
    public static String[] chooseLyrics(Map<String, Object> record, String requestedFormat) {
        if (record == null) return null;
        if (Boolean.TRUE.equals(record.get("instrumental"))) return null;

        String synced = stringValue(record.get("syncedLyrics"));
        String plain = stringValue(record.get("plainLyrics"));

        if (FORMAT_TEXT.equals(requestedFormat)) {
            if (!isBlank(plain)) return new String[]{FORMAT_TEXT, plain.trim()};
            if (!isBlank(synced)) {
                String stripped = stripTimestamps(synced);
                if (!isBlank(stripped)) return new String[]{FORMAT_TEXT, stripped};
            }
            return null;
        }

        if (!isBlank(synced)) {
            String normalized = normalizeLrc(synced);
            if (!normalized.isEmpty()) return new String[]{FORMAT_LRC, normalized};
        }
        if (!isBlank(plain)) return new String[]{FORMAT_TEXT, plain.trim()};
        return null;
    }

    /**
     * Pick the best record from an /api/search result: closest duration within the tolerance,
     * synced lyrics first.
     */
    public static Map<String, Object> pickSearchResult(List<Object> results, long durationSeconds) {
        if (results == null) return null;
        Map<String, Object> best = null;
        long bestScore = Long.MAX_VALUE;
        for (Object item : results) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) item;
            if (Boolean.TRUE.equals(record.get("instrumental"))) continue;

            boolean hasSynced = !isBlank(stringValue(record.get("syncedLyrics")));
            boolean hasPlain = !isBlank(stringValue(record.get("plainLyrics")));
            if (!hasSynced && !hasPlain) continue;

            long difference = 0;
            if (durationSeconds > 0) {
                Object duration = record.get("duration");
                if (!(duration instanceof Number)) continue;
                difference = Math.abs(Math.round(((Number) duration).doubleValue()) - durationSeconds);
                if (difference > SEARCH_DURATION_TOLERANCE) continue;
            }

            long score = difference * 2 + (hasSynced ? 0 : 1);
            if (score < bestScore) {
                bestScore = score;
                best = record;
            }
        }
        return best;
    }

    // endregion

    // region LRC

    /**
     * Normalize LRC for the app's parser, which only accepts {@code [mm:ss.xx]text}:
     * minutes are padded to two digits, milliseconds are truncated to hundredths, missing
     * fractions become {@code .00}, a line with several timestamps is split into one line per
     * timestamp, metadata tags and untimed lines are dropped, and lines are sorted by time.
     */
    public static String normalizeLrc(String lrc) {
        if (lrc == null) return "";
        List<long[]> order = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (String rawLine : lrc.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            Matcher matcher = TIMESTAMP.matcher(rawLine);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            while (matcher.find() && matcher.start() == textStart) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long hundredths = 0;
                if (fraction != null) {
                    if (fraction.length() == 1) hundredths = Long.parseLong(fraction) * 10;
                    else hundredths = Long.parseLong(fraction.substring(0, 2));
                }
                times.add((minutes * 60 + seconds) * 100 + hundredths);
                textStart = matcher.end();
            }
            if (times.isEmpty()) continue;

            String text = rawLine.substring(textStart).trim();
            for (long time : times) {
                order.add(new long[]{time, lines.size()});
                lines.add(formatTimestamp(time) + text);
            }
        }

        Collections.sort(order, (a, b) -> {
            int byTime = Long.compare(a[0], b[0]);
            return byTime != 0 ? byTime : Long.compare(a[1], b[1]);
        });

        StringBuilder result = new StringBuilder();
        for (long[] entry : order) {
            if (result.length() > 0) result.append('\n');
            result.append(lines.get((int) entry[1]));
        }
        return result.toString();
    }

    static String formatTimestamp(long hundredthsTotal) {
        long minutes = hundredthsTotal / 6000;
        long seconds = (hundredthsTotal / 100) % 60;
        long hundredths = hundredthsTotal % 100;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths);
    }

    public static String stripTimestamps(String lrc) {
        String normalized = normalizeLrc(lrc);
        StringBuilder result = new StringBuilder();
        for (String line : normalized.split("\n")) {
            if (line.isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            result.append(line.substring(line.indexOf(']') + 1));
        }
        return result.toString().trim();
    }

    // endregion

    // region Minimal JSON reader (LRCLIB responses only; avoids org.json and extra dependencies)

    public static Object parseJson(String json) {
        JsonReader reader = new JsonReader(json);
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (!reader.atEnd()) throw new IllegalArgumentException("Trailing JSON content");
        return value;
    }

    private static final class JsonReader {
        private final String text;
        private int position;

        JsonReader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(position))) position++;
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = text.charAt(position);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, position)) {
                throw new IllegalArgumentException("Invalid JSON literal at " + position);
            }
            position += literal.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            position++; // {
            skipWhitespace();
            if (!atEnd() && text.charAt(position) == '}') {
                position++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (atEnd() || text.charAt(position) != ':') throw new IllegalArgumentException("Expected ':'");
                position++;
                map.put(key, readValue());
                skipWhitespace();
                if (atEnd()) throw new IllegalArgumentException("Unterminated object");
                char c = text.charAt(position++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("Expected ',' in object");
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            position++; // [
            skipWhitespace();
            if (!atEnd() && text.charAt(position) == ']') {
                position++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) throw new IllegalArgumentException("Unterminated array");
                char c = text.charAt(position++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("Expected ',' in array");
            }
        }

        private String readString() {
            if (atEnd() || text.charAt(position) != '"') throw new IllegalArgumentException("Expected string");
            position++;
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (atEnd()) throw new IllegalArgumentException("Unterminated string");
                char c = text.charAt(position++);
                if (c == '"') return builder.toString();
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                if (atEnd()) throw new IllegalArgumentException("Unterminated escape");
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (position + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
                        builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Bad escape \\" + escaped);
                }
            }
        }

        private Number readNumber() {
            int start = position;
            while (!atEnd() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
            String number = text.substring(start, position);
            if (number.isEmpty()) throw new IllegalArgumentException("Unexpected JSON character at " + start);
            if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }
    }

    // endregion

    static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
