package com.cuiyi.passwordnotebook;

import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * \u65e7\u6570\u636e\u8fc1\u79fb\u5668 v3.0
 *
 * \u7b56\u7565\uff1a\u4e8b\u52a1\u5f0f\u2014\u2014\u5148\u5168\u91cf\u89e3\u5bc6\u5230\u5185\u5b58\uff0c\u786e\u8ba4 100% \u6210\u529f\u540e\u624d\u6279\u91cf\u91cd\u5199\u3002
 * \u4e2d\u9014\u4efb\u4f55\u5931\u8d25\uff0c\u65e7\u6570\u636e\u4fdd\u6301\u539f\u6837\uff0c\u4e0d\u4f1a\u4e22\u5931\u3002
 *
 * \u5b89\u5168\u8fb9\u754c\uff1a\u65e7\u6570\u636e\u5728\u8fc1\u79fb\u524d\u5df2\u5904\u4e8e\u4f4e\u5b89\u5168\u72b6\u6001\uff08\u5bc6\u94a5\u53ef\u516c\u5f00\u8ba1\u7b97\uff09\uff0c
 * \u56e0\u6b64\u8fc1\u79fb\u5b8c\u6210\u540e\u5fc5\u987b\u63d0\u793a\u7528\u6237\u8f6e\u6362\u6240\u6709\u5bc6\u7801\u3002
 */
public final class VaultMigrator {

    public static final String PREF_FILE = "pwdnb";
    public static final String KEY_RECORDS = "records";
    public static final String KEY_VERSION = "vaultVersion";
    public static final String KEY_SALT = "kdfSalt";
    public static final String KEY_ITER = "kdfIterations";
    public static final String KEY_VERIFIER = "kdfVerifier";
    public static final String KEY_CORE_WORD = "coreWord";
    public static final String KEY_CUSTOM_SALT = "customSalt";

    /** \u5f53\u524d\u4ed3\u5e93\u683c\u5f0f\u7248\u672c */
    public static final int VAULT_VERSION = 3;
    /** \u65e7\u683c\u5f0f\uff08v1/v2\uff09\u672a\u6807\u8bb0\u7248\u672c */
    public static final int VAULT_VERSION_LEGACY = 1;

    /** \u8fc1\u79fb\u7ed3\u679c */
    public static class Result {
        public boolean migrated;
        public int totalLines;
        public int successLines;
        public int failedLines;
        public String error;
        /** \u8fc1\u79fb\u540e\u5f97\u5230\u7684\u660e\u6587\u884c\uff08\u4ec5\u5185\u5b58\uff09 */
        public List<String> plainLines = new ArrayList<String>();

        @Override
        public String toString() {
            return "migrated=" + migrated + " total=" + totalLines
                    + " ok=" + successLines + " fail=" + failedLines
                    + (error != null ? " err=" + error : "");
        }
    }

    /** \u65e7\u5bc6\u6587\u884c\u5206\u9694\u7b26\uff1a\u540d\u79f0\u4e0e\u5bc6\u6587\u4e4b\u95f4\u7528\u201c\u4e00\u70b9\u4e0b\u63a0\u201d\u7b26\u5206\u9694 */
    private static final String RECORD_SEP = "\u2e41";

    private VaultMigrator() {
        throw new AssertionError("no instance");
    }

    /** \u5224\u65ad\u662f\u5426\u4e3a\u65e7\u683c\u5f0f\uff08\u9700\u8fc1\u79fb\uff09 */
    public static boolean needsMigration(SharedPreferences prefs) {
        int v = prefs.getInt(KEY_VERSION, VAULT_VERSION_LEGACY);
        if (v >= VAULT_VERSION) {
            return false;
        }
        String raw = prefs.getString(KEY_RECORDS, "");
        return raw != null && !raw.isEmpty();
    }

