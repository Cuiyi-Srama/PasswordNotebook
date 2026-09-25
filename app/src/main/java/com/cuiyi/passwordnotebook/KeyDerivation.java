package com.cuiyi.passwordnotebook;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Key derivation for the vault. v4.
 *
 * Design: the master password is the only secret.
 * - PBKDF2-HMAC-SHA256
 * - per-vault random 16 byte salt (stored in the clear, that is fine)
 * - iteration count calibrated on the device, and persisted in the vault header
 * - derived bytes are wiped after use
 *
 * The previous release shipped a hardcoded AES key inside the dex, which meant
 * that decompiling the APK was enough to decrypt every vault. Nothing here
 * relies on a constant.
 */
public final class KeyDerivation {

    public static final int SALT_BYTES = 16;
    public static final int KEY_BYTES = 32;

    /** Lower bound we refuse to go below. OWASP suggests 600000 for SHA256. */
    public static final int ITERATIONS_FLOOR = 210000;
    /** Default when calibration is not run. */
    public static final int ITERATIONS_DEFAULT = 600000;
    /** Upper bound so a slow device does not lock the user out for seconds. */
    public static final int ITERATIONS_CEILING = 2000000;

    private KeyDerivation() {
        throw new AssertionError("no instance");
    }

    public static byte[] derive(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (salt == null || salt.length < 8) {
            throw new IllegalArgumentException("salt must be at least 8 bytes");
        }
        if (iterations < ITERATIONS_FLOOR) {
            throw new IllegalArgumentException("iteration count too low: " + iterations);
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Pick an iteration count that keeps derivation near targetMillis on this
     * device. Result is clamped to [ITERATIONS_FLOOR, ITERATIONS_CEILING].
     */
    public static int calibrate(char[] password, byte[] salt, long targetMillis) {
        int iterations = ITERATIONS_FLOOR;
        try {
            while (iterations < ITERATIONS_CEILING) {
                long start = System.nanoTime();
                derive(password, salt, iterations);
                long elapsed = (System.nanoTime() - start) / 1000000L;
                if (elapsed >= targetMillis) {
                    break;
                }
                iterations = (int) Math.min((long) iterations * 2L, ITERATIONS_CEILING);
            }
        } catch (GeneralSecurityException e) {
            return ITERATIONS_DEFAULT;
        }
        return Math.max(ITERATIONS_FLOOR, Math.min(iterations, ITERATIONS_CEILING));
    }

    /** Overwrite sensitive bytes. Best effort on the JVM, still worth doing. */
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
