// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.net;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Rate limiting for mass VK API operations (SEC-03). It does not and cannot bypass VK limits;
 * it only reduces the risk: serializes requests, adds jitter, backs off on Flood control (error 9)
 * and stops honestly on captcha (error 14). No guarantees — the server decides.
 */
public final class RateLimiter {

    public static final int ERROR_FLOOD_CONTROL = 9;
    public static final int ERROR_CAPTCHA = 14;

    private static final int MAX_FLOOD_STEPS = 8;

    private final long minIntervalMs;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    private long nextAllowedAt;
    private int floodCount;

    public RateLimiter(long minIntervalMs) {
        this(minIntervalMs, 2_000L, 300_000L);
    }

    public RateLimiter(long minIntervalMs, long baseBackoffMs, long maxBackoffMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
        this.baseBackoffMs = Math.max(0L, baseBackoffMs);
        this.maxBackoffMs = Math.max(baseBackoffMs, maxBackoffMs);
    }

    /**
     * Blocks the calling (background) thread until the next request is allowed.
     * Must not be called on the main thread.
     */
    public void acquire() throws InterruptedException {
        long wait;
        synchronized (this) {
            wait = nextAllowedAt - System.currentTimeMillis();
            nextAllowedAt = Math.max(System.currentTimeMillis(), nextAllowedAt) + minIntervalMs + jitter();
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    public synchronized void onSuccess() {
        floodCount = 0;
    }

    /**
     * Applies exponential backoff after VK Flood control and returns the delay in ms.
     */
    public synchronized long onFloodControl() {
        floodCount = Math.min(floodCount + 1, MAX_FLOOD_STEPS);
        long delay = Math.min(maxBackoffMs, baseBackoffMs << (floodCount - 1));
        nextAllowedAt = System.currentTimeMillis() + delay;
        return delay;
    }

    public synchronized long currentBackoffMs() {
        if (floodCount == 0) return 0L;
        return Math.min(maxBackoffMs, baseBackoffMs << (floodCount - 1));
    }

    /**
     * Feeds a VK API error code through the limiter: error 9 backs off, error 14 stops the queue.
     *
     * @throws CaptchaRequiredException when VK asks for captcha (error 14); the caller must persist
     *                                  progress and stop, not retry.
     */
    public void onApiError(int code) {
        if (code == ERROR_CAPTCHA) {
            throw new CaptchaRequiredException();
        }
        if (code == ERROR_FLOOD_CONTROL) {
            onFloodControl();
        }
    }

    public static boolean isCaptcha(int code) {
        return code == ERROR_CAPTCHA;
    }

    public static boolean isFloodControl(int code) {
        return code == ERROR_FLOOD_CONTROL;
    }

    private long jitter() {
        if (minIntervalMs <= 0) return 0L;
        return ThreadLocalRandom.current().nextLong(minIntervalMs / 5 + 1);
    }

    public static final class CaptchaRequiredException extends RuntimeException {
        public CaptchaRequiredException() {
            super("VK captcha required (error 14): stop the queue and keep progress");
        }
    }
}
