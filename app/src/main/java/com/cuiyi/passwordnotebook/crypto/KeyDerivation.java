package com.cuiyi.passwordnotebook.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Turns the user master password into the vault key.
 *
 * The whole point of this class is that the key depends on something only the
 * user knows. The previous release derived the key from a constant compiled
 * into the APK, which meant that a decompiled build was enough to read every
 * stored entry. Nothing here may fall back to a fixed value.
 */
public final class KeyDerivation {

    public static final int SALT_BYTES = 16;
    public static final int KEY_BYTES = 32;

    /**
     * Refuse anything below this. The OWASP guidance for PBKDF2-HMAC-SHA256 is
     * 600000; devices that cannot reach that within a reasonable unlock time
     * are clamped here rather than silently weakened further.
     */
    public static final int ITERATIONS_FLOOR = 210_000;
    public static final int ITERATIONS_DEFAULT = 600_000;
    public static final int ITERATIONS_CEILING = 2_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyDerivation() {
        throw new AssertionError("no instance");
    }

    /**
     * Derive a raw key.
     *
     * @param password   master password, never persisted
     * @param salt       per-vault random salt
     * @param iterations PBKDF2 rounds, must be at or above the floor
     */
    public static byte[] derive(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("master password must not be empty");
        }
        if (salt == null || salt.length < 8) {
            throw new IllegalArgumentException("salt must be at least 8 bytes");
        }
        if (iterations < ITERATIONS_FLOOR) {
            throw new IllegalArgumentException("iteration count below floor: " + iterations);
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * Measure this device and pick an iteration count near the target time.
     * Doubles from the floor until derivation costs roughly targetMillis.
     *
     * @param targetMillis desired cost per unlock, typically 250 to 400
     * @return rounds to persist alongside the salt
     */
    public static int calibrate(char[] password, byte[] salt, long targetMillis) {
        int iterations = ITERATIONS_FLOOR;
        try {
            while (iterations < ITERATIONS_CEILING) {
                long start = System.nanoTime();
                derive(password, salt, iterations);
                long spent = (System.nanoTime() - start) / 1_000_000L;
                if (spent >= targetMillis) {
                    break;
                }
                iterations = (int) Math.min((long) iterations * 2L, ITERATIONS_CEILING);
            }
        } catch (GeneralSecurityException e) {
            return ITERATIONS_DEFAULT;
        }
        return Math.max(ITERATIONS_FLOOR, Math.min(iterations, ITERATIONS_CEILING));
    }

    /** Best effort zeroing of key material. */
    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    public static void wipe(char[] data) {
        if (data != null) {
            Arrays.fill(data, '\0');
        }
    }
}
