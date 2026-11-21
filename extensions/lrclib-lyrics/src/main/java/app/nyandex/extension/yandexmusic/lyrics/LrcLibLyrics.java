/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Called from the patched app. Lyrics come only from LRCLIB; the app's own lyrics API is not used.
 *
 * {@link #rememberTrack} runs at the end of the track model constructor and keeps the metadata
 * LRCLIB needs (the lyrics repository only receives the track id). {@link #lyrics} replaces the
 * lyrics repository's download: it returns LRCLIB lyrics, or null when LRCLIB has none or cannot
 * be reached. Only an unreachable LRCLIB is shown to the user, as a toast. {@link #prefetch}
 * runs whenever the player asks for the next item of the queue and loads that track's lyrics
 * in the background.
 *
 * Answers are kept in memory and on disk (the app's cache directory), so replays and offline
 * tracks need no network. "No lyrics" is kept for {@link #MISS_TTL_MS} because LRCLIB grows.
 */
@SuppressWarnings("unused")
public final class LrcLibLyrics {
    private static final String TAG = "NyandexLrcLib";
    private static final String USER_AGENT =
            "Nyandex/1.0 (Yandex Music lyrics from LRCLIB)";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_TRACKS = 512;
    private static final String[] NOT_FOUND = new String[0];
    private static final long TOAST_INTERVAL_MS = 30_000;
    private static final String UNREACHABLE_MESSAGE = "Не удалось подключиться к LRCLIB";

    /** How long "LRCLIB has no lyrics for this track" is trusted. */
    static final long MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000;
    private static final String CACHE_DIRECTORY = "nyandex-lyrics";
    /** Default number of answers kept on disk; the settings page can change it (0 turns the disk cache off). */
    static final int DEFAULT_CACHE_LIMIT = 3000;
    private static final int PRUNE_EVERY_WRITES = 25;

    /** Format the app asks for when a track has synced lyrics; every track has them after the patch. */
    private static final String PREFETCH_FORMAT = LrcLibCore.FORMAT_LRC;
    private static final int MAX_PREFETCHED = 256;

    /** Preferences of the Nyandex settings page (same file as NyandexSettings). */
    static final String PREFERENCES = "nyandex";
    /** Lyrics timing offset in milliseconds; positive shows lines earlier. */
    static final String OFFSET_KEY = "lyrics_offset_ms";
    /** Whether the next track's lyrics are loaded in advance (default on). */
    static final String PREFETCH_KEY = "lyrics_prefetch";
    /** Maximum number of answers kept on disk; 0 keeps nothing on disk. */
    static final String CACHE_LIMIT_KEY = "lyrics_cache_limit";

    /** LRCLIB could not be reached: network error or an HTTP status other than 200 and 404. */
    private static final class UnreachableException extends IOException {
        UnreachableException(String message) {
            super(message);
        }
    }

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

    /** Answers per track and format, including "no lyrics"; an unreachable LRCLIB is not cached. */
    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();

    /** One lock per track and format, so a prefetch and a real request never query twice. */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    /** Tracks already handed to the prefetcher in this process (bounded, most recent kept). */
    private static final Set<String> PREFETCHED = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_PREFETCHED;
                }
            });

    private static final ExecutorService PREFETCHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NyandexLyricsPrefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static volatile long lastToastAt;
    private static volatile Context context;
    private static int diskWrites;

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
     * Replaces the app's lyrics download.
     *
     * @param trackId Track id passed to the lyrics repository.
     * @param format  The app's requested lyrics format enum (TEXT, LRC, RICH_JSON).
     * @return {formatName, content}, or null if LRCLIB has no lyrics or cannot be reached.
     */
    public static String[] lyrics(String trackId, Object format) {
        try {
            if (trackId == null) return null;
            String requested = format instanceof Enum ? ((Enum<?>) format).name() : String.valueOf(format);

            if (Looper.myLooper() == Looper.getMainLooper()) {
                // Never block the UI thread; the repository normally runs on a background dispatcher.
                String[] cached = CACHE.get(trackId + '|' + requested);
                if (cached == null) {
                    Log.w(TAG, "Called on the main thread, skipping LRCLIB");
                    return null;
                }
                return withOffset(cached.length == 0 ? null : cached);
            }

            String[] lyrics;
            try {
                lyrics = load(trackId, requested);
            } catch (IOException exception) {
                Log.w(TAG, "LRCLIB unreachable for track " + trackId + ": " + exception);
                toastUnreachable();
                return null;
            }
            return withOffset(lyrics);
        } catch (Throwable throwable) {
            Log.e(TAG, "LRCLIB lookup failed", throwable);
            return null;
        }
    }

    /**
     * Called with the queue item the player will play next (a static helper in the track class
     * extracts the id). Must stay cheap and never throw: the player calls it often.
     */
    public static void prefetch(String trackId) {
        try {
            if (trackId == null) return;
            synchronized (PREFETCHED) {
                if (!PREFETCHED.add(trackId)) return;
            }
            PREFETCHER.execute(() -> prefetchNow(trackId));
        } catch (Throwable ignored) {
        }
    }

    private static void prefetchNow(String trackId) {
        try {
            SharedPreferences preferences = preferences();
            if (preferences != null && !preferences.getBoolean(PREFETCH_KEY, true)) return;
            String[] lyrics = load(trackId, PREFETCH_FORMAT);
            Log.i(TAG, "Prefetched track " + trackId + ": " + (lyrics == null ? "no lyrics" : lyrics[0]));
        } catch (IOException exception) {
            // Silent: the real request shows the toast if LRCLIB is still unreachable then.
            synchronized (PREFETCHED) {
                PREFETCHED.remove(trackId);
            }
            Log.i(TAG, "Prefetch of track " + trackId + " failed: " + exception);
        } catch (Throwable throwable) {
            Log.e(TAG, "Prefetch failed", throwable);
        }
    }

    /**
     * Lyrics from the memory cache, the disk cache or LRCLIB, in that order. Never on the main
     * thread. One query per track and format at a time.
     *
     * @return {formatName, content} without offset, or null when LRCLIB has no lyrics.
     * @throws IOException If LRCLIB cannot be reached.
     */
    private static String[] load(String trackId, String requested) throws IOException {
        String cacheKey = trackId + '|' + requested;
        String[] cached = CACHE.get(cacheKey);
        if (cached != null) return cached.length == 0 ? null : cached;

        Object lock = LOCKS.get(cacheKey);
        if (lock == null) {
            Object created = new Object();
            lock = LOCKS.putIfAbsent(cacheKey, created);
            if (lock == null) lock = created;
        }
        synchronized (lock) {
            try {
                cached = CACHE.get(cacheKey);
                if (cached != null) return cached.length == 0 ? null : cached;

                cached = readDisk(trackId, requested);
                if (cached != null) {
                    CACHE.put(cacheKey, cached);
                    return cached.length == 0 ? null : cached;
                }

                Track track = track(trackId);
                if (track == null) {
                    Log.w(TAG, "No metadata captured for track " + trackId);
                    return null;
                }

                String[] lyrics = query(track, requested);
                CACHE.put(cacheKey, lyrics == null ? NOT_FOUND : lyrics);
                writeDisk(trackId, requested, lyrics);
                Log.i(TAG, lyrics == null
                        ? "LRCLIB has no lyrics for " + track.title
                        : "Using LRCLIB " + lyrics[0] + " lyrics for " + track.title);
                return lyrics;
            } finally {
                LOCKS.remove(cacheKey, lock);
            }
        }
    }

    private static Track track(String trackId) {
        synchronized (TRACKS) {
            Track track = TRACKS.get(trackId);
            if (track == null) {
                int separator = trackId.indexOf(':');
                if (separator > 0) track = TRACKS.get(trackId.substring(0, separator));
            }
            return track;
        }
    }

    /** Apply the timing offset from the Nyandex settings page to LRC lyrics. */
    private static String[] withOffset(String[] lyrics) {
        if (lyrics == null || !LrcLibCore.FORMAT_LRC.equals(lyrics[0])) return lyrics;
        SharedPreferences preferences = preferences();
        int offset = preferences == null ? 0 : preferences.getInt(OFFSET_KEY, 0);
        if (offset == 0) return lyrics;
        return new String[]{lyrics[0], LrcLibCore.shiftLrc(lyrics[1], offset)};
    }

    /**
     * Forget every cached answer, in memory and on disk. Called by the Nyandex settings page.
     *
     * @return The number of files deleted.
     */
    public static int clearCache() {
        CACHE.clear();
        synchronized (PREFETCHED) {
            PREFETCHED.clear();
        }
        int deleted = 0;
        File directory = cacheDirectory();
        File[] files = directory == null ? null : directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.delete()) deleted++;
            }
        }
        return deleted;
    }

    /**
     * Apply the disk cache limit from the settings page right away. Called by the settings page
     * after the limit changes.
     *
     * @return The number of files deleted.
     */
    public static int trimCache() {
        File directory = cacheDirectory();
        if (directory == null) return 0;
        int limit = cacheLimit();
        if (limit > 0) return prune(directory, limit);
        int deleted = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.delete()) deleted++;
            }
        }
        return deleted;
    }

    /** Number of answers in the disk cache, for the settings page. */
    public static int cacheSize() {
        File directory = cacheDirectory();
        File[] files = directory == null ? null : directory.listFiles();
        return files == null ? 0 : files.length;
    }

    // region Disk cache

    private static int cacheLimit() {
        SharedPreferences preferences = preferences();
        int limit = preferences == null ? DEFAULT_CACHE_LIMIT : preferences.getInt(CACHE_LIMIT_KEY, DEFAULT_CACHE_LIMIT);
        return Math.max(0, limit);
    }

    private static File cacheDirectory() {
        Context current = appContext();
        if (current == null) return null;
        File directory = new File(current.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        return directory;
    }

    /** @return Cached lyrics, an empty array for a fresh "no lyrics", or null if not cached. */
    private static String[] readDisk(String trackId, String requested) {
        try {
            if (cacheLimit() == 0) return null;
            File directory = cacheDirectory();
            if (directory == null) return null;
            File file = new File(directory, LrcLibCore.cacheFileName(trackId, requested));
            if (!file.isFile()) return null;
            String entry;
            try (InputStream input = new FileInputStream(file)) {
                entry = readAll(input);
            }
            String[] lyrics = LrcLibCore.decodeCacheEntry(entry, System.currentTimeMillis(), MISS_TTL_MS);
            if (lyrics == null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis()); // Pruning removes the least recently used.
            return lyrics;
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not read cached lyrics", throwable);
            return null;
        }
    }

    private static void writeDisk(String trackId, String requested, String[] lyrics) {
        try {
            int limit = cacheLimit();
            if (limit == 0) return;
            File directory = cacheDirectory();
            if (directory == null) return;
            File file = new File(directory, LrcLibCore.cacheFileName(trackId, requested));
            File temporary = new File(directory, file.getName() + ".tmp");
            try (OutputStream output = new FileOutputStream(temporary)) {
                output.write(LrcLibCore.encodeCacheEntry(lyrics, System.currentTimeMillis()).getBytes("UTF-8"));
            }
            if (!temporary.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
                return;
            }
            boolean prune;
            synchronized (LrcLibLyrics.class) {
                prune = diskWrites++ % PRUNE_EVERY_WRITES == 0;
            }
            if (prune) prune(directory, limit);
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not cache lyrics", throwable);
        }
    }

    /**
     * Keep the disk cache bounded: above {@code limit} entries, drop the least recently used
     * down to five sixths of the limit, so pruning does not run on every write.
     *
     * @return The number of files deleted.
     */
    private static int prune(File directory, int limit) {
        File[] files = directory.listFiles();
        if (files == null || files.length <= limit) return 0;
        int keep = limit - limit / 6;
        final long[] modified = new long[files.length];
        Integer[] order = new Integer[files.length];
        for (int i = 0; i < files.length; i++) {
            modified[i] = files[i].lastModified();
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(modified[a], modified[b]));
        int deleted = 0;
        for (int i = 0; i < files.length - keep; i++) {
            if (files[order[i]].delete()) deleted++;
        }
        return deleted;
    }

    // endregion

    private static Context appContext() {
        Context current = context;
        if (current != null) return current;
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (application instanceof Context) {
                current = ((Context) application).getApplicationContext();
                context = current;
            }
        } catch (Throwable ignored) {
        }
        return current;
    }

    private static SharedPreferences preferences() {
        Context current = appContext();
        return current == null ? null : current.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** At most one toast per {@link #TOAST_INTERVAL_MS}: the app retries failed lyrics loads. */
    private static void toastUnreachable() {
        try {
            long now = SystemClock.elapsedRealtime();
            if (lastToastAt != 0 && now - lastToastAt < TOAST_INTERVAL_MS) return;
            lastToastAt = now;

            final Context current = appContext();
            if (current == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(current, UNREACHABLE_MESSAGE, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Look the track up on LRCLIB, from the most to the least exact query:
     * <ol>
     *   <li>/api/get with each artist form and the title as the app has it;</li>
     *   <li>/api/get with the main artist and the cleaned title ({@link LrcLibCore#cleanTitle});</li>
     *   <li>/api/search by artist and cleaned title (LRCLIB filters by artist);</li>
     *   <li>/api/search?q= "artist title", then by cleaned title alone: LRCLIB does not filter
     *       these by artist, so the result's artist must match one of the track's artists.</li>
     * </ol>
     * Search results must also be within a few seconds of the track's duration.
     */
    private static String[] query(Track track, String requested) throws IOException {
        List<String> artists = new ArrayList<>();
        if (track.artists != null) {
            for (Object artist : track.artists) {
                if (artist != null) artists.add(String.valueOf(artist));
            }
        }
        long seconds = LrcLibCore.secondsFromMillis(track.durationMs);
        List<String> artistQueries = LrcLibCore.artistCandidates(artists);
        List<String> titles = LrcLibCore.titleCandidates(track.title);
        if (titles.isEmpty()) return null;
        String mainArtist = artistQueries.isEmpty() ? null : artistQueries.get(artistQueries.size() - 1);
        String cleanTitle = titles.get(titles.size() - 1);

        for (String artist : artistQueries) {
            String[] lyrics = getLyrics(LrcLibCore.buildGetUrl(artist, titles.get(0), seconds), requested);
            if (lyrics != null) return lyrics;
        }
        if (mainArtist != null && titles.size() > 1) {
            String[] lyrics = getLyrics(LrcLibCore.buildGetUrl(mainArtist, cleanTitle, seconds), requested);
            if (lyrics != null) return lyrics;
        }

        String[] lyrics = searchLyrics(LrcLibCore.buildSearchUrl(mainArtist, cleanTitle), seconds, null, requested);
        if (lyrics != null || mainArtist == null) return lyrics;

        lyrics = searchLyrics(LrcLibCore.buildQuerySearchUrl(mainArtist + " " + cleanTitle), seconds, artists, requested);
        if (lyrics != null) return lyrics;
        return searchLyrics(LrcLibCore.buildSearchUrl(null, cleanTitle), seconds, artists, requested);
    }

    private static String[] getLyrics(String url, String requested) throws IOException {
        String body = get(url);
        if (body == null) return null;
        Object json = LrcLibCore.parseJson(body);
        if (!(json instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) json;
        return LrcLibCore.chooseLyrics(record, requested);
    }

    private static String[] searchLyrics(String url, long seconds, List<String> artists, String requested)
            throws IOException {
        String body = get(url);
        if (body == null) return null;
        Object json = LrcLibCore.parseJson(body);
        if (!(json instanceof List)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> best = LrcLibCore.pickSearchResult((List<Object>) json, seconds, artists);
        return LrcLibCore.chooseLyrics(best, requested);
    }

    /**
     * @return The response body for HTTP 200, null for 404 (no such lyrics).
     * @throws IOException If LRCLIB cannot be reached or answers with another status.
     */
    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Lrclib-Client", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (status != HttpURLConnection.HTTP_OK) {
                throw new UnreachableException("HTTP " + status + " for " + url);
            }
            try (InputStream input = connection.getInputStream()) {
                return readAll(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString("UTF-8");
    }
}
