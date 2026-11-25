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
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import ru.vtosters.hooks.other.ThemesUtils;
import ru.vtosters.lite.utils.AccountManagerUtils;
import ru.vtosters.lite.utils.AndroidUtils;

public class SpotifyImportActivity extends AppCompatActivity {
    private static final int REQ_CSV = 7101;
    private static final int REQ_STORAGE = 7102;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

    private LinearLayout content;
    private LinearLayout footer;
    private ProgressBar progress;
    private TextView status;
    private TextView pausedJobs;
    private boolean running;
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
        if (requestCode != REQ_CSV || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingCsvKey = null;
            pendingCsvName = null;
            return;
        }

        Uri uri = data.getData();
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
                main.post(() -> startCsv(parsed, key, name));
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
                "Найдёт аудиофайлы в Music/ и Download/, сопоставит их с VK "
                        + "и добавит найденное в «Мою музыку» без дублей.",
                12,
                secondaryText(),
                false
        );
        description.setPadding(0, 0, 0, dp(14));
        card.addView(description);

        TextView choose = button("Добавить в «Мою музыку»");
        choose.setOnClickListener(v -> startLocalImport());
        card.addView(choose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        return card;
    }

    private void startLocalImport() {
        if (running) {
            Toast.makeText(this, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
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
        running = true;
        showProgress("Ищем аудиофайлы...");

        executor.execute(() -> {
            try {
                File folder = LocalMusicScanner.defaultFolder();
                List<SpotifyTrack> tracks = LocalMusicScanner.scan(this, folder);
                main.post(() -> {
                    if (tracks.isEmpty()) {
                        running = false;
                        hideProgress();
                        Toast.makeText(
                                this,
                                "В папке " + folder.getAbsolutePath() + " нет аудиофайлов",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    hideProgress();
                    showLocalImportDialog(tracks);
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

    private void showLocalImportDialog(List<SpotifyTrack> tracks) {
        new AlertDialog.Builder(this)
                .setTitle("Найдено " + tracks.size() + " аудиофайлов")
                .setItems(new String[]{
                        "В «Мою музыку»",
                        "В существующий плейлист",
                        "В новый плейлист"
                }, (d, which) -> {
                    if (which == 0) {
                        runLocalImport(tracks, true, 0, 0);
                    } else if (which == 1) {
                        loadLocalPlaylists(tracks);
                    } else {
                        showNewLocalPlaylistDialog(tracks);
                    }
                })
                .setNegativeButton("Отмена", (d, which) -> running = false)
                .setOnCancelListener(d -> running = false)
                .show();
    }

    private void loadLocalPlaylists(List<SpotifyTrack> tracks) {
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
                                runLocalImport(tracks, true, ids[0], ids[1]);
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

    private void showNewLocalPlaylistDialog(List<SpotifyTrack> tracks) {
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
                    createLocalPlaylist(tracks, name);
                })
                .setNegativeButton("Отмена", (d, which) -> running = false)
                .show();
    }

    private void createLocalPlaylist(List<SpotifyTrack> tracks, String name) {
        showProgress("Создаём плейлист...");
        executor.execute(() -> {
            try {
                int owner = VkPlaylistApi.currentUserId();
                Playlist playlist = VkPlaylistApi.createPlaylist(
                        owner, name, "Импортировано локально", new ArrayList<>());
                main.post(() -> {
                    hideProgress();
                    runLocalImport(tracks, true, owner, playlist.a);
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

    private void runLocalImport(List<SpotifyTrack> tracks, boolean onlyNew) {
        runLocalImport(tracks, onlyNew, 0, 0);
    }

    private void runLocalImport(List<SpotifyTrack> tracks, boolean onlyNew, int targetOwner, int targetPlaylist) {
        showProgress("Ищем треки в VK...");

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
                    running = false;
                    hideProgress();
                    Toast.makeText(
                            SpotifyImportActivity.this,
                            "Ошибка: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        };

        executor.execute(() -> SpotifyImportService.importLocalMusic(
                this,
                tracks,
                onlyNew,
                targetOwner,
                targetPlaylist,
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

    private void startCsv(CsvPlaylistParser.Result parsed, String key, String name) {
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
        showProgress(sync ? "Синхронизируем CSV..." : "Импортируем CSV...");

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
                    running = false;
                    hideProgress();
                    Toast.makeText(
                            SpotifyImportActivity.this,
                            "Ошибка: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        };

        executor.execute(() -> {
            if (sync) {
                SpotifyImportService.syncTracks(this, finalKey, finalName, parsed.tracks, progressCallback, callback);
            } else {
                SpotifyImportService.importTracks(this, finalKey, finalName, parsed.tracks, progressCallback, callback);
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
        showProgress(sync ? "Синхронизируем..." : "Импортируем...");

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
                    running = false;
                    hideProgress();
                    Toast.makeText(
                            SpotifyImportActivity.this,
                            "Ошибка: " + message(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        };

        executor.execute(() -> {
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
    }

    private void hideProgress() {
        footer.setVisibility(View.GONE);
        updatePausedJobs();
    }

    private void updatePausedJobs() {
        if (pausedJobs == null) {
            return;
        }
        int count = new ImportProgressDb(this).paused(AccountManagerUtils.getUserId()).size();
        pausedJobs.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        pausedJobs.setText(count > 0 ? "Прервано: " + count : "Прервано");
    }

    private void showPausedJobs() {
        List<ImportProgressDb.PausedJob> jobs =
                new ImportProgressDb(this).paused(AccountManagerUtils.getUserId());
        if (jobs.isEmpty()) {
            updatePausedJobs();
            Toast.makeText(this, "Нет прерванных импортов", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            ImportProgressDb.PausedJob job = jobs.get(i);
            titles[i] = "Добавлено " + job.added + " из " + job.total
                    + " · не найдено " + job.miss + "\n" + job.key;
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
                .setTitle(job.key)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        resumeJob(job);
                    } else if (which == 1) {
                        new ImportProgressDb(this).retryMisses(
                                AccountManagerUtils.getUserId(), job.key);
                        Toast.makeText(this, "Ненайденные будут искать снова", Toast.LENGTH_SHORT).show();
                    } else {
                        new ImportProgressDb(this).clear(AccountManagerUtils.getUserId(), job.key);
                        updatePausedJobs();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void resumeJob(ImportProgressDb.PausedJob job) {
        if (job.key != null && job.key.startsWith("csv:")) {
            pickCsv(job.key, job.key.substring("csv:".length()));
            return;
        }
        if (job.key != null && job.key.startsWith("local")) {
            Toast.makeText(this,
                    "Запустите импорт из папки заново — прогресс сохранён",
                    Toast.LENGTH_LONG).show();
            return;
        }
        start(new SpotifyPlaylist(job.key, "Spotify", "", "", 0, ""), true);
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
