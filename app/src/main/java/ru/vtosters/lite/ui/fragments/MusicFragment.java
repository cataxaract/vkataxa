package ru.vtosters.lite.ui.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import bruhcollective.itaysonlab.libvkx.client.LibVKXClient;
import com.vk.core.dialogs.alert.VkAlertDialog;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;
import com.vtosters.lite.ui.SummaryListPreference;
import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.hooks.other.ThemesUtils;
import ru.vtosters.lite.core.flags.FeatureFlags;
import ru.vtosters.lite.downloaders.AudioDownloader;
import ru.vtosters.lite.music.LastFMScrobbler;
import ru.vtosters.lite.music.repository.MusicBulkService;
import ru.vtosters.lite.music.spotify.MusicDedupe;
import ru.vtosters.lite.music.spotify.SpotifyImportActivity;
import ru.vtosters.lite.core.music.MusicRepository;
import ru.vtosters.lite.music.repository.MusicRepositoryImpl;
import ru.vtosters.lite.ui.PreferenceFragmentUtils;
import ru.vtosters.lite.utils.AndroidUtils;
import ru.vtosters.lite.utils.LifecycleUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ru.vtosters.hooks.other.Preferences.getBoolValue;
import static ru.vtosters.hooks.other.ThemesUtils.getTextAttr;

