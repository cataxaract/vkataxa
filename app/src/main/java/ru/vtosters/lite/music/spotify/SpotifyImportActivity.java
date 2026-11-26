package ru.vtosters.lite.music.spotify;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.generic.RoundingParams;
import com.facebook.drawee.view.SimpleDraweeView;
import com.vk.core.dialogs.alert.VkAlertDialog;
import com.vk.dto.music.Playlist;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.hooks.other.ThemesUtils;
import ru.vtosters.lite.core.flags.FeatureFlags;
import ru.vtosters.lite.utils.AccountManagerUtils;
import ru.vtosters.lite.utils.AndroidUtils;

public class SpotifyImportActivity extends AppCompatActivity {
    private static final int REQ_CSV = 7101;
    private static final int REQ_STORAGE = 7102;
    private static final int REQ_TREE = 7103;
    private static final String PREF_LOCAL_TREE = "music_local_tree";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

    private LinearLayout content;
    private LinearLayout footer;
    private ProgressBar progress;
    private TextView status;
    private TextView pausedJobs;
    private TextView localFolder;
    private TextView cancelButton;
    private boolean running;
    private Future<?> currentTask;
    private volatile boolean cancelRequested;
    private String pendingCsvKey;
    private String pendingCsvName;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(ThemesUtils.getBackgroundContent());
        getWindow().setNavigationBarColor(ThemesUtils.getBackgroundContent());
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePausedJobs();
        updateLocalFolder();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                int flags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            flags != 0 ? flags : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                Preferences.getPreferences().edit().putString(PREF_LOCAL_TREE, tree.toString()).apply();
                updateLocalFolder();
                scanLocalMusic();
            }
            return;
        }
        if (requestCode != REQ_CSV || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingCsvKey = null;
            pendingCsvName = null;
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        String source = uri.toString();
        String fileName = displayName(uri);
        String key = pendingCsvKey;
        String name = pendingCsvName;
        pendingCsvKey = null;
        pendingCsvName = null;

        showProgress("Читаем CSV...");
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("Не удалось открыть CSV");
                }
                CsvPlaylistParser.Result parsed = CsvPlaylistParser.parse(fileName, input);
                main.post(() -> startCsv(parsed, key, name, source));
            } catch (Throwable error) {
                main.post(() -> {
                    hideProgress();
                    Toast.makeText(this, "Ошибка CSV: " + message(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanLocalMusic();
        } else {
            Toast.makeText(this, "Нужен доступ к файлам, чтобы найти музыку", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        setContentView(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView back = text("‹", 34, text(), true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), 0, dp(12), dp(2));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back);

        TextView title = text("Импорт из Spotify", 20, text(), true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toolbar.addView(title);

        // TEMP-UI(MU-10): toolbar "Прервано" + paused-jobs dialogs. Remove after the redesign.
        pausedJobs = text("Прервано", 13, accent(), true);
        pausedJobs.setPadding(dp(8), dp(8), dp(8), dp(8));
        pausedJobs.setVisibility(View.GONE);
        pausedJobs.setOnClickListener(v -> showPausedJobs());
        toolbar.addView(pausedJobs);

        TextView refresh = text("Обновить", 13, accent(), true);
        refresh.setPadding(dp(10), dp(8), dp(4), dp(8));
        refresh.setOnClickListener(v -> loadPlaylists());
        toolbar.addView(refresh);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(16), dp(10), dp(16), dp(12));
        footer.setBackgroundColor(background());
        footer.setVisibility(View.GONE);
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        if (progress.getProgressDrawable() != null) {
            progress.getProgressDrawable().setColorFilter(accent(), android.graphics.PorterDuff.Mode.SRC_IN);
        }
        footer.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4)
        ));

        status = text("", 12, secondaryText(), false);
        status.setPadding(0, dp(8), 0, 0);
        footer.addView(status);

        cancelButton = text("Отмена", 12, accent(), true);
        cancelButton.setGravity(Gravity.END);
        cancelButton.setPadding(0, dp(8), 0, 0);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> cancelImport());
        footer.addView(cancelButton);
    }

    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "vkataxa".equals(data.getScheme()) && "spotify-callback".equals(data.getHost())) {
            String error = data.getQueryParameter("error");
            if (error != null) {
                renderAuth("Spotify вернул ошибку: " + error);
                return;
            }

            String code = data.getQueryParameter("code");
            String state = data.getQueryParameter("state");
            if (code != null) {
                showProgress("Подключаем Spotify...");
                executor.execute(() -> {
                    try {
                        SpotifyAuth.exchangeCode(this, code, state);
                        SpotifyApi.getMeName(this);
                        main.post(this::loadPlaylists);
                    } catch (Throwable e) {
                        main.post(() -> {
                            hideProgress();
                            renderAuth("Не удалось подключить Spotify: " + message(e));
                        });
                    }
                });
                return;
            }
        }

        if (SpotifyAuthStore.isAuthorized(this)) {
            loadPlaylists();
        } else {
            renderAuth(null);
        }
    }

    private void premiumNotice(String error) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(
                cardBackground(),
                withAlpha(accent(), 45),
                20
        ));
        content.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView mark = text("SPOTIFY PREMIUM", 10, accent(), true);
        mark.setLetterSpacing(0.12f);
        card.addView(mark);

        TextView title = text("Premium-режим недоступен", 22, text(), true);
        title.setPadding(0, dp(5), 0, dp(6));
        card.addView(title);

        TextView message = text(error, 12, secondaryText(), false);
        card.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, 0);
        card.addView(actions);

        TextView retry = button("Повторить");
        retry.setOnClickListener(v -> loadPlaylists());
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(42), 1f));

        TextView switchAccount = text("Сменить аккаунт", 12, accent(), true);
        switchAccount.setGravity(Gravity.CENTER);
        switchAccount.setPadding(dp(12), 0, 0, 0);
        switchAccount.setOnClickListener(v -> {
            SpotifyAuthStore.clear(this);
            renderAuth(null);
        });
        actions.addView(switchAccount, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
        ));
    }

    private void renderAuth(String error) {
        running = false;
        hideProgress();
        content.removeAllViews();

        if (SpotifyAuthStore.isAuthorized(this) && error != null
                && (error.contains("Premium") || error.contains("403"))) {
            addCsvSection();
            premiumNotice(error);
            return;
        }

        addCsvSection();

        TextView premiumHeading = text("Premium-аккаунт", 16, text(), true);
        premiumHeading.setPadding(0, dp(18), 0, dp(8));
        content.addView(premiumHeading);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(24), dp(20), dp(24));
        hero.setBackground(rounded(withAlpha(accent(), 22), withAlpha(accent(), 90), 22));
        content.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView mark = text("SPOTIFY → VK", 11, accent(), true);
        mark.setLetterSpacing(0.12f);
        hero.addView(mark);

        TextView title = text("Перенесите плейлисты\nв VK Музыку", 26, text(), true);
        title.setPadding(0, dp(8), 0, dp(8));
        hero.addView(title);

        TextView description = text(
                "Войдите в Spotify, выберите плейлист и импортируйте его в VK. "
                        + "Треки находятся автоматически, а повторная синхронизация обновит плейлист.",
                13,
                secondaryText(),
                false
        );
        description.setPadding(0, 0, 0, dp(16));
        hero.addView(description);

        TextView login = button("Войти через Spotify");
        login.setOnClickListener(v -> openAuth());
        hero.addView(login, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        if (error != null) {
            TextView errorView = text(error, 12, 0xFFFF6B6B, false);
            errorView.setPadding(0, dp(14), 0, 0);
            hero.addView(errorView);
        }

        TextView hint = text(
                "Нужен Spotify-аккаунт с плейлистами. Пароль вводится только на сайте Spotify.",
                11,
                secondaryText(),
                false
        );
        hint.setPadding(0, dp(14), 0, 0);
        content.addView(hint);
    }

    private void loadPlaylists() {
        if (!SpotifyAuthStore.isAuthorized(this)) {
            renderAuth(null);
            return;
        }

        showProgress("Загружаем плейлисты...");
        executor.execute(() -> {
            try {
                String name = SpotifyApi.getMeName(this);
                List<SpotifyPlaylist> playlists = SpotifyApi.getPlaylists(this);
                main.post(() -> {
                    hideProgress();
                    renderPlaylists(name, playlists);
                });
            } catch (Throwable e) {
                main.post(() -> {
                    hideProgress();
                    renderAuth("Не удалось загрузить плейлисты: " + message(e));
                });
            }
        });
    }

    private void renderPlaylists(String accountName, List<SpotifyPlaylist> playlists) {
        content.removeAllViews();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(12));
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(titles);

        String connected = "Подключено: " + (accountName == null || accountName.isEmpty() ? "Spotify" : accountName);
        titles.addView(text(connected, 12, secondaryText(), false));

        TextView heading = text("Выберите плейлист", 18, text(), true);
        heading.setPadding(0, dp(2), 0, 0);
        titles.addView(heading);

        TextView logout = text("Выйти", 12, accent(), true);
        logout.setPadding(dp(10), dp(8), 0, dp(8));
        logout.setOnClickListener(v -> {
            SpotifyAuthStore.clear(this);
            renderAuth(null);
        });
        header.addView(logout);

        addCsvSection();

        if (playlists.isEmpty()) {
            TextView empty = text("В Spotify не найдено плейлистов", 14, secondaryText(), false);
            empty.setPadding(0, dp(20), 0, 0);
            content.addView(empty);
            return;
        }

        for (SpotifyPlaylist playlist : playlists) {
            content.addView(playlistRow(playlist), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private View playlistRow(SpotifyPlaylist playlist) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(rounded(
                cardBackground(),
                withAlpha(accent(), 50),
                16
        ));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(rowParams);

        SimpleDraweeView cover = new SimpleDraweeView(this);
        cover.setLayoutParams(new LinearLayout.LayoutParams(dp(58), dp(58)));
        cover.setBackground(rounded(withAlpha(accent(), 70), withAlpha(accent(), 120), 12));
        if (!TextUtils.isEmpty(playlist.imageUrl)) {
            cover.setImageURI(Uri.parse(playlist.imageUrl));
            if (cover.getHierarchy() instanceof GenericDraweeHierarchy) {
                ((GenericDraweeHierarchy) cover.getHierarchy())
                        .a(RoundingParams.d(dp(12)));
            }
        }
        row.addView(cover);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.setMargins(dp(12), 0, dp(8), 0);
        info.setLayoutParams(infoParams);
        row.addView(info);

        TextView name = text(playlist.name, 15, text(), true);
        name.setMaxLines(2);
        info.addView(name);

        String meta = playlist.trackCount + " треков"
                + (TextUtils.isEmpty(playlist.owner) ? "" : " · " + playlist.owner);
        info.addView(text(meta, 11, secondaryText(), false));

        SpotifySyncStore.Entry entry = SpotifySyncStore.get(this, playlist.id);
        String state;
        if (entry != null && entry.valid()) {
            state = "Синхронизирован: " + dateFormat.format(new Date(entry.lastSync));
        } else {
            state = "Ещё не импортирован";
        }
        TextView stateView = text(state, 11, entry != null ? accent() : secondaryText(), false);
        stateView.setPadding(0, dp(3), 0, 0);
        info.addView(stateView);

        TextView action = button(entry != null && entry.valid() ? "Синхронизировать" : "Импортировать");
        action.setPadding(dp(14), 0, dp(14), 0);
        action.setOnClickListener(v -> {
            if (running) {
                Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
                return;
            }
            start(playlist, entry != null && entry.valid());
        });
        row.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
        ));

        return row;
    }

    private void addCsvSection() {
        content.addView(csvCard(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        content.addView(localMusicCard());

        List<SpotifySyncStore.Entry> entries = SpotifySyncStore.all(this);
        boolean hasCsv = false;
        for (SpotifySyncStore.Entry entry : entries) {
            if (entry.spotifyId != null && entry.spotifyId.startsWith("csv:") && entry.valid()) {
                if (!hasCsv) {
                    TextView heading = text("CSV-плейлисты", 16, text(), true);
                    heading.setPadding(0, dp(18), 0, dp(8));
                    content.addView(heading);
                    hasCsv = true;
                }
                content.addView(csvRow(entry), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        }
    }

    private View csvCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(
                cardBackground(),
                withAlpha(accent(), 45),
                20
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(18), 0, 0);
        card.setLayoutParams(params);

        TextView mark = text("БЕЗ PREMIUM", 10, accent(), true);
        mark.setLetterSpacing(0.12f);
        card.addView(mark);

        TextView title = text("Импорт из CSV", 22, text(), true);
        title.setPadding(0, dp(5), 0, dp(6));
        card.addView(title);

        TextView description = text(
                "Экспортируйте плейлист Spotify в CSV через Exportify или другой экспортёр "
                        + "и выберите файл. Логин и Premium не нужны.",
                12,
                secondaryText(),
                false
        );
        description.setPadding(0, 0, 0, dp(14));
        card.addView(description);

        TextView choose = button("Выбрать CSV");
        choose.setOnClickListener(v -> pickCsv(null, null));
        card.addView(choose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        return card;
    }

    private View localMusicCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(
                cardBackground(),
                withAlpha(accent(), 45),
                20
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(params);

        TextView mark = text("ЛОКАЛЬНАЯ МУЗЫКА", 10, accent(), true);
        mark.setLetterSpacing(0.12f);
        card.addView(mark);

        TextView title = text("Импорт локальной музыки", 22, text(), true);
        title.setPadding(0, dp(5), 0, dp(6));
        card.addView(title);

        TextView description = text(
                "Выберите папку с треками (например, папку Omnia) или оставьте Music/ и Download/. "
                        + "Мод сопоставит файлы с VK и добавит найденное без дублей.",
                12,
                secondaryText(),
                false
        );
        description.setPadding(0, 0, 0, dp(10));
        card.addView(description);

        localFolder = text("", 12, secondaryText(), false);
        localFolder.setPadding(0, 0, 0, dp(10));
        updateLocalFolder();
        card.addView(localFolder);

        LinearLayout folderActions = new LinearLayout(this);
        folderActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView pickFolder = button("Выбрать папку");
        pickFolder.setOnClickListener(v -> pickLocalFolder());
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        pickParams.setMargins(0, 0, dp(8), 0);
        folderActions.addView(pickFolder, pickParams);
        TextView resetFolder = button("Сбросить");
        resetFolder.setOnClickListener(v -> resetLocalFolder());
        folderActions.addView(resetFolder, new LinearLayout.LayoutParams(0, dp(40), 1f));
        card.addView(folderActions);

        TextView choose = button("Сканировать и импортировать");
        choose.setOnClickListener(v -> startLocalImport());
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        chooseParams.setMargins(0, dp(8), 0, 0);
        card.addView(choose, chooseParams);
        return card;
    }

    private void startLocalImport() {
        if (running) {
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasTree = !Preferences.getString(PREF_LOCAL_TREE).isEmpty();
        if (!hasTree && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_STORAGE
            );
            return;
        }
        scanLocalMusic();
    }

    private void scanLocalMusic() {
        if (running) {
            return;
        }
        String tree = Preferences.getString(PREF_LOCAL_TREE);
        running = true;
        showProgress("Ищем аудиофайлы...");

        executor.execute(() -> {
            try {
                List<SpotifyTrack> tracks;
                String source = tree.isEmpty() ? "media" : tree;
                String label;
                if (!tree.isEmpty()) {
                    Uri uri = Uri.parse(tree);
                    tracks = LocalMusicScanner.scanTree(this, uri);
                    label = folderName(uri);
                } else {
                    File folder = LocalMusicScanner.defaultFolder();
                    tracks = LocalMusicScanner.scan(this, folder);
                    label = "Music/ и Download/";
                }
                main.post(() -> {
                    if (tracks.isEmpty()) {
                        running = false;
                        hideProgress();
                        Toast.makeText(this, "В " + label + " нет аудиофайлов", Toast.LENGTH_LONG).show();
                        return;
                    }
                    hideProgress();
                    if (FeatureFlags.isEnabled(FeatureFlags.Flag.IMPORT_UI)) {
                        showLocalTrackSelection(tracks, source, label);
                    } else {
                        showLocalImportDialog(tracks, source, label);
                    }
                });
            } catch (Throwable error) {
                main.post(() -> {
                    running = false;
                    hideProgress();
                    Toast.makeText(this, "Ошибка сканирования: " + message(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // IMP-3: dry-run selection/filter before local import; nothing is added until the target dialog.
    private void showLocalTrackSelection(List<SpotifyTrack> tracks, String source, String label) {
        final boolean[] selected = new boolean[tracks.size()];
        final List<Integer> visible = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            visible.add(i);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.7f)));

        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("Фильтр: исполнитель или название");
        filter.setInputType(InputType.TYPE_CLASS_TEXT);
        filter.setTextColor(text());
        filter.setHintTextColor(secondaryText());
        root.addView(filter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button all = new Button(this);
        all.setAllCaps(false);
        all.setText("Все");
        Button none = new Button(this);
        none.setAllCaps(false);
        none.setText("Ничего");
        Button next = new Button(this);
        next.setAllCaps(false);
        next.setText("Далее (0)");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(all, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(none, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(next, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        root.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Runnable updateNext = () -> {
            int count = 0;
            for (boolean value : selected) {
                if (value) {
                    count++;
                }
            }
            next.setText("Далее (" + count + ")");
        };

        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return visible.size();
            }

            @Override
            public Object getItem(int position) {
                return tracks.get(visible.get(position));
            }

            @Override
            public long getItemId(int position) {
                return visible.get(position);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                CheckBox view = convertView instanceof CheckBox
                        ? (CheckBox) convertView : new CheckBox(SpotifyImportActivity.this);
                int index = visible.get(position);
                SpotifyTrack track = tracks.get(index);
                view.setOnCheckedChangeListener(null);
                view.setText(track.artist + " — " + track.title + " · "
                        + formatDuration(track.durationSeconds()));
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                view.setTextColor(text());
                view.setChecked(selected[index]);
                view.setOnCheckedChangeListener((button, checked) -> {
                    selected[index] = checked;
                    updateNext.run();
                });
                return view;
            }
        };
        list.setAdapter(adapter);

        Runnable rebuild = () -> {
            String query = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (int i = 0; i < tracks.size(); i++) {
                SpotifyTrack track = tracks.get(i);
                String haystack = (track.artist + " " + track.title)
                        .toLowerCase(Locale.ROOT);
                if (query.isEmpty() || haystack.contains(query)) {
                    visible.add(i);
                }
            }
            adapter.notifyDataSetChanged();
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                rebuild.run();
            }
        });

        all.setOnClickListener(v -> {
            for (int index : visible) {
                selected[index] = true;
            }
            adapter.notifyDataSetChanged();
            updateNext.run();
        });
        none.setOnClickListener(v -> {
            for (int index : visible) {
                selected[index] = false;
            }
            adapter.notifyDataSetChanged();
            updateNext.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Выбор треков (" + tracks.size() + ")")
                .setView(root)
                .setNegativeButton("Отмена", (d, which) -> running = false)
                .setOnCancelListener(d -> running = false)
                .show();

        next.setOnClickListener(v -> {
            List<SpotifyTrack> chosen = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) {
                if (selected[i]) {
                    chosen.add(tracks.get(i));
                }
            }
            if (chosen.isEmpty()) {
                Toast.makeText(this, "Ничего не выбрано", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showLocalImportDialog(chosen, source, label + " · выбрано "
                    + chosen.size() + " из " + tracks.size());
        });

        updateNext.run();
        rebuild.run();
    }

    private static String formatDuration(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }

    private void showLocalImportDialog(List<SpotifyTrack> tracks, String source, String label) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(20), dp(24), dp(8));
        header.addView(text("Найдено " + tracks.size() + " аудиофайлов", 20, text(), true));
        TextView summary = text(preview(label, tracks), 12, secondaryText(), false);
        summary.setPadding(0, dp(6), 0, 0);
        header.addView(summary);

        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setItems(new String[]{
                        "В «Мою музыку»",
                        "В существующий плейлист",
                        "В новый плейлист"
                }, (d, which) -> {
                    if (which == 0) {
                        runLocalImport(tracks, true, 0, 0, source);
                    } else if (which == 1) {
                        loadLocalPlaylists(tracks, source);
                    } else {
                        showNewLocalPlaylistDialog(tracks, source);
                    }
                })
                .setNegativeButton("Отмена", (d, which) -> running = false)
                .setOnCancelListener(d -> running = false)
                .show();
    }

    private void pickLocalFolder() {
        if (running) {
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_TREE);
        } catch (Throwable error) {
            Toast.makeText(this, "Не удалось открыть выбор папки: " + message(error), Toast.LENGTH_LONG).show();
        }
    }

    private void resetLocalFolder() {
        if (running) {
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }
        Preferences.getPreferences().edit().remove(PREF_LOCAL_TREE).apply();
        updateLocalFolder();
    }

    private void updateLocalFolder() {
        if (localFolder == null) {
            return;
        }
        String tree = Preferences.getString(PREF_LOCAL_TREE);
        localFolder.setText(tree.isEmpty()
                ? "Папка по умолчанию: Music/ и Download/"
                : "Папка: " + folderName(Uri.parse(tree)));
    }

    private static String folderName(Uri tree) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
            if (id != null && !id.isEmpty()) {
                id = Uri.decode(id);
                if (id.startsWith("primary:")) {
                    id = id.substring("primary:".length());
                }
                return id;
            }
        } catch (Throwable ignored) {
        }
        String last = tree.getLastPathSegment();
        return last == null ? tree.toString() : Uri.decode(last);
    }

    private static String preview(String source, List<SpotifyTrack> tracks) {
        StringBuilder text = new StringBuilder("Папка: ").append(source);
        if (!tracks.isEmpty()) {
            SpotifyTrack first = tracks.get(0);
            String artist = first.artist == null ? "" : first.artist;
            String title = first.title == null ? "" : first.title;
            text.append("\nПервый: ");
            if (!artist.isEmpty()) {
                text.append(artist).append(" — ");
            }
            text.append(title);
            if (tracks.size() > 1) {
                text.append(" (+").append(tracks.size() - 1).append(")");
            }
        }
        return text.toString();
    }

    private void loadLocalPlaylists(List<SpotifyTrack> tracks, String source) {
        showProgress("Загружаем плейлисты...");
        executor.execute(() -> {
            try {
                List<Playlist> playlists = VkPlaylistApi.getUserPlaylists();
                main.post(() -> {
                    hideProgress();
                    if (playlists.isEmpty()) {
                        Toast.makeText(this, "Плейлисты не найдены", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] titles = new String[playlists.size()];
                    for (int i = 0; i < playlists.size(); i++) {
                        titles[i] = playlists.get(i).g;
                    }
                    new VkAlertDialog.Builder(this)
                            .setTitle("Куда добавить?")
                            .setItems(titles, (d, which) -> {
                                Playlist playlist = playlists.get(which);
                                int[] ids = parsePlaylistId(playlist.v1());
                                if (ids == null) {
                                    Toast.makeText(this, "Некорректный плейлист", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                runLocalImport(tracks, true, ids[0], ids[1], source);
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                });
            } catch (Throwable error) {
                main.post(() -> {
                    hideProgress();
                    Toast.makeText(this, "Ошибка: " + message(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showNewLocalPlaylistDialog(List<SpotifyTrack> tracks, String source) {
        EditText input = new EditText(this);
        input.setHint("Название плейлиста");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Новый плейлист")
                .setView(input)
                .setPositiveButton("Создать", (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "Импорт локальной музыки";
                    }
                    createLocalPlaylist(tracks, name, source);
                })
                .setNegativeButton("Отмена", (d, which) -> running = false)
                .show();
    }

    private void createLocalPlaylist(List<SpotifyTrack> tracks, String name, String source) {
        showProgress("Создаём плейлист...");
        executor.execute(() -> {
            try {
                int owner = VkPlaylistApi.currentUserId();
                Playlist playlist = VkPlaylistApi.createPlaylist(
                        owner, name, "Импортировано локально", new ArrayList<>());
                main.post(() -> {
                    hideProgress();
                    runLocalImport(tracks, true, owner, playlist.a, source);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    hideProgress();
                    Toast.makeText(this, "Не удалось создать плейлист: " + message(error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static int[] parsePlaylistId(String playlistId) {
        if (playlistId == null) {
            return null;
        }
        int separator = playlistId.indexOf('_');
        if (separator <= 0 || separator == playlistId.length() - 1) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(playlistId.substring(0, separator)),
                    Integer.parseInt(playlistId.substring(separator + 1))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void runLocalImport(List<SpotifyTrack> tracks, boolean onlyNew,
                                int targetOwner, int targetPlaylist, String source) {
        cancelRequested = false;
        showImportProgress("Ищем треки в VK...");

        SpotifyImportService.Progress progressCallback = (done, total, message) -> main.post(() -> {
            if (total > 0) {
                progress.setProgress((int) (1000f * done / total));
            }
            status.setText(message);
        });

        SpotifyImportService.Callback callback = new SpotifyImportService.Callback() {
            @Override
            public void onDone(SpotifyImportService.Result result) {
                main.post(() -> {
                    cancelRequested = false;
                    running = false;
                    hideProgress();
                    Toast.makeText(SpotifyImportActivity.this, result.summary(), Toast.LENGTH_LONG).show();
                    showNotFound(result);
                    afterImport();
                });
            }

            @Override
            public void onError(Throwable error) {
                main.post(() -> {
                    boolean canceled = cancelRequested;
                    cancelRequested = false;
                    running = false;
                    hideProgress();
                    if (canceled) {
                        return;
                    }
                    Toast.makeText(
                            SpotifyImportActivity.this,
                            "Ошибка: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        };

        currentTask = executor.submit(() -> SpotifyImportService.importLocalMusic(
                this,
                tracks,
                onlyNew,
                targetOwner,
                targetPlaylist,
                source,
                progressCallback,
                callback
        ));
    }

    private View csvRow(SpotifySyncStore.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(rounded(
                cardBackground(),
                withAlpha(accent(), 50),
                16
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(params);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(info);

        TextView name = text(entry.name, 15, text(), true);
        name.setMaxLines(2);
        info.addView(name);

        TextView meta = text(entry.tracks.size() + " треков · " + dateFormat.format(new Date(entry.lastSync)),
                11, secondaryText(), false);
        meta.setPadding(0, dp(2), 0, 0);
        info.addView(meta);

        TextView action = text("Синхронизировать", 12, accent(), true);
        action.setPadding(dp(10), dp(6), 0, dp(2));
        action.setOnClickListener(v -> {
            if (running) {
                Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
                return;
            }
            pickCsv(entry.spotifyId, entry.name);
        });

        TextView delete = text("Удалить", 11, secondaryText(), false);
        delete.setPadding(dp(10), dp(2), 0, dp(6));
        delete.setOnClickListener(v -> confirmDeleteCsv(entry));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.END);
        actions.addView(action);
        actions.addView(delete);
        row.addView(actions);
        return row;
    }

    private void confirmDeleteCsv(SpotifySyncStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить плейлист?")
                .setMessage("Плейлист «" + entry.name + "» будет удалён из VK, а связь с CSV — очищена.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> deleteCsv(entry))
                .show();
    }

    private void deleteCsv(SpotifySyncStore.Entry entry) {
        if (running) {
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }

        running = true;
        showProgress("Удаляем плейлист...");
        executor.execute(() -> {
            try {
                VkPlaylistApi.deletePlaylist(entry.vkOwnerId, entry.vkPlaylistId);
                SpotifySyncStore.remove(this, entry.spotifyId);
                main.post(() -> {
                    running = false;
                    hideProgress();
                    Toast.makeText(this, "Плейлист удалён", Toast.LENGTH_SHORT).show();
                    afterImport();
                });
            } catch (Throwable error) {
                main.post(() -> {
                    running = false;
                    hideProgress();
                    Toast.makeText(
                            this,
                            "Не удалось удалить: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void pickCsv(String key, String name) {
        pendingCsvKey = key;
        pendingCsvName = name;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/csv",
                "text/comma-separated-values",
                "application/csv",
                "text/plain"
        });
        try {
            startActivityForResult(intent, REQ_CSV);
        } catch (Throwable error) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            startActivityForResult(fallback, REQ_CSV);
        }
    }

    private void startCsv(CsvPlaylistParser.Result parsed, String key, String name, String source) {
        if (running) {
            hideProgress();
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }

        String finalKey = key == null ? "csv:" + parsed.name : key;
        String finalName = name == null ? parsed.name : name;
        SpotifySyncStore.Entry existing = SpotifySyncStore.get(this, finalKey);
        boolean sync = existing != null && existing.valid();

        running = true;
        cancelRequested = false;
        showImportProgress(sync ? "Синхронизируем CSV..." : "Импортируем CSV...");

        SpotifyImportService.Progress progressCallback = (done, total, message) -> main.post(() -> {
            if (total > 0) {
                progress.setProgress((int) (1000f * done / total));
            }
            status.setText(message);
        });

        SpotifyImportService.Callback callback = new SpotifyImportService.Callback() {
            @Override
            public void onDone(SpotifyImportService.Result result) {
                main.post(() -> {
                    cancelRequested = false;
                    running = false;
                    hideProgress();
                    Toast.makeText(SpotifyImportActivity.this, result.summary(), Toast.LENGTH_LONG).show();
                    showNotFound(result);
                    afterImport();
                });
            }

            @Override
            public void onError(Throwable error) {
                main.post(() -> {
                    boolean canceled = cancelRequested;
                    cancelRequested = false;
                    running = false;
                    hideProgress();
                    if (canceled) {
                        return;
                    }
                    Toast.makeText(
                            SpotifyImportActivity.this,
                            "Ошибка: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        };

        currentTask = executor.submit(() -> {
            if (sync) {
                SpotifyImportService.syncTracks(this, finalKey, finalName, parsed.tracks, source, progressCallback, callback);
            } else {
                SpotifyImportService.importTracks(this, finalKey, finalName, parsed.tracks, source, progressCallback, callback);
            }
        });
    }

    private void showNotFound(SpotifyImportService.Result result) {
        if (result == null || result.notFoundTracks.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        int limit = Math.min(result.notFoundTracks.size(), 100);
        for (int i = 0; i < limit; i++) {
            builder.append("• ").append(result.notFoundTracks.get(i)).append('\n');
        }
        if (result.notFoundTracks.size() > limit) {
            builder.append("… и ещё ").append(result.notFoundTracks.size() - limit).append('\n');
        }

        TextView body = text(builder.toString().trim(), 13, text(), false);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("Не найдено: " + result.notFoundTracks.size())
                .setView(scroll)
                .setPositiveButton("ОК", null)
                .show();
    }

    private void afterImport() {
        if (SpotifyAuthStore.isAuthorized(this)) {
            loadPlaylists();
        } else {
            renderAuth(null);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "Spotify CSV.csv";
    }

    private void start(SpotifyPlaylist playlist, boolean sync) {
        running = true;
        cancelRequested = false;
        showImportProgress(sync ? "Синхронизируем..." : "Импортируем...");

        SpotifyImportService.Progress progressCallback = (done, total, message) -> main.post(() -> {
            if (total > 0) {
                progress.setProgress((int) (1000f * done / total));
            }
            status.setText(message);
        });

        SpotifyImportService.Callback callback = new SpotifyImportService.Callback() {
            @Override
            public void onDone(SpotifyImportService.Result result) {
                main.post(() -> {
                    cancelRequested = false;
                    running = false;
                    hideProgress();
                    Toast.makeText(SpotifyImportActivity.this, result.summary(), Toast.LENGTH_LONG).show();
                    showNotFound(result);
                    afterImport();
                });
            }

            @Override
            public void onError(Throwable error) {
                main.post(() -> {
                    boolean canceled = cancelRequested;
                    cancelRequested = false;
                    running = false;
                    hideProgress();
                    if (canceled) {
                        return;
                    }
                    Toast.makeText(
                            SpotifyImportActivity.this,
                            "Ошибка: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        };

        currentTask = executor.submit(() -> {
            if (sync) {
                SpotifyImportService.syncPlaylist(this, playlist, progressCallback, callback);
            } else {
                SpotifyImportService.importPlaylist(this, playlist, progressCallback, callback);
            }
        });
    }

    private void openAuth() {
        try {
            String url = SpotifyAuth.createAuthUrl(this);
            CustomTabsIntent customTabs = new CustomTabsIntent.Builder()
                    .setToolbarColor(ThemesUtils.getHeaderBackground())
                    .setShowTitle(true)
                    .build();
            customTabs.launchUrl(this, Uri.parse(url));
        } catch (Throwable error) {
            try {
                String url = SpotifyAuth.createAuthUrl(this);
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) {
                Toast.makeText(this, "Не удалось открыть Spotify", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showProgress(String message) {
        footer.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText(message);
        if (cancelButton != null) {
            cancelButton.setVisibility(View.GONE);
        }
    }

    private void showImportProgress(String message) {
        showProgress(message);
        if (cancelButton != null) {
            cancelButton.setVisibility(View.VISIBLE);
        }
    }

    private void cancelImport() {
        if (!running) {
            return;
        }
        cancelRequested = true;
        Future<?> task = currentTask;
        if (task != null) {
            task.cancel(true);
        }
        running = false;
        hideProgress();
        Toast.makeText(this, "Отмена: прогресс сохранён", Toast.LENGTH_SHORT).show();
    }

    private void hideProgress() {
        footer.setVisibility(View.GONE);
        if (cancelButton != null) {
            cancelButton.setVisibility(View.GONE);
        }
        updatePausedJobs();
    }

    private void updatePausedJobs() {
        if (pausedJobs == null) {
            return;
        }
        int count = ImportProgressDb.get(this).paused(AccountManagerUtils.getUserId()).size();
        pausedJobs.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        pausedJobs.setText(count > 0 ? "Прервано: " + count : "Прервано");
    }

    private void showPausedJobs() {
        List<ImportProgressDb.PausedJob> jobs =
                ImportProgressDb.get(this).paused(AccountManagerUtils.getUserId());
        if (jobs.isEmpty()) {
            updatePausedJobs();
            Toast.makeText(this, "Нет прерванных импортов", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            ImportProgressDb.PausedJob job = jobs.get(i);
            titles[i] = "Добавлено " + job.added + " из " + job.total
                    + " · не найдено " + job.miss + "\n" + jobLabel(job);
        }
        new VkAlertDialog.Builder(this)
                .setTitle("Прерванные импорты")
                .setItems(titles, (dialog, which) -> showPausedJobActions(jobs.get(which)))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showPausedJobActions(ImportProgressDb.PausedJob job) {
        String[] actions = {"Продолжить", "Повторить ненайденные", "Забыть"};
        new VkAlertDialog.Builder(this)
                .setTitle(jobLabel(job))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        resumeJob(job);
                    } else if (which == 1) {
                        ImportProgressDb.get(this).retryMisses(
                                AccountManagerUtils.getUserId(), job.key);
                        Toast.makeText(this, "Ненайденные будут искать снова", Toast.LENGTH_SHORT).show();
                    } else {
                        ImportProgressDb.get(this).clear(AccountManagerUtils.getUserId(), job.key);
                        updatePausedJobs();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void resumeJob(ImportProgressDb.PausedJob job) {
        if (running) {
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }
        // BUG-26: legacy row-based item keys cannot be matched to the new stable keys. Warn instead
        // of silently re-searching; already added VK uids are still de-duplicated.
        try {
            if (ImportProgressDb.get(this).hasLegacyItems(
                    AccountManagerUtils.getUserId(), job.key)) {
                Toast.makeText(this,
                        "Импорт сохранён в старом формате: часть треков будет найдена заново, "
                                + "уже добавленные не дублируются",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }
        if (job.key != null && job.key.startsWith("csv:")) {
            resumeCsv(job);
            return;
        }
        if (job.key != null && job.key.startsWith("local")) {
            resumeLocal(job);
            return;
        }
        start(new SpotifyPlaylist(job.key, jobLabel(job), "", "", 0, ""), true);
    }

    /** BUG-37: resume CSV from the persisted URI without asking for the file again. */
    private void resumeCsv(ImportProgressDb.PausedJob job) {
        String source = job.source;
        if (source != null && (source.startsWith("content:") || source.startsWith("file:"))) {
            final String finalSource = source;
            showProgress("Читаем CSV...");
            executor.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(Uri.parse(finalSource))) {
                    if (input == null) {
                        throw new IllegalStateException("Не удалось открыть CSV");
                    }
                    CsvPlaylistParser.Result parsed =
                            CsvPlaylistParser.parse(displayName(Uri.parse(finalSource)), input);
                    main.post(() -> startCsv(parsed, job.key, jobLabel(job), finalSource));
                } catch (Throwable error) {
                    main.post(() -> {
                        hideProgress();
                        Toast.makeText(this,
                                "Не удалось открыть CSV: " + message(error),
                                Toast.LENGTH_LONG).show();
                        pickCsv(job.key, jobLabel(job));
                    });
                }
            });
            return;
        }
        pickCsv(job.key, jobLabel(job));
    }

    /** BUG-37: resume local import from the saved SAF folder (or default Music/Download). */
    private void resumeLocal(ImportProgressDb.PausedJob job) {
        String source = job.source;
        if (source == null || source.isEmpty()) {
            source = Preferences.getString(PREF_LOCAL_TREE);
        }
        final String finalSource = source == null || source.isEmpty() ? "media" : source;

        running = true;
        showProgress("Ищем аудиофайлы...");
        executor.execute(() -> {
            try {
                List<SpotifyTrack> tracks;
                String label;
                if ("media".equals(finalSource)) {
                    tracks = LocalMusicScanner.scan(this, LocalMusicScanner.defaultFolder());
                    label = "Music/ и Download/";
                } else {
                    Uri uri = Uri.parse(finalSource);
                    tracks = LocalMusicScanner.scanTree(this, uri);
                    label = folderName(uri);
                }
                main.post(() -> {
                    if (tracks.isEmpty()) {
                        running = false;
                        hideProgress();
                        Toast.makeText(this, "В " + label + " нет аудиофайлов",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    runLocalImport(tracks, true, job.ownerId, job.playlistId, finalSource);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    running = false;
                    hideProgress();
                    Toast.makeText(this, "Ошибка сканирования: " + message(error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static String jobLabel(ImportProgressDb.PausedJob job) {
        if (job.label != null && !job.label.isEmpty()) {
            return job.label;
        }
        if (job.key != null && job.key.startsWith("csv:")) {
            return job.key.substring("csv:".length());
        }
        if (job.key != null && job.key.startsWith("local")) {
            return "Локальная музыка";
        }
        if (job.key != null && job.key.startsWith("spotify:")) {
            return "Spotify";
        }
        return job.key == null ? "Импорт" : job.key;
    }

    private int dp(int value) {
        return Math.round(AndroidUtils.dp2px(value));
    }

    private int background() {
        if (!ThemesUtils.isDarkTheme()) {
            return ThemesUtils.getBackgroundContent();
        }
        return ThemesUtils.isAmoledTheme() ? Color.BLACK : ThemesUtils.getBackgroundContent();
    }

    private int cardBackground() {
        return ThemesUtils.isDarkTheme() ? 0xFF0D0D0D : withAlpha(text(), 8);
    }

    private int text() {
        return ThemesUtils.isDarkTheme() ? Color.WHITE : ThemesUtils.getTextAttr();
    }

    private int secondaryText() {
        return ThemesUtils.isDarkTheme() ? 0xB3FFFFFF : ThemesUtils.getSTextAttr();
    }

    private int accent() {
        int reserved = ThemesUtils.getReservedAccent();
        return reserved != Color.TRANSPARENT ? reserved : ThemesUtils.getAccentColor();
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView button(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(accent(), accent(), 14));
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private static String message(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isEmpty()) {
            return error == null ? "неизвестная ошибка" : error.getClass().getSimpleName();
        }
        if (message.contains("Active premium subscription required")) {
            return "Spotify требует Premium у владельца приложения. "
                    + "Создайте приложение под Premium-аккаунтом или оформите Premium и повторите.";
        }
        if (message.contains("Can't confirm validation") || message.contains("Captcha needed")) {
            return "Не удалось подтвердить, что вы не робот. Повторите импорт и введите код с картинки.";
        }
        return message;
    }
}