    /**
     * \u7b2c\u4e00\u9636\u6bb5\uff1a\u7528\u65e7\u5bc6\u94a5\u5168\u91cf\u89e3\u5bc6\u65e7\u6570\u636e\u5230\u5185\u5b58\u3002
     * \u4e0d\u5199\u4efb\u4f55\u6570\u636e\u3002
     */
    public static Result decryptLegacyToMemory(SharedPreferences prefs) {
        Result r = new Result();
        String raw = prefs.getString(KEY_RECORDS, "");
        if (raw == null || raw.isEmpty()) {
            r.migrated = false;
            return r;
        }
        byte[] legacyKey;
        try {
            legacyKey = CryptoHelper.getLegacyKey().getEncoded();
        } catch (GeneralSecurityException e) {
            r.error = "legacy key derive failed: " + e.getMessage();
            return r;
        }
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            r.totalLines++;
            String plain = decodeLegacyLine(line, legacyKey);
            if (plain == null) {
                r.failedLines++;
            } else {
                r.successLines++;
                r.plainLines.add(plain);
            }
        }
        KeyDerivation.wipe(legacyKey);
        r.migrated = r.failedLines == 0;
        if (!r.migrated) {
            r.error = "partial decrypt failure: " + r.failedLines + "/" + r.totalLines;
        }
        return r;
    }

    /** \u89e3\u6790\u4e00\u884c\u65e7\u683c\u5f0f\u8bb0\u5f55\uff0c\u5931\u8d25\u8fd4\u56de null */
    private static String decodeLegacyLine(String line, byte[] legacyKey) {
        try {
            if (line.contains(RECORD_SEP)) {
                String[] parts = line.split(RECORD_SEP, 2);
                if (parts.length >= 2) {
                    return CryptoHelper.decrypt(parts[1], legacyKey);
                }
                return null;
            }
            // \u65e7\u5f0f\u660e\u6587\u884c\uff08\u65e7\u7248\u672c\u53ef\u80fd\u76f4\u63a5\u5b58\u660e\u6587\uff09\uff0c\u539f\u6837\u4fdd\u7559
            if (line.contains("\t") || line.contains(",")) {
                return line;
            }
            return line;
        } catch (CryptoHelper.CryptoException e) {
            return null;
        }
    }

    /**
     * \u7b2c\u4e8c\u9636\u6bb5\uff1a\u5c06\u5185\u5b58\u4e2d\u7684\u660e\u6587\u7528\u65b0\u5bc6\u94a5\u91cd\u65b0\u52a0\u5bc6\uff0c\u5e76\u5199\u5165\u65b0\u683c\u5f0f\u5143\u6570\u636e\u3002
     *
     * @param plainLines  \u7b2c\u4e00\u9636\u6bb5\u5f97\u5230\u7684\u660e\u6587\u884c
     * @param newKey      \u4ece\u7528\u6237\u4e3b\u5bc6\u7801\u6d3e\u751f\u7684\u65b0\u5bc6\u94a5
     * @param kdfSalt     \u65b0\u968f\u673a\u76d0
     * @param iterations  \u5b9e\u6d4b\u6821\u51c6\u7684\u8fed\u4ee3\u6b21\u6570
     * @param coreWord    \u7528\u6237\u6838\u5fc3\u8bcd\uff08\u5c06\u88ab Keystore \u52a0\u5bc6\u5b58\u50a8\uff09
     * @param customSalt  \u7528\u6237\u81ea\u5b9a\u4e49\u76d0\uff08\u5c06\u88ab Keystore \u52a0\u5bc6\u5b58\u50a8\uff09
     */
    public static Result commitMigration(SharedPreferences prefs, List<String> plainLines,
                                         byte[] newKey, byte[] kdfSalt, int iterations,
                                         String coreWord, String customSalt) {
        Result r = new Result();
        r.totalLines = plainLines.size();
        // 1. \u5148\u5728\u5185\u5b58\u4e2d\u5b8c\u6210\u5168\u90e8\u52a0\u5bc6\uff0c\u4efb\u4f55\u4e00\u884c\u5931\u8d25\u5c31\u4e2d\u6b62
        List<String> newLines = new ArrayList<String>();
        for (String plain : plainLines) {
            try {
                newLines.add(CryptoHelper.encrypt(plain, newKey));
                r.successLines++;
            } catch (CryptoHelper.CryptoException e) {
                r.failedLines++;
                r.error = "encrypt failed at line " + r.successLines + ": " + e.getMessage();
                return r;
            }
        }
        if (r.failedLines > 0) {
            return r;
        }

        // 2. \u6784\u9020\u65b0\u539f\u6570\u636e
        StringBuilder sb = new StringBuilder();
        for (String l : newLines) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(l);
        }

        // 3. \u5148\u5199 keystore \u52a0\u5bc6\u7684\u6838\u5fc3\u8bcd\u4e0e\u76d0\uff08\u5931\u8d25\u5219\u6574\u4f53\u4e2d\u6b62\uff09
        if (coreWord != null && !coreWord.isEmpty()) {
            if (!SecureStore.putString(prefs, KEY_CORE_WORD, coreWord)) {
                r.error = "failed to store coreWord securely";
                r.failedLines = r.totalLines;
                r.successLines = 0;
                return r;
            }
        }
        if (customSalt != null && !customSalt.isEmpty()) {
            if (!SecureStore.putString(prefs, KEY_CUSTOM_SALT, customSalt)) {
                r.error = "failed to store customSalt securely";
                r.failedLines = r.totalLines;
                r.successLines = 0;
                return r;
            }
        }

        // 4. \u5199\u539f\u6570\u636e\u4e0e\u5143\u6570\u636e\uff08\u5355\u4e2a commit \u4fdd\u8bc1 public \u4e00\u81f4\u6027\uff09
        String verifier = buildVerifier(newKey);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_RECORDS, sb.toString());
        ed.putInt(KEY_VERSION, VAULT_VERSION);
        ed.putString(KEY_SALT, Base64.encodeToString(kdfSalt, Base64.NO_WRAP));
        ed.putInt(KEY_ITER, iterations);
        ed.putString(KEY_VERIFIER, verifier);
        boolean ok = ed.commit();
        if (!ok) {
            r.error = "commit failed";
            return r;
        }
        r.migrated = true;
        return r;
    }

    /** \u6784\u9020\u4e3b\u5bc6\u7801\u6821\u9a8c\u5668\uff08\u7528\u4e8e\u9a8c\u8bc1\u5bc6\u7801\u662f\u5426\u6b63\u786e\uff0c\u4e0d\u6cc4\u9732\u5bc6\u94a5\uff09 */
    public static String buildVerifier(byte[] key) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update("pwdnb-v3-verifier".getBytes(Charset.forName("UTF-8")));
            byte[] d = md.digest(key);
            return Base64.encodeToString(d, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    /** \u6821\u9a8c\u4e3b\u5bc6\u7801\u662f\u5426\u6b63\u786e */
    public static boolean verifyKey(SharedPreferences prefs, byte[] key) {
        String stored = prefs.getString(KEY_VERIFIER, null);
        if (stored == null || stored.isEmpty()) {
            return true;
        }
        return constantTimeEquals(stored, buildVerifier(key));
    }

    /** \u5e38\u91cf\u65f6\u95f4\u6bd4\u8f83\uff0c\u9632\u65f6\u5e8f\u5074\u9053 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** \u8bfb\u53d6\u5df2\u4fdd\u5b58\u7684 KDF \u53c2\u6570 */
    public static byte[] getSalt(SharedPreferences prefs) {
        String s = prefs.getString(KEY_SALT, null);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Base64.decode(s, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int getIterations(SharedPreferences prefs) {
        return prefs.getInt(KEY_ITER, KeyDerivation.ITERATIONS_DEFAULT);
    }
}
