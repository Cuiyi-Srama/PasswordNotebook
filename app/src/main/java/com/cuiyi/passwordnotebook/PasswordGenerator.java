package com.cuiyi.passwordnotebook;

import android.util.Base64;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * \u5bc6\u7801\u751f\u6210\u5f15\u64ce v3.0
 *
 * v3 \u4fee\u590d\uff1a
 * 1. \u6838\u5fc3\u8bcd\u4e0d\u518d\u76f4\u63a5 SHA-256\uff0c\u800c\u662f\u4f5c\u4e3a KDF \u5bc6\u5bc6\u53c2\u4e0e PBKDF2
 * 2. \u6bcf\u7ad9\u70b9\u72ec\u7acb\u968f\u673a\u76d0\uff0c\u907f\u514d\u8de8\u7ad9\u70b9\u76f8\u540c\u5bc6\u7801
 * 3. generateRandom \u4f7f\u7528\u62d2\u7edd\u91c7\u6837\u907f\u514d\u6a21\u504f\u5dee
 * 4. \u65e7\u63a5\u53e3\u4fdd\u7559\u4f46\u6807\u8bb0\u4e3a\u53ef\u9009\u7684\u517c\u5bb9\u6a21\u5f0f
 *
 * \u5b89\u5168\u8fb9\u754c\uff1a\u6838\u5fc3\u8bcd\u672c\u8eab\u5c31\u662f\u552f\u4e00\u79d8\u5bc6\uff0c\u5fc5\u987b\u6709\u8db3\u591f\u71b5\uff08\u5efa\u8bae 6-7 \u4e2a Diceware \u8bcd\uff09。
 * \u8be5\u6a21\u5f0f\u662f\u786e\u5b9a\u6027\u7684\uff0c\u65e0\u6cd5\u9632\u5fa1\u6838\u5fc3\u8bcd\u6cc4\u9732\u3002\u4ec5\u5efa\u8bae\u7528\u4e8e\u4f4e\u4ef7\u503c\u8d26\u53f7\u3002
 */
public class PasswordGenerator {

    /** \u7ad9\u70b9\u76d0\u5b57\u8282\u6570 */
    private static final int SITE_SALT_BYTES = 16;
    /** \u6d3e\u751f\u5bc6\u7801\u7528\u7684\u8fed\u4ee3\u6b21\u6570\uff08\u53ef\u8c03\uff0c\u7528\u4e8e\u51dd\u805a\u9884\u7b97\uff09 */
    private static final int DERIVE_ITERATIONS = 200000;

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String COMMON = "!@#$%^&*-_=+.";
    private static final String EXT = "<>[]{}()/\\";

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * v3 \u6d4b\u5b9a\u6027\u5bc6\u7801\uff1a\u6838\u5fc3\u8bcd + \u7ad9\u70b9\u6807\u8bc6 + \u7ad9\u70b9\u968f\u673a\u76d0 \u2192 PBKDF2 \u2192 \u5b57\u7b26\u96c6\u6620\u5c04\u3002
     *
     * @param coreWord  \u7528\u6237\u6838\u5fc3\u8bcd\uff08\u5b86\u91cf\u5b9c \u2265 80 bit\uff09
     * @param siteId    \u7ad9\u70b9\u6807\u8bc6\uff08\u5982\u57df\u540d\uff09
     * @param siteSalt  \u6bcf\u7ad9\u70b9\u72ec\u7acb\u968f\u673a\u76d0\uff0c\u53ef\u660e\u6587\u5b58\u50a8
     * @param year      \u5e74\u4efd\uff08\u53ef\u4f20 0 \u8868\u793a\u4e0d\u4f9d\u8d56\u65f6\u95f4\uff09
     * @param week      \u5468\u6570（\u53ef\u4f20 0\uff09
     * @param length    \u8f93\u51fa\u957f\u5ea6
     */
    public static String generateDerived(String coreWord, String siteId, byte[] siteSalt,
                                         int year, int week, int length)
            throws GeneralSecurityException {
        if (coreWord == null || coreWord.isEmpty()) {
            throw new IllegalArgumentException("coreWord must not be empty");
        }
        if (siteId == null) {
            siteId = "";
        }
        if (siteSalt == null || siteSalt.length < 8) {
            throw new IllegalArgumentException("siteSalt must be at least 8 bytes");
        }
        if (length < 8 || length > 128) {
            throw new IllegalArgumentException("length out of range: " + length);
        }
        String material = coreWord + "\u0000" + siteId + "\u0000" + year + "-W" + week;
        char[] mp = material.toCharArray();
        byte[] key;
        try {
            key = KeyDerivation.deriveKey(mp, siteSalt, DERIVE_ITERATIONS);
        } finally {
            KeyDerivation.wipe(mp);
        }
        try {
            return encodeUnbiased(key, length);
        } finally {
            KeyDerivation.wipe(key);
        }
    }

