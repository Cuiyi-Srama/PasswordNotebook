package com.cuiyi.passwordnotebook.gen;

import com.cuiyi.passwordnotebook.crypto.KeyDerivation;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Calendar;

/**
 * Produces passwords in the two modes the app offers.
 *
 * RANDOM   - fresh each time from the system CSPRNG. This is the mode to use.
 * PERIODIC - recomputable from a core word plus a period. Useful when the user
 *            wants a password they can reconstruct from memory with no app.
 *
 * The periodic mode used to hash the core word with SHA-256 and a fixed salt,
 * which is fast to brute force if the core word is weak. It now runs through
 * PBKDF2 with a per-site random salt so an attacker cannot cheaply test
 * candidates, and the period is part of the input.
 *
 * There is no week-dependency toggle: when a period is supplied it is always
 * mixed in. Callers that want a periodless password pass period 0/0.
 */
public final class PasswordFactory {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 128;

    public static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    public static final String DIGITS = "0123456789";
    public static final String SPECIAL_COMMON = "!@#$%^&*-_=+.";
    public static final String SPECIAL_EXTENDED = "<>[]{}()/";

    /**
     * Rounds for the periodic derivation.
     *
     * A core word is usually shorter and more predictable than a master
     * password, so this is where a weak input is most likely and the count must
     * stay meaningfully high. 350 000 keeps roughly a 3x margin over the vault
     * floor while staying responsive on a mid-range phone: at 600 000 the wait
     * was long enough to be felt even on a flagship, and the derivation runs on
     * a worker thread now, so the cost shows up as a spinner rather than a
     * freeze.
     */
    private static final int PERIODIC_ITERATIONS = 350_000;
    private static final int SITE_SALT_BYTES = 16;

    /** Domain separation tag for {@link #deterministicSiteSalt}. */
    private static final byte[] DETERMINISTIC_SALT_LABEL =
            "pwdnb-site-salt-v1".getBytes();

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordFactory() {
        throw new AssertionError("no instance");
    }

    // ---------------- random ----------------

    /**
     * Fresh password. Every enabled class contributes at least one character,
     * the rest come from the union, then the result is shuffled.
     *
     * @throws IllegalArgumentException when the length cannot hold one of each
     */
    public static String random(int length, boolean upper, boolean lower, boolean digits,
                                boolean common, boolean extended) {
        checkLength(length);
        String pool = buildAlphabet(upper, lower, digits, common, extended);
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("enable at least one character class");
        }
        // Count the enabled classes, not the characters they hold. Using the
        // total character count here made every length shorter than the full
        // alphabet (~84) fail, which disabled generation for any normal length.
        int classCount = 0;
        if (upper) classCount++;
        if (lower) classCount++;
        if (digits) classCount++;
        if (common) classCount++;
        if (extended) classCount++;
        if (length < classCount) {
            throw new IllegalArgumentException(
                    "length " + length + " cannot fit one character from each of the "
                            + classCount + " enabled classes");
        }

