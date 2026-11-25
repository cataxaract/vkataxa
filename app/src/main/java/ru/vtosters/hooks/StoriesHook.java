package ru.vtosters.hooks;

import ru.vtosters.lite.core.guard.HookGuard;

import static ru.vtosters.hooks.other.Preferences.*;

public class StoriesHook {
    public static String ads() {
        return HookGuard.safe("Stories", "ads", () -> adsstories() ? "null" : "ads");
    }

    public static boolean showstories() {
        return stories();
    }

    public static boolean getStoriesRead() {
        return getBoolValue("read_s", false);
    }
}
