/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Locale;

/**
 * Preferences of the Nyandex settings page, stored in the app's own private storage.
 */
@SuppressWarnings("unused")
public final class NyandexSettings {
    static final String TAG = "NyandexSettings";
    static final String PREFERENCES = "nyandex";
    static final String HIDDEN_TAB_PREFIX = "hide_tab_";

    /**
     * Bottom bar tabs, by the name of the app's tab enum constant, in bar order.
     * Each entry: {enum name, English label, Russian label}.
     */
    static final String[][] BOTTOM_TABS = {
            {"WAVE", "My Vibe", "Моя волна"},
            {"LANDING", "Main", "Главное"},
            {"PODCASTS", "Podcasts and books", "Подкасты и книги"},
            {"CONCERTS", "Concerts", "Концерты"},
            {"KIDS", "For kids", "Детям"},
            {"MY_MUSIC", "Collection", "Коллекция"},
    };

    private static volatile Context context;

    private NyandexSettings() {
    }

    /**
     * Whether the "Hide bottom bar tabs" patch is applied. The patch replaces this method so it
     * returns true; the settings page shows the tab section only then.
     */
    public static boolean bottomTabsAvailable() {
        return false;
    }

    static Context context() {
        Context current = context;
        if (current != null) return current;
        try {
            // The application object of the current process; a public, unrestricted hidden API.
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (application instanceof Context) {
                current = ((Context) application).getApplicationContext();
                context = current;
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "No application context", throwable);
        }
        return current;
    }

    static SharedPreferences preferences() {
        Context current = context();
        return current == null ? null : current.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static boolean isTabHidden(SharedPreferences preferences, String tab) {
        return preferences.getBoolean(HIDDEN_TAB_PREFIX + tab, false);
    }

    static void setTabHidden(String tab, boolean hidden) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putBoolean(HIDDEN_TAB_PREFIX + tab, hidden).apply();
    }

    static boolean isRussian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    static String text(String english, String russian) {
        return isRussian() ? russian : english;
    }
}
