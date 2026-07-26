package com.cuiyi.passwordnotebook;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import android.util.Base64;

/**
 * AES-256-GCM 加密模块
 * 密钥派生: PBKDF2WithHmacSHA256, 10000次迭代
 * IV: 12字节随机, GCM Tag: 128位
 */
public class CryptoHelper {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String defaultSalt = "pwdnb@2026!salt#v1.5";

    public static SecretKey getKey() throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
            defaultSalt.toCharArray(),
            defaultSalt.getBytes("UTF-8"),
            10000, 256
        );
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public static String encrypt(String plaintext) {
        try {
            SecretKey key = getKey();
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            return plaintext;
        }
    }

    public static String decrypt(String b64) {
        try {
            byte[] combined = Base64.decode(b64, Base64.NO_WRAP);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            SecretKey key = getKey();
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            return new String(cipher.doFinal(ciphertext), "UTF-8");
        } catch (Exception e) {
            return b64;
        }
    }
}
