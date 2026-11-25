// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.log;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Logging for mod code: tag gets a "VT:" prefix, secrets are redacted, and messages per tag are
 * rate-limited so a hot hook cannot flood logcat.
 */
public final class VtLog {

    private static final String TAG_PREFIX = "VT:";
    private static final int MAX_PER_WINDOW = 20;
    private static final long WINDOW_MS = 60_000L;

    private static final Pattern ACCESS_TOKEN = Pattern.compile("(access_token=)[^&\\s\"']+");
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?\\d[\\d\\s()\\-]{8,}\\d)(?!\\d)");

    private static final ConcurrentHashMap<String, Counter> COUNTERS = new ConcurrentHashMap<>();

    private VtLog() {
    }

    public static void e(String tag, String msg, Throwable t) {
        log(Log.ERROR, tag, msg, t);
    }

    public static void e(String tag, String msg) {
        log(Log.ERROR, tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        log(Log.WARN, tag, msg, t);
    }

    public static void w(String tag, String msg) {
        log(Log.WARN, tag, msg, null);
    }

    public static void i(String tag, String msg) {
        log(Log.INFO, tag, msg, null);
    }

    private static void log(int priority, String tag, String msg, Throwable t) {
        String safeTag = TAG_PREFIX + (tag == null ? "?" : tag);
        if (!allow(safeTag)) {
            return;
        }
        String text = redact(msg);
        try {
            if (t == null) {
                Log.println(priority, safeTag, text);
            } else {
                Log.println(priority, safeTag, text + "\n" + Log.getStackTraceString(t));
            }
        } catch (Throwable ignored) {
            // logging must never break the caller
        }
    }

    static String redact(String msg) {
        if (msg == null) {
            return "null";
        }
        String result = ACCESS_TOKEN.matcher(msg).replaceAll("$1***");
        result = BEARER.matcher(result).replaceAll("$1***");
        result = PHONE.matcher(result).replaceAll("***");
        return result;
    }

    private static boolean allow(String tag) {
        long now = System.currentTimeMillis();
        Counter counter = COUNTERS.computeIfAbsent(tag, k -> new Counter());
        synchronized (counter) {
            if (now - counter.windowStart >= WINDOW_MS) {
                counter.windowStart = now;
                counter.count = 0;
                counter.warned = false;
            }
            if (counter.count < MAX_PER_WINDOW) {
                counter.count++;
                return true;
            }
            if (!counter.warned) {
                counter.warned = true;
                try {
                    Log.println(Log.WARN, tag, "VtLog: rate limit " + MAX_PER_WINDOW + "/min, further messages suppressed");
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    private static final class Counter {
        long windowStart;
        int count;
        boolean warned;
    }
}
