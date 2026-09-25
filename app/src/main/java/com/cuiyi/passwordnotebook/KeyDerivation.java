package com.cuiyi.passwordnotebook;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * \u5bc6\u94a5\u6d3e\u751f\u6a21\u5757 v3.0
 */
public final class KeyDerivation {

    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;
    public static final int ITERATIONS_DEFAULT = 600000;
    public static final int ITERATIONS_MIN = 210000;

    private KeyDerivation() {
        throw new AssertionError("no instance");
    }

    public static byte[] deriveKey(char[] masterPassword, byte[] salt, int iterations)
            throws GeneralSecurityException {
        if (masterPassword == null || masterPassword.length == 0) {
            throw new IllegalArgumentException("master password must not be empty");
        }
        if (salt == null || salt.length < 8) {
            throw new IllegalArgumentException("salt must be at least 8 bytes");
        }
        if (iterations < ITERATIONS_MIN) {
            throw new IllegalArgumentException("iterations too low: " + iterations);
        }
        PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static int calibrate(char[] password, byte[] salt, long targetMillis) {
        int iters = ITERATIONS_MIN;
        final int maxIters = 2000000;
        try {
            while (iters < maxIters) {
                long t0 = System.nanoTime();
                deriveKey(password, salt, iters);
                long elapsed = (System.nanoTime() - t0) / 1000000L;
                if (elapsed >= targetMillis) {
                    break;
                }
                iters = (int) Math.min((long) iters * 2L, maxIters);
            }
        } catch (GeneralSecurityException e) {
            return ITERATIONS_DEFAULT;
        }
        return Math.max(ITERATIONS_MIN, Math.min(iters, maxIters));
    }

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