public class MusicFragment extends TrackedMaterialPreferenceToolbarFragment {
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private static MusicRepository repo() {
        return MusicRepositoryImpl.get();
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        addPreferencesFromResource(com.vtosters.lite.R.xml.empty);

        requireActivity().getWindow().setStatusBarColor(ThemesUtils.getBackgroundContent());
        requireActivity().getWindow().setNavigationBarColor(ThemesUtils.getBackgroundContent());

        PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Офлайн прослушивание");

        if (FeatureFlags.isEnabled(FeatureFlags.Flag.MUSIC_SORT)) {
            PreferenceFragmentUtils.addPreference(
                    getPreferenceScreen(),
                    "music_sort",
                    "Сортировка музыки",
                    sortLabel(repo().sortMode()),
                    null,
                    preference -> {
                        showSortDialog();
                        return true;
                    }
            );
        }

        // TEMP-UI(MU-14): read-only отчёт о повторах; удалить после редизайна.
        if (FeatureFlags.isEnabled(FeatureFlags.Flag.MUSIC_DEDUPE)) {
            PreferenceFragmentUtils.addPreference(
                    getPreferenceScreen(),
                    "music_dedupe",
                    "Поиск повторов",
                    "Моя музыка и плейлисты — только отчёт",
                    null,
                    preference -> {
                        runDedupe();
                        return true;
                    }
            );
        }

        PreferenceFragmentUtils.addPreference(
                getPreferenceScreen(),
                "cached_tracks",
                getString(com.vtosters.lite.R.string.cached_tracks_title),
                String.format(requireContext().getString(com.vtosters.lite.R.string.cached_tracks_counter), repo().cachedTrackCount()),
                null,
                preference -> {
                    if (repo().isCacheEmpty()) {
                        AndroidUtils.sendToast(requireContext().getString(com.vtosters.lite.R.string.no_cache_error));
                    } else {
                        delcache(requireContext(), false);
                    }
                    return true;
                }
        );

        if (repo().hasCachedPlaylists()) {
            PreferenceFragmentUtils.addPreference(
                    getPreferenceScreen(),
                    "cached_playlists",
                    "Кешированные плейлисты",
                    String.format("Скачано плейлистов: %d", repo().cachedPlaylistsNow().size()),
                    null,
                    preference -> {
                        cachedPlaylistsDialog(requireContext());
                        return true;
                    }
            );

            PreferenceFragmentUtils.addPreference(
                    getPreferenceScreen(),
                    "cached_playlists",
                    "Удалить все скачанные плейлисты",
                    null,
                    null,
                    preference -> {
                        delcache(requireContext(), true);
                        return true;
                    }
            );
        }

        PreferenceFragmentUtils.addPreference(
                getPreferenceScreen(),
                "audio_download",
                getString(com.vtosters.lite.R.string.audio_download_title),
                null,
                null,
                preference -> {
                    dlaudio(requireContext());
                    return true;
                }
        );

        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "invertCachedTracks",
                getString(com.vtosters.lite.R.string.invertcached_title),
                getString(com.vtosters.lite.R.string.invertcached_summ),
                null,
                false,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("invertCachedTracks", (boolean) o).apply();
                    return true;
                }
        ).setEnabled(!repo().isCacheEmpty());

        PreferenceFragmentUtils.addPreference(
                getPreferenceScreen(),
                "autocache_params",
                getString(com.vtosters.lite.R.string.autocache_title),
                getAutocacheSumm(),
                null,
                preference -> {
                    List<String> items = Arrays.asList("Не кешировать", "Только свои", "Все");

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, items) {
                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            TextView textView = (TextView) super.getView(position, convertView, parent);
                            textView.setTextColor(getTextAttr());
                            return textView;
                        }
                    };

                    int selectedItem = Preferences.getPreferences().getInt("autocaching", 0);
                    if (selectedItem >= 0 && selectedItem < items.size()) {
                        adapter.getItem(selectedItem);
                    }

                    new VkAlertDialog.Builder(getActivity())
                            .setAdapter(adapter, (dialog, which) -> {
                                adapter.getItem(which);
                                Preferences.getPreferences().edit().putInt("autocaching", which).apply();
                                findPreference("autocache_params").setSummary(getAutocacheSumm());
                                dialog.cancel();
                            })
                            .show();

                    return true;
                }
        );

        PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Скачивание аудио в MP3");

        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "dldir",
                "Альтернативная папка для скачивания",
                "Использовать папку Downloads вместо Music для скачивания музыки в MP3",
                null,
                false,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("dldir", (boolean) o).apply();
                    LifecycleUtils.restartApplicationWithTimer();
                    return true;
                }
        );

        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "setMetaData",
                "Сохранять метадату песен",
                "Сохранять id3v2 теги для песен для MP3",
                null,
                true,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("setMetaData", (boolean) o).apply();
                    return true;
                }
        );

        PreferenceFragmentUtils.addPreference(
                getPreferenceScreen(),
                "metadataSeparator",
                "Разделитель для id3v2 тегов",
                null,
                null,
                preference -> {
                    LinearLayout linearLayout = new LinearLayout(requireContext());
                    linearLayout.setOrientation(LinearLayout.VERTICAL);

                    EditText separator = new EditText(requireContext());
                    separator.setHint("По-умолчанию: ; с пробелом");
                    separator.setTextColor(ThemesUtils.getTextAttr());
                    separator.setHintTextColor(ThemesUtils.getSTextAttr());
                    separator.setBackgroundTintList(ThemesUtils.getAccenedColorStateList());
                    separator.setText(Preferences.metadataSeparator());
                    linearLayout.addView(separator);
                    separator.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                    ViewGroup.MarginLayoutParams margin = ((ViewGroup.MarginLayoutParams) separator.getLayoutParams());
                    margin.setMargins(AndroidUtils.dp2px(20f), 0, AndroidUtils.dp2px(20f), 0);
                    separator.setLayoutParams(margin);

                    new VkAlertDialog.Builder(requireContext())
                            .setTitle("Разделитель")
                            .setView(linearLayout)
                            .setPositiveButton("Сохранить", (dialog, which) -> {
                                if (separator.getText().toString().isEmpty()) {
                                    AndroidUtils.sendToast("Разделитель не может быть пустым");
                                    return;
                                }
                                Preferences.setMetadataSeparator(separator.getText().toString());
                                findPreference("metadataSeparator").setSummary(separator.getText().toString());
                            })
                            .setNegativeButton("Отмена", (dialog, which) -> dialog.cancel())
                            .show();

                    return true;
                }
        );

        PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Моя волна и Spotify");

        PreferenceFragmentUtils.addListPreference(
                getPreferenceScreen(),
                "wave_source",
                "auto",
                "Источник рекомендаций",
                new CharSequence[]{"Авто", "ВКонтакте", "Last.fm"},
                new CharSequence[]{"auto", "vk", "lastfm"}
        );

        PreferenceFragmentUtils.addPreference(
                getPreferenceScreen(),
                "spotify_import",
                "Импорт из Spotify",
                "Перенести плейлисты Spotify в VK Музыку",
                null,
                preference -> {
                    requireContext().startActivity(new Intent(requireContext(), SpotifyImportActivity.class));
                    return true;
                }
        );

        PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Рекомендации музыки");
        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "sendMusicMetrics",
                "Отправлять статистику",
                "Статистика прослушиваний необходима для работы рекомендаций и истории прослушивания музыки",
                null,
                true,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("sendMusicMetrics", (boolean) o).apply();
                    return true;
                }
        );

        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "playStatCatalog",
                "Список истории прослушиваний",
                "Показывать вкладку истории прослушиваний в музыкальном разделе\n\nОтключение ускорит открытие музыкального раздела при медленном интернете",
                null,
                false,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("playStatCatalog", (boolean) o).apply();
                    return true;
                }
        ).setVisible(Preferences.sendMusicMetrics() && !getBoolValue("useOldAppVer", false));

        if (!Preferences.serverFeaturesDisable()) {
            PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Интеграция Genius");
            PreferenceFragmentUtils.addMaterialSwitchPreference(
                    getPreferenceScreen(),
                    "useGenius",
                    getString(com.vtosters.lite.R.string.useGenius_title),
                    getString(com.vtosters.lite.R.string.useGenius_summ),
                    null,
                    false,
                    (preference, o) -> {
                        Preferences.getPreferences().edit().putBoolean("useGenius", (boolean) o).apply();
                        return true;
                    }
            );

            PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Интеграция Last.fm");

            PreferenceFragmentUtils.addPreference(
                    getPreferenceScreen(),
                    "lastfm_auth",
                    getString(com.vtosters.lite.R.string.lastfm_auth_title),
                    getString(com.vtosters.lite.R.string.lastfm_auth_summ),
                    null,
                    preference -> {
                        if (LastFMScrobbler.isLoggedIn()) {
                            logout(getContext());
                        } else {
                            lastfmAuth(getContext());
                        }
                        updateLastFmPref();
                        return true;
                    }
            );

            PreferenceFragmentUtils.addMaterialSwitchPreference(
                    getPreferenceScreen(),
                    "lastfm_enabled",
                    AndroidUtils.getString("lastfm_enabled_title"),
                    AndroidUtils.getString("lastfm_enabled_summ"),
                    null,
                    false,
                    (preference, o) -> {
                        Preferences.getPreferences().edit().putBoolean("lastfm_enabled", (boolean) o).apply();
                        return true;
                    }
            ).setEnabled(LastFMScrobbler.isLoggedIn());

            if (LastFMScrobbler.isLoggedIn()) {
                findPreference("lastfm_auth").setSummary(getString(com.vtosters.lite.R.string.lastfm_authorized_as) + " " + LastFMScrobbler.getUserName());
            }

            PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Интеграция VK X");

            PreferenceFragmentUtils.addPreference(
                    getPreferenceScreen(),
                    "",
                    getString(com.vtosters.lite.R.string.vkx_why),
                    getString(com.vtosters.lite.R.string.vkx_why_summary),
                    null,
                    null
            );

            PreferenceFragmentUtils.addMaterialSwitchPreference(
                    getPreferenceScreen(),
                    "libvkx_integration",
                    getString(com.vtosters.lite.R.string.vkx_integration),
                    null,
                    null,
                    false,
                    (preference, o) -> {
                        Preferences.getPreferences().edit().putBoolean("libvkx_integration", (boolean) o).apply();
                        return true;
                    }
            );

            if (!LibVKXClient.isVkxInstalled()) {
                findPreference("libvkx_integration").setSummary("У вас не установлен VKX. Интеграция работать не будет");
                findPreference("libvkx_integration").setEnabled(false);
            }
        }

        PreferenceFragmentUtils.addPreferenceCategory(getPreferenceScreen(), "Прочее");

        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "useOldAppVer",
                getString(com.vtosters.lite.R.string.useOldAppVer_title),
                "Включите если вас не устраивают новые рекомендации и вас интересует только свое аудио",
                null,
                false,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("useOldAppVer", (boolean) o).apply();
                    LifecycleUtils.restartApplicationWithTimer();
                    return true;
                }
        );

        SummaryListPreference list = new SummaryListPreference(getPreferenceScreen().getContext());
        list.setTitle(com.vtosters.lite.R.string.musicdefcatalog_title);
        list.setKey("musicdefcatalog");
        list.setEntryValues(com.vtosters.lite.R.array.musicdefcatalog);
        list.setEntries(com.vtosters.lite.R.array.musicdefcatalog_value);
        list.setDialogTitle(com.vtosters.lite.R.string.musicdefcatalog_dialogTitle);
        list.setDefaultValue("default");
        list.setVisible(!Preferences.getBoolValue("useOldAppVer", false));

        getPreferenceScreen().addPreference(list);

        PreferenceFragmentUtils.addMaterialSwitchPreference(
                getPreferenceScreen(),
                "playlistsCatalogs",
                "Списки плейлистов",
                "Показывать вкладку плейлистов и альбомов прослушиваний в музыкальном разделе\n\nОтключение ускорит открытие музыкального раздела при медленном интернете",
                null,
                true,
                (preference, o) -> {
                    Preferences.getPreferences().edit().putBoolean("playlistsCatalogs", (boolean) o).apply();
                    return true;
                }
        ).setVisible(!getBoolValue("useOldAppVer", false));
    }

    private void lastfmAuth(Context ctx) {
        LinearLayout linearLayout = new LinearLayout(ctx);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        EditText fn = new EditText(ctx);
        fn.setHint(com.vtosters.lite.R.string.lastfm_login);
        fn.setTextColor(ThemesUtils.getTextAttr());
        fn.setHintTextColor(ThemesUtils.getSTextAttr());
        fn.setBackgroundTintList(ThemesUtils.getAccenedColorStateList());
        linearLayout.addView(fn);
        fn.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        ViewGroup.MarginLayoutParams margin = ((ViewGroup.MarginLayoutParams) fn.getLayoutParams());
        margin.setMargins(AndroidUtils.dp2px(20f), 0, AndroidUtils.dp2px(20f), 0);
        fn.setLayoutParams(margin);

        EditText ln = new EditText(ctx);
        ln.setHint(com.vtosters.lite.R.string.lastfm_password);
        ln.setTextColor(ThemesUtils.getTextAttr());
        ln.setHintTextColor(ThemesUtils.getSTextAttr());
        ln.setBackgroundTintList(ThemesUtils.getAccenedColorStateList());
        linearLayout.addView(ln);
        ln.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        ln.setLayoutParams(margin);

        new VkAlertDialog.Builder(ctx)
                .setTitle(com.vtosters.lite.R.string.lastfm_enter_credentials)
                .setPositiveButton(com.vtosters.lite.R.string.lastfm_enter,
                        (dialog, which) -> {
                            String login = fn.getText().toString();
                            String pass = ln.getText().toString();
                            LastFMScrobbler.authenticate(login, pass);
                        }
                )
                .setView(linearLayout)
                .show();
    }

    // TEMP-UI(MU-14): read-only отчёт; удалить после редизайна.
    private void runDedupe() {
        Context ctx = requireContext();
        Activity activity = requireActivity();
        AndroidUtils.sendToast("Ищем повторы...");
        executor.submit(() -> {
            try {
                MusicDedupe.Report report = MusicDedupe.scan();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing()) {
                        showDedupeSelection(ctx, report);
                    }
                });
            } catch (Throwable error) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing()) {
                        AndroidUtils.sendToast("Ошибка поиска повторов: " + error.getMessage());
                    }
                });
            }
        });
    }

    // TEMP-UI(MU-14/MU-13): выбор и удаление похожих; удалить после редизайна.
    private void showDedupeSelection(Context ctx, MusicDedupe.Report report) {
        int similarTotal = 0;
        for (MusicDedupe.Section section : report.sections) {
            similarTotal += section.similarGroups();
        }

        if (similarTotal == 0) {
            new VkAlertDialog.Builder(ctx)
                    .setTitle("Поиск повторов")
                    .setMessage(report.toText())
                    .setPositiveButton("Закрыть", null)
                    .show();
            return;
        }

        List<DedupeSelection> selections = new ArrayList<>();
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(AndroidUtils.dp2px(16));
        content.setPadding(padding, padding, padding, padding);

        TextView summary = new TextView(ctx);
        summary.setText("Похожих групп: " + similarTotal + ".\n"
                + "Отметь треки, которые нужно удалить; по умолчанию ничего не выбрано.\n"
                + "В «Моей музыке» снимается лайк, в плейлистах трек убирается из плейлиста.");
        summary.setTextSize(13);
        summary.setTextColor(getTextAttr());
        content.addView(summary);

        Button delete = new Button(ctx);
        delete.setAllCaps(false);
        delete.setText("Удалить выбранные (0)");
        delete.setTextColor(getTextAttr());

        final AlertDialog[] dialogRef = new AlertDialog[1];
        Runnable updateCount = () -> {
            int count = 0;
            for (DedupeSelection selection : selections) {
                if (selection.selected) {
                    count++;
                }
            }
            delete.setText("Удалить выбранные (" + count + ")");
        };

        for (MusicDedupe.Section section : report.sections) {
            if (section.similar.isEmpty()) {
                continue;
            }
            TextView header = new TextView(ctx);
            header.setText(section.name + " · " + section.similarGroups() + " похожих групп");
            header.setTextSize(15);
            header.setTextColor(getTextAttr());
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            header.setPadding(0, Math.round(AndroidUtils.dp2px(12)), 0,
                    Math.round(AndroidUtils.dp2px(4)));
            content.addView(header);

            for (MusicDedupe.Group group : section.similar) {
                TextView groupTitle = new TextView(ctx);
                groupTitle.setText(MusicDedupe.display(group.tracks.get(0))
                        + " · " + group.tracks.size() + " шт.");
                groupTitle.setTextSize(13);
                groupTitle.setTextColor(getTextAttr());
                groupTitle.setPadding(0, Math.round(AndroidUtils.dp2px(6)), 0, 0);
                content.addView(groupTitle);

                for (MusicTrack track : group.tracks) {
                    CheckBox check = new CheckBox(ctx);
                    check.setText(MusicDedupe.display(track) + " · " + duration(track)
                            + " · id " + track.y1());
                    check.setTextSize(13);
                    check.setTextColor(getTextAttr());
                    DedupeSelection selection = new DedupeSelection(section, track);
                    selections.add(selection);
                    check.setOnCheckedChangeListener((button, checked) -> {
                        selection.selected = checked;
                        updateCount.run();
                    });
                    content.addView(check);
                }
            }
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(content);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(delete, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.6f)));

        final AlertDialog dialog = new VkAlertDialog.Builder(ctx)
                .setTitle("Поиск повторов")
                .setView(root)
                .setNegativeButton("Закрыть", null)
                .show();
        dialogRef[0] = dialog;
        delete.setOnClickListener(v -> {
            List<DedupeSelection> chosen = new ArrayList<>();
            for (DedupeSelection selection : selections) {
                if (selection.selected) {
                    chosen.add(selection);
                }
            }
            if (chosen.isEmpty()) {
                AndroidUtils.sendToast("Ничего не выбрано");
                return;
            }
            confirmDedupeDelete(ctx, chosen, dialogRef[0]);
        });
    }

    private void confirmDedupeDelete(Context ctx, List<DedupeSelection> chosen,
                                     AlertDialog selectionDialog) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DedupeSelection selection : chosen) {
            String name = selection.section.myMusic
                    ? "Моя музыка" : "Плейлист " + selection.section.name;
            counts.merge(name, 1, Integer::sum);
        }
        StringBuilder message = new StringBuilder("Будет удалено ")
                .append(chosen.size()).append(" трек(ов):\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            message.append("• ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append('\n');
        }
        message.append("\nВ «Моей музыке» снимается лайк, в плейлистах трек убирается из плейлиста.")
                .append("\nДействие необратимо; разные версии/ремастеры не определяются автоматически.");

        new VkAlertDialog.Builder(ctx)
                .setTitle("Удалить выбранные?")
                .setMessage(message.toString())
                .setPositiveButton("Удалить", (dialog, which) -> {
                    if (selectionDialog != null) {
                        selectionDialog.dismiss();
                    }
                    startDedupeDeletion(ctx, chosen);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void startDedupeDeletion(Context ctx, List<DedupeSelection> chosen) {
        Map<MusicDedupe.Section, DeleteGroup> grouped = new LinkedHashMap<>();
        for (DedupeSelection selection : chosen) {
            DeleteGroup group = grouped.get(selection.section);
            if (group == null) {
                group = new DeleteGroup(selection.section);
                grouped.put(selection.section, group);
            }
            group.tracks.add(selection.track);
        }
        List<DeleteGroup> groups = new ArrayList<>(grouped.values());
        AndroidUtils.sendToast("Удаляем выбранные треки...");
        runDedupeDeleteGroup(ctx, groups, 0, 0, 0);
    }

    private void runDedupeDeleteGroup(Context ctx, List<DeleteGroup> groups,
                                      int index, int okTotal, int failedTotal) {
        if (index >= groups.size()) {
            AndroidUtils.sendToast("Удалено: " + okTotal + ", ошибок: " + failedTotal);
            return;
        }
        DeleteGroup group = groups.get(index);
        MusicBulkService.Action action = group.section.myMusic
                ? MusicBulkService.Action.REMOVE_FROM_MY_MUSIC
                : MusicBulkService.Action.REMOVE_FROM_PLAYLIST;
        String playlistId = group.section.myMusic
                ? null : group.section.ownerId + "_" + group.section.playlistId;
        boolean started = MusicBulkService.start(group.tracks, action, playlistId,
                new MusicBulkService.Listener() {
                    @Override
                    public void onProgress(int done, int total) {
                    }

                    @Override
                    public void onFinished(int ok, int failed, boolean canceled, boolean captcha) {
                        if (captcha) {
                            AndroidUtils.sendToast(
                                    "Captcha: удаление остановлено, повторите позже");
                            return;
                        }
                        if (canceled) {
                            AndroidUtils.sendToast("Удаление отменено");
                            return;
                        }
                        runDedupeDeleteGroup(ctx, groups, index + 1,
                                okTotal + ok, failedTotal + failed);
                    }
                });
        if (!started) {
            AndroidUtils.sendToast("Дождитесь завершения другой операции");
        }
    }

    private static String duration(MusicTrack track) {
        int seconds = Math.max(0, track.h);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static final class DedupeSelection {
        final MusicDedupe.Section section;
        final MusicTrack track;
        boolean selected;

        DedupeSelection(MusicDedupe.Section section, MusicTrack track) {
            this.section = section;
            this.track = track;
        }
    }

    private static final class DeleteGroup {
        final MusicDedupe.Section section;
        final List<MusicTrack> tracks = new ArrayList<>();

        DeleteGroup(MusicDedupe.Section section) {
            this.section = section;
        }
    }

    private void showSortDialog() {
        String[] items = {"По дате добавления", "По названию", "По исполнителю", "По длительности"};
        MusicRepository.SortMode[] modes = MusicRepository.SortMode.values();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView textView = (TextView) super.getView(position, convertView, parent);
                textView.setTextColor(getTextAttr());
                return textView;
            }
        };

        new VkAlertDialog.Builder(requireContext())
                .setTitle("Сортировка музыки")
                .setAdapter(adapter, (dialog, which) -> {
                    repo().setSortMode(modes[which]);
                    findPreference("music_sort").setSummary(sortLabel(repo().sortMode()));
                    dialog.cancel();
                })
                .show();
    }

    private static String sortLabel(MusicRepository.SortMode mode) {
        if (mode == null) {
            return "По дате добавления";
        }
        return switch (mode) {
            case TITLE -> "По названию";
            case ARTIST -> "По исполнителю";
            case DURATION -> "По длительности";
            default -> "По дате добавления";
        };
    }

    private static String getAutocacheSumm() {
        int autocache = Preferences.autocache();
        return switch (autocache) {
            case 1 -> "Только для своих";
            case 2 -> "Кешировать всё";
            default -> "Отключено";
        };
    }

    public void updateLastFmPref() {
        if (LastFMScrobbler.isLoggedIn()) {
            findPreference("lastfm_auth").setSummary(getString(com.vtosters.lite.R.string.lastfm_authorized_as) + " " + LastFMScrobbler.getUserName());
            findPreference("lastfm_enabled").setEnabled(true);
        } else {
            findPreference("lastfm_auth").setSummary(getString(com.vtosters.lite.R.string.lastfm_auth_summ));
            findPreference("lastfm_enabled").setEnabled(false);
        }
    }

    private void logout(Context ctx) {
        new VkAlertDialog.Builder(ctx)
                .setTitle(com.vtosters.lite.R.string.lastfm_logout_title)
                .setMessage(com.vtosters.lite.R.string.lastfm_logout_confirm)
                .setPositiveButton(com.vtosters.lite.R.string.vkim_yes,
                        (dialog, which) -> LastFMScrobbler.logout())
                .setNeutralButton(com.vtosters.lite.R.string.vkim_no,
                        (dialog, which) -> dialog.cancel())
                .show();
    }

    @SuppressLint("DefaultLocale")
    private void cachedPlaylistsDialog(Context ctx) {
        List<Playlist> playlists = repo().cachedPlaylistsNow();
        String[] playlistNames = new String[playlists.size()];

        for (int i = 0; i < playlists.size(); i++) {
            playlistNames[i] = playlists.get(i).g; // Assuming Playlist class has a getTitle() method
        }

        VkAlertDialog.Builder builder = new VkAlertDialog.Builder(ctx);
        builder.setTitle("Скачанные плейлисты");
        builder.setItems(playlistNames, (dialog, which) -> {
            Playlist selectedPlaylist = playlists.get(which);
            String playlistId = selectedPlaylist.v1();
            repo().removeCachedPlaylist(playlistId);
            AndroidUtils.sendToast("Плейлист удален");
            findPreference("cached_playlists").setSummary(String.format("Скачано плейлистов: %d", repo().cachedPlaylistsNow().size()));
        });

        builder.show();
    }

    private void delcache(Context ctx, boolean isPlaylists) {
        new VkAlertDialog.Builder(ctx)
                .setTitle(com.vtosters.lite.R.string.warning)
                .setMessage(com.vtosters.lite.R.string.cached_tracks_remove_confirm)
                .setPositiveButton(com.vtosters.lite.R.string.yes, (dialog, which) -> {
                    executor.submit(() -> {
                        if (isPlaylists) {
                            repo().removeAllCachedPlaylists();
                        } else {
                            repo().clearOwnCachedTracks();
                        }
                    });
                    findPreference("cached_tracks").setSummary(String.format(requireContext().getString(com.vtosters.lite.R.string.cached_tracks_counter), repo().cachedTrackCount()));
                })
                .setNeutralButton(com.vtosters.lite.R.string.no, (dialog, which) -> dialog.cancel())
                .show();
    }

    private void dlaudio(Context ctx) {
        if (LibVKXClient.isIntegrationEnabled()) {
            AndroidUtils.sendToast(AndroidUtils.getString("vkx_integration_enabled_info"));
            return;
        }

        new VkAlertDialog.Builder(ctx)
                .setTitle(com.vtosters.lite.R.string.download_method)
                .setMessage(com.vtosters.lite.R.string.download_method_desc)
                .setPositiveButton(com.vtosters.lite.R.string.download_method_cache, (dialog, which) -> {
                    executor.submit(AudioDownloader::cacheAllAudios);
                })
                .setNegativeButton(com.vtosters.lite.R.string.download_method_mp3, (dialog, which) -> {
                    executor.submit(AudioDownloader::downloadAllAudios);
                })
                .show();
    }

    @Override
    public int T4() {
        return com.vtosters.lite.R.string.vtl_music_settings;
    }
}