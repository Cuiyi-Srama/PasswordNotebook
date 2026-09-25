package com.cuiyi.passwordnotebook;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * The single entry point to encrypted storage. v4.
 *
 * State machine:
 *   NEW      -> no vault file yet, caller must call create()
 *   LOCKED   -> vault exists, key not in memory
 *   UNLOCKED -> key held in memory, entries readable
 *
 * The derived key lives only here and only in memory. lock() wipes it.
 */
public final class Vault {

    public static final String VAULT_FILE = "vault.pwdnb";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PREF = "pwd_nb_v4";
    private static final String PREF_ITERATIONS = "iterations";

    /** Cached key. Null whenever the vault is locked. */
    private static byte[] sKey;
    /** Cached decrypted entries, dropped on lock. */
    private static List<Entry> sEntries;
    private static File sFile;

    private Vault() {
        throw new AssertionError("no instance");
    }

    public static synchronized File file(Context context) {
        if (sFile == null) {
            sFile = new File(context.getFilesDir(), VAULT_FILE);
        }
        return sFile;
    }

    public static synchronized boolean exists(Context context) {
        return file(context).exists() && file(context).length() > 0;
    }

    public static synchronized boolean isUnlocked() {
        return sKey != null;
    }

    /** Drop the key and the plaintext entries from memory. */
    public static synchronized void lock() {
        KeyDerivation.wipe(sKey);
        sKey = null;
        if (sEntries != null) {
            VaultCrypto.wipeList(sEntries);
            sEntries = null;
        }
    }

    /** Create a brand new vault. Returns the iteration count that was used. */
    public static synchronized int create(Context context, char[] masterPassword)
            throws CryptoException, GeneralSecurityException, IOException {
        byte[] salt = KeyDerivation.newSalt();
        int iterations = KeyDerivation.calibrate(masterPassword, salt, 300);
        byte[] key = KeyDerivation.derive(masterPassword, salt, iterations);
        List<Entry> empty = new ArrayList<Entry>();
        write(context, empty, key, salt, iterations);
        KeyDerivation.wipe(sKey);
        sKey = key;
        sEntries = empty;
        rememberIterations(context, iterations);
        return iterations;
    }

    /**
     * Unlock an existing vault.
     *
     * @return true on success, false when the password is wrong
     */
    public static synchronized boolean unlock(Context context, char[] masterPassword)
            throws CryptoException, GeneralSecurityException, IOException {
        String raw = readText(file(context));
        VaultCrypto.Header header = VaultCrypto.readHeader(raw);
        if (header == null) {
            // Not a v4 file. If a v2 file happens to sit here, do not pretend
            // the password was wrong; the caller needs to know the format
            // differs so it can offer an import instead.
            throw new CryptoException("unsupported vault format");
        }
        byte[] key = KeyDerivation.derive(masterPassword, header.salt, header.iterations);
        List<Entry> entries = VaultCrypto.open(raw, key);
        if (entries == null) {
            KeyDerivation.wipe(key);
            return false;
        }
        KeyDerivation.wipe(sKey);
        sKey = key;
        sEntries = entries;
        return true;
    }

    /** All entries. Empty when locked. */
    public static synchronized List<Entry> entries() {
        if (sEntries == null) {
            return new ArrayList<Entry>();
        }
        return new ArrayList<Entry>(sEntries);
    }

    public static synchronized int size() {
        return sEntries == null ? 0 : sEntries.size();
    }

    /** Replace the whole vault and persist it. */
    public static synchronized void replaceAll(Context context, List<Entry> entries)
            throws CryptoException, IOException, GeneralSecurityException {
        requireUnlocked();
        String raw = readText(file(context));
        VaultCrypto.Header header = VaultCrypto.readHeader(raw);
        if (header == null) {
            throw new CryptoException("vault header unreadable");
        }
        write(context, entries, sKey, header.salt, header.iterations);
        sEntries = new ArrayList<Entry>(entries);
    }

    public static synchronized void add(Context context, Entry entry)
            throws CryptoException, IOException, GeneralSecurityException {
        List<Entry> list = entries();
        list.add(entry);
        replaceAll(context, list);
    }

    /**
     * Write entries atomically: serialize to a temp file, then rename.
     * A crash mid-write therefore cannot destroy the existing vault.
     */
    private static void write(Context context, List<Entry> entries, byte[] key,
                              byte[] salt, int iterations)
            throws CryptoException, IOException {
        File target = file(context);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        String sealed = VaultCrypto.seal(entries, key, salt, iterations);
        File temp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            OutputStreamWriter writer = new OutputStreamWriter(out, UTF8);
            writer.write(sealed);
            writer.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("could not replace existing vault");
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("could not move vault into place");
        }
    }

    /** Export the sealed vault text so it can be backed up or shared. */
    public static synchronized String exportSealed(Context context) throws IOException {
        return readText(file(context));
    }

    /** Import a sealed vault, replacing the current one. */
    public static synchronized int importSealed(Context context, String raw, char[] masterPassword)
            throws CryptoException, GeneralSecurityException, IOException {
        VaultCrypto.Header header = VaultCrypto.readHeader(raw);
        if (header == null) {
            throw new CryptoException("not a v4 vault");
        }
        byte[] key = KeyDerivation.derive(masterPassword, header.salt, header.iterations);
        List<Entry> entries = VaultCrypto.open(raw, key);
        if (entries == null) {
            KeyDerivation.wipe(key);
            throw new CryptoException("wrong password for this backup");
        }
        writeRaw(context, raw);
        KeyDerivation.wipe(sKey);
        sKey = key;
        sEntries = entries;
        rememberIterations(context, header.iterations);
        return entries.size();
    }

    private static void writeRaw(Context context, String raw) throws IOException {
        File target = file(context);
        File temp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            OutputStreamWriter writer = new OutputStreamWriter(out, UTF8);
            writer.write(raw);
            writer.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("could not replace existing vault");
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("could not move vault into place");
        }
    }

    private static void requireUnlocked() throws CryptoException {
        if (sKey == null) {
            throw new CryptoException("vault is locked");
        }
    }

    private static String readText(File f) throws IOException {
        if (!f.exists()) {
            return "";
        }
        FileInputStream in = new FileInputStream(f);
        try {
            InputStreamReader reader = new InputStreamReader(in, UTF8);
            StringBuilder sb = new StringBuilder((int) Math.max(64, f.length()));
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) > 0) {
                sb.append(buf, 0, read);
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    private static void rememberIterations(Context context, int iterations) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        prefs.edit().putInt(PREF_ITERATIONS, iterations).apply();
    }

    public static int lastIterations(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(PREF_ITERATIONS, KeyDerivation.ITERATIONS_DEFAULT);
    }
}
