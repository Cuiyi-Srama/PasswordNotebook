package com.cuiyi.passwordnotebook.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Opens the system file picker and reads the chosen file as UTF-8 text.
 *
 * Uses ACTION_OPEN_DOCUMENT (Storage Access Framework) rather than a custom
 * browser. The system grants read access to exactly the one file the user taps
 * and to nothing else, so the app needs no storage permission at all and never
 * gains broad filesystem access. A hand-rolled browser built on java.io.File
 * cannot see most of the filesystem under scoped storage and would also need a
 * blanket permission, which is strictly worse on both counts.
 *
 * The caller must forward onActivityResult to {@link #handleResult}.
 */
public final class FilePicker {

    /** Request code the host activity should watch for. */
    public static final int REQUEST_CODE = 0x50D1;

    /** Hard ceiling so a mistyped pick cannot exhaust memory. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /** Called with the file text, or null when reading failed or was cancelled. */
    public interface Listener {
        void onPicked(String text, String displayName);
    }

    private FilePicker() {
        throw new AssertionError("no instance");
    }

    /**
     * Launches the system picker. Text files are offered first but the user may
     * switch to "all files", because backups are sometimes renamed or saved
     * with an unexpected extension.
     */
    public static void show(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "text/*",
                "application/json",
                "application/octet-stream"
        });
        try {
            activity.startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception e) {
            // No file manager installed, or the OEM blocked the intent.
            throw new IllegalStateException("无法打开系统文件选择器：" + e.getMessage(), e);
        }
    }

    /**
     * Reads the picked document. Returns without calling the listener when the
     * result is not ours or the user cancelled.
     */
    public static void handleResult(Activity activity, int requestCode, int resultCode,
                                    Intent data, Listener listener) {
        if (requestCode != REQUEST_CODE) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            listener.onPicked(null, null);
            return;
        }
        String name = displayName(activity, uri);
        try {
            String text = readAll(activity, uri);
            listener.onPicked(text, name);
        } catch (Exception e) {
            listener.onPicked(null, name);
        }
    }

    private static String displayName(Activity activity, Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) {
            return uri.toString();
        }
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    private static String readAll(Activity activity, Uri uri) throws Exception {
        InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("无法打开所选文件");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IllegalStateException("文件过大，超过 4 MB 上限");
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
