package ru.vtosters.lite.utils;

import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the VK captcha image and detects the "picture is not supported"
 * placeholder that VK serves to the old VK Android client this build is based on.
 *
 * The import flow auto-cancels such unsolvable captchas and retries later instead
 * of leaving the user with an image that has no code.
 */
public final class CaptchaImageLoader {
    private static final String TAG = "VKataxaCaptcha";
    private static final String PLACEHOLDER_SHA256 =
            "d79d09f4dc1a26111edcc8dd3b0d31c8d19cebed1281f3addd2326ce40f95376";

    private static volatile boolean autoCancelPlaceholder;

    private static final String VK_MODERN_UA =
            "VKAndroidApp/8.30-20000 (Android 14; SDK 34; arm64-v8a; Google Pixel 7; ru; 1080x2400)";
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private CaptchaImageLoader() {
    }

    public static void setAutoCancelPlaceholder(boolean value) {
        autoCancelPlaceholder = value;
    }

    public static boolean shouldAutoCancel(byte[] bytes) {
        if (!autoCancelPlaceholder) {
            return false;
        }
        boolean placeholder = isPlaceholder(bytes);
        Log.d(TAG, "captcha placeholder=" + placeholder + ", autoCancel=" + placeholder);
        return placeholder;
    }

    private static boolean isPlaceholder(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return PLACEHOLDER_SHA256.equals(hex.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static byte[] load(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        List<String> urls = urls(url.trim());
        String[] agents = {VK_MODERN_UA, CHROME_UA};

        for (String candidate : urls) {
            for (String agent : agents) {
                byte[] bytes = fetch(candidate, agent);
                if (isImage(bytes)) {
                    logImage(agent, bytes);
                    return bytes;
                }
            }
        }

        try {
            byte[] bytes = com.vk.core.network.Network.a(url);
            if (isImage(bytes)) {
                logImage("default", bytes);
            }
            return bytes;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> urls(String url) {
        List<String> result = new ArrayList<>();
        result.add(url);

        String normalized = url;
        if (normalized.startsWith("//")) {
            normalized = "https:" + normalized;
        } else if (normalized.startsWith("/")) {
            normalized = "https://api.vk.com" + normalized;
        }
        if (!result.contains(normalized)) {
            result.add(normalized);
        }

        String query = null;
        int queryStart = normalized.indexOf('?');
        if (queryStart >= 0) {
            query = normalized.substring(queryStart);
        }
        if (query != null && normalized.contains("api.vk.com/captcha.php")) {
            String mobile = "https://m.vk.com/captcha.php" + query;
            if (!result.contains(mobile)) {
                result.add(mobile);
            }
            String web = "https://vk.com/captcha.php" + query;
            if (!result.contains(web)) {
                result.add(web);
            }
        }
        return result;
    }

    private static byte[] fetch(String url, String userAgent) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            String type = connection.getContentType();
            if (type != null && !type.startsWith("image/") && !type.startsWith("application/octet")) {
                Log.d(TAG, "skip " + url + " type=" + type + " agent=" + shortAgent(userAgent));
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                return read(input);
            }
        } catch (Throwable error) {
            Log.d(TAG, "failed " + url + " agent=" + shortAgent(userAgent) + ": " + error);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (output.size() > 2 * 1024 * 1024) {
                return null;
            }
        }
        return output.toByteArray();
    }

    private static boolean isImage(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return false;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;
        boolean png = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47;
        boolean jpeg = b0 == 0xFF && b1 == 0xD8;
        boolean gif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46;
        boolean webp = b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46;
        return png || jpeg || gif || webp;
    }

    private static void logImage(String userAgent, byte[] bytes) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            Log.d(TAG, "loaded captcha image: agent=" + shortAgent(userAgent)
                    + " size=" + bytes.length + " w=" + options.outWidth + " h=" + options.outHeight);
        } catch (Throwable ignored) {
        }
    }

    private static String shortAgent(String userAgent) {
        int space = userAgent.indexOf(' ');
        return space > 0 ? userAgent.substring(0, space) : userAgent;
    }
}
