// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import ru.vtosters.lite.core.log.VtLog;

/**
 * AES/GCM encryption backed by AndroidKeyStore for secrets stored in SharedPreferences (SEC-01).
 * Values are stored as {@code v1:<base64 iv>:<base64 ciphertext>}; plain legacy values are
 * returned as-is so callers can migrate them. AES keys in AndroidKeyStore require API 23+;
 * on older devices the value is stored as-is.
 */
public final class KeystorePrefs {

    private static final String TAG = "SecurePrefs";
    private static final String ALIAS = "vtosters_secure_prefs_v1";
    private static final String PREFIX = "v1:";

    private KeystorePrefs() {
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return plain;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":"
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        } catch (Throwable t) {
            VtLog.e(TAG, "encrypt failed", t);
            return plain;
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            VtLog.e(TAG, "decrypt failed", t);
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        synchronized (KeystorePrefs.class) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) {
                return (SecretKey) keyStore.getKey(ALIAS, null);
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        }
    }
}
