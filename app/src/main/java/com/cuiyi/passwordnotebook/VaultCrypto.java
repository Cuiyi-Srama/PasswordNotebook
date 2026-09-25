package com.cuiyi.passwordnotebook;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whole-vault serialization. v4 format.
 *
 * Wire format (all strings UTF-8 unless noted):
 *
 *   PWDNB4\n
 *   kdf=pbkdf2-hmac-sha256\n
 *   iter=<integer>\n
 *   salt=<base64, 16 bytes>\n
 *   check=<base64>\n
 *   ---\n
 *   <base64 ciphertext of the JSON body>
 *
 * The JSON body is an array of entries:
 *   [{"t":title,"p":password,"n":note,"g":tag,"u":updatedAt}, ...]
 *
 * Why a JSON body rather than one ciphertext per line: a single GCM operation
 * over the whole vault means partial edits cannot leave the file in a state
 * where some records are authenticated and others are not. The header is
 * plaintext so the KDF parameters can be read before the password is known,
 * which lets the app tell "wrong password" apart from "corrupt file".
 */
public final class VaultCrypto {

    public static final int FORMAT_VERSION = 4;
    static final String MAGIC = "PWDNB4";
    private static final String CHECK_PLAINTEXT = "pwdnb-check-v4";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static class Header {
        public String kdf;
        public int iterations;
        public byte[] salt;
        public String check;
    }

    public static class Payload {
        public Header header;
        public String body;   // base64 ciphertext
    }

    private VaultCrypto() {
        throw new AssertionError("no instance");
    }

    /** Encrypt a list of entries into the v4 wire format. */
    public static String seal(List<Entry> entries, byte[] key, byte[] salt, int iterations)
            throws CryptoException {
        if (key == null || key.length != KeyDerivation.KEY_BYTES) {
            throw new CryptoException("key must be 32 bytes");
        }
        String json = toJson(entries);
        String body = CryptoHelper.encrypt(json, key);
        String check = CryptoHelper.encrypt(CHECK_PLAINTEXT, key);

        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("kdf=pbkdf2-hmac-sha256").append('\n');
        sb.append("iter=").append(iterations).append('\n');
        sb.append("salt=").append(Base64.encodeToString(salt, Base64.NO_WRAP)).append('\n');
        sb.append("check=").append(check).append('\n');
        sb.append("---").append('\n');
        sb.append(body);
        return sb.toString();
    }

