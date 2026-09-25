package com.cuiyi.passwordnotebook;

import android.util.Base64;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM \u52a0\u5bc6\u6a21\u5757 v3.0
 *
 * \u5173\u952e\u4fee\u6539\uff1afail-closed\u3002
 * \u65e7\u7248\u672c\u5728\u5f02\u5e38\u65f6\u8fd4\u56de\u660e\u6587/\u5bc6\u6587\uff0c\u9020\u6210\u9759\u9ed8\u964d\u7ea7\u3002
 * \u65b0\u7248\u672c\u629b CryptoException\uff0c\u4e0a\u5c42\u5fc5\u987b\u5904\u7406\u3002
 */
public class CryptoHelper {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** \u65e7\u7248\u786c\u7f16\u7801\u5e38\u91cf\uff0c\u4ec5\u7528\u4e8e\u65e7\u6570\u636e\u8fc1\u79fb\uff0c\u4e0d\u5f97\u7528\u4e8e\u65b0\u6570\u636e */
    @Deprecated
    static final String LEGACY_SALT = "pwdnb@2026!salt#v1.5";
    @Deprecated
    static final int LEGACY_ITERATIONS = 10000;

    /** \u52a0\u5bc6/\u89e3\u5bc6\u5931\u8d25\u5f02\u5e38\u3002\u4e0d\u518d\u541e\u6389\uff0c\u4e0d\u518d\u964d\u7ea7\u3002 */
    public static class CryptoException extends Exception {
        public CryptoException(String msg) {
            super(msg);
        }

        public CryptoException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * \u65e7\u7248\u5bc6\u94a5\uff08\u786c\u7f16\u7801\u5e38\u91cf\u6d3e\u751f\uff09\u3002
     * \u4ec5\u4f9b\u8fc1\u79fb\u5668\u89e3\u5bc6\u65e7\u6570\u636e\u4f7f\u7528\u3002
     */
    @Deprecated
    public static SecretKey getLegacyKey() throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(
                LEGACY_SALT.toCharArray(),
                LEGACY_SALT.getBytes(Charset.forName("UTF-8")),
                LEGACY_ITERATIONS,
                256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /** \u7528 raw 256-bit \u5bc6\u94a5\u52a0\u5bc6\uff08v3 \u4e3b\u8def\u5f84\uff09 */
    public static String encrypt(String plaintext, byte[] rawKey) throws CryptoException {
        if (plaintext == null) {
            throw new CryptoException("plaintext is null");
        }
        if (rawKey == null || rawKey.length != 32) {
            throw new CryptoException("rawKey must be 256-bit");
        }
        try {
            SecretKey key = new SecretKeySpec(rawKey, "AES");
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(Charset.forName("UTF-8")));
            byte[] combined = new byte[GCM_IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ct, 0, combined, GCM_IV_LENGTH, ct.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("\u52a0\u5bc6\u5931\u8d25\uff0c\u62d2\u7edd\u964d\u7ea7\u4e3a\u660e\u6587", e);
        }
    }

    /** \u7528 raw 256-bit \u5bc6\u94a5\u89e3\u5bc6\uff08v3 \u4e3b\u8def\u5f84\uff09 */
    public static String decrypt(String b64, byte[] rawKey) throws CryptoException {
        if (b64 == null || rawKey == null || rawKey.length != 32) {
            throw new CryptoException("invalid arguments");
        }
        try {
            byte[] combined = Base64.decode(b64, Base64.NO_WRAP);
            if (combined.length <= GCM_IV_LENGTH) {
                throw new CryptoException("ciphertext too short");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ct = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ct, 0, ct.length);
            SecretKey key = new SecretKeySpec(rawKey, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ct), Charset.forName("UTF-8"));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("\u89e3\u5bc6\u5931\u8d25\uff1a\u6570\u636e\u53ef\u80fd\u88ab\u7be1\u6539\u6216\u5bc6\u94a5\u9519\u8bef", e);
        }
    }

    /** \u65e7\u63a5\u53e3\uff08\u4fdd\u7559\u4ee5\u4fbf\u9010\u6b65\u8fc1\u79fb\uff09\uff0c\u5185\u90e8\u8d70 legacy key\u3002\u5f02\u5e38\u65f6\u629b\u51fa\u800c\u975e\u8fd4\u56de\u660e\u6587\u3002 */
    @Deprecated
    public static String encrypt(String plaintext) throws CryptoException {
        try {
            return encryptWithKey(plaintext, getLegacyKey());
        } catch (GeneralSecurityException e) {
            throw new CryptoException("legacy encrypt failed", e);
        }
    }

    @Deprecated
    public static String decrypt(String b64) throws CryptoException {
        try {
            return decryptWithKey(b64, getLegacyKey());
        } catch (GeneralSecurityException e) {
            throw new CryptoException("legacy decrypt failed", e);
        }
    }

    public static String encryptWithKey(String plaintext, SecretKey key) throws CryptoException {
        if (key == null) {
            throw new CryptoException("key is null");
        }
        return encrypt(plaintext, key.getEncoded());
    }

    public static String decryptWithKey(String b64, SecretKey key) throws CryptoException {
        if (key == null) {
            throw new CryptoException("key is null");
        }
        return decrypt(b64, key.getEncoded());
    }
}
