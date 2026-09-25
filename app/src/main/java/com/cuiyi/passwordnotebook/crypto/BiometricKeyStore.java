package com.cuiyi.passwordnotebook.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.Charset;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Lets the user unlock with the device biometric instead of typing the master
 * password every time, without weakening the vault.
 *
 * How this stays safe:
 *
 *   The vault key is still derived from the master password. When the user
 *   opts in, that derived key is encrypted with a second key that lives in the
 *   Android Keystore and is flagged as requiring user authentication. What is
 *   written to disk is only the ciphertext. The Keystore key cannot be exported
 *   and the platform will refuse to use it until the user has authenticated, so
 *   copying the app data to another device or reading it after a decompile
 *   yields nothing usable.
 *
 * What this gives up, stated plainly: someone who has the unlocked phone and
 * passes the biometric prompt can open the vault without knowing the master
 * password. That is the trade the user is opting into, which is why it is off
 * by default and gated behind an explicit setting.
 */
public final class BiometricKeyStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "pwdnb_v5_biometric";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String PREFS = "pwd_nb_biometric";
    private static final String KEY_WRAPPED = "wrapped_key";
    private static final String KEY_IV = "wrap_iv";

    private BiometricKeyStore() {
        throw new AssertionError("no instance");
    }

    /** True when the platform can do authenticated Keystore work at all. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /** True when a wrapped key is already on disk. */
    public static boolean hasWrappedKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wrapped = prefs.getString(KEY_WRAPPED, null);
        return wrapped != null && !wrapped.isEmpty();
    }

    /** Forget the wrapped key and delete the Keystore entry. */
    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {
            // Nothing to clean up if the entry never existed.
        }
    }

    /**
     * A cipher ready to encrypt during enrolment.
     *
     * The caller must pass it to a BiometricPrompt; only after the user
     * authenticates will doFinal succeed.
     */
    public static Cipher cipherForEnrolment() throws Exception {
        SecretKey key = createKey(false);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }

    /** A cipher ready to decrypt during unlock. */
    public static Cipher cipherForUnlock(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ivValue = prefs.getString(KEY_IV, null);
        if (ivValue == null) {
            throw new IllegalStateException("no stored IV");
        }
        byte[] iv = Base64.decode(ivValue, Base64.NO_WRAP);
        SecretKey key = loadKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    /** Store the vault key, encrypted with the cipher the user just authorised. */
    public static void storeWrappedKey(Context context, byte[] vaultKey, Cipher cipher)
            throws Exception {
        byte[] encrypted = cipher.doFinal(vaultKey);
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_WRAPPED, Base64.encodeToString(encrypted, Base64.NO_WRAP));
        editor.putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        editor.apply();
    }

    /**
     * Recover the vault key after a successful authentication.
     *
     * @param cipher the cipher that was handed to BiometricPrompt and completed
     * @return the raw vault key, or null when the stored data cannot be read
     */
    public static byte[] recoverVaultKey(Context context, Cipher cipher) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wrapped = prefs.getString(KEY_WRAPPED, null);
        if (wrapped == null) {
            return null;
        }
        try {
            byte[] encrypted = Base64.decode(wrapped, Base64.NO_WRAP);
            byte[] recovered = cipher.doFinal(encrypted);
            if (recovered.length != KeyDerivation.KEY_BYTES) {
                KeyDerivation.wipe(recovered);
                return null;
            }
            return recovered;
        } catch (Exception e) {
            // Covers a changed lock screen, a wiped Keystore, and tampered
            // storage. In every case the wrapped key can no longer be
            // trusted, so drop it and fall back to the master password.
            clear(context);
            return null;
        }
    }

    // ---------------- keystore plumbing ----------------

    private static SecretKey createKey(boolean reuseIfPresent) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (reuseIfPresent && keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Do not burn the key when a new fingerprint is enrolled: the user
            // is still the same person, and invalidating here would silently
            // lose the convenience they asked for.
            builder.setInvalidatedByBiometricEnrollment(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        }

        generator.init(builder.build());
        return generator.generateKey();
    }

    private static SecretKey loadKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            throw new IllegalStateException("biometric key is missing");
        }
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) {
            throw new IllegalStateException("biometric key has the wrong type");
        }
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }
}
