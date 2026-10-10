package com.cuiyi.passwordnotebook.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Writes a text file to a location the user chooses.
 *
 * Uses ACTION_CREATE_DOCUMENT (Storage Access Framework), the write side of what
 * {@link FilePicker} does for reads.
 *
 * The previous implementation guessed at a path instead: it tried
 * /sdcard/Download first and fell back to getExternalFilesDir() when that threw.
 * Under scoped storage the first candidate always throws, because writing to the
 * public Downloads folder needs a permission this app deliberately does not
 * request. So every export silently landed in
 * /storage/emulated/0/Android/data/<pkg>/files/, which the system file manager
 * cannot open on Android 11 and later. The file existed and the confirmation
 * dialog showed its path, but it could not be reached — which made the backup
 * feature look functional while being useless exactly when it mattered.
 *
 * With SAF the destination is picked by the user, the grant is scoped to that
 * one document, and no storage permission is required.
 *
 * The caller must stash the text it wants written and forward onActivityResult
 * to {@link #handleResult}.
 */
public final class FileSaver {

    /** Request code the host activity should watch for. */
    public static final int REQUEST_CODE = 0x50D2;

    private FileSaver() {
        throw new AssertionError("no instance");
    }

    /**
     * Opens the system save dialog. The suggested name is only a default; the
     * user may rename it or pick any folder, including one synced to a cloud
     * drive.
     */
    public static void show(Activity activity, String fileName, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        activity.startActivityForResult(intent, REQUEST_CODE);
    }

    /**
     * Returns the chosen destination, or null when the result is not ours or the
     * user backed out. A null return is not an error and needs no message.
     */
    public static Uri handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            return null;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return null;
        }
        return data.getData();
    }

    /**
     * Writes the whole string as UTF-8, replacing anything already at the
     * destination. Truncation matters: the system dialog may offer an existing
     * file, and appending a second backup onto the first would produce a file
     * that parses as garbage.
     */
    public static void write(Activity activity, Uri uri, String text) throws Exception {
        OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) {
            throw new IllegalStateException("无法写入所选位置");
        }
        try {
            out.write(text.getBytes(Charset.forName("UTF-8")));
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Exception ignored) {
                // Closing a stream that already failed is not worth reporting.
            }
        }
    }

    /** Last path segment, for telling the user what the file ended up called. */
    public static String displayName(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) {
            return uri.toString();
        }
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }
}
