// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.flags;

import android.content.pm.PackageInfo;

import androidx.core.content.pm.PackageInfoCompat;

import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.lite.core.log.VtLog;
import ru.vtosters.lite.utils.AndroidUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single registry of feature flags (F-02). Every new feature must be gated by a flag here,
 * disabled by default; the value is stored in the regular mod preferences file.
 */
public final class FeatureFlags {

    private static final String TAG = "Flags";
    private static final ConcurrentHashMap<Flag, Boolean> CACHE = new ConcurrentHashMap<>();
    private static volatile int sVersionCode = -1;

    private FeatureFlags() {
    }

    public enum Flag {
        ANTI_DELETE("anti_delete", false, "Удалённые при закрытом VK", 0),
        PINNED_CHATS("pinned_chats", false, "Закреплённые чаты: баг исчезновения + функция", 0),
        MAX_USERBOT("max_userbot", false, "MAX: чтение выбранных чатов (read-only)", 0),
        MUSIC_LIKE("music_like", false, "Лайк в один клик и состояние «сохранено»", 0),
        MUSIC_BULK("music_bulk", false, "Музыка: мультивыбор и массовые операции", 0),
        MUSIC_NAV("music_nav", false, "«Моя волна»/«Главная» с любой страницы музыки", 0),
        ARTIST_LATEST("artist_latest", false, "Последние треки на странице артиста", 0),
        LOCAL_IMPORT("local_import", false, "Импорт треков из локальной папки", 0),
        SPOTIFY_SYNC("spotify_sync", false, "Spotify → VK: добавление найденных треков", 0),
        IMPORT_QUEUE("import_queue", false, "Импорт без капчи: дедуп и очередь", 0),
        SPOTIFY_VPN("spotify_vpn", false, "Exportify: обработка 403/без VPN", 0),
        IMPORT_UI("import_ui", false, "Импорт: выбор и фильтр треков перед импортом", 0),
        MUSIC_SORT("music_sort", true, "Сортировка и управление музыкой", 0),
        MUSIC_DEDUPE("music_dedupe", false, "Музыка: поиск повторов в «Моей музыке» и плейлистах", 0);

        public final String key;
        public final boolean def;
        public final String description;
        public final int minVersion;

        Flag(String key, boolean def, String description, int minVersion) {
            this.key = key;
            this.def = def;
            this.description = description;
            this.minVersion = minVersion;
        }
    }

    public static boolean isEnabled(Flag flag) {
        if (flag == null || !isAvailable(flag)) {
            return false;
        }
        Boolean cached = CACHE.get(flag);
        if (cached != null) {
            return cached;
        }
        boolean value;
        try {
            value = Preferences.getBoolValue(flag.key, flag.def);
        } catch (Throwable t) {
            VtLog.e(TAG, "read failed: " + flag.key, t);
            value = flag.def;
        }
        CACHE.put(flag, value);
        return value;
    }

    public static void set(Flag flag, boolean value) {
        if (flag == null) {
            return;
        }
        try {
            Preferences.getPreferences().edit().putBoolean(flag.key, value).apply();
            CACHE.put(flag, value);
        } catch (Throwable t) {
            VtLog.e(TAG, "write failed: " + flag.key, t);
        }
    }

    public static boolean isAvailable(Flag flag) {
        return flag != null && versionCode() >= flag.minVersion;
    }

    public static String prefsKey(Flag flag) {
        return flag == null ? "" : flag.key;
    }

    public static int enabledCount() {
        int count = 0;
        for (Flag flag : Flag.values()) {
            if (isEnabled(flag)) {
                count++;
            }
        }
        return count;
    }

    private static int versionCode() {
        int cached = sVersionCode;
        if (cached >= 0) {
            return cached;
        }
        int code = 0;
        try {
            PackageInfo info = AndroidUtils.getGlobalContext().getPackageManager()
                    .getPackageInfo(AndroidUtils.getPackageName(), 0);
            long version = PackageInfoCompat.getLongVersionCode(info);
            code = version > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) version;
        } catch (Throwable t) {
            VtLog.w(TAG, "versionCode unavailable: " + t);
        }
        sVersionCode = code;
        return code;
    }
}
