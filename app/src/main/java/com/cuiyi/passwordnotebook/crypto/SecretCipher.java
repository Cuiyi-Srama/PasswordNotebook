package com.cuiyi.passwordnotebook.crypto;

import android.util.Base64;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a caller supplied key.
 *
 * Output layout is base64(iv || ciphertext || tag), twelve byte IV in front,
 * which is what every other component here expects.
 *
 * Two rules this class enforces:
 *   1. Never invent a key. The caller must pass one derived from the password.
 *   2. Never degrade on failure. Both directions throw.
 */
public final class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretCipher() {
        throw new AssertionError("no instance");
    }

    public static String encrypt(String plaintext, byte[] key) throws CryptoException {
        if (plaintext == null) {
            throw new CryptoException("plaintext is null");
        }
        requireKey(key);
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plaintext.getBytes(UTF8));
            byte[] out = new byte[IV_BYTES + body.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(body, 0, out, IV_BYTES, body.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("encryption failed", e);
        }
    }

    public static String decrypt(String encoded, byte[] key) throws CryptoException {
        if (encoded == null) {
            throw new CryptoException("ciphertext is null");
        }
        requireKey(key);
        byte[] all;
        try {
            all = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("ciphertext is not valid base64", e);
        }
        // IV plus a full GCM tag is the minimum that can ever be valid.
        if (all.length <= IV_BYTES + 16) {
            throw new CryptoException("ciphertext is too short");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            byte[] body = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, body, 0, body.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), UTF8);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("decryption failed: wrong password or tampered data", e);
        }
    }

    private static void requireKey(byte[] key) throws CryptoException {
        if (key == null || key.length != KeyDerivation.KEY_BYTES) {
            throw new CryptoException("key must be " + KeyDerivation.KEY_BYTES + " bytes");
        }
    }
}
