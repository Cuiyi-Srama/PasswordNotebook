package com.cuiyi.passwordnotebook;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads vault files written by v2.x so users can bring their data forward.
 * Decryption only; see LegacyCrypto for why the old key still exists here.
 *
 * Recognised shapes, checked in order:
 *
 *  A) v2 export, encrypted fields
 *     name \u2561 password \u2561 note \u2561 tag \u2561 timestamp
 *
 *  B) v2 export, mixed
 *     some fields already plain (short strings), others still base64
 *
 *  C) v2.1 draft, whole line encrypted
 *     base64blob
 *
 *  D) plain TSV / CSV
 *     name \t password \t note \t tag
 *
 * The importer never throws on a single bad line. It counts what it recovered
 * and reports the rest, because a user with a half-corrupted backup still
 * wants the half that survived.
 */
public final class LegacyImporter {

    public static class Result {
        public final List<Entry> entries = new ArrayList<Entry>();
        public int totalLines;
        public int recovered;
        public int skipped;
        public boolean detectedLegacy;

        public String summary() {
            return "parsed " + totalLines + " lines, recovered " + recovered
                    + ", skipped " + skipped
                    + (detectedLegacy ? " (legacy encrypted format)" : "");
        }
    }

    private LegacyImporter() {
        throw new AssertionError("no instance");
    }

    public static Result parse(String text) {
        Result result = new Result();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        String[] lines = text.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            result.totalLines++;
            Entry entry = parseLine(line, result);
            if (entry == null || entry.isBlank()) {
                result.skipped++;
            } else {
                result.entries.add(entry);
                result.recovered++;
            }
        }
        return result;
    }

    private static Entry parseLine(String line, Result result) {
        // Shape A/B: v2 uses U+2561 between fields.
        if (line.indexOf(LegacyCrypto.LEGACY_FIELD_SEP) >= 0) {
            result.detectedLegacy = true;
            String[] parts = splitKeepEmpty(line, LegacyCrypto.LEGACY_FIELD_SEP);
            return fromParts(parts, true);
        }
        // Shape C: whole line is one ciphertext.
        if (!line.contains("\t") && !line.contains(",")
                && LegacyCrypto.looksEncrypted(line)) {
            String decrypted = LegacyCrypto.decrypt(line);
            if (decrypted != null) {
                result.detectedLegacy = true;
                // The draft stored the fields tab separated inside the blob.
                if (decrypted.indexOf('\t') >= 0) {
                    return fromParts(decrypted.split("\t", -1), false);
                }
                if (decrypted.indexOf(LegacyCrypto.ALT_FIELD_SEP) >= 0) {
                    return fromParts(splitKeepEmpty(decrypted, LegacyCrypto.ALT_FIELD_SEP), false);
                }
                Entry en = new Entry();
                en.title = "未命名";
                en.password = decrypted.trim();
                return en;
            }
            // Fall through: maybe it is shaped differently.
        }
        // Shape D: plain delimited text.
        String sep = line.contains("\t") ? "\t" : (line.contains(",") ? "," : null);
        if (sep != null) {
            return fromParts(line.split(sep, -1), false);
        }
        Entry en = new Entry();
        en.title = line;
        return en;
    }

    /**
     * @param parts   fields in order: title, password, note, tag[, timestamp]
     * @param decrypt attempt legacy decryption on base64-looking fields
     */
    private static Entry fromParts(String[] parts, boolean decrypt) {
        Entry en = new Entry();
        en.title = field(parts, 0, decrypt);
        en.password = field(parts, 1, decrypt);
        en.note = field(parts, 2, decrypt);
        en.tag = field(parts, 3, decrypt);

        // The v2 writer appended a human readable timestamp as the last field,
        // and sometimes folded the tag and the timestamp together.
        if (parts.length >= 5) {
            String last = decrypt ? safeDecrypt(parts[4]) : parts[4];
            if (last != null) {
                last = last.trim();
                if (!last.isEmpty()) {
                    if (looksLikeTimestamp(last)) {
                        en.updatedAt = parseTimestamp(last);
                    } else if (en.tag == null || en.tag.isEmpty()) {
                        en.tag = last;
                    }
                }
            }
        }
        // A tag field may carry "tag<TAB>timestamp".
        if (en.tag != null && en.tag.indexOf('\t') >= 0) {
            String[] tt = en.tag.split("\t", 2);
            en.tag = tt[0].trim();
            if (tt.length > 1 && looksLikeTimestamp(tt[1].trim())) {
                en.updatedAt = parseTimestamp(tt[1].trim());
            }
        }
        if (en.title == null || en.title.trim().isEmpty()) {
            en.title = "未命名";
        }
        return en;
    }

    private static String field(String[] parts, int index, boolean decrypt) {
        if (index >= parts.length) {
            return "";
        }
        String value = parts[index];
        if (value == null) {
            return "";
        }
        if (decrypt) {
            String decoded = safeDecrypt(value);
            if (decoded != null) {
                return decoded.trim();
            }
        }
        return value.trim();
    }

    /** Decrypt when the field looks like ciphertext, otherwise keep as is. */
    private static String safeDecrypt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!LegacyCrypto.looksEncrypted(trimmed)) {
            return trimmed;
        }
        String decoded = LegacyCrypto.decrypt(trimmed);
        return decoded != null ? decoded : trimmed;
    }

    private static String[] splitKeepEmpty(String s, char sep) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out.toArray(new String[0]);
    }

    private static boolean looksLikeTimestamp(String s) {
        if (s.length() < 8) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits >= 6 && (s.contains("-") || s.contains("/") || s.contains(":"));
    }

    private static long parseTimestamp(String s) {
        String[] patterns = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(pattern);
                fmt.setLenient(true);
                java.util.Date d = fmt.parse(s);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
                // try the next pattern
            }
        }
        return System.currentTimeMillis();
    }
}
