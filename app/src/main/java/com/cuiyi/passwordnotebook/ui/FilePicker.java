package com.cuiyi.passwordnotebook.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Picks a text file from a folder the user can browse.
 *
 * Deliberately not using the Storage Access Framework: the app only ever reads
 * its own backup files, and SAF would add a permission prompt and an opaque
 * URI for no benefit. The user starts in Download and can walk up and down.
 */
public final class FilePicker {

    /** Called with the chosen file, or null when the user cancels. */
    public interface Listener {
        void onPicked(File file);
    }

    private static final String[] TEXT_SUFFIXES = {".txt", ".csv", ".json", ".bak"};

    private FilePicker() {
        throw new AssertionError("no instance");
    }

    public static void show(final Context context, final Listener listener) {
        File start = new File("/sdcard/Download");
        if (!start.isDirectory()) {
            start = new File("/sdcard");
        }
        browse(context, start, listener);
    }

    private static void browse(final Context context, final File dir, final Listener listener) {
        final List<File> items = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            items.add(parent);
            labels.add("..  " + parent.getAbsolutePath());
        }

        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File child : children) {
                String name = child.getName();
                if (name.startsWith(".")) {
                    continue;
                }
                if (child.isDirectory()) {
                    items.add(child);
                    labels.add("[目录]  " + name);
                } else if (looksLikeText(name)) {
                    items.add(child);
                    labels.add("              " + name + "   (" + child.length() + " 字节)");
                }
            }
        }

        final String title = dir.getAbsolutePath();
        if (labels.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage("这个目录里没有可读取的文本文件。")
                    .setPositiveButton("返回上级", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            File parent = dir.getParentFile();
                            if (parent != null) {
                                browse(context, parent, listener);
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        File chosen = items.get(which);
                        if (chosen.isDirectory()) {
                            browse(context, chosen, listener);
                        } else {
                            listener.onPicked(chosen);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean looksLikeText(String name) {
        String lower = name.toLowerCase();
        for (String suffix : TEXT_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
