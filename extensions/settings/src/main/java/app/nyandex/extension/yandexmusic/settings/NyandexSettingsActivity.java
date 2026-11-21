/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.extension.yandexmusic.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * The Nyandex settings page, styled after the app's own settings screen: its colors and YS
 * fonts, large section headings, outlined pill switches. Plain platform views, so it does not
 * depend on the app's obfuscated UI classes; the fonts are looked up in the app's resources by
 * name and fall back to the system fonts.
 */
public final class NyandexSettingsActivity extends Activity {
    private LinearLayout content;
    private Palette palette;
    private Fonts fonts;

    /** Whether a setting that needs a restart was changed while the page was open. */
    private boolean changed;

    /** Colors of the app's settings screen. */
    private static final class Palette {
        final boolean dark;
        final int background;
        final int primary;
        final int secondary;
        final int button;
        final int ripple;

        Palette(boolean dark) {
            this.dark = dark;
            background = dark ? 0xFF1B1B1D : 0xFFFFFFFF;
            primary = dark ? 0xE6FFFFFF : 0xE6000000;
            secondary = dark ? 0x80FFFFFF : 0x80000000;
            button = dark ? 0xFF2E2E31 : 0xFFF1F0EE;
            ripple = dark ? 0x1FFFFFFF : 0x1F000000;
        }
    }

    /** The app's YS fonts, or system fonts when they cannot be loaded. */
    private static final class Fonts {
        final Typeface headline;
        final Typeface title;
        final Typeface medium;
        final Typeface regular;

        Fonts(Context context) {
            Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            headline = font(context, bold, "ym_headline_black_font", "ys_display_bold");
            title = font(context, bold, "ys_display_bold", "ys_text_bold");
            this.medium = font(context, medium, "ys_text_medium");
            regular = font(context, Typeface.DEFAULT, "ys_text_regular");
        }

        private static Typeface font(Context context, Typeface fallback, String... names) {
            if (Build.VERSION.SDK_INT < 26) return fallback;
            Resources resources = context.getResources();
            for (String name : names) {
                try {
                    int id = resources.getIdentifier(name, "font", context.getPackageName());
                    if (id != 0) {
                        Typeface typeface = resources.getFont(id);
                        if (typeface != null) return typeface;
                    }
                } catch (Throwable ignored) {
                }
            }
            return fallback;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        palette = new Palette(isDarkTheme());
        setTheme(palette.dark
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        fonts = new Fonts(this);
        styleWindow();

        // The app enables predictive back (android:enableOnBackInvokedCallback), which bypasses
        // onBackPressed on Android 13+; a back callback is needed there.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leave);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);
        root.setFitsSystemWindows(true);
        root.addView(toolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(32));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

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
            addSectionHeader(NyandexSettings.text("Nothing to set up", "Настраивать нечего"));
            addParagraph(NyandexSettings.text(
                    "No patch with settings is applied.",
                    "Не применён ни один патч с настройками."));
        }
    }

