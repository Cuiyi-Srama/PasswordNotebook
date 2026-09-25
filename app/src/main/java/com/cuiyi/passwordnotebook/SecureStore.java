package com.cuiyi.passwordnotebook;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * \u5b89\u5168\u5b58\u50a8\u5c42 v3.0
 *
 * \u7528 Android Keystore \u5c06\u654f\u611f\u914d\u7f6e\uff08\u6838\u5fc3\u8bcd/\u81ea\u5b9a\u4e49\u76d0\uff09\u52a0\u5bc6\u540e\u518d\u5199\u5165 SharedPreferences\u3002
 * Keystore \u5bc6\u94a5\u4e0d\u53ef\u5bfc\u51fa\uff0c\u4ec5\u672c\u5e94\u7528\u53ef\u7528\u3002
 *
 * \u6ce8\u610f\uff1aKeystore \u4ec5\u63d0\u4f9b\u201c\u672c\u673a\u9694\u79bb\u201d\uff0c\u4e0d\u80fd\u63d0\u4f9b\u201c\u7528\u6237\u6301\u6709\u7684\u79d8\u5bc6\u201d\u3002
 * \u771f\u6b63\u7684\u5bc6\u7801\u7ba1\u7406\u5668\u5b89\u5168\u6a21\u578b\u4ecd\u9700\u4e3b\u5bc6\u7801\u53c2\u4e0e KDF\u3002
 */
public final class SecureStore {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "pwdnb_v3_wrap";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    /** \u8ba1\u7b97\u673a\u7aef\u8bfb\u53d6\u7528\uff1a\u5bc6\u94a5\u4e0d\u53ef\u5bfc\u51fa\uff0c\u4e5f\u4e0d\u4f1a\u88ab SharedPreferences \u8bfb\u8005\u76f4\u63a5\u83b7\u53d6 */
    private SecureStore() {
        throw new AssertionError("no instance");
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setInvalidatedByBiometricEnrollment(false);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    /** \u52a0\u5bc6\u5b57\u7b26\u4e32\uff0c\u8fd4\u56de base64(iv||ct)\u3002\u5931\u8d25\u629b\u5f02\u5e38\uff08fail-closed\uff09 */
    public static String encrypt(String plain) throws GeneralSecurityException, IOException {
        if (plain == null) {
            throw new IllegalArgumentException("plain is null");
        }
        SecretKey key = getOrCreateKey();
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(Charset.forName("UTF-8")));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    /** \u89e3\u5bc6\uff1b\u5bc6\u6587\u88ab\u7be1\u6539\u6216\u65e0\u6cd5\u89e3\u5f00\u65f6\u629b\u5f02\u5e38 */
    public static String decrypt(String b64) throws GeneralSecurityException, IOException {
        if (b64 == null || b64.isEmpty()) {
            throw new IllegalArgumentException("b64 is empty");
        }
        byte[] all = Base64.decode(b64, Base64.NO_WRAP);
        if (all.length <= IV_LEN) {
            throw new GeneralSecurityException("ciphertext too short");
        }
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[all.length - IV_LEN];
        System.arraycopy(all, 0, iv, 0, IV_LEN);
        System.arraycopy(all, IV_LEN, ct, 0, ct.length);
        SecretKey key = getOrCreateKey();
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(c.doFinal(ct), Charset.forName("UTF-8"));
    }

    /** \u8bfb\u53d6\u5e76\u89e3\u5bc6\u7684\u5b57\u7b26\u4e32\uff1b\u4e0d\u5b58\u5728\u6216\u89e3\u5f00\u5931\u8d25\u8fd4\u56de null */
    public static String getString(SharedPreferences prefs, String key) {
        String enc = prefs.getString(key, null);
        if (enc == null || enc.isEmpty()) {
            return null;
        }
        try {
            return decrypt(enc);
        } catch (Exception e) {
            return null;
        }
    }

    /** \u52a0\u5bc6\u540e\u5199\u5165\uff1b\u4e0d\u5199\u660e\u6587\u3002\u5931\u8d25\u8fd4\u56de false */
    public static boolean putString(SharedPreferences prefs, String key, String value) {
        if (value == null) {
            prefs.edit().remove(key).commit();
            return true;
        }
        try {
            String enc = encrypt(value);
            return prefs.edit().putString(key, enc).commit();
        } catch (Exception e) {
            return false;
        }
    }
}