    /** Parse the plaintext header. Returns null if this is not a v4 vault. */
    public static Header readHeader(String raw) {
        if (raw == null) {
            return null;
        }
        String[] lines = raw.split("\n");
        if (lines.length < 6 || !MAGIC.equals(lines[0].trim())) {
            return null;
        }
        Header h = new Header();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) {
                break;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (name.equals("kdf")) {
                h.kdf = value;
            } else if (name.equals("iter")) {
                try {
                    h.iterations = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (name.equals("salt")) {
                try {
                    h.salt = Base64.decode(value, Base64.NO_WRAP);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            } else if (name.equals("check")) {
                h.check = value;
            }
        }
        if (h.salt == null || h.salt.length < 8 || h.iterations <= 0 || h.check == null) {
            return null;
        }
        return h;
    }

    /** Extract the encrypted body. */
    public static String readBody(String raw) {
        if (raw == null) {
            return null;
        }
        int idx = raw.indexOf("\n---\n");
        if (idx < 0) {
            return null;
        }
        return raw.substring(idx + 5).trim();
    }

    /**
     * Verify the password against the header check value, then decrypt.
     *
     * @return null when the password is wrong; throws when the file is unusable
     */
    public static List<Entry> open(String raw, byte[] key) throws CryptoException {
        Header h = readHeader(raw);
        if (h == null) {
            throw new CryptoException("not a v4 vault");
        }
        String body = readBody(raw);
        if (body == null || body.isEmpty()) {
            throw new CryptoException("vault body is missing");
        }
        // Wrong password shows up as a failed check, which we report as null.
        try {
            String probe = CryptoHelper.decrypt(h.check, key);
            if (!CHECK_PLAINTEXT.equals(probe)) {
                return null;
            }
        } catch (CryptoException e) {
            return null;
        }
        String json = CryptoHelper.decrypt(body, key);
        return fromJson(json);
    }

    // ---------------- minimal JSON, no external dependency ----------------

    static String toJson(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < entries.size(); i++) {
            Entry en = entries.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            appendField(sb, "t", en.title, true);
            appendField(sb, "p", en.password, false);
            appendField(sb, "n", en.note, false);
            appendField(sb, "g", en.tag, false);
            sb.append(",\"u\":").append(en.updatedAt);
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(name).append("\":").append(quote(value));
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\n"); break;
                case '\r': sb.append("\r"); break;
                case '\t': sb.append("\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static List<Entry> fromJson(String json) throws CryptoException {
        List<Entry> out = new ArrayList<Entry>();
        if (json == null) {
            return out;
        }
        int i = 0;
        int n = json.length();
        while (i < n && json.charAt(i) != '[') {
            i++;
        }
        i++;
        while (i < n) {
            while (i < n && (json.charAt(i) == ',' || json.charAt(i) == ' ' || json.charAt(i) == '\n')) {
                i++;
            }
            if (i >= n || json.charAt(i) == ']') {
                break;
            }
            if (json.charAt(i) != '{') {
                throw new CryptoException("malformed entry at offset " + i);
            }
            Entry en = new Entry();
            i++;
            while (i < n && json.charAt(i) != '}') {
                int keyStart = json.indexOf('"', i);
                if (keyStart < 0) {
                    break;
                }
                int keyEnd = json.indexOf('"', keyStart + 1);
                if (keyEnd < 0) {
                    break;
                }
                String key = json.substring(keyStart + 1, keyEnd);
                int colon = json.indexOf(':', keyEnd);
                if (colon < 0) {
                    break;
                }
                i = colon + 1;
                while (i < n && json.charAt(i) == ' ') {
                    i++;
                }
                if (i < n && json.charAt(i) == '"') {
                    StringBuilder val = new StringBuilder();
                    i++;
                    while (i < n && json.charAt(i) != '"') {
                        char c = json.charAt(i);
                        if (c == '\\' && i + 1 < n) {
                            char nx = json.charAt(i + 1);
                            i += 2;
                            switch (nx) {
                                case 'n': val.append('\n'); break;
                                case 'r': val.append('\r'); break;
                                case 't': val.append('\t'); break;
                                case '"': val.append('"'); break;
                                case '\\': val.append('\\'); break;
                                case 'u':
                                    if (i + 4 <= n) {
                                        try {
                                            val.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                                            i += 4;
                                        } catch (NumberFormatException ignored) {
                                            // keep going, treat as literal
                                        }
                                    }
                                    break;
                                default: val.append(nx);
                            }
                        } else {
                            val.append(c);
                            i++;
                        }
                    }
                    i++;
                    assign(en, key, val.toString());
                } else {
                    int start = i;
                    while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}') {
                        i++;
                    }
                    String num = json.substring(start, i).trim();
                    if ("u".equals(key)) {
                        try {
                            en.updatedAt = Long.parseLong(num);
                        } catch (NumberFormatException ignored) {
                            // leave default timestamp
                        }
                    }
                }
                while (i < n && (json.charAt(i) == ',' || json.charAt(i) == ' ')) {
                    i++;
                }
            }
            i++;
            if (!en.isBlank()) {
                out.add(en);
            }
        }
        return out;
    }

    private static void assign(Entry en, String key, String value) {
        if ("t".equals(key)) {
            en.title = value;
        } else if ("p".equals(key)) {
            en.password = value;
        } else if ("n".equals(key)) {
            en.note = value;
        } else if ("g".equals(key)) {
            en.tag = value;
        }
    }

    public static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    public static void wipeList(List<Entry> entries) {
        if (entries == null) {
            return;
        }
        for (Entry en : entries) {
            en.password = null;
        }
    }
}