    /** The app's theme setting (Settings → Theme), or the system's when it follows the system. */
    private boolean isDarkTheme() {
        String theme = null;
        try {
            theme = getSharedPreferences("Yandex_Music", MODE_PRIVATE).getString("theme", null);
        } catch (Throwable ignored) {
        }
        if ("DARK".equals(theme)) return true;
        if ("LIGHT".equals(theme)) return false;
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void styleWindow() {
        Window window = getWindow();
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.background);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!palette.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    // region Sections

    private void addLyricsSection() {
        addSectionHeader(NyandexSettings.text("Lyrics", "Тексты песен"));
        addParagraph(NyandexSettings.text(
                "Lyrics come from LRCLIB, a free community database.",
                "Тексты загружаются из LRCLIB — открытой базы, которую пополняют слушатели."));

        addSwitchRow(
                NyandexSettings.text("Load the next track's lyrics in advance", "Загружать текст следующего трека заранее"),
                NyandexSettings.text("Lyrics open instantly when the track starts",
                        "Текст откроется сразу, как только начнётся трек"),
                NyandexSettings.lyricsPrefetch(),
                NyandexSettings::setLyricsPrefetch);
        final TextView[] storageInfo = new TextView[1];
        final View downloadRow = addSwitchRow(
                NyandexSettings.text("Download lyrics with tracks", "Скачивать тексты вместе с треками"),
                NyandexSettings.text("Downloaded tracks get their lyrics for offline listening. "
                                + "Needs the next setting.",
                        "У скачанных треков текст будет доступен без интернета. Работает вместе со "
                                + "следующей настройкой."),
                NyandexSettings.lyricsDownloadWithTracks(),
                checked -> {
                    NyandexSettings.setLyricsDownloadWithTracks(checked);
                    NyandexSettings.callLyrics("applyStorageSettings");
                    refreshStorage(storageInfo[0]);
                });
        addSwitchRow(
                NyandexSettings.text("Keep lyrics of downloaded tracks", "Не удалять тексты скачанных треков"),
                NyandexSettings.text("Stored separately and not counted in the limit below",
                        "Хранятся отдельно и не учитываются в ограничении ниже"),
                NyandexSettings.lyricsKeepDownloaded(),
                checked -> {
                    NyandexSettings.setLyricsKeepDownloaded(checked);
                    NyandexSettings.callLyrics("applyStorageSettings");
                    setRowEnabled(downloadRow, checked);
                    refreshStorage(storageInfo[0]);
                });
        setRowEnabled(downloadRow, NyandexSettings.lyricsKeepDownloaded());

        addSubheader(NyandexSettings.text("Saved lyrics limit", "Сколько текстов хранить"));
        addParagraph(NyandexSettings.text(
                "Saved lyrics open instantly and work offline. Above the limit, the least recently "
                        + "played are removed. One entry takes a few kilobytes.",
                "Сохранённые тексты открываются сразу и доступны офлайн. Сверх предела удаляются "
                        + "давно не игравшие. Одна запись занимает несколько килобайт."));
        final List<View> indicators = new ArrayList<>();
        final int current = NyandexSettings.lyricsCacheLimit();
        for (final int limit : NyandexSettings.LYRICS_CACHE_LIMITS) {
            final View indicator = new View(this);
            indicator.setTag(limit);
            indicators.add(indicator);
            addRow(limitLabel(limit), null, indicator, dp(24), dp(24), view -> {
                NyandexSettings.setLyricsCacheLimit(limit);
                NyandexSettings.callLyrics("trimCache");
                for (View other : indicators) other.setBackground(radio(other == indicator));
                refreshStorage(storageInfo[0]);
            });
            indicator.setBackground(radio(limit == current));
        }

        addSubheader(NyandexSettings.text("Timing offset", "Сдвиг синхронизации"));
        addParagraph(NyandexSettings.text(
                "If lines appear too late or too early, for example with Bluetooth headphones. "
                        + "Applies to lyrics opened after the change.",
                "Если строки появляются с опозданием или раньше времени, например в Bluetooth-наушниках. "
                        + "Применяется к текстам, открытым после изменения."));
        addOffsetRow();

        addSubheader(NyandexSettings.text("Storage", "Хранилище"));
        storageInfo[0] = addParagraph("");
        showStorage(storageInfo[0]);
        addParagraph(NyandexSettings.text(
                "Clearing also removes the lyrics of downloaded tracks; with \"Download lyrics with tracks\" on "
                        + "they are loaded again.",
                "Очистка удаляет и тексты скачанных треков; если включено «Скачивать тексты вместе с "
                        + "треками», они загрузятся заново."));
        addPillButton(NyandexSettings.text("Clear saved lyrics", "Очистить сохранённые тексты"), view -> {
            NyandexSettings.callLyrics("clearCache");
            refreshStorage(storageInfo[0]);
        });
    }

    private void addBottomTabsSection() {
        addSectionHeader(NyandexSettings.text("Bottom bar", "Нижняя панель"));
        addParagraph(NyandexSettings.text(
                "Choose the tabs to show. Changes apply after the app restarts; at least one tab always stays.",
                "Выберите, какие вкладки показывать. Изменения применятся после перезапуска приложения; "
                        + "хотя бы одна вкладка остаётся всегда."));

        android.content.SharedPreferences preferences = NyandexSettings.preferences();
        for (String[] tab : NyandexSettings.BOTTOM_TABS) {
            final String name = tab[0];
            boolean hidden = preferences != null && NyandexSettings.isTabHidden(preferences, name);
            addSwitchRow(NyandexSettings.isRussian() ? tab[2] : tab[1], null, !hidden, shown -> {
                NyandexSettings.setTabHidden(name, !shown);
                changed = true;
            });
        }
        addPillButton(NyandexSettings.text("Restart the app", "Перезапустить приложение"), view -> restartApp());
    }

    private void addOffsetRow() {
        final TextView value = new TextView(this);
        value.setTypeface(fonts.medium);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        value.setTextColor(palette.primary);
        final TextView hint = new TextView(this);
        hint.setTypeface(fonts.regular);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setTextColor(palette.secondary);
        final int[] offset = {NyandexSettings.lyricsOffset()};
        showOffset(value, hint, offset[0]);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(value);
        texts.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View.OnClickListener later = view -> {
            offset[0] = Math.max(-NyandexSettings.LYRICS_OFFSET_LIMIT_MS, offset[0] - NyandexSettings.LYRICS_OFFSET_STEP_MS);
            NyandexSettings.setLyricsOffset(offset[0]);
            showOffset(value, hint, offset[0]);
        };
        View.OnClickListener earlier = view -> {
            offset[0] = Math.min(NyandexSettings.LYRICS_OFFSET_LIMIT_MS, offset[0] + NyandexSettings.LYRICS_OFFSET_STEP_MS);
            NyandexSettings.setLyricsOffset(offset[0]);
            showOffset(value, hint, offset[0]);
        };
        row.addView(roundButton("−", later), squareParams(dp(44), 0));
        row.addView(roundButton("+", earlier), squareParams(dp(44), dp(12)));
        content.addView(row, matchWrap());

        TextView reset = addPillButton(NyandexSettings.text("Reset offset", "Сбросить сдвиг"), view -> {
            offset[0] = 0;
            NyandexSettings.setLyricsOffset(0);
            showOffset(value, hint, 0);
        });
        ((LinearLayout.LayoutParams) reset.getLayoutParams()).topMargin = dp(4);
    }

    private void showOffset(TextView value, TextView hint, int offsetMs) {
        if (offsetMs == 0) {
            value.setText(NyandexSettings.text("No offset", "Без сдвига"));
            hint.setText(NyandexSettings.text("Lines follow LRCLIB timing", "Строки идут по времени из LRCLIB"));
            return;
        }
        value.setText((offsetMs > 0 ? "+" : "−") + Math.abs(offsetMs) + NyandexSettings.text(" ms", " мс"));
        hint.setText(offsetMs > 0
                ? NyandexSettings.text("Lines appear earlier", "Строки появляются раньше")
                : NyandexSettings.text("Lines appear later", "Строки появляются позже"));
    }

    private void showStorage(TextView view) {
        if (view == null) return;
        int cached = Math.max(0, NyandexSettings.callLyrics("cacheSize"));
        int downloaded = Math.max(0, NyandexSettings.callLyrics("downloadedCacheSize"));
        view.setText(NyandexSettings.text(
                "Saved: " + cached + " lyrics lookups, plus " + downloaded + " for downloaded tracks. "
                        + "Tracks without lyrics are counted too.",
                "Сохранено: " + cached + ", и ещё " + downloaded + " для скачанных треков. "
                        + "Треки без текста тоже учитываются."));
    }

    /** File work runs in the background; update the counts now and once it has likely finished. */
    private void refreshStorage(final TextView view) {
        if (view == null) return;
        showStorage(view);
        view.postDelayed(() -> showStorage(view), 500);
        view.postDelayed(() -> showStorage(view), 2500);
    }

    private static void setRowEnabled(View row, boolean enabled) {
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.4f);
    }

