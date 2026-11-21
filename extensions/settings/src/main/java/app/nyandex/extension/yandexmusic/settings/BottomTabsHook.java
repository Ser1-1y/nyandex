/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.settings;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Called by the bottom bar right after it collected the tabs to show.
 */
@SuppressWarnings("unused")
public final class BottomTabsHook {
    private BottomTabsHook() {
    }

    /**
     * Remove the tabs hidden on the Nyandex settings page from the bar's tab set.
     * At least one tab always stays, so the bar can never become empty.
     */
    public static void filterTabs(Set<?> tabs) {
        try {
            if (tabs == null || tabs.size() <= 1) return;
            SharedPreferences preferences = NyandexSettings.preferences();
            if (preferences == null) return;

            List<Object> hidden = new ArrayList<>();
            for (Object tab : tabs) {
                if (tab instanceof Enum && NyandexSettings.isTabHidden(preferences, ((Enum<?>) tab).name())) {
                    hidden.add(tab);
                }
            }
            if (hidden.isEmpty()) return;
            if (hidden.size() >= tabs.size()) {
                hidden.remove(hidden.size() - 1); // keep the last tab (Collection)
            }

            Iterator<?> iterator = tabs.iterator();
            while (iterator.hasNext()) {
                if (hidden.contains(iterator.next())) iterator.remove();
            }
        } catch (Throwable throwable) {
            Log.e(NyandexSettings.TAG, "Failed to filter bottom bar tabs", throwable);
        }
    }
}
