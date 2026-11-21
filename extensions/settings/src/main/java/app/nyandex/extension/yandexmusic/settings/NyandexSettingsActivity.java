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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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
