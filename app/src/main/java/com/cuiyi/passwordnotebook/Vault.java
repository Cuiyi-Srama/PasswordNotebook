package com.cuiyi.passwordnotebook;

import android.content.SharedPreferences;
import java.security.GeneralSecurityException;

/**
 * \u71b5\u5e93\u8bbf\u95ee\u5c42 v3.0
 *
 * \u5c06\u201c\u4e3b\u5bc6\u7801\u89e3\u9501\u201d\u3001\u201c\u8bb0\u5f55\u52a0\u89e3\u5bc6\u201d\u3001\u201c\u65e7\u6570\u636e\u8fc1\u79fb\u201d\u5c01\u88c5\u6210\u5355\u4e00\u5165\u53e3\uff0c
 * MainActivity \u53ea\u9700\u8c03\u7528\u672c\u7c7b\uff0c\u907f\u514d\u5bc6\u94a5\u5728 UI \u5c42\u6d41\u8f6c\u3002
 */
public final class Vault {

    /** \u89e3\u9501\u72b6\u6001\uff1a\u5bc6\u94a5\u4ec5\u5728\u5185\u5b58\uff0c\u4e0d\u843d\u76d8 */
    private static byte[] sKey;

    private Vault() {
        throw new AssertionError("no instance");
    }

    /** \u662f\u5426\u5df2\u89e3\u9501 */
    public static synchronized boolean isUnlocked() {
        return sKey != null;
    }

    /** \u9501\u5b9a\u5e76\u64e6\u9664\u5185\u5b58\u4e2d\u7684\u5bc6\u94a5 */
    public static synchronized void lock() {
        KeyDerivation.wipe(sKey);
        sKey = null;
    }

    /** \u83b7\u53d6\u5f53\u524d\u5bc6\u94a5\u5f15\u7528\uff08\u672a\u89e3\u9501\u8fd4\u56de null\uff09 */
    public static synchronized byte[] key() {
        return sKey;
    }

    /** \u662f\u5426\u9700\u8981\u65b0\u5efa\u71b5\u5e93\uff08\u9996\u6b21\u4f7f\u7528 / \u65e7\u6570\u636e\u5df2\u8fc1\u79fb\u5b8c\u6bd5\uff09 */
    public static boolean isNewVault(SharedPreferences prefs) {
        return prefs.getInt(VaultMigrator.KEY_VERSION, VaultMigrator.VAULT_VERSION_LEGACY)
                < VaultMigrator.VAULT_VERSION;
    }

