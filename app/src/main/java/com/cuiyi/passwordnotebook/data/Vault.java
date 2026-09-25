package com.cuiyi.passwordnotebook.data;

import android.content.Context;
import com.cuiyi.passwordnotebook.crypto.CryptoException;
import com.cuiyi.passwordnotebook.crypto.KeyDerivation;
import com.cuiyi.passwordnotebook.crypto.SecretCipher;
import com.cuiyi.passwordnotebook.crypto.VaultFile;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * The single entry point to stored data.
 *
 * Lifecycle:
 *   NO_VAULT -> create() has not run yet
 *   LOCKED   -> the file exists but the key is not in memory
 *   UNLOCKED -> the key is held here and entries are readable
 *
 * The derived key never leaves this class. lock() wipes it and drops the
 * plaintext copy of the entries, and the activity calls lock() whenever it is
 * paused, so the key is not sitting in memory while the app is in the
 * background.
 */
public final class Vault {

    public static final String FILE_NAME = "vault.pwdnb";

    /** Fixed string encrypted with the key to verify a password quickly. */
    private static final String VERIFY_TOKEN = "pwdnb-v5-check";

    private static byte[] key;
    private static List<Entry> entries;

    private Vault() {
        throw new AssertionError("no instance");
    }

    // ---------------- state ----------------

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean exists(Context context) {
        File f = file(context);
        return f.exists() && f.length() > 0;
    }

    public static synchronized boolean isUnlocked() {
        return key != null;
    }

    /** Drop the key and the decrypted entries. */
    public static synchronized void lock() {
        KeyDerivation.wipe(key);
        key = null;
        entries = null;
    }

    // ---------------- create ----------------

    /**
     * Create a new empty vault.
     *
     * @return the iteration count chosen for this device
     */
    public static synchronized int create(Context context, char[] masterPassword)
            throws CryptoException, GeneralSecurityException, IOException {
        byte[] salt = KeyDerivation.newSalt();
        int iterations = KeyDerivation.calibrate(masterPassword, salt, 300L);
        byte[] derived = KeyDerivation.derive(masterPassword, salt, iterations);
        List<Entry> empty = new ArrayList<Entry>();
        persist(context, empty, derived, salt, iterations);
        adopt(derived, empty);
        return iterations;
    }

    // ---------------- unlock ----------------

    /**
     * Unlock with the master password.
     *
     * @return true when the password is correct
     * @throws CryptoException when the file is not a readable vault
     */
    public static synchronized boolean unlock(Context context, char[] masterPassword)
            throws CryptoException, GeneralSecurityException, IOException {
        String text = VaultFile.read(file(context));
        VaultFile.Header header = VaultFile.readHeader(text);
        if (header == null) {
            throw new CryptoException("unrecognised vault format");
        }
        byte[] derived = KeyDerivation.derive(masterPassword, header.salt, header.iterations);

        // Verify before decrypting the bulk: a wrong password fails here fast.
        String probe;
        try {
            probe = SecretCipher.decrypt(header.check, derived);
        } catch (CryptoException e) {
            KeyDerivation.wipe(derived);
            return false;
        }
        if (!VERIFY_TOKEN.equals(probe)) {
            KeyDerivation.wipe(derived);
            return false;
        }

        String body = VaultFile.readBody(text);
        if (body == null || body.isEmpty()) {
            KeyDerivation.wipe(derived);
            throw new CryptoException("vault body is missing");
        }
        String json = SecretCipher.decrypt(body, derived);
        List<Entry> loaded = VaultJson.read(json);
        adopt(derived, loaded);
        return true;
    }

    // ---------------- biometric ----------------

    /**
     * Open the vault with a key that was already derived, instead of a password.
     *
     * Used by the biometric path, where the key comes back from the Keystore
     * wrapper rather than from PBKDF2. The candidate is still checked against
     * the file token, so a stale wrapper is rejected cleanly instead of
     * producing a vault full of garbage.
     *
     * @return true when this key opens the vault
     */
    public static synchronized boolean unlockWithKey(Context context, byte[] candidate)
            throws CryptoException, IOException {
        if (candidate == null || candidate.length != KeyDerivation.KEY_BYTES) {
            return false;
        }
        String text = VaultFile.read(file(context));
        VaultFile.Header header = VaultFile.readHeader(text);
        if (header == null) {
            throw new CryptoException("unrecognised vault format");
        }
        String probe;
        try {
            probe = SecretCipher.decrypt(header.check, candidate);
        } catch (CryptoException e) {
            return false;
        }
        if (!VERIFY_TOKEN.equals(probe)) {
            return false;
        }
        String body = VaultFile.readBody(text);
        if (body == null || body.isEmpty()) {
            throw new CryptoException("vault body is missing");
        }
        String json = SecretCipher.decrypt(body, candidate);
        List<Entry> loaded = VaultJson.read(json);
        // Keep our own copy so the caller's array can be wiped immediately.
        byte[] owned = new byte[candidate.length];
        System.arraycopy(candidate, 0, owned, 0, candidate.length);
        adopt(owned, loaded);
        return true;
    }