        char[] out = new char[length];
        int at = 0;
        if (upper) out[at++] = pick(UPPER);
        if (lower) out[at++] = pick(LOWER);
        if (digits) out[at++] = pick(DIGITS);
        if (common) out[at++] = pick(SPECIAL_COMMON);
        if (extended) out[at++] = pick(SPECIAL_EXTENDED);
        while (at < length) {
            out[at++] = pick(pool);
        }
        shuffle(out);
        return new String(out);
    }

    // ---------------- periodic ----------------

    /**
     * Recomputable password.
     *
     * @param coreWord user secret, must not be empty
     * @param siteId   what the password is for, e.g. a domain or app name
     * @param siteSalt random per-site salt, stored in the clear
     * @param year     period year, or 0 to omit the period
     * @param week     period week, or 0 to omit the period
     * @param length   output length
     */
    public static String periodic(String coreWord, String siteId, byte[] siteSalt,
                                  int year, int week, int length) throws Exception {
        return periodic(coreWord, siteId, siteSalt, year, week, length,
                true, true, true, true, true);
    }

    /**
     * Recomputable password using an explicit character set.
     *
     * The character set is folded into the derivation input rather than applied
     * afterwards. Two reasons: the enabled classes become part of what the
     * password is derived from, so a later change of the checkboxes cannot
     * silently turn an existing password into a different one while looking
     * "the same" to the user; and the mapping then needs no rejection loop over
     * a fixed alphabet, which keeps the output length exact for tiny alphabets
     * such as digits-only bank PINs.
     *
     * A digits-only password has at most 10^length possibilities no matter how
     * many PBKDF2 rounds are used: the rounds protect the core word, not the
     * output space. The interface warns about this where it matters.
     */
    public static String periodic(String coreWord, String siteId, byte[] siteSalt,
                                  int year, int week, int length,
                                  boolean upper, boolean lower, boolean digits,
                                  boolean common, boolean extended) throws Exception {
        if (coreWord == null || coreWord.trim().isEmpty()) {
            throw new IllegalArgumentException("core word must not be empty");
        }
        if (siteSalt == null || siteSalt.length < 8) {
            throw new IllegalArgumentException("site salt must be at least 8 bytes");
        }
        checkLength(length);

        String alphabet = buildAlphabet(upper, lower, digits, common, extended);
        if (alphabet.isEmpty()) {
            throw new IllegalArgumentException("enable at least one character class");
        }

        StringBuilder material = new StringBuilder();
        material.append(coreWord.trim()).append('\u0000')
                .append(siteId == null ? "" : siteId);
        // The class selection is part of the input, so the same core word and
        // usage label with a different alphabet yields an unrelated password.
        material.append('\u0000').append(charClassTag(upper, lower, digits, common, extended));
        if (year != 0 || week != 0) {
            material.append('\u0000').append(year).append("-W").append(week);
        }

        char[] secret = material.toString().toCharArray();
        byte[] derived;
        try {
            derived = KeyDerivation.deriveForPurpose(secret, siteSalt,
                    PERIODIC_ITERATIONS, "pwdnb-periodic");
        } finally {
            KeyDerivation.wipe(secret);
        }
        try {
            return mapToAlphabet(derived, length, alphabet);
        } finally {
            KeyDerivation.wipe(derived);
        }
    }

    /**
     * Stable one-letter tag for a class selection.
     *
     * Kept separate from the alphabet itself so that reordering or extending
     * one of the pools does not change every previously derived password.
     */
    private static String charClassTag(boolean upper, boolean lower, boolean digits,
                                       boolean common, boolean extended) {
        StringBuilder tag = new StringBuilder(5);
        tag.append(upper ? 'U' : '-');
        tag.append(lower ? 'L' : '-');
        tag.append(digits ? 'D' : '-');
        tag.append(common ? 'C' : '-');
        tag.append(extended ? 'E' : '-');
        return tag.toString();
    }

    public static byte[] newSiteSalt() {
        byte[] salt = new byte[SITE_SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * Salt derived from the core word and the usage label, with nothing stored.
     *
     * This is what makes a recomputable password live up to its name: the whole
     * derivation input is core word + usage + period, so the same three values
     * reproduce the password on any device, in any app, even with no backup at
     * all. A random salt cannot do that, because losing the device would lose
     * the salt and the password could never be derived again.
     *
     * The trade off is that two copies of this app with the same core word and
     * usage produce the same salt, so an attacker holding the core word guesses
     * can test candidates offline without needing anything from the device. That
     * is what the 350 000 round count is there to make expensive, and users who
     * would rather not accept it can pick the random salt instead.
     *
     * @return a salt of {@link #SITE_SALT_BYTES} bytes, or null if either input
     *         is empty, in which case the caller must fall back to something else
     */
    public static byte[] deterministicSiteSalt(String coreWord, String siteId) {
        if (coreWord == null || coreWord.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // A domain tag keeps this value distinct from any other hash the app
            // computes over the same inputs.
            digest.update(DETERMINISTIC_SALT_LABEL);
            digest.update((byte) 0);
            digest.update(coreWord.getBytes("UTF-8"));
            digest.update((byte) 0);
            digest.update((siteId == null ? "" : siteId).getBytes("UTF-8"));
            byte[] full = digest.digest();
            byte[] salt = new byte[SITE_SALT_BYTES];
            System.arraycopy(full, 0, salt, 0, SITE_SALT_BYTES);
            return salt;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Current year and week number, matching what the app displays. */
    public static int[] currentPeriod() {
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int week = calendar.get(Calendar.WEEK_OF_YEAR);
        // A few locales report week 53 in early January; fold it back to 52.
        if (week > 52 && calendar.get(Calendar.MONTH) == Calendar.JANUARY) {
            year--;
            week = 52;
        }
        return new int[]{year, week};
    }

    /**
     * Map arbitrary bytes onto the printable alphabet without modulo bias.
     *
     * A byte is usable only when it falls below the largest multiple of the
     * alphabet size that fits in 256. Rejected bytes are simply dropped and
     * more material is pulled from a counter-extended hash until the requested
     * length is filled.
     */
    static String mapToAlphabet(byte[] seed, int length) {
        return mapToAlphabet(seed, length,
                UPPER + LOWER + DIGITS + SPECIAL_COMMON + SPECIAL_EXTENDED);
    }

    /**
     * The characters offered by the currently enabled classes.
     *
     * Shared by both modes so the checkboxes mean the same thing everywhere.
     * Returns an empty string when nothing is enabled; callers turn that into a
     * user-visible error rather than dividing by zero.
     */
    public static String buildAlphabet(boolean upper, boolean lower, boolean digits,
                                       boolean common, boolean extended) {
        StringBuilder pool = new StringBuilder();
        if (upper) pool.append(UPPER);
        if (lower) pool.append(LOWER);
        if (digits) pool.append(DIGITS);
        if (common) pool.append(SPECIAL_COMMON);
        if (extended) pool.append(SPECIAL_EXTENDED);
        return pool.toString();
    }

    static String mapToAlphabet(byte[] seed, int length, String alphabet) {
        int size = alphabet.length();
        int limit = 256 - (256 % size);

        StringBuilder out = new StringBuilder(length);
        int counter = 0;
        while (out.length() < length && counter < 100_000) {
            byte[] block = hashBlock(seed, counter++);
            for (int i = 0; i < block.length && out.length() < length; i++) {
                int value = block[i] & 0xFF;
                if (value < limit) {
                    out.append(alphabet.charAt(value % size));
                }
            }
        }
        if (out.length() < length) {
            throw new IllegalStateException("could not fill a password of length " + length);
        }
        return out.toString();
    }

    private static byte[] hashBlock(byte[] seed, int counter) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed);
            digest.update((byte) (counter & 0xFF));
            digest.update((byte) ((counter >>> 8) & 0xFF));
            digest.update((byte) ((counter >>> 16) & 0xFF));
            digest.update((byte) ((counter >>> 24) & 0xFF));
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---------------- shared ----------------

    /** One character from pool, via rejection sampling. */
    private static char pick(CharSequence pool) {
        int size = pool.length();
        int limit = 256 - (256 % size);
        while (true) {
            int value = RANDOM.nextInt(256);
            if (value < limit) {
                return pool.charAt(value % size);
            }
        }
    }

    private static void shuffle(char[] chars) {
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char swap = chars[i];
            chars[i] = chars[j];
            chars[j] = swap;
        }
    }

    private static void checkLength(int length) {
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length out of range: " + length);
        }
    }

    /** Coarse strength meter, 0 to 5 stars. */
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
