package com.cuiyi.passwordnotebook;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Calendar;

/**
 * Password generation. v4.
 *
 * Two families:
 *  - random():    cryptographically random, the default and the one to use
 *  - periodic():  deterministic, derived from a core word plus a date. Kept for
 *                 people who want a password they can recompute from memory.
 *
 * Changes from v2.x:
 *  - periodic() used to feed the core word straight into SHA-256 with a fixed
 *    salt. It now runs through PBKDF2 with a per-site random salt, so knowing
 *    the algorithm no longer lets someone enumerate candidates quickly.
 *  - random() used to index the pool with nextInt(n). That is fine on the JVM,
 *    but the pool is not a power of two in general, so the same helper is used
 *    everywhere here through rejection sampling for clarity.
 *  - the old code could return a password shorter than requested when the
 *    caller asked for fewer characters than the number of enabled classes;
 *    that now raises instead of silently returning something unexpected.
 */
public final class PasswordGenerator {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String COMMON = "!@#$%^&*-_=+.";
    private static final String EXTENDED = "<>[]{}()/";

    /** Used only by the deterministic mode and only as KDF input. */
    private static final int PERIODIC_ITERATIONS = 200000;
    private static final int SITE_SALT_BYTES = 16;

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 128;

    private static final SecureRandom RNG = new SecureRandom();

    private PasswordGenerator() {
        throw new AssertionError("no instance");
    }

    /** ISO-ish year and week, matching what the old app displayed. */
    public static int[] currentYearWeek() {
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int week = cal.get(Calendar.WEEK_OF_YEAR);
        // Some locales report week 53 in early January; fold it back.
        if (week > 52 && cal.get(Calendar.MONTH) == Calendar.JANUARY) {
            year--;
            week = 52;
        }
        return new int[]{year, week};
    }

    /**
     * Cryptographically random password. Guarantees at least one character from
     * every enabled class, then fills the rest from the union of those classes.
     */
    public static String random(int length, boolean upper, boolean lower, boolean digits,
                                boolean specialCommon, boolean specialExtended) {
        StringBuilder pool = new StringBuilder();
        StringBuilder classes = new StringBuilder();
        if (upper) {
            pool.append(UPPER);
            classes.append(UPPER);
        }
        if (lower) {
            pool.append(LOWER);
            classes.append(LOWER);
        }
        if (digits) {
            pool.append(DIGITS);
            classes.append(DIGITS);
        }
        if (specialCommon) {
            pool.append(COMMON);
            classes.append(COMMON);
        }
        if (specialExtended) {
            pool.append(EXTENDED);
            classes.append(EXTENDED);
        }
        if (pool.length() == 0) {
            pool.append(UPPER).append(LOWER).append(DIGITS);
        }
        int enabled = countClasses(upper, lower, digits, specialCommon, specialExtended);
        if (enabled == 0) {
            enabled = 3;
        }
        if (length < enabled) {
            throw new IllegalArgumentException(
                    "length " + length + " is less than the " + enabled + " enabled character classes");
        }
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length out of range: " + length);
        }