    /**
     * The live key, so it can be wrapped for biometric unlock.
     * Returns null while locked. Callers must not retain the array.
     */
    public static synchronized byte[] currentKey() {
        return key;
    }

    // ---------------- read ----------------

    /** A copy of the current entries; empty while locked. */
    public static synchronized List<Entry> entries() {
        return entries == null ? new ArrayList<Entry>() : new ArrayList<Entry>(entries);
    }

    public static synchronized int size() {
        return entries == null ? 0 : entries.size();
    }

    // ---------------- write ----------------

    /** Replace everything and persist. */
    public static synchronized void replaceAll(Context context, List<Entry> replacement)
            throws CryptoException, IOException, GeneralSecurityException {
        requireUnlocked();
        String text = VaultFile.read(file(context));
        VaultFile.Header header = VaultFile.readHeader(text);
        if (header == null) {
            throw new CryptoException("vault header unreadable");
        }
        persist(context, replacement, key, header.salt, header.iterations);
        entries = new ArrayList<Entry>(replacement);
    }

    public static synchronized void add(Context context, Entry entry)
            throws CryptoException, IOException, GeneralSecurityException {
        List<Entry> list = entries();
        list.add(entry);
        replaceAll(context, list);
    }

    public static synchronized void remove(Context context, Entry target)
            throws CryptoException, IOException, GeneralSecurityException {
        List<Entry> list = entries();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target || list.get(i).sameAs(target)) {
                list.remove(i);
                break;
            }
        }
        replaceAll(context, list);
    }

    /** Append entries, skipping any whose title and password already exist. */
    public static synchronized int addAllSkippingDuplicates(Context context, List<Entry> incoming)
            throws CryptoException, IOException, GeneralSecurityException {
        List<Entry> list = entries();
        int added = 0;
        for (Entry candidate : incoming) {
            boolean duplicate = false;
            for (Entry existing : list) {
                if (existing.sameAs(candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                list.add(candidate);
                added++;
            }
        }
        replaceAll(context, list);
        return added;
    }

    // ---------------- backup ----------------

    /** The raw sealed text, safe to copy anywhere. */
    public static synchronized String exportSealed(Context context) throws IOException {
        return VaultFile.read(file(context));
    }

    /**
     * Replace the vault with a sealed backup.
     *
     * @return how many entries the backup held
     */
    public static synchronized int importSealed(Context context, String sealed, char[] masterPassword)
            throws CryptoException, GeneralSecurityException, IOException {
        VaultFile.Header header = VaultFile.readHeader(sealed);
        if (header == null) {
            throw new CryptoException("backup is not a recognised vault");
        }
        byte[] derived = KeyDerivation.derive(masterPassword, header.salt, header.iterations);
        String probe;
        try {
            probe = SecretCipher.decrypt(header.check, derived);
        } catch (CryptoException e) {
            KeyDerivation.wipe(derived);
            throw new CryptoException("backup password is wrong");
        }
        if (!VERIFY_TOKEN.equals(probe)) {
            KeyDerivation.wipe(derived);
            throw new CryptoException("backup password is wrong");
        }
        String body = VaultFile.readBody(sealed);
        if (body == null || body.isEmpty()) {
            KeyDerivation.wipe(derived);
            throw new CryptoException("backup body is missing");
        }
        String json = SecretCipher.decrypt(body, derived);
        List<Entry> loaded = VaultJson.read(json);
        VaultFile.write(file(context), sealed);
        adopt(derived, loaded);
        return loaded.size();
    }

    // ---------------- internals ----------------

    private static void adopt(byte[] derived, List<Entry> loaded) {
        KeyDerivation.wipe(key);
        key = derived;
        entries = loaded;
    }

    private static void persist(Context context, List<Entry> list, byte[] derived,
                                byte[] salt, int iterations)
            throws CryptoException, IOException {
        String json = VaultJson.write(list);
        String body = SecretCipher.encrypt(json, derived);
        String check = SecretCipher.encrypt(VERIFY_TOKEN, derived);
        String text = VaultFile.compose(salt, iterations, check, body);
        VaultFile.write(file(context), text);
    }

    private static void requireUnlocked() throws CryptoException {
        if (key == null) {
            throw new CryptoException("vault is locked");
        }
    }
}