    /**
     * \u7528\u62d2\u7edd\u91c7\u6837\u5c06\u5bc6\u94a5\u5b57\u8282\u65e0\u504f\u5730\u6620\u5c04\u5230\u5b57\u7b26\u96c6\u3002
     * \u907f\u514d\u76f4\u63a5 `% pool.length` \u5bfc\u81f4\u7684\u6a21\u504f\u5dee\u3002
     */
    public static String encodeUnbiased(byte[] seed, int length) {
        StringBuilder pool = new StringBuilder();
        pool.append(UPPER).append(LOWER).append(DIGITS).append(COMMON).append(EXT);
        int n = pool.length();
        // \u62d2\u7edd\u9608\u503c\uff1a\u4e22\u5f03 >= limit \u7684\u53d6\u503c\uff0c\u4fdd\u8bc1\u6bcf\u4e2a\u7b26\u53f7\u7b49\u6982\u7387
        int limit = 256 - (256 % n);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        int guard = 0;
        final int maxGuard = 100000;
        while (out.length() < length && guard++ < maxGuard) {
            byte[] block = expand(seed, pos++);
            for (byte b : block) {
                int v = b & 0xFF;
                if (v >= limit) {
                    continue;
                }
                out.append(pool.charAt(v % n));
                if (out.length() >= length) {
                    break;
                }
            }
        }
        if (out.length() < length) {
            throw new IllegalStateException("encode failed to fill length");
        }
        return out.toString();
    }

    /** \u5c06\u79cd\u5b50\u5c55\u5f00\u4e3a 32 \u5b57\u8282\u5757\uff08SHA-256(seed || counter)\uff09 */
    private static byte[] expand(byte[] seed, int counter) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(seed);
            md.update((byte) (counter & 0xFF));
            md.update((byte) ((counter >>> 8) & 0xFF));
            md.update((byte) ((counter >>> 16) & 0xFF));
            md.update((byte) ((counter >>> 24) & 0xFF));
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** \u751f\u6210\u7ad9\u70b9\u968f\u673a\u76d0 */
    public static byte[] newSiteSalt() {
        byte[] s = new byte[SITE_SALT_BYTES];
        RNG.nextBytes(s);
        return s;
    }

    /**
     * \u968f\u673a\u5bc6\u7801\u751f\u6210\uff08v3\uff1a\u62d2\u7edd\u91c7\u6837\uff0c\u4fdd\u8bc1\u6bcf\u7c7b\u81f3\u5c11\u51fa\u73b0\u4e00\u6b21\uff09
     */
    public static String generateRandom(int length, boolean upper, boolean lower, boolean digits,
                                        boolean specialCommon, boolean specialExt) {
        StringBuilder pool = new StringBuilder();
        java.util.List<String> classes = new java.util.ArrayList<String>();
        if (upper) { pool.append(UPPER); classes.add(UPPER); }
        if (lower) { pool.append(LOWER); classes.add(LOWER); }
        if (digits) { pool.append(DIGITS); classes.add(DIGITS); }
        if (specialCommon) { pool.append(COMMON); classes.add(COMMON); }
        if (specialExt) { pool.append(EXT); classes.add(EXT); }
        if (pool.length() == 0) {
            return "";
        }
        if (length < classes.size()) {
            throw new IllegalArgumentException(
                    "length " + length + " < enabled classes " + classes.size());
        }
        char[] out = new char[length];
        int i = 0;
        for (String cls : classes) {
            out[i++] = cls.charAt(RNG.nextInt(cls.length()));
        }
        while (i < length) {
            out[i++] = randomCharUnbiased(pool);
        }
        // Fisher-Yates \u65e0\u504f\u6d17\u724c
        for (int k = out.length - 1; k > 0; k--) {
            int j = RNG.nextInt(k + 1);
            char t = out[k];
            out[k] = out[j];
            out[j] = t;
        }
        return new String(out);
    }

    /** \u4ece\u5b57\u7b26\u96c6\u4e2d\u65e0\u504f\u62bd\u4e00\u4e2a\u5b57\u7b26 */
    private static char randomCharUnbiased(CharSequence pool) {
        int n = pool.length();
        int limit = 256 - (256 % n);
        while (true) {
            int v = RNG.nextInt(256);
            if (v < limit) {
                return pool.charAt(v % n);
            }
        }
    }

    /** \u5bc6\u7801\u5f3a\u5ea6\u8bc4\u4f30 (0-5\u661f) */
    public static int evaluateStrength(String pwd) {
        if (pwd == null || pwd.isEmpty()) {
            return 0;
        }
        int score = 0;
        if (pwd.length() >= 8) score++;
        if (pwd.length() >= 12) score++;
        if (pwd.matches(".*[a-z].*")) score++;
        if (pwd.matches(".*[A-Z].*")) score++;
        if (pwd.matches(".*\\d.*")) score++;
        if (pwd.matches(".*[!@#$%^&*\\-_.=+<>\\[\\]{}()/\\\\].*")) score++;
        return Math.min(score, 5);
    }
}
