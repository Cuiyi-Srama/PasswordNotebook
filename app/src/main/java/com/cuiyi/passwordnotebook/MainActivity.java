package com.cuiyi.passwordnotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Main screen. v4.
 *
 * Gating: the vault must be unlocked before any entry is readable. The unlock
 * prompt is mandatory and not dismissible, because the previous release would
 * happily render records using a key baked into the APK.
 */
public class MainActivity extends Activity {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int BG = 0xFF0A0A14;
    private static final int CARD = 0xFF14142A;
    private static final int TEXT = 0xFFE8E8F0;
    private static final int MUTED = 0xFF8888A0;
    private static final int ACCENT = 0xFF3DDC84;
    private static final int DANGER = 0xFFFF6B6B;

    private LinearLayout contentArea;
    private TextView[] tabs;
    private EditText searchBox;
    private LinearLayout recordList;
    private Handler handler = new Handler();

    // generator state
    private TextView passwordView;
    private TextView strengthView;
    private TextView lengthView;
    private SeekBar lengthBar;
    private Switch swUpper;
    private Switch swLower;
    private Switch swDigits;
    private Switch swCommon;
    private Switch swExtended;
    private int passwordLength = 16;

    private Runnable typingTask;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildShell();
        handler.post(new Runnable() {
            @Override
            public void run() {
                gate();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Re-lock when leaving the app so the key does not sit in memory.
        Vault.lock();
    }

    // ---------------- shell ----------------

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF101024);
        tabs = new TextView[3];
        String[] names = {"生成", "记录", "设置"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(15f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(14), dp(16), dp(14));
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            t.setTextColor(MUTED);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
            tabs[i] = t;
            bar.addView(t);
        }
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(dp(14), dp(12), dp(14), dp(24));
        scroll.addView(contentArea);
        root.addView(scroll);

        setContentView(root);
        selectTab(0);
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == index ? ACCENT : MUTED);
        }
        contentArea.removeAllViews();
        if (!Vault.isUnlocked()) {
            contentArea.addView(hint("未解锁，请重新打开应用"));
            return;
        }
        if (index == 0) {
            buildGenerator();
        } else if (index == 1) {
            buildRecords();
        } else {
            buildSettings();
        }
    }

    // ---------------- gating ----------------

    private void gate() {
        if (!Vault.exists(this)) {
            promptCreate();
        } else {
            promptUnlock();
        }
    }

    private void promptCreate() {
        final EditText p1 = passwordField("主密码（至少 8 位）");
        final EditText p2 = passwordField("再输一次");
        LinearLayout box = column();
        box.addView(p1);
        box.addView(p2);
        box.addView(hint("主密码用于加密整个数据库，不会被保存。忘记就无法恢复。\n\n首次使用建议先在“设置”里导入旧版备份。"));

        new AlertDialog.Builder(this)
                .setTitle("设置主密码")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String a = p1.getText().toString();
                        String b = p2.getText().toString();
                        if (a.length() < 8) {
                            toast("主密码至少 8 位");
                            promptCreate();
                            return;
                        }
                        if (!a.equals(b)) {
                            toast("两次输入不一致");
                            promptCreate();
                            return;
                        }
                        char[] pw = a.toCharArray();
                        try {
                            int iters = Vault.create(MainActivity.this, pw);
                            toast("已创建，迭代 " + iters + " 次");
                            selectTab(0);
                        } catch (Exception e) {
                            toast("创建失败: " + e.getMessage());
                            promptCreate();
                        } finally {
                            KeyDerivation.wipe(pw);
                        }
                    }
                })
                .show();
    }

    private void promptUnlock() {
        final EditText p1 = passwordField("主密码");
        LinearLayout box = column();
        box.addView(p1);

        new AlertDialog.Builder(this)
                .setTitle("解锁")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("解锁", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        attemptUnlock(p1.getText().toString());
                    }
                })
                .setNeutralButton("恢复备份", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        promptRestore();
                    }
                })
                .show();
    }

    private void attemptUnlock(String password) {
        char[] pw = password.toCharArray();
        try {
            boolean ok = Vault.unlock(this, pw);
            if (ok) {
                toast("已解锁");
                selectTab(0);
            } else {
                toast("主密码错误");
                promptUnlock();
            }
        } catch (CryptoException e) {
            toast("无法读取数据库: " + e.getMessage());
            selectTab(0);
        } catch (Exception e) {
            toast("解锁失败: " + e.getMessage());
            promptUnlock();
        } finally {
            KeyDerivation.wipe(pw);
        }
    }

    // ---------------- generator ----------------

    private void buildGenerator() {
        LinearLayout root = column();

        TextView out = new TextView(this);
        out.setText("--------");
        out.setTextSize(30f);
        out.setTextColor(ACCENT);
        out.setTypeface(Typeface.MONOSPACE);
        out.setGravity(Gravity.CENTER);
        out.setPadding(dp(8), dp(18), dp(8), dp(18));
        out.setBackground(box(CARD, 16));
        passwordView = out;
        root.addView(out);

        TextView strength = new TextView(this);
        strength.setTextSize(14f);
        strength.setTextColor(MUTED);
        strength.setGravity(Gravity.CENTER);
        strength.setPadding(0, dp(6), 0, dp(6));
        strengthView = strength;
        root.addView(strength);

        LinearLayout actions = row();
        actions.addView(button("重新生成", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generate();
            }
        }));
        actions.addView(button("复制", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCurrent();
            }
        }));
        actions.addView(button("保存", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptSave();
            }
        }));
        root.addView(actions);

        root.addView(section("字符集"));
        swUpper = addSwitch(root, "大写 A-Z", true);
        swLower = addSwitch(root, "小写 a-z", true);
        swDigits = addSwitch(root, "数字 0-9", true);
        swCommon = addSwitch(root, "常见符号", true);
        swExtended = addSwitch(root, "扩展符号", false);

        root.addView(section("长度"));
        LinearLayout lenRow = row();
        TextView label = new TextView(this);
        label.setText("字符数");
        label.setTextColor(TEXT);
        label.setTextSize(14f);
        lenRow.addView(label);
        lengthView = new TextView(this);
        lengthView.setText(String.valueOf(passwordLength));
        lengthView.setTextColor(ACCENT);
        lengthView.setTextSize(18f);
        lengthView.setTypeface(Typeface.MONOSPACE);
        lengthView.setPadding(dp(10), 0, 0, 0);
        lenRow.addView(lengthView);
        root.addView(lenRow);

        SeekBar bar = new SeekBar(this);
        bar.setMax(48);
        bar.setProgress(passwordLength - 8);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                passwordLength = progress + 8;
                lengthView.setText(String.valueOf(passwordLength));
                if (fromUser) {
                    generate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        lengthBar = bar;
        root.addView(bar);

        contentArea.addView(root);
        generate();
    }

    private void generate() {
        if (typingTask != null) {
            handler.removeCallbacks(typingTask);
        }
        String value;
        try {
            value = PasswordGenerator.random(passwordLength,
                    swUpper.isChecked(), swLower.isChecked(), swDigits.isChecked(),
                    swCommon.isChecked(), swExtended.isChecked());
        } catch (IllegalArgumentException e) {
            value = "请至少启用一种字符类型";
        }
        typeOut(value);
        int score = PasswordGenerator.strength(value);
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            stars.append(i < score ? "\u2605" : "\u2606");
        }
        strengthView.setText(stars.toString());
    }

    private void typeOut(final String value) {
        passwordView.setText("");
        typingTask = new Runnable() {
            @Override
            public void run() {
                int shown = passwordView.getText().length();
                if (shown < value.length()) {
                    passwordView.setText(value.substring(0, shown + 1));
                    handler.postDelayed(this, 22);
                }
            }
        };
        handler.post(typingTask);
    }

    private void copyCurrent() {
        String value = passwordView.getText().toString();
        if (value.isEmpty() || value.startsWith("请")) {
            toast("先生成密码");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("password", value));
        toast("已复制");
    }

    private void promptSave() {
        final String value = passwordView.getText().toString();
        if (value.isEmpty() || value.startsWith("请")) {
            toast("先生成密码");
            return;
        }
        final EditText title = field("名称");
        final EditText note = field("备注（可选）");
        final EditText tag = field("标签（可选）");
        LinearLayout box = column();
        box.addView(title);
        box.addView(note);
        box.addView(tag);
        new AlertDialog.Builder(this)
                .setTitle("保存密码")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Entry entry = new Entry(
                                title.getText().toString().trim(),
                                value,
                                note.getText().toString().trim(),
                                tag.getText().toString().trim());
                        if (entry.title.isEmpty()) {
                            entry.title = "未命名";
                        }
                        saveEntry(entry);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveEntry(Entry entry) {
        try {
            Vault.add(this, entry);
            toast("已保存");
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    // ---------------- records ----------------

    private void buildRecords() {
        LinearLayout root = column();

        searchBox = field("搜索名称、标签或备注");
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                refreshRecords(e.toString());
            }
        });
        root.addView(searchBox);

        recordList = new LinearLayout(this);
        recordList.setOrientation(LinearLayout.VERTICAL);
        root.addView(recordList);

        contentArea.addView(root);
        refreshRecords("");
    }

    private void refreshRecords(String query) {
        if (recordList == null) {
            return;
        }
        recordList.removeAllViews();
        List<Entry> all = Vault.entries();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (Entry entry : all) {
            if (!q.isEmpty() && !matches(entry, q)) {
                continue;
            }
            recordList.addView(card(entry));
            recordList.addView(spaceView(6));
            shown++;
        }
        if (shown == 0) {
            recordList.addView(hint(all.isEmpty() ? "暂无记录" : "没有匹配的记录"));
        }
    }

    private boolean matches(Entry e, String q) {
        return contains(e.title, q) || contains(e.tag, q) || contains(e.note, q);
    }

    private boolean contains(String s, String q) {
        return s != null && s.toLowerCase().contains(q);
    }

    private View card(final Entry entry) {
        LinearLayout card = column();
        card.setBackground(box(CARD, 12));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText(entry.titleOrFallback());
        title.setTextColor(TEXT);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        if (entry.tag != null && !entry.tag.isEmpty()) {
            TextView tag = new TextView(this);
            tag.setText(entry.tag);
            tag.setTextColor(ACCENT);
            tag.setTextSize(12f);
            card.addView(tag);
        }
        if (entry.note != null && !entry.note.isEmpty()) {
            TextView note = new TextView(this);
            note.setText(entry.note);
            note.setTextColor(MUTED);
            note.setTextSize(12f);
            card.addView(note);
        }

        LinearLayout actions = row();
        actions.setPadding(0, dp(8), 0, 0);

        final TextView masked = new TextView(this);
        masked.setText("••••••••");
        masked.setTextColor(ACCENT);
        masked.setTextSize(15f);
        masked.setTypeface(Typeface.MONOSPACE);
        masked.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(masked);
        masked.setOnClickListener(new View.OnClickListener() {
            private boolean visible;

            @Override
            public void onClick(View v) {
                visible = !visible;
                masked.setText(visible ? entry.password : "••••••••");
            }
        });

        actions.addView(button("复制", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("password", entry.password));
                toast("已复制");
            }
        }));
        actions.addView(button("编辑", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptEdit(entry);
            }
        }));
        actions.addView(button("删除", DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(entry);
            }
        }));
        card.addView(actions);
        return card;
    }

    private void promptEdit(final Entry entry) {
        final EditText title = field("名称");
        title.setText(entry.title);
        final EditText pwd = field("密码");
        pwd.setText(entry.password);
        final EditText note = field("备注");
        note.setText(entry.note);
        final EditText tag = field("标签");
        tag.setText(entry.tag);

        LinearLayout box = column();
        box.addView(title);
        box.addView(pwd);
        box.addView(note);
        box.addView(tag);

        new AlertDialog.Builder(this)
                .setTitle("编辑")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        entry.title = title.getText().toString().trim();
                        entry.password = pwd.getText().toString();
                        entry.note = note.getText().toString().trim();
                        entry.tag = tag.getText().toString().trim();
                        entry.updatedAt = System.currentTimeMillis();
                        persistAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除「" + entry.titleOrFallback() + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<Entry> list = Vault.entries();
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i) == entry || sameEntry(list.get(i), entry)) {
                                list.remove(i);
                                break;
                            }
                        }
                        try {
                            Vault.replaceAll(MainActivity.this, list);
                            toast("已删除");
                        } catch (Exception e) {
                            toast("删除失败: " + e.getMessage());
                        }
                        refreshRecords(searchBox == null ? "" : searchBox.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean sameEntry(Entry a, Entry b) {
        return a.title != null && a.title.equals(b.title)
                && a.password != null && a.password.equals(b.password);
    }

    private void persistAll() {
        try {
            Vault.replaceAll(this, Vault.entries());
            toast("已保存");
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
        refreshRecords(searchBox == null ? "" : searchBox.getText().toString());
    }

    // ---------------- settings ----------------

    private void buildSettings() {
        LinearLayout root = column();

        root.addView(section("数据"));
        root.addView(button("导入旧版备份", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptLegacyImport();
            }
        }));
        root.addView(spaceView(6));
        root.addView(button("从文件恢复加密备份", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptRestore();
            }
        }));
        root.addView(spaceView(6));
        root.addView(button("导出加密备份", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportBackup();
            }
        }));
        root.addView(spaceView(6));
        root.addView(button("导出明文 CSV（危险）", DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPlainExport();
            }
        }));

        root.addView(section("安全"));
        root.addView(button("手动锁定", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Vault.lock();
                toast("已锁定");
                gate();
            }
        }));

        root.addView(section("关于"));
        TextView about = hint("密码小本 v4.0\n\n"
                + "所有数据使用 AES-256-GCM 加密，密钥由你的主密码通过 PBKDF2-HMAC-SHA256 派生，"
                + "盐值随机、迭代次数按本机性能校准。主密码不会被保存，忘记则无法恢复。\n\n"
                + "应用不申请任何权限，不联网。备份文件是加密的，可以安全地放入网盘。");
        root.addView(about);

        contentArea.addView(root);
    }

    // ---------------- legacy import ----------------

    private void promptLegacyImport() {
        final EditText input = field("粘贴旧版备份内容");
        input.setMinLines(6);
        input.setGravity(Gravity.TOP);

        LinearLayout box = column();
        box.addView(hint("支持：旧版加密备份（含分隔符）、逐行解密的旧草稿、以及明文 TSV。"));
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("导入旧版备份")
                .setView(box)
                .setPositiveButton("解析并导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doLegacyImport(input.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doLegacyImport(String text) {
        if (text == null || text.trim().isEmpty()) {
            toast("内容为空");
            return;
        }
        LegacyImporter.Result result = LegacyImporter.parse(text);
        if (result.recovered == 0) {
            toast("没有解析到记录（" + result.summary() + "）");
            return;
        }
        List<Entry> existing = Vault.entries();
        int added = 0;
        for (Entry incoming : result.entries) {
            if (!isDuplicate(existing, incoming)) {
                existing.add(incoming);
                added++;
            }
        }
        try {
            Vault.replaceAll(this, existing);
            toast("导入 " + added + " 条（跳过重复 " + (result.recovered - added) + "）");
            selectTab(1);
        } catch (Exception e) {
            toast("写入失败: " + e.getMessage());
        }
    }

    private boolean isDuplicate(List<Entry> list, Entry candidate) {
        for (Entry e : list) {
            if (e.title != null && e.title.equals(candidate.title)
                    && e.password != null && e.password.equals(candidate.password)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- backup ----------------

    private void exportBackup() {
        try {
            String sealed = Vault.exportSealed(this);
            File dir = new File("/sdcard/Download");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, "PasswordNotebook_v4_backup.txt");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                OutputStreamWriter writer = new OutputStreamWriter(fos, UTF8);
                writer.write(sealed);
                writer.flush();
            } finally {
                fos.close();
            }
            toast("已导出到 " + out.getAbsolutePath());
        } catch (Exception e) {
            toast("导出失败: " + e.getMessage());
        }
    }

    private void confirmPlainExport() {
        new AlertDialog.Builder(this)
                .setTitle("导出明文？")
                .setMessage("明文 CSV 会包含所有密码，任何拿到该文件的 App 都能读取。\n\n"
                        + "仅在你需要迁移到其他密码管理器时使用，用完立即删除。")
                .setPositiveButton("我知道风险，导出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        exportPlain();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportPlain() {
        try {
            File dir = new File("/sdcard/Download");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, "PasswordNotebook_plain.csv");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                OutputStreamWriter writer = new OutputStreamWriter(fos, UTF8);
                writer.write("title,password,note,tag,updatedAt\n");
                for (Entry e : Vault.entries()) {
                    writer.write(csv(e.title) + ',' + csv(e.password) + ','
                            + csv(e.note) + ',' + csv(e.tag) + ',' + e.updatedAt + '\n');
                }
                writer.flush();
            } finally {
                fos.close();
            }
            toast("已导出明文 CSV，请尽快删除");
        } catch (Exception e) {
            toast("导出失败: " + e.getMessage());
        }
    }

    private String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private void promptRestore() {
        final EditText input = field("粘贴加密备份内容");
        input.setMinLines(6);
        input.setGravity(Gravity.TOP);
        final EditText password = passwordField("该备份的主密码");

        LinearLayout box = column();
        box.addView(input);
        box.addView(password);
        box.addView(hint("内容为 PasswordNotebook_v4_backup.txt 的全文。注意：导入会覆盖当前数据。"));

        new AlertDialog.Builder(this)
                .setTitle("恢复备份")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        char[] pw = password.getText().toString().toCharArray();
                        try {
                            int count = Vault.importSealed(MainActivity.this, input.getText().toString(), pw);
                            toast("已恢复 " + count + " 条记录");
                            selectTab(1);
                        } catch (Exception e) {
                            toast("恢复失败: " + e.getMessage());
                            gate();
                        } finally {
                            KeyDerivation.wipe(pw);
                        }
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        gate();
                    }
                })
                .show();
    }

    // ---------------- ui helpers ----------------

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(13f);
        t.setPadding(0, dp(20), 0, dp(8));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(13f);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView button(String text, int color, View.OnClickListener listener) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(box(CARD, 10));
        t.setClickable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        t.setOnClickListener(listener);
        return t;
    }

    private TextView button(String text, int color, View.OnClickListener listener, boolean full) {
        TextView t = button(text, color, listener);
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private EditText field(String hintText) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(14f);
        e.setBackground(box(CARD, 8));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private EditText passwordField(String hintText) {
        EditText e = field(hintText);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private Switch addSwitch(LinearLayout parent, String label, boolean defaultValue) {
        LinearLayout row = row();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(14f);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);
        final Switch sw = new Switch(this);
        sw.setChecked(defaultValue);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                generate();
            }
        });
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private View spaceView(int heightDp) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(heightDp)));
        return s;
    }

    private GradientDrawable box(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
