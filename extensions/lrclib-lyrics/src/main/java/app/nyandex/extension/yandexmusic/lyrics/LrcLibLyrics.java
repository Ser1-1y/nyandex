/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.lyrics;

import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Called from the patched app.
 *
 * {@link #rememberTrack} runs at the end of the track model constructor and keeps the metadata
 * LRCLIB needs (the lyrics repository only receives the track id). {@link #replacePaywall} runs
 * right before the app parses downloaded lyrics and replaces only Yandex's Plus notice («Подключите
 * подписку на Плюс, чтобы видеть тексты песен»), which accounts without Plus receive instead of
 * the lyrics. Real lyrics and errors are left to the app.
 */
@SuppressWarnings("unused")
public final class LrcLibLyrics {
    private static final String TAG = "NyandexLrcLib";
    private static final String USER_AGENT =
            "Nyandex/1.0 (Yandex Music lyrics fallback)";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_TRACKS = 512;
    private static final String[] NOT_FOUND = new String[0];

    private static final class Track {
        final String title;
        final long durationMs;
        final List<?> artists;

        Track(String title, long durationMs, List<?> artists) {
            this.title = title;
            this.durationMs = durationMs;
            this.artists = artists;
        }
    }

    private static final Map<String, Track> TRACKS = new LinkedHashMap<String, Track>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Track> eldest) {
            return size() > MAX_TRACKS;
        }
    };

    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();

    private LrcLibLyrics() {
    }

    /** Injected at the end of the track model constructor. Must stay cheap and never throw. */
    public static void rememberTrack(String id, String title, long durationMs, List<?> artists) {
        try {
            if (id == null || title == null) return;
            synchronized (TRACKS) {
                TRACKS.put(id, new Track(title, durationMs, artists));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Called right before the app parses downloaded lyrics.
     *
     * @param trackId Track id passed to the lyrics repository.
     * @param format  The app's requested lyrics format enum (TEXT, LRC, RICH_JSON).
     * @param info    The app's lyrics download info (unused; it holds a signed download URL).
     * @param content The downloaded lyrics text.
     * @return {formatName, content} with LRCLIB lyrics if {@code content} is the Plus notice,
     * otherwise null so the app parses its own lyrics unchanged.
     */
    public static String[] replacePaywall(String trackId, Object format, Object info, String content) {
        try {
            if (!LrcLibCore.isPaywallText(content)) return null;

            String requested = format instanceof Enum ? ((Enum<?>) format).name() : String.valueOf(format);
            Log.i(TAG, "Plus notice instead of lyrics for track " + trackId + " (" + requested + "): "
                    + content.replace('\n', ' '));
            if (trackId == null) return null;

            String cacheKey = trackId + '|' + requested;
            String[] cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached.length == 0 ? null : cached;
            }

            Track track;
            synchronized (TRACKS) {
                track = TRACKS.get(trackId);
                if (track == null) {
                    int separator = trackId.indexOf(':');
                    if (separator > 0) track = TRACKS.get(trackId.substring(0, separator));
                }
            }
            if (track == null) {
                Log.w(TAG, "No metadata captured for track " + trackId);
                return null;
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                // Never block the UI thread; the repository normally runs on a background dispatcher.
                Log.w(TAG, "Called on the main thread, skipping LRCLIB");
                return null;
            }

            String[] lyrics = query(track, requested);
            CACHE.put(cacheKey, lyrics == null ? NOT_FOUND : lyrics);
            Log.i(TAG, lyrics == null
                    ? "LRCLIB has no lyrics for " + track.title
                    : "Using LRCLIB " + lyrics[0] + " lyrics for " + track.title);
            return lyrics;
        } catch (Throwable throwable) {
            Log.e(TAG, "LRCLIB fallback failed", throwable);
            return null;
        }
    }

    private static String[] query(Track track, String requested) throws Exception {
        List<String> artists = new ArrayList<>();
        if (track.artists != null) {
            for (Object artist : track.artists) {
                if (artist != null) artists.add(String.valueOf(artist));
            }
        }
        long seconds = LrcLibCore.secondsFromMillis(track.durationMs);
        List<String> candidates = LrcLibCore.artistCandidates(artists);

        for (String artist : candidates) {
            String body = get(LrcLibCore.buildGetUrl(artist, track.title, seconds));
            if (body == null) continue;
            Object json = LrcLibCore.parseJson(body);
            if (json instanceof Map) {
                @SuppressWarnings("unchecked")
                String[] lyrics = LrcLibCore.chooseLyrics((Map<String, Object>) json, requested);
                if (lyrics != null) return lyrics;
            }
        }

        String searchArtist = candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
        String body = get(LrcLibCore.buildSearchUrl(searchArtist, track.title));
        if (body == null) return null;
        Object json = LrcLibCore.parseJson(body);
        if (!(json instanceof List)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> best = LrcLibCore.pickSearchResult((List<Object>) json, seconds);
        return LrcLibCore.chooseLyrics(best, requested);
    }

    /** @return The response body for HTTP 200, null for 404 and other statuses. */
    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Lrclib-Client", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "LRCLIB HTTP " + status + " for " + url);
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

}
