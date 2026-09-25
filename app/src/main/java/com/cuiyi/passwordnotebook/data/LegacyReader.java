package com.cuiyi.passwordnotebook.data;

import android.util.Base64;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Import support for data written by the 2.x releases.
 *
 * That build compiled a fixed AES key into the APK, so anything it wrote can
 * be read by anyone who decompiles the app. The key is kept here for exactly
 * one purpose: letting an existing user recover their own entries. Nothing in
 * the write path uses it, and the constants are private to this class so no
 * other component can accidentally start encrypting with them.
 */
public final class LegacyReader {

    /** The 2.x hardcoded key. Import only. */
    private static final byte[] LEGACY_KEY = {
            90, 63, -116, 23, -30, 75, -99, 97,
            113, -86, 51, -34, 79, 44, -69, 25,
            -114, 85, 13, -57, 106, 31, -13, -120,
            66, -67, 126, 17, -103, 54, -38, -27
    };

    /** Field separator used by 2.x exports. */
    private static final char SEP_MAIN = '\u2561';
    /** Field separator used by the unfinished 2.1 rewrite. */
    private static final char SEP_ALT = '\u2e41';

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;

    /** base64(IV 12 + tag 16) is 40 chars; anything shorter is not ciphertext. */
    private static final int MIN_CIPHERTEXT_CHARS = 40;
    private static final int TAG_BITS = 128;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private LegacyReader() {
        throw new AssertionError("no instance");
    }

    /** Outcome of parsing a pasted backup. */
    public static class Result {
        public final List<Entry> entries = new ArrayList<Entry>();
        public int linesSeen;
        public int skipped;
        public boolean usedLegacyFormat;

        public String describe() {
            return "扫描 " + linesSeen + " 行，识别 " + entries.size()
                    + " 条" + (skipped > 0 ? "，跳过 " + skipped + " 行" : "")
                    + (usedLegacyFormat ? "（旧版加密格式）" : "");
        }
    }

