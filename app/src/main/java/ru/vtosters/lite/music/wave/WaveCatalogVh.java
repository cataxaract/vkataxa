package ru.vtosters.lite.music.wave;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vk.catalog2.core.CatalogEntryPointParams;
import com.vk.catalog2.core.blocks.UIBlock;
import com.vk.catalog2.core.holders.common.CatalogListViewHolder;
import com.vk.catalog2.core.holders.common.CatalogScrollToTopStrategy;
import com.vk.core.ui.IdClickListener;
import com.vk.core.ui.TabletUiHelper;
import com.vk.dto.music.MusicTrack;
import com.vk.music.common.MusicPlaybackLaunchContext;
import com.vk.music.player.PlayState;
import com.vk.music.player.PlayerListener;
import com.vk.music.player.PlayerModel;
import com.vk.music.player.TrackInfo;
import com.vk.music.ui.track.adapters.MusicTrackItemsAdapter;
import com.vtosters.lite.R;

import java.util.ArrayList;
import java.util.List;

import ru.vtosters.hooks.other.ThemesUtils;
import ru.vtosters.lite.utils.AndroidUtils;
import ru.vtosters.lite.utils.music.MusicTrackUtils;

final class WaveCatalogVh implements CatalogListViewHolder {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final CatalogEntryPointParams params;
    private final PlayerModel player;
    private final List<MusicTrack> tracks = new ArrayList<>();

    private Context context;
    private FrameLayout root;
    private NestedScrollView scroll;
    private LinearLayout content;
    private RecyclerView recycler;
    private MusicTrackItemsAdapter adapter;
    private ProgressBar progress;
    private LinearLayout errorBox;
    private TextView metaView;
    private TextView currentTitle;
    private TextView currentArtist;
    private LinearLayout chips;
    private PlayButtonView playButton;
    private PlayerListener playerListener;
    private String mode = "auto";
    private String source = "VK";
    private boolean loading;
    private boolean loaded;
    private boolean pendingLoad;

    WaveCatalogVh(CatalogEntryPointParams params) {
        this.params = params;
        this.player = params == null ? null : params.k();
    }

    @Override
    public View a(LayoutInflater inflater, ViewGroup parent, Bundle bundle) {
        context = inflater.getContext();

        root = new FrameLayout(context);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(background());

        scroll = new NestedScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(scroll);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(28));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        content.addView(header());
        content.addView(moodSection());
        content.addView(queueHeader());

