package com.cuiyi.passwordnotebook.data;

/** One stored credential. */
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

    /** True when there is nothing worth storing. */
    public boolean isBlank() {
        return blank(title) && blank(password) && blank(note) && blank(tag);
    }

    public String displayTitle() {
        return blank(title) ? "未命名" : title;
    }

    /** True when title and password both match the other entry. */
    public boolean sameAs(Entry other) {
        if (other == null) {
            return false;
        }
        return equals(title, other.title) && equals(password, other.password);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean equals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }
}