    /**
     * Parse pasted text. Handles, in order of preference:
     *
     *   A. 2.x encrypted export:  title ⟨SEP⟩ password ⟨SEP⟩ note ⟨SEP⟩ tag ⟨SEP⟩ time
     *   B. 2.x whole-line blob
     *   C. plain delimited rows: tab, ASCII comma or the full-width comma
     *   D. alternating pairs: one line for the title, the next for the password
     *
     * A malformed row never aborts the import; it is counted and skipped, since
     * a partially damaged backup is still worth recovering.
     */
    public static Result parse(String text) {
        Result result = new Result();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        String[] rawLines = text.split("\r?\n");
        List<String> pending = new ArrayList<String>();
        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (looksLikeSeparatorRow(line)) {
                flushPendingPairs(pending, result);
                result.linesSeen++;
                Entry entry = parseSeparatedRow(line, result);
                if (entry == null || entry.isBlank()) {
                    result.skipped++;
                } else {
                    result.entries.add(entry);
                }
                continue;
            }
            if (looksLikeDelimitedRow(line)) {
                flushPendingPairs(pending, result);
                result.linesSeen++;
                Entry entry = parseDelimitedRow(line);
                if (entry == null || entry.isBlank()) {
                    result.skipped++;
                } else {
                    result.entries.add(entry);
                }
                continue;
            }
            if (looksLikeWholeLineBlob(line)) {
                flushPendingPairs(pending, result);
                result.linesSeen++;
                Entry entry = parseWholeLineBlob(line, result);
                if (entry == null || entry.isBlank()) {
                    result.skipped++;
                } else {
                    result.entries.add(entry);
                }
                continue;
            }
            // Bare text: hold it, it may be the first half of a title/password pair.
            pending.add(line);
        }
        flushPendingPairs(pending, result);
        return result;
    }

    /** Consume leftover bare lines as title/password pairs. */
    private static void flushPendingPairs(List<String> pending, Result result) {
        if (pending.isEmpty()) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            result.linesSeen++;
            String first = pending.get(i);
            boolean hasPartner = i + 1 < pending.size();
            if (hasPartner) {
                Entry entry = new Entry();
                entry.title = first;
                entry.password = pending.get(i + 1);
                result.entries.add(entry);
                i++;
            } else {
                Entry entry = new Entry();
                entry.title = first;
                result.entries.add(entry);
            }
        }
        pending.clear();
    }

    private static boolean looksLikeSeparatorRow(String line) {
        return line.indexOf(SEP_MAIN) >= 0 || line.indexOf(SEP_ALT) >= 0;
    }

    private static boolean looksLikeDelimitedRow(String line) {
        return line.indexOf('\t') >= 0 || line.indexOf(',') >= 0 || line.indexOf('\uFF0C') >= 0;
    }

    private static boolean looksLikeWholeLineBlob(String line) {
        if (line.length() < 32) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Entry parseSeparatedRow(String line, Result result) {
        char sep = line.indexOf(SEP_MAIN) >= 0 ? SEP_MAIN : SEP_ALT;
        result.usedLegacyFormat = true;
        String[] parts = splitKeepingEmpty(line, sep);
        Entry entry = new Entry();
        entry.title = field(parts, 0);
        entry.password = field(parts, 1);
        entry.note = field(parts, 2);
        entry.tag = field(parts, 3);
        entry.updatedAt = timestampFrom(parts, 4);
        if (entry.title.isEmpty()) {
            entry.title = "未命名";
        }
        return entry;
    }

    private static Entry parseDelimitedRow(String line) {
        String[] parts;
        if (line.indexOf('\t') >= 0) {
            parts = line.split("\t", -1);
        } else if (line.indexOf(',') >= 0) {
            parts = line.split(",", -1);
        } else {
            parts = line.split("\uFF0C", -1);
        }
        Entry entry = new Entry();
        entry.title = field(parts, 0);
        entry.password = field(parts, 1);
        entry.note = field(parts, 2);
        entry.tag = field(parts, 3);
        if (entry.title.isEmpty()) {
            entry.title = "未命名";
        }
        return entry;
    }

    private static Entry parseWholeLineBlob(String line, Result result) {
        String decoded = legacyDecrypt(line);
        if (decoded == null) {
            return null;
        }
        result.usedLegacyFormat = true;
        if (decoded.indexOf('\t') >= 0) {
            return parseDelimitedRow(decoded);
        }
        if (decoded.indexOf(SEP_ALT) >= 0) {
            return parseSeparatedRow(decoded, result);
        }
        Entry entry = new Entry();
        entry.title = "未命名";
        entry.password = decoded.trim();
        return entry;
    }

    /**
     * Decrypt one field when it looks like legacy ciphertext; otherwise trim it.
     * Long plain values that happen to be base64-shaped will fail the GCM tag
     * check and are then returned unchanged, so labels survive the attempt.
     */
    private static String field(String[] parts, int index) {
        if (index >= parts.length || parts[index] == null) {
            return "";
        }
        // Only the surrounding separator padding is trimmed; whatever the
        // decrypted value itself contains is preserved verbatim, because a
        // password with a leading or trailing space is legal and silently
        // stripping it would make the stored credential wrong.
        String value = parts[index].trim();
        if (value.isEmpty()) {
            return "";
        }
        // Ciphertext is base64(IV 12 + tag 16) at minimum, which is 40 chars,
        // so anything shorter cannot be ciphertext and is kept as plain text.
        if (value.length() >= MIN_CIPHERTEXT_CHARS) {
            String decoded = legacyDecrypt(value);
            if (decoded != null) {
                return decoded;
            }
        }
        return value;
    }

    /** The last field of a 2.x row may carry the tag and a timestamp. */
    private static long timestampFrom(String[] parts, int index) {
        long now = System.currentTimeMillis();
        if (index >= parts.length || parts[index] == null) {
            return now;
        }
        String value = parts[index].trim();
        if (value.isEmpty()) {
            return now;
        }
        if (value.indexOf('\t') >= 0) {
            value = value.substring(value.indexOf('\t') + 1).trim();
        }
        return parseTimestamp(value, now);
    }

    private static long parseTimestamp(String value, long fallback) {
        if (value.length() < 8) {
            return fallback;
        }
        String[] patterns = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern);
                format.setLenient(true);
                java.util.Date parsed = format.parse(value);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (Exception ignored) {
                // try the next pattern
            }
        }
        return fallback;
    }

    private static String legacyDecrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            // An empty field was stored as IV + tag with a zero-length ciphertext,
            // which is exactly IV_BYTES + 16. Rejecting that length (the old code
            // used <=) made every blank note or label look like a decryption
            // failure, so the raw base64 was kept as the field value and the
            // imported records showed garbage instead of an empty string.
            if (all.length < IV_BYTES + 16) {
                return null;
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] body = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, body, 0, body.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(LEGACY_KEY, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] splitKeepingEmpty(String text, char sep) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == sep) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts.toArray(new String[0]);
    }
}
