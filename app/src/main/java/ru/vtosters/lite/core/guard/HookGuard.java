// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.guard;

import ru.vtosters.lite.core.log.VtLog;

import java.util.function.Supplier;

/**
 * Boundary between patched VK code and mod code: any Throwable is logged and swallowed,
 * the caller gets a safe fallback instead of a crash.
 */
public final class HookGuard {

    private HookGuard() {
    }

    public static <T> T safe(String tag, T fallback, Supplier<T> body) {
        try {
            return body.get();
        } catch (Throwable t) {
            VtLog.e(tag, "hook failed", t);
            return fallback;
        }
    }

    public static void safe(String tag, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            VtLog.e(tag, "hook failed", t);
        }
    }
}