    private static String limitLabel(int limit) {
        if (limit == 0) {
            return NyandexSettings.text("Don't save", "Не сохранять");
        }
        String label = NyandexSettings.text(limit + " tracks", limit + " треков");
        return limit == NyandexSettings.LYRICS_CACHE_LIMIT_DEFAULT
                ? label + NyandexSettings.text(" (default)", " (по умолчанию)")
                : label;
    }

    // endregion

    // region Building blocks

    private interface OnToggle {
        void onToggle(boolean checked);
    }

    private View toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(16), 0);

        BackArrow back = new BackArrow(this, palette.primary);
        back.setContentDescription(NyandexSettings.text("Back", "Назад"));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, oval(Color.WHITE)));
        back.setOnClickListener(view -> leave());
        bar.addView(back, squareParams(dp(48), 0));

        TextView title = new TextView(this);
        title.setText(SettingsEntry.title());
        title.setTypeface(fonts.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(palette.primary);
        title.setSingleLine(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(12);
        bar.addView(title, params);
        return bar;
    }

    /** A large heading, like "Коллекция" on the app's settings screen. */
    private void addSectionHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTypeface(fonts.headline);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        header.setTextColor(palette.primary);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = content.getChildCount() == 0 ? dp(16) : dp(48);
        params.bottomMargin = dp(4);
        content.addView(header, params);
    }

    private void addSubheader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTypeface(fonts.title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.setTextColor(palette.primary);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(28);
        content.addView(header, params);
    }

    private TextView addParagraph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTypeface(fonts.regular);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(palette.secondary);
        view.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        content.addView(view, params);
        return view;
    }

    private View addSwitchRow(String title, String subtitle, boolean checked, final OnToggle onToggle) {
        final Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setThumbDrawable(switchThumb());
        toggle.setTrackDrawable(switchTrack());
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, isChecked) -> onToggle.onToggle(isChecked));
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        View row = addRow(title, subtitle, toggle, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, view -> toggle.toggle());
        toggle.setClickable(false);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
                info.setClassName(Switch.class.getName());
            }
        });
        return row;
    }

    /** A row with a title, an optional subtitle and a trailing control; the whole row is clickable. */
    private View addRow(String title, String subtitle, View trailing, int trailingWidth, int trailingHeight,
                        View.OnClickListener onClick) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTypeface(fonts.medium);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTextColor(palette.primary);
        texts.addView(titleView);
        if (subtitle != null) {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTypeface(fonts.regular);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            subtitleView.setTextColor(palette.secondary);
            subtitleView.setPadding(0, dp(2), 0, 0);
            texts.addView(subtitleView);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(subtitle == null ? 56 : 72));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, rounded(Color.WHITE, dp(16))));
        row.setOnClickListener(onClick);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams trailingParams = new LinearLayout.LayoutParams(trailingWidth, trailingHeight);
        trailingParams.leftMargin = dp(16);
        row.addView(trailing, trailingParams);

        // Rows bleed into the page padding so the ripple has room; the text stays aligned.
        LinearLayout.LayoutParams params = matchWrap();
        params.leftMargin = -dp(16);
        params.rightMargin = -dp(16);
        content.addView(row, params);
        return row;
    }

    private TextView addPillButton(String text, View.OnClickListener onClick) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTypeface(fonts.medium);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTextColor(palette.primary);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(24), dp(14), dp(24), dp(14));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple),
                rounded(palette.button, dp(26)), null));
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        content.addView(button, params);
        return button;
    }

    private TextView roundButton(String text, View.OnClickListener onClick) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTypeface(fonts.medium);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        button.setTextColor(palette.primary);
        button.setGravity(Gravity.CENTER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), oval(palette.button), null));
        button.setOnClickListener(onClick);
        return button;
    }

    // endregion

    // region Drawables

    /** Outlined pill when off, filled when on, like the app's switches. */
    private Drawable switchTrack() {
        GradientDrawable off = rounded(Color.TRANSPARENT, dp(16));
        off.setStroke(dp(2), palette.primary);
        off.setSize(dp(52), dp(32));
        GradientDrawable on = rounded(palette.primary, dp(16));
        on.setSize(dp(52), dp(32));
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, on);
        track.addState(new int[]{}, off);
        return track;
    }

    private Drawable switchThumb() {
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, new InsetDrawable(dot(palette.background, dp(20)), dp(6)));
        thumb.addState(new int[]{}, new InsetDrawable(dot(palette.primary, dp(20)), dp(6)));
        return thumb;
    }

    private Drawable radio(boolean selected) {
        GradientDrawable ring = oval(Color.TRANSPARENT);
        ring.setStroke(dp(2), selected ? palette.primary : palette.secondary);
        if (!selected) return ring;
        LayerDrawable layers = new LayerDrawable(new Drawable[]{ring, dot(palette.primary, dp(12))});
        layers.setLayerInset(1, dp(6), dp(6), dp(6), dp(6));
        return layers;
    }

    private static GradientDrawable dot(int color, int size) {
        GradientDrawable dot = oval(color);
        dot.setSize(size, size);
        return dot;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(radius);
        drawable.setColor(color);
        return drawable;
    }

    /** The app's back arrow: a shaft with two strokes, drawn so no app icon resource is needed. */
    private static final class BackArrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackArrow(Context context, int color) {
            super(context);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.2f,
                    context.getResources().getDisplayMetrics()));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float half = size * 0.22f;
            canvas.drawLine(cx - half, cy, cx + half, cy, paint);
            canvas.drawLine(cx - half, cy, cx - half + size * 0.16f, cy - size * 0.16f, paint);
            canvas.drawLine(cx - half, cy, cx - half + size * 0.16f, cy + size * 0.16f, paint);
        }
    }

    // endregion

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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams squareParams(int size, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = leftMargin;
        return params;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
