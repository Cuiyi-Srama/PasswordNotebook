package com.cuiyi.passwordnotebook;

/** One vault record. */
public class Entry {

    public String title;
    public String password;
    public String note;
    public String tag;
    public long updatedAt;

    public Entry() {
        this.updatedAt = System.currentTimeMillis();
    }

    public Entry(String title, String password, String note, String tag) {
        this.title = title;
        this.password = password;
        this.note = note;
        this.tag = tag;
        this.updatedAt = System.currentTimeMillis();
    }

    /** True when this entry carries no useful content. */
    public boolean isBlank() {
        return isBlank(title) && isBlank(password) && isBlank(note) && isBlank(tag);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public String titleOrFallback() {
        return isBlank(title) ? "未命名" : title;
    }
}
