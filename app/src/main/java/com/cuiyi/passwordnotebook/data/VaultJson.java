package com.cuiyi.passwordnotebook.data;

import com.cuiyi.passwordnotebook.crypto.CryptoException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON for the vault body, so the app needs no third-party parser.
 *
 * Shape: {"v":5,"e":[{"t":..,"p":..,"n":..,"g":..,"u":123}, ...]}
 *
 * Short keys keep the plaintext small before it is encrypted. The parser is
 * strict about structure and lenient about missing optional fields, so a body
 * written by a future version with extra keys still loads.
 */
public final class VaultJson {

    private VaultJson() {
        throw new AssertionError("no instance");
    }

    public static String write(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"v\":").append(5).append(",\"e\":[");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"t\":").append(quoted(e.title)).append(',');
            sb.append("\"p\":").append(quoted(e.password)).append(',');
            sb.append("\"n\":").append(quoted(e.note)).append(',');
            sb.append("\"g\":").append(quoted(e.tag)).append(',');
            sb.append("\"u\":").append(e.updatedAt);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static List<Entry> read(String json) throws CryptoException {
        List<Entry> out = new ArrayList<Entry>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        Cursor cursor = new Cursor(json);
        cursor.expect('{');
        while (true) {
            String key = cursor.readKey();
            if ("e".equals(key)) {
                cursor.expect('[');
                readEntries(cursor, out);
            } else {
                cursor.skipValue();
            }
            char next = cursor.peek();
            if (next == ',') {
                cursor.next();
                continue;
            }
            break;
        }
        return out;
    }

    private static void readEntries(Cursor cursor, List<Entry> out) throws CryptoException {
        if (cursor.peek() == ']') {
            cursor.next();
            return;
        }
        while (true) {
            out.add(readEntry(cursor));
            char next = cursor.peek();
            if (next == ',') {
                cursor.next();
                continue;
            }
            cursor.expect(']');
            break;
        }
    }

    private static Entry readEntry(Cursor cursor) throws CryptoException {
        Entry entry = new Entry();
        cursor.expect('{');
        while (true) {
            String key = cursor.readKey();
            if ("t".equals(key)) {
                entry.title = cursor.readString();
            } else if ("p".equals(key)) {
                entry.password = cursor.readString();
            } else if ("n".equals(key)) {
                entry.note = cursor.readString();
            } else if ("g".equals(key)) {
                entry.tag = cursor.readString();
            } else if ("u".equals(key)) {
                entry.updatedAt = cursor.readLong();
            } else {
                cursor.skipValue();
            }
            char next = cursor.peek();
            if (next == ',') {
                cursor.next();
                continue;
            }
            cursor.expect('}');
            break;
        }
        return entry;
    }

    /** Small scanning helper; avoids regex and substring churn. */
    private static final class Cursor {
        private final String text;
        private int index;

        Cursor(String text) {
            this.text = text;
        }

        char peek() throws CryptoException {
            skipWhitespace();
            if (index >= text.length()) {
                throw new CryptoException("unexpected end of JSON");
            }
            return text.charAt(index);
        }

        void next() {
            index++;
        }

        void expect(char expected) throws CryptoException {
            if (peek() != expected) {
                throw new CryptoException("expected " + expected + " at " + index);
            }
            index++;
        }

        String readKey() throws CryptoException {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ':') {
                index++;
            }
            return key;
        }

        String readString() throws CryptoException {
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == 'n') {
                // null literal
                index += 4;
                return "";
            }
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (index >= text.length()) {
                    break;
                }
                char esc = text.charAt(index++);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (index + 4 <= text.length()) {
                            try {
                                sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                                index += 4;
                            } catch (NumberFormatException ignored) {
                                // leave the sequence out rather than abort the parse
                            }
                        }
                        break;
                    default: sb.append(esc);
                }
            }
            throw new CryptoException("unterminated string in JSON");
        }

        long readLong() throws CryptoException {
            skipWhitespace();
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if ((c >= '0' && c <= '9') || c == '-') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new CryptoException("expected a number at " + start);
            }
            try {
                return Long.parseLong(text.substring(start, index));
            } catch (NumberFormatException e) {
                throw new CryptoException("bad number", e);
            }
        }

        void skipValue() throws CryptoException {
            char c = peek();
            if (c == '"') {
                readString();
                return;
            }
            if (c == '{' || c == '[') {
                char close = c == '{' ? '}' : ']';
                int depth = 0;
                while (index < text.length()) {
                    char x = text.charAt(index++);
                    if (x == c) depth++;
                    else if (x == close) {
                        depth--;
                        if (depth == 0) return;
                    } else if (x == '"') {
                        // skip a nested string so its braces do not count
                        index--;
                        readString();
                    }
                }
                throw new CryptoException("unbalanced value");
            }
            // primitive
            while (index < text.length()) {
                char x = text.charAt(index);
                if (x == ',' || x == '}' || x == ']') {
                    return;
                }
                index++;
            }
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }
    }

    private static String quoted(String s) {
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
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
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
}