        recycler = new RecyclerView(context);
        recycler.setLayoutManager(new LinearLayoutManager(context));
        recycler.setNestedScrollingEnabled(false);
        recycler.setBackgroundColor(Color.TRANSPARENT);
        recycler.setClipToPadding(false);
        recycler.setPadding(0, 0, 0, dp(18));
        if (player != null) {
            adapter = new MusicTrackItemsAdapter.a(player)
                    .a(R.layout.music_audio_item_playlist)
                    .a(new IdClickListener.c<MusicTrack>() {
                        @Override
                        public void a(int id, MusicTrack item) {
                            if (item != null) {
                                play(item);
                            }
                        }
                    })
                    .a(false)
                    .a();
            recycler.setAdapter(adapter);
        }
        content.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        ));

        progress = new ProgressBar(context);
        if (progress.getIndeterminateDrawable() != null) {
            progress.getIndeterminateDrawable().setColorFilter(accent(), android.graphics.PorterDuff.Mode.SRC_IN);
        }
        root.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));

        errorBox = errorBox();
        errorBox.setVisibility(View.GONE);
        root.addView(errorBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));

        loaded = true;
        bindPlayer();
        load(false);
        return root;
    }

    private View header() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));
        header.setBackground(headerTint());

        TextView title = text("Моя волна", 30, text(), true);
        header.addView(title);

        metaView = text("Бесконечный поток на основе ваших прослушиваний", 12, secondaryText(), false);
        metaView.setPadding(0, dp(4), 0, 0);
        header.addView(metaView);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(14), 0, 0);
        header.addView(actions);

        playButton = new PlayButtonView(context, accent(), contrast(accent()));
        playButton.setOnClickListener(v -> toggleWavePlayback());
        actions.addView(playButton, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView refresh = text("Обновить", 13, secondaryText(), false);
        refresh.setPadding(dp(16), dp(8), 0, dp(8));
        refresh.setOnClickListener(v -> load(true));
        actions.addView(refresh);

        LinearLayout current = new LinearLayout(context);
        current.setOrientation(LinearLayout.HORIZONTAL);
        current.setGravity(Gravity.CENTER_VERTICAL);
        current.setPadding(0, dp(18), 0, dp(4));
        current.setOnClickListener(v -> {
            if (tracks.isEmpty()) {
                load(true);
            } else {
                play(tracks.get(0));
            }
        });
        header.addView(current, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout art = new FrameLayout(context);
        art.setBackground(roundedSurface(withAlpha(accent(), dark() ? 45 : 35), dp(10)));
        current.addView(art, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView note = text("♪", 18, accent(), false);
        note.setGravity(Gravity.CENTER);
        art.addView(note, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout currentTextCol = new LinearLayout(context);
        currentTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(12), 0, 0, 0);
        currentTextCol.setLayoutParams(textParams);
        current.addView(currentTextCol);

        currentTitle = text("Волна готова", 14, text(), true);
        currentTitle.setSingleLine(true);
        currentTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        currentTextCol.addView(currentTitle);

        currentArtist = text("Нажмите ▶", 11, secondaryText(), false);
        currentArtist.setSingleLine(true);
        currentArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        currentTextCol.addView(currentArtist);

        return header;
    }

    private View moodSection() {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(20), dp(6), 0, dp(4));
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("Настроить волну", 15, text(), true);
        section.addView(title);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(8), 0, 0);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        section.addView(scroll);

        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(chips, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addChip("Для вас", "auto");
        addChip("Открытия", "lastfm");
        addChip("Новинки", "new");
        addChip("Спокойное", "calm");
        updateChips();
        return section;
    }

    private void addChip(String label, String chipMode) {
        TextView chip = text(label, 12, text(), false);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        chip.setTag(chipMode);
        chip.setOnClickListener(v -> {
            mode = chipMode;
            updateChips();
            if (loading) {
                pendingLoad = true;
            } else {
                load(true);
            }
        });
        chips.addView(chip);
    }

    private void updateChips() {
        if (chips == null) {
            return;
        }
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean selected = mode.equals(chip.getTag());
            chip.setTextColor(selected ? contrast(accent()) : text());
            chip.setBackground(roundedSurface(
                    selected ? accent() : surface(),
                    dp(18)
            ));
        }
    }

    private View queueHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(8));
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("Дальше в волне", 16, text(), true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView count = text("", 12, secondaryText(), false);
        count.setTag("count");
        header.addView(count);
        return header;
    }

    private LinearLayout errorBox() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22), dp(18), dp(22), dp(18));
        box.setBackground(roundedSurface(surface(), dp(16)));

        TextView message = text("Не удалось собрать волну", 14, text(), true);
        message.setGravity(Gravity.CENTER);
        box.addView(message);

        TextView retry = text("Повторить", 13, accent(), true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(10), dp(10), dp(10), 0);
        retry.setOnClickListener(v -> load(true));
        box.addView(retry);
        return box;
    }

    private void load(boolean force) {
        if (loading || context == null) {
            return;
        }

        loading = true;
        progress.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        errorBox.setVisibility(View.GONE);
        if (!tracks.isEmpty() && metaView != null) {
            metaView.setText("Обновляем поток...");
        }

        WaveRepository.load(context, player, mode, 40, force, new WaveRepository.Callback() {
            @Override
            public void onSuccess(List<MusicTrack> result, String resultSource) {
                MAIN.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    errorBox.setVisibility(View.GONE);
                    tracks.clear();
                    tracks.addAll(result);
                    source = resultSource;
                    if (adapter != null) {
                        adapter.setItems(tracks);
                    }
                    updateListHeight();
                    updateHeader();
                    if (pendingLoad) {
                        pendingLoad = false;
                        load(true);
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                MAIN.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    if (tracks.isEmpty()) {
                        errorBox.setVisibility(View.VISIBLE);
                        if (metaView != null) {
                            metaView.setText("Пока не удалось собрать поток");
                        }
                    } else {
                        AndroidUtils.sendToast("Не удалось обновить волну");
                        updateHeader();
                    }
                    if (pendingLoad) {
                        pendingLoad = false;
                        load(true);
                    }
                });
            }
        });
    }

    private void updateHeader() {
        if (metaView != null) {
            metaView.setText(tracks.isEmpty()
                    ? "Бесконечный поток на основе ваших прослушиваний"
                    : tracks.size() + " треков · " + source + " · обновлено сейчас");
        }

        MusicTrack current = player == null ? null : player.S();
        if (current == null && !tracks.isEmpty()) {
            current = tracks.get(0);
        }
        if (current == null) {
            if (currentTitle != null) {
                currentTitle.setText("Волна готова");
                currentArtist.setText("Нажмите ▶");
            }
            return;
        }

        if (currentTitle != null) {
            currentTitle.setText(current.f);
            String artist = MusicTrackUtils.getArtists(current);
            currentArtist.setText(artist == null || artist.isEmpty() ? "Неизвестный исполнитель" : artist);
        }

        View count = root == null ? null : root.findViewWithTag("count");
        if (count instanceof TextView) {
            ((TextView) count).setText(tracks.isEmpty() ? "" : String.valueOf(tracks.size()));
        }
    }

    private void updateListHeight() {
        if (recycler == null) {
            return;
        }
        recycler.post(() -> {
            int rowHeight = recycler.getChildCount() > 0
                    ? recycler.getChildAt(0).getHeight()
                    : dp(72);
            ViewGroup.LayoutParams params = recycler.getLayoutParams();
            params.height = Math.max(0, rowHeight * tracks.size());
            recycler.setLayoutParams(params);
        });
    }

    private void bindPlayer() {
        if (player == null || playerListener != null) {
            return;
        }

        playerListener = new PlayerListener.a() {
            @Override
            public void a(PlayState state, TrackInfo info) {
                updatePlayButton(state);
            }

            @Override
            public void a(TrackInfo info) {
                updatePlayButton(player.Q());
            }

            @Override
            public void b(TrackInfo info) {
                updatePlayButton(player.Q());
            }
        };
        player.a(playerListener, true);
        updatePlayButton(player.Q());
    }

    private void unbindPlayer() {
        if (player != null && playerListener != null) {
            player.a(playerListener);
            playerListener = null;
        }
    }

    private void updatePlayButton(PlayState state) {
        if (playButton == null) {
            return;
        }
        boolean playing = state == PlayState.PLAYING;
        MAIN.post(() -> {
            if (playButton != null) {
                playButton.setPlaying(playing);
            }
        });
    }

    private void toggleWavePlayback() {
        if (player == null) {
            if (!tracks.isEmpty()) {
                play(tracks.get(0));
            }
            return;
        }

        PlayState state = player.Q();
        if (state == PlayState.PLAYING) {
            player.e();
            return;
        }

        MusicTrack current = player.S();
        if (state == PlayState.PAUSED && isWaveTrack(current)) {
            player.t();
            return;
        }

        if (tracks.isEmpty()) {
            load(true);
        } else {
            play(tracks.get(0));
        }
    }

    private boolean isWaveTrack(MusicTrack track) {
        if (track == null) {
            return false;
        }
        String uid = track.y1();
        for (MusicTrack item : tracks) {
            if (uid != null && uid.equals(item.y1())) {
                return true;
            }
        }
        return false;
    }

    private void play(MusicTrack track) {
        if (player == null) {
            AndroidUtils.sendToast("Плеер недоступен");
            return;
        }
        if (tracks.isEmpty()) {
            return;
        }

        List<MusicTrack> queue = new ArrayList<>(tracks);
        if (!queue.contains(track)) {
            queue.add(0, track);
        }
        player.a(track, queue, MusicPlaybackLaunchContext.f("vkataxa_wave"));
    }

    private int dp(int value) {
        return Math.round(AndroidUtils.dp2px(value));
    }

    private int background() {
        if (!dark()) {
            return ThemesUtils.getBackgroundContent();
        }
        return ThemesUtils.isAmoledTheme() ? Color.BLACK : ThemesUtils.getBackgroundContent();
    }

    private boolean dark() {
        return ThemesUtils.isDarkTheme();
    }

    private int text() {
        return dark() ? Color.WHITE : ThemesUtils.getTextAttr();
    }

    private int secondaryText() {
        return dark() ? 0xB3FFFFFF : ThemesUtils.getSTextAttr();
    }

    private int accent() {
        return ThemesUtils.getAccentColor();
    }

    private int surface() {
        return dark() ? 0xFF151515 : 0xFFF2F2F2;
    }

    private GradientDrawable headerTint() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        withAlpha(accent(), dark() ? 28 : 18),
                        Color.TRANSPARENT
                }
        );
        drawable.setCornerRadius(0);
        return drawable;
    }

    private GradientDrawable roundedSurface(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static int contrast(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return lum > 145 ? Color.BLACK : Color.WHITE;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class PlayButtonView extends View {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private final RectF bar = new RectF();
        private boolean playing;

        PlayButtonView(Context context, int circleColor, int iconColor) {
            super(context);
            circle.setColor(circleColor);
            icon.setColor(iconColor);
            setClickable(true);
            setFocusable(true);
            setPlaying(false);
        }

        void setPlaying(boolean playing) {
            this.playing = playing;
            setContentDescription(playing ? "Пауза" : "Запустить волну");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float cx = width / 2f;
            float cy = height / 2f;
            float radius = Math.min(width, height) / 2f;
            canvas.drawCircle(cx, cy, radius, circle);

            if (playing) {
                icon.setStyle(Paint.Style.FILL);
                float barWidth = radius * 0.18f;
                float barHeight = radius * 0.66f;
                float gap = radius * 0.22f;
                float left = cx - gap / 2f - barWidth;
                float top = cy - barHeight / 2f;
                bar.set(left, top, left + barWidth, top + barHeight);
                canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, icon);
                left = cx + gap / 2f;
                bar.set(left, top, left + barWidth, top + barHeight);
                canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, icon);
                return;
            }

            icon.setStyle(Paint.Style.FILL_AND_STROKE);
            icon.setStrokeJoin(Paint.Join.ROUND);
            icon.setStrokeWidth(radius * 0.07f);
            float halfHeight = radius * 0.38f;
            float triWidth = radius * 0.64f;
            float left = cx - triWidth * 0.34f;
            float right = left + triWidth;

            triangle.reset();
            triangle.moveTo(left, cy - halfHeight);
            triangle.lineTo(left, cy + halfHeight);
            triangle.lineTo(right, cy);
            triangle.close();
            canvas.drawPath(triangle, icon);
        }
    }

    @Override
    public void a() {
        unbindPlayer();
    }

    @Override
    public void a(UIBlock block) {
    }

    @Override
    public void a(UIBlock block, int position) {
    }

    @Override
    public void a(UIBlock block, int position, int payload) {
    }

    @Override
    public void a(CatalogScrollToTopStrategy strategy) {
    }

    @Override
    public TabletUiHelper d() {
        return null;
    }

    @Override
    public void e() {
    }

    @Override
    public void p() {
    }

    @Override
    public void onPause() {
        unbindPlayer();
    }

    @Override
    public void onResume() {
        bindPlayer();
        if (root != null && !loaded) {
            loaded = true;
            load(false);
        }
    }

    @Override
    public void F() {
        if (scroll != null) {
            scroll.smoothScrollTo(0, 0);
        }
    }
}
