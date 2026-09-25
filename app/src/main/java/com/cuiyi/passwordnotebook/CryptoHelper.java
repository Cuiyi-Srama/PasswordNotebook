package com.cuiyi.passwordnotebook;

import android.util.Base64;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM. v4.
 *
 * Two things changed from the previous release:
 * 1. The key is supplied by the caller (derived from the user master password),
 *    not returned from a hardcoded byte array.
 * 2. Failures raise instead of returning the input. The old code returned the
 *    plaintext when encryption failed and returned the raw ciphertext when
 *    decryption failed, which could silently write secrets in the clear.
 */
public final class CryptoHelper {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private CryptoHelper() {
        throw new AssertionError("no instance");
    }

    /** Encrypt UTF-8 text. Output is base64 of (iv || ciphertext || tag). */
    public static String encrypt(String plaintext, byte[] key) throws CryptoException {
        if (plaintext == null) {
            throw new CryptoException("plaintext is null");
        }
        requireKey(key);
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF8));
            byte[] out = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("encryption failed", e);
        }
    }

    /** Decrypt output produced by encrypt(). Throws on tamper or wrong key. */
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
