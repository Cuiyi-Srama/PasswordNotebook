package com.cuiyi.passwordnotebook;

import android.util.Base64;
import java.nio.charset.Charset;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypt-only support for vaults written by v2.x.
 *
 * The old builds used a hardcoded 32 byte AES key compiled into the dex, so
 * anyone who decompiled the APK could read every entry. That key is kept here
 * solely so existing users can import their own data once. Nothing in the new
 * code path ever encrypts with it.
 */
final class LegacyCrypto {

    /** The v2.x hardcoded key. Import-only. */
    private static final byte[] LEGACY_KEY = {
            90, 63, -116, 23, -30, 75, -99, 97,
            113, -86, 51, -34, 79, 44, -69, 25,
            -114, 85, 13, -57, 106, 31, -13, -120,
            66, -67, 126, 17, -103, 54, -38, -27
    };

    /** Entry separator used by v2.x exports. */
    static final char LEGACY_FIELD_SEP = '\u2561';
    /** Entry separator used by the unreleased 2.1 rewrite. */
    static final char ALT_FIELD_SEP = '\u2e41';

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private LegacyCrypto() {
        throw new AssertionError("no instance");
    }

    /** Returns null when the input cannot be decrypted. */
    static String decrypt(String encoded) {
        if (encoded == null || encoded.length() < 24) {
            return null;
        }
        try {
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            if (all.length <= IV_BYTES + 16) {
                return null;
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] body = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, body, 0, body.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(LEGACY_KEY, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the string looks like a legacy ciphertext rather than a label. */
    static boolean looksEncrypted(String s) {
        if (s == null || s.length() < 24) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
