package com.cuiyi.passwordnotebook.crypto;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Reads and writes the on-disk vault container.
 *
 * Layout:
 *
 *   PWDNB5
 *   kdf=pbkdf2-hmac-sha256
 *   iter=600000
 *   salt=<base64>
 *   check=<base64>
 *   ---
 *   <base64 ciphertext of the JSON body>
 *
 * The header is deliberately readable without the password. That lets the app
 * tell "wrong password" apart from "corrupt file" and means the KDF settings
 * can be changed later without stranding existing vaults.
 *
 * Writes go to a sibling temp file and are then renamed over the target, so a
 * crash part way through cannot leave a half-written vault behind.
 */
public final class VaultFile {

    public static final int FORMAT_VERSION = 5;
    public static final String MAGIC = "PWDNB5";

    private static final String SEPARATOR = "---";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private VaultFile() {
        throw new AssertionError("no instance");
    }

    /** Everything the app needs before it has the password. */
    public static class Header {
        public String kdf;
        public int iterations;
        public byte[] salt;
        public String check;
    }

    /** Build the full file text. */
    public static String compose(byte[] saltBytes, int iterations,
                                 String encryptedCheck, String encryptedBody) {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("kdf=pbkdf2-hmac-sha256").append('\n');
        sb.append("iter=").append(iterations).append('\n');
        sb.append("salt=").append(Base64.encodeToString(saltBytes, Base64.NO_WRAP)).append('\n');
        sb.append("check=").append(encryptedCheck).append('\n');
        sb.append(SEPARATOR).append('\n');
        sb.append(encryptedBody);
        return sb.toString();
    }

    /** Parse the plaintext header, or null when this is not a v5 vault. */
    public static Header readHeader(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n");
        if (lines.length < 6 || !MAGIC.equals(lines[0].trim())) {
            return null;
        }
        Header header = new Header();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (SEPARATOR.equals(line)) {
                break;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if ("kdf".equals(name)) {
                header.kdf = value;
            } else if ("iter".equals(name)) {
                try {
                    header.iterations = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if ("salt".equals(name)) {
                try {
                    header.salt = Base64.decode(value, Base64.NO_WRAP);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            } else if ("check".equals(name)) {
                header.check = value;
            }
        }
        if (header.salt == null || header.salt.length < 8
                || header.iterations <= 0 || header.check == null) {
            return null;
        }
        return header;
    }

    /** The encrypted body, or null when the separator is missing. */
    public static String readBody(String text) {
        if (text == null) {
            return null;
        }
        int idx = text.indexOf("\n" + SEPARATOR + "\n");
        if (idx < 0) {
            return null;
        }
        return text.substring(idx + SEPARATOR.length() + 2).trim();
    }

    public static String read(File file) throws IOException {
        if (!file.exists()) {
            return "";
        }
        FileInputStream in = new FileInputStream(file);
        try {
            InputStreamReader reader = new InputStreamReader(in, UTF8);
            StringBuilder sb = new StringBuilder((int) Math.max(128, file.length()));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    /** Write atomically: temp file, fsync, then rename over the target. */
    public static void write(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        File temp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            OutputStreamWriter writer = new OutputStreamWriter(out, UTF8);
            writer.write(text);
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
}