    /**
     * \u9996\u6b21\u521d\u59cb\u5316\uff1a\u7528\u4e3b\u5bc6\u7801\u521b\u5efa\u65b0\u71b5\u5e93\u3002
     * \u82e5\u5df2\u6709\u65e7\u6570\u636e\uff0c\u8bf7\u5148\u8c03用 migrate()\u3002
     */
    public static synchronized String initNew(SharedPreferences prefs, char[] masterPassword)
            throws GeneralSecurityException {
        byte[] salt = KeyDerivation.newSalt();
        int iters = KeyDerivation.calibrate(masterPassword, salt, 300);
        byte[] key = KeyDerivation.deriveKey(masterPassword, salt, iters);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt(VaultMigrator.KEY_VERSION, VaultMigrator.VAULT_VERSION);
        ed.putString(VaultMigrator.KEY_SALT,
                android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP));
        ed.putInt(VaultMigrator.KEY_ITER, iters);
        ed.putString(VaultMigrator.KEY_VERIFIER, VaultMigrator.buildVerifier(key));
        if (!ed.commit()) {
            KeyDerivation.wipe(key);
            throw new GeneralSecurityException("failed to persist vault metadata");
        }
        KeyDerivation.wipe(sKey);
        sKey = key;
        return "OK";
    }

    /**
     * \u7528\u4e3b\u5bc6\u7801\u89e3\u9501\u5df2\u6709\u71b5\u5e93\u3002
     *
     * @return null \u8868\u793a\u6210\u529f\uff1b\u5426\u5219\u8fd4\u56de\u9519\u8bef\u63cf\u8ff0
     */
    public static synchronized String unlock(SharedPreferences prefs, char[] masterPassword) {
        byte[] salt = VaultMigrator.getSalt(prefs);
        if (salt == null) {
            return "\u71b5\u5e93\u5143\u6570\u636e\u7f3a\u5931\uff08salt\uff09";
        }
        int iters = VaultMigrator.getIterations(prefs);
        byte[] key;
        try {
            key = KeyDerivation.deriveKey(masterPassword, salt, iters);
        } catch (GeneralSecurityException e) {
            return "\u5bc6\u94a5\u6d3e\u751f\u5931\u8d25\uff1a" + e.getMessage();
        }
        if (!VaultMigrator.verifyKey(prefs, key)) {
            KeyDerivation.wipe(key);
            return "\u4e3b\u5bc6\u7801\u9519\u8bef";
        }
        KeyDerivation.wipe(sKey);
        sKey = key;
        return null;
    }

    /**
     * \u8fc1\u79fb\u65e7\u6570\u636e\u5230\u65b0\u683c\u5f0f\u3002
     * \u4e8b\u52a1\u5f0f\uff1a\u4efb\u4f55\u4e00\u6b65\u5931\u8d25\u90fd\u4e0d\u5199\u5165\uff0c\u65e7\u6570\u636e\u4fdd\u6301\u539f\u6837\u3002
     */
    public static synchronized String migrate(SharedPreferences prefs, char[] masterPassword,
                                              String coreWord, String customSalt) {
        // 1. \u5168\u91cf\u89e3\u5bc6\u65e7\u6570\u636e
        VaultMigrator.Result dec = VaultMigrator.decryptLegacyToMemory(prefs);
        if (dec.error != null) {
            return "\u65e7\u6570\u636e\u89e3\u5bc6\u5931\u8d25\uff0c\u5df2\u4e2d\u6b62\u8fc1\u79fb\uff08\u65e7\u6570\u636e\u672a\u52a8\uff09\uff1a" + dec.error;
        }
        // 2. \u6d3e\u751f\u65b0\u5bc6\u94a5
        byte[] salt = KeyDerivation.newSalt();
        int iters = KeyDerivation.calibrate(masterPassword, salt, 300);
        byte[] key;
        try {
            key = KeyDerivation.deriveKey(masterPassword, salt, iters);
        } catch (GeneralSecurityException e) {
            return "\u65b0\u5bc6\u94a5\u6d3e\u751f\u5931\u8d25\uff1a" + e.getMessage();
        }
        // 3. \u4e8b\u52a1\u5f0f\u63d0\u4ea4
        VaultMigrator.Result w = VaultMigrator.commitMigration(
                prefs, dec.plainLines, key, salt, iters, coreWord, customSalt);
        if (!w.migrated) {
            KeyDerivation.wipe(key);
            return "\u8fc1\u79fb\u5199\u5165\u5931\u8d25\uff1a" + w.error;
        }
        KeyDerivation.wipe(sKey);
        sKey = key;
        return null;
    }

    /** \u52a0\u5bc6\u4e00\u884c\u8bb0\u5f55\uff08\u672a\u89e3\u9501\u629b\u5f02\u5e38\uff09 */
    public static String encryptLine(String plain) throws CryptoHelper.CryptoException {
        byte[] k = sKey;
        if (k == null) {
            throw new CryptoHelper.CryptoException("\u71b5\u5e93\u672a\u89e3\u9501");
        }
        return CryptoHelper.encrypt(plain, k);
    }

    /** \u89e3\u5bc6\u4e00\u884c\u8bb0\u5f55\uff08\u5931\u8d25\u629b\u5f02\u5e38\uff0c\u4e0d\u964d\u7ea7\uff09 */
    public static String decryptLine(String b64) throws CryptoHelper.CryptoException {
        byte[] k = sKey;
        if (k == null) {
            throw new CryptoHelper.CryptoException("\u71b5\u5e93\u672a\u89e3\u9501");
        }
        return CryptoHelper.decrypt(b64, k);
    }

    /** \u5b89\u5168\u5730\u8bfb\u53d6\u6838\u5fc3\u8bcd\uff08Keystore \u52a0\u5bc6\u5b58\u50a8\uff09 */
    public static String getCoreWord(SharedPreferences prefs) {
        return SecureStore.getString(prefs, VaultMigrator.KEY_CORE_WORD);
    }

    public static boolean putCoreWord(SharedPreferences prefs, String coreWord) {
        return SecureStore.putString(prefs, VaultMigrator.KEY_CORE_WORD, coreWord);
    }

    public static String getCustomSalt(SharedPreferences prefs) {
        return SecureStore.getString(prefs, VaultMigrator.KEY_CUSTOM_SALT);
    }

    public static boolean putCustomSalt(SharedPreferences prefs, String salt) {
        return SecureStore.putString(prefs, VaultMigrator.KEY_CUSTOM_SALT, salt);
    }
}