        char[] out = new char[length];
        int at = 0;
        if (upper) {
            out[at++] = pick(UPPER);
        }
        if (lower) {
            out[at++] = pick(LOWER);
        }
        if (digits) {
            out[at++] = pick(DIGITS);
        }
        if (specialCommon) {
            out[at++] = pick(COMMON);
        }
        if (specialExtended) {
            out[at++] = pick(EXTENDED);
        }
        while (at < length) {
            out[at++] = pick(pool);
        }
        shuffle(out);
        return new String(out);
    }

    /**
     * Deterministic password for a given core word, site and period.
     *
     * @param coreWord  user secret, must not be empty
     * @param siteId    what this password is for, e.g. a domain
     * @param siteSalt  random per-site salt, stored in the clear
     * @param year      period year, or 0 with week 0 to drop the period
     * @param week      period week
     * @param length    output length
     */
    public static String periodic(String coreWord, String siteId, byte[] siteSalt,
                                  int year, int week, boolean weekDependent, int length)
            throws Exception {
        if (coreWord == null || coreWord.isEmpty()) {
            throw new IllegalArgumentException("core word must not be empty");
        }
        if (siteSalt == null || siteSalt.length < 8) {
            throw new IllegalArgumentException("site salt must be at least 8 bytes");
        }
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length out of range: " + length);
        }
        StringBuilder material = new StringBuilder();
        material.append(coreWord).append('\u0000').append(siteId == null ? "" : siteId);
        if (weekDependent) {
            material.append('\u0000').append(year).append("-W").append(week);
        }
        char[] secret = material.toString().toCharArray();
        byte[] key;
        try {
            key = KeyDerivation.derive(secret, siteSalt, PERIODIC_ITERATIONS);
        } finally {
            KeyDerivation.wipe(secret);
        }
        try {
            return encode(key, length);
        } finally {
            KeyDerivation.wipe(key);
        }
    }

    public static byte[] newSiteSalt() {
        byte[] salt = new byte[SITE_SALT_BYTES];
        RNG.nextBytes(salt);
        return salt;
    }

    /**
     * Map arbitrary bytes onto the printable alphabet without modulo bias.
     * A byte is only used when it falls below the largest multiple of the
     * alphabet size that fits in 256; the rest is rejected and re-drawn from
     * a hash of the seed and a counter.
     */
    static String encode(byte[] seed, int length) {
        final StringBuilder alphabet = new StringBuilder();
        alphabet.append(UPPER).append(LOWER).append(DIGITS).append(COMMON).append(EXTENDED);
        final int n = alphabet.length();
        final int limit = 256 - (256 % n);

        StringBuilder out = new StringBuilder(length);
        int counter = 0;
        int guard = 0;
        while (out.length() < length && guard++ < 100000) {
            byte[] block = hashBlock(seed, counter++);
            for (int i = 0; i < block.length && out.length() < length; i++) {
                int v = block[i] & 0xFF;
                if (v >= limit) {
                    continue;
                }
                out.append(alphabet.charAt(v % n));
            }
        }
        if (out.length() < length) {
            throw new IllegalStateException("could not fill password of length " + length);
        }
        return out.toString();
    }

    private static byte[] hashBlock(byte[] seed, int counter) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(seed);
            md.update((byte) (counter & 0xFF));
            md.update((byte) ((counter >>> 8) & 0xFF));
            md.update((byte) ((counter >>> 16) & 0xFF));
            md.update((byte) ((counter >>> 24) & 0xFF));
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Picks one character from pool using rejection sampling. */
    private static char pick(CharSequence pool) {
        int n = pool.length();
        int limit = 256 - (256 % n);
        while (true) {
            int v = RNG.nextInt(256);
            if (v < limit) {
                return pool.charAt(v % n);
            }
        }
    }

    /** Unbiased Fisher-Yates. */
    private static void shuffle(char[] arr) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = RNG.nextInt(i + 1);
            char t = arr[i];
            arr[i] = arr[j];
            arr[j] = t;
        }
    }

    private static int countClasses(boolean... flags) {
        int n = 0;
        for (boolean f : flags) {
            if (f) {
                n++;
            }
        }
        return n;
    }

    /** Rough strength meter, 0 to 5. */
    public static int strength(String password) {
        if (password == null || password.isEmpty()) {
            return 0;
        }
        int variety = 0;
        if (password.matches(".*[A-Z].*")) variety++;
        if (password.matches(".*[a-z].*")) variety++;
        if (password.matches(".*[0-9].*")) variety++;
        if (password.matches(".*[^A-Za-z0-9].*")) variety++;

        int score = password.length() >= 8 ? 2 : 1;
        if (password.length() >= 14) score++;
        if (variety >= 3) score++;
        if (variety == 4 && password.length() >= 10) score++;
        return Math.min(score, 5);
    }
}
