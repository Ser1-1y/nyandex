/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.settings;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/**
 * The Nyandex settings page. Plain platform views, so it does not depend on the app's
 * obfuscated UI classes.
 */
public final class NyandexSettingsActivity extends Activity {
    private LinearLayout content;

    /** Whether a setting that needs a restart was changed while the page was open. */
    private boolean changed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? android.R.style.Theme_DeviceDefault_DayNight
                : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(savedInstanceState);
        setTitle(SettingsEntry.title());

        // The app enables predictive back (android:enableOnBackInvokedCallback), which bypasses
        // onBackPressed on Android 13+; a back callback is needed there.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leave);
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        content.setPadding(padding, padding / 2, padding, padding);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        boolean anySection = false;
        if (NyandexSettings.lyricsClass() != null) {
            addLyricsSection();
            anySection = true;
        }
        if (NyandexSettings.bottomTabsAvailable()) {
            addBottomTabsSection();
            anySection = true;
        }
        if (!anySection) {
            addText(NyandexSettings.text(
                    "No patch with settings is applied.",
                    "Не применён ни один патч с настройками."), false);
        }
    }

    private void addLyricsSection() {
        addHeader(NyandexSettings.text("Lyrics (LRCLIB)", "Тексты песен (LRCLIB)"));

        Switch prefetch = new Switch(this);
        prefetch.setText(NyandexSettings.text(
                "Load the next track's lyrics in advance",
                "Заранее загружать текст следующего трека"));
        prefetch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        prefetch.setMinHeight(dp(52));
        prefetch.setChecked(NyandexSettings.lyricsPrefetch());
        prefetch.setOnCheckedChangeListener((button, checked) -> NyandexSettings.setLyricsPrefetch(checked));
        content.addView(prefetch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addText(NyandexSettings.text("Timing offset", "Сдвиг синхронизации"), false);
        addText(NyandexSettings.text(
                "Use it when lines appear too late or too early, for example with Bluetooth headphones. "
                        + "Applies to lyrics opened after the change.",
                "Если строки появляются с опозданием или раньше времени, например в Bluetooth-наушниках. "
                        + "Применяется к текстам, открытым после изменения."), true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        value.setGravity(Gravity.CENTER);
        final int[] offset = {NyandexSettings.lyricsOffset()};
        showOffset(value, offset[0]);

        Button later = new Button(this);
        later.setText("\u2212");
        later.setOnClickListener(view -> {
            offset[0] = Math.max(-NyandexSettings.LYRICS_OFFSET_LIMIT_MS, offset[0] - NyandexSettings.LYRICS_OFFSET_STEP_MS);
            NyandexSettings.setLyricsOffset(offset[0]);
            showOffset(value, offset[0]);
        });
        Button earlier = new Button(this);
        earlier.setText("+");
        earlier.setOnClickListener(view -> {
            offset[0] = Math.min(NyandexSettings.LYRICS_OFFSET_LIMIT_MS, offset[0] + NyandexSettings.LYRICS_OFFSET_STEP_MS);
            NyandexSettings.setLyricsOffset(offset[0]);
            showOffset(value, offset[0]);
        });
        Button reset = new Button(this);
        reset.setText(NyandexSettings.text("Reset", "Сброс"));
        reset.setOnClickListener(view -> {
            offset[0] = 0;
            NyandexSettings.setLyricsOffset(0);
            showOffset(value, 0);
        });
        row.addView(later, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(earlier, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addText(NyandexSettings.text("Saved lyrics limit", "Сколько текстов хранить"), false);
        addText(NyandexSettings.text(
                "Saved lyrics work offline and load instantly. When the limit is reached, the least "
                        + "recently played are removed. One entry takes a few kilobytes.",
                "Сохранённые тексты доступны офлайн и открываются сразу. При достижении предела "
                        + "удаляются давно не игравшие. Одна запись занимает несколько килобайт."), true);

        RadioGroup limits = new RadioGroup(this);
        limits.setOrientation(RadioGroup.VERTICAL);
        int current = NyandexSettings.lyricsCacheLimit();
        for (int limit : NyandexSettings.LYRICS_CACHE_LIMITS) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(limitLabel(limit));
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            option.setMinHeight(dp(44));
            option.setTag(limit);
            limits.addView(option);
            if (limit == current) option.setChecked(true);
        }
        content.addView(limits, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView cacheInfo = new TextView(this);
        cacheInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cacheInfo.setAlpha(0.7f);
        cacheInfo.setPadding(0, dp(12), 0, dp(4));
        showCacheSize(cacheInfo);
        content.addView(cacheInfo);

        Button clear = new Button(this);
        clear.setText(NyandexSettings.text("Clear saved lyrics", "Очистить сохранённые тексты"));
        clear.setOnClickListener(view -> {
            NyandexSettings.callLyrics("clearCache");
            showCacheSize(cacheInfo);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(clear, params);

        limits.setOnCheckedChangeListener((group, checkedId) -> {
            View option = group.findViewById(checkedId);
            if (option == null || !(option.getTag() instanceof Integer)) return;
            NyandexSettings.setLyricsCacheLimit((Integer) option.getTag());
            NyandexSettings.callLyrics("trimCache");
            showCacheSize(cacheInfo);
        });
    }

    private static String limitLabel(int limit) {
        if (limit == 0) {
            return NyandexSettings.text("Don't save (always load from LRCLIB)", "Не сохранять (всегда загружать из LRCLIB)");
        }
        String label = NyandexSettings.text(limit + " tracks", limit + " треков");
        return limit == NyandexSettings.LYRICS_CACHE_LIMIT_DEFAULT
                ? label + NyandexSettings.text(" (default)", " (по умолчанию)")
                : label;
    }

    private static void showOffset(TextView view, int offsetMs) {
        if (offsetMs == 0) {
            view.setText(NyandexSettings.text("No offset", "Без сдвига"));
            return;
        }
        String amount = (offsetMs > 0 ? "+" : "\u2212") + Math.abs(offsetMs) + NyandexSettings.text(" ms", " мс");
        view.setText(amount + "\n" + (offsetMs > 0
                ? NyandexSettings.text("lines appear earlier", "строки появляются раньше")
                : NyandexSettings.text("lines appear later", "строки появляются позже")));
    }

    private static void showCacheSize(TextView view) {
        int size = Math.max(0, NyandexSettings.callLyrics("cacheSize"));
        view.setText(NyandexSettings.text(
                "Saved for offline: " + size + " lyrics lookups (including tracks without lyrics).",
                "Сохранено для офлайна: " + size + " (включая треки без текста)."));
    }

    private void addBottomTabsSection() {
        addHeader(NyandexSettings.text("Hide bottom bar tabs", "Скрыть вкладки нижней панели"));
        addText(NyandexSettings.text(
                "Hidden tabs disappear after the app restarts. At least one tab always stays.",
                "Скрытые вкладки исчезнут после перезапуска приложения. Хотя бы одна вкладка "
                        + "всегда остаётся."), true);

        SharedPreferences preferences = NyandexSettings.preferences();
        for (String[] tab : NyandexSettings.BOTTOM_TABS) {
            Switch toggle = new Switch(this);
            toggle.setText(NyandexSettings.isRussian() ? tab[2] : tab[1]);
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            toggle.setMinHeight(dp(52));
            toggle.setChecked(preferences != null && NyandexSettings.isTabHidden(preferences, tab[0]));
            final String name = tab[0];
            toggle.setOnCheckedChangeListener((button, checked) -> {
                NyandexSettings.setTabHidden(name, checked);
                changed = true;
            });
            content.addView(toggle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Button restart = new Button(this);
        restart.setText(NyandexSettings.text("Restart the app", "Перезапустить приложение"));
        restart.setOnClickListener(view -> restartApp());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(12);
        content.addView(restart, params);
    }

    private void addHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.setPadding(0, dp(16), 0, dp(4));
        content.addView(header);
    }

    private void addText(String text, boolean secondary) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, secondary ? 14 : 16);
        if (secondary) view.setAlpha(0.7f);
        view.setPadding(0, dp(4), 0, dp(8));
        content.addView(view);
    }

    private void restartApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finishAffinity();
        Process.killProcess(Process.myPid());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            leave();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        leave();
    }

    /** Close the page; after a change, recommend restarting the app first. */
    private void leave() {
        if (!changed) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(NyandexSettings.text("Restart the app?", "Перезапустить приложение?"))
                .setMessage(NyandexSettings.text(
                        "Some changes apply only after the app restarts.",
                        "Часть изменений применится только после перезапуска приложения."))
                .setPositiveButton(NyandexSettings.text("Restart", "Перезапустить"),
                        (dialog, which) -> restartApp())
                .setNegativeButton(NyandexSettings.text("Later", "Позже"),
                        (dialog, which) -> finish())
                .show();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
