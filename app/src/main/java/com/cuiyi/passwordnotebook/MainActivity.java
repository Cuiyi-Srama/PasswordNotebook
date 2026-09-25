package com.cuiyi.passwordnotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Random;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private LinearLayout contentArea;
    private Handler handler = new Handler(Looper.getMainLooper());
    private String currentTab = "generate";

    // ==================== Generate Tab State ====================
    private TextView etCoreWord, tvGenResult;
    private EditText etCustomSalt;
    private CheckBox swDigits, swUpper, swLower, swSpecialCommon, swSpecialExt, swSalt, swWeekDep;
    private SeekBar seekLen;
    private TextView tvLen;

    // ==================== Records Tab State ====================
    private LinearLayout recordList;
    private EditText etSearch;

    // ==================== Lifecycle ====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(VaultMigrator.PREF_FILE, MODE_PRIVATE);
        initUI();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                requestUnlock();
            }
        });
    }

    // ==================== Vault Unlock ====================

    /** 请求解锁：新建、迁移、解锁三种路径 */
    private void requestUnlock() {
        if (Vault.isUnlocked()) {
            return;
        }
        if (Vault.isNewVault(prefs)) {
            String raw = prefs.getString(VaultMigrator.KEY_RECORDS, "");
            if (raw != null && !raw.isEmpty()) {
                promptMigration();
            } else {
                promptCreatePassword();
            }
        } else {
            promptUnlock();
        }
    }

    /** 首次创建主密码 */
    private void promptCreatePassword() {
        final EditText p1 = new EditText(this);
        p1.setHint("主密码（至少 8 位，务必牢记！丢失无法恢复）");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText p2 = new EditText(this);
        p2.setHint("再次输入");
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(p1);
        box.addView(p2);
        new AlertDialog.Builder(this)
            .setTitle("设置主密码")
            .setMessage("抠纵密码将用于加密整个熵库。"
                + "它不会被保存，一旦遗忘将无法解密数据。")
            .setCancelable(false)
            .setView(box)
            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String a = p1.getText().toString();
                    String b = p2.getText().toString();
                    if (a.length() < 8) {
                        toast("主密码至少 8 位");
                        promptCreatePassword();
                        return;
                    }
                    if (!a.equals(b)) {
                        toast("两次输入不一致");
                        promptCreatePassword();
                        return;
                    }
                    char[] mp = a.toCharArray();
                    try {
                        Vault.initNew(prefs, mp);
                        toast("主密码已设置");
                        refreshRecords();
                    } catch (Exception e) {
                        toast("初始化失败：" + e.getMessage());
                    } finally {
                        KeyDerivation.wipe(mp);
                        a = null;
                        b = null;
                    }
                }
            })
            .show();
    }

    /** 旧数据迁移向导 */
    private void promptMigration() {
        new AlertDialog.Builder(this)
            .setTitle("需要迁移旧数据")
            .setMessage("检测到旧版本数据。旧版本使用公开常量派生密钥，"
                + "存在安全风险。迁移后将改用您的主密码保护。\n\n"
                + "迁移过程不会丢失数据（事务式：失败则保持原样）。\n\n"
                + "⚠ 注意：旧数据在迁移前处于低安全状态，"
                + "建议迁移后立即更换所有相关密码。")
            .setCancelable(false)
            .setPositiveButton("立即迁移", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    promptMigrationPassword();
                }
            })
            .show();
    }

    private void promptMigrationPassword() {
        final EditText p1 = new EditText(this);
        p1.setHint("设置新主密码（至少 8 位）");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText p2 = new EditText(this);
        p2.setHint("再次输入");
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(p1);
        box.addView(p2);
        new AlertDialog.Builder(this)
            .setTitle("设置新主密码")
            .setCancelable(false)
            .setView(box)
            .setPositiveButton("开始迁移", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String a = p1.getText().toString();
                    String b = p2.getText().toString();
                    if (a.length() < 8) { toast("主密码至少 8 位"); promptMigrationPassword(); return; }
                    if (!a.equals(b)) { toast("两次输入不一致"); promptMigrationPassword(); return; }
                    char[] mp = a.toCharArray();
                    try {
                        String oldCore = prefs.getString("coreWord", "");
                        String oldSalt = prefs.getString("customSalt", "");
                        String err = Vault.migrate(prefs, mp, oldCore, oldSalt);
                        if (err != null) {
                            toast(err);
                            return;
                        }
                        toast("迁移完成");
                        refreshRecords();
                    } finally {
                        KeyDerivation.wipe(mp);
                        a = null;
                        b = null;
                    }
                }
            })
            .show();
    }

    /** 已有熵库解锁 */
    private void promptUnlock() {
        final EditText p1 = new EditText(this);
        p1.setHint("主密码");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(p1);
        new AlertDialog.Builder(this)
            .setTitle("解锁熵库")
            .setCancelable(false)
            .setView(box)
            .setPositiveButton("解锁", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String a = p1.getText().toString();
                    char[] mp = a.toCharArray();
                    String err;
                    try {
                        err = Vault.unlock(prefs, mp);
                    } finally {
                        KeyDerivation.wipe(mp);
                        a = null;
                    }
                    if (err == null) {
                        toast("已解锁");
                        refreshRecords();
                    } else {
                        toast(err);
                        promptUnlock();
                    }
                }
            })
            .show();
    }

    // ==================== UI Initialization ====================
    private void initUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0a0a0a);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));

        // Matrix Rain Background
        LiquidBackgroundView bg = new LiquidBackgroundView(this);
        bg.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bg);

        // Tab Bar
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        tabBar.setBackgroundColor(0xcc0a1220);

        String[] tabs = {"生成", "记录", "设置"};
        for (String t : tabs) {
            Button btn = new Button(this);
            btn.setText(t);
            btn.setTextSize(14);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(16), dp(8), dp(16), dp(8));
            btn.setBackgroundColor(0xff1a3a2a);
            btn.setTextColor(0xffd0ffd0);
            btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            btn.setOnClickListener(v -> switchTab(t));
            tabBar.addView(btn);
        }
        root.addView(tabBar);

        // Scrollable Content
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 2));
        sv.setPadding(dp(12), dp(8), dp(12), dp(24));

        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);

        sv.addView(contentArea);
        root.addView(sv);

        setContentView(root);
        switchTab("生成");
    }

    private void switchTab(String tab) {
        currentTab = tab;
        contentArea.removeAllViews();
        switch (tab) {
            case "生成": buildGenTab(); break;
            case "记录": buildRecTab(); break;
            case "设置": buildSetTab(); break;
        }
    }

    // ==================== Utility Methods ====================
    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void space(LinearLayout parent, int dp) {
        TextView sp = new TextView(this);
        sp.setHeight(dp(dp));
        parent.addView(sp);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ==================== Generate Tab ====================
    private void buildGenTab() {
        LinearLayout pg = new LinearLayout(this);
        pg.setOrientation(LinearLayout.VERTICAL);
        pg.setBackgroundColor(0x661a3a2a);
        pg.setPadding(dp(16), dp(16), dp(16), dp(24));
        pg.setBackgroundColor(0x661a3a2a);

        // Tab switching: Hashed / Periodic / Random
        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        String[] modes = {"哈希", "周期", "随机"};
        for (String m : modes) {
            Button btn = new Button(this);
            btn.setText(m);
            btn.setTextSize(13);
            btn.setPadding(dp(12), dp(6), dp(12), dp(6));
            btn.setBackgroundColor(0xff0a2a1a);
            btn.setTextColor(0xffa0ffa0);
            btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            btn.setOnClickListener(v -> {
                currentMode = m;
                buildGenMode(pg, m);
            });
            modeBar.addView(btn);
        }
        pg.addView(modeBar);
        space(pg, 12);

        // Result display
        tvGenResult = new TextView(this);
        tvGenResult.setText("点击生成密码");
        tvGenResult.setTextColor(0xff80ff80);
        tvGenResult.setTextSize(18);
        tvGenResult.setGravity(Gravity.CENTER);
        tvGenResult.setPadding(dp(12), dp(16), dp(12), dp(16));
        tvGenResult.setBackgroundColor(0x44000000);
        pg.addView(tvGenResult);

        space(pg, 8);

        // Copy & Regenerate buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnCopy = new Button(this);
        btnCopy.setText("复制");
        btnCopy.setTextSize(13);
        btnCopy.setBackgroundColor(0xff1a5a3a);
        btnCopy.setTextColor(0xffd0ffd0);
        btnCopy.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        btnCopy.setOnClickListener(v -> {
            String txt = tvGenResult.getText().toString();
            if (!txt.isEmpty() && !txt.equals("点击生成密码")) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("pwd", txt));
                toast("已复制");
            }
        });
        btnRow.addView(btnCopy);

        Button btnGen = new Button(this);
        btnGen.setText("生成");
        btnGen.setTextSize(13);
        btnGen.setBackgroundColor(0xff1a5a3a);
        btnGen.setTextColor(0xffd0ffd0);
        btnGen.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        btnGen.setOnClickListener(v -> doGen());
        btnRow.addView(btnGen);

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setTextSize(13);
        btnSave.setBackgroundColor(0xff1a5a3a);
        btnSave.setTextColor(0xffd0ffd0);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        btnSave.setOnClickListener(v -> {
            String pwd = tvGenResult.getText().toString();
            if (pwd.isEmpty() || pwd.equals("点击生成密码")) { toast("先生成密码"); return; }
            showSaveDialog(pwd);
        });
        btnRow.addView(btnSave);
        pg.addView(btnRow);

        space(pg, 12);
        pg.addView(buildGenSettings());
        contentArea.addView(pg);
    }

    private String currentMode = "哈希";

    private void buildGenMode(LinearLayout parent, String mode) {
        currentMode = mode;
        // Just update the display - the core gen logic uses currentMode
        doGen();
    }

    private LinearLayout buildGenSettings() {
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);

        // Core word input
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView lbl1 = new TextView(this);
        lbl1.setText("核心词");
        lbl1.setTextColor(0xffa0ffa0);
        lbl1.setTextSize(13);
        lbl1.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(lbl1);
        etCoreWord = new TextView(this);
        String cw = Vault.getCoreWord(prefs);
        etCoreWord.setText(cw != null ? cw : "");
        etCoreWord.setTextColor(0xffd0ffd0);
        etCoreWord.setTextSize(14);
        etCoreWord.setPadding(dp(8), dp(4), dp(8), dp(4));
        etCoreWord.setBackgroundColor(0x44000000);
        etCoreWord.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
        etCoreWord.setFocusable(true);
        etCoreWord.setClickable(true);
        etCoreWord.setOnClickListener(v -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("核心词");
            final EditText input = new EditText(this);
            input.setText(etCoreWord.getText().toString());
            input.setTextColor(0xffd0ffd0);
            b.setView(input);
            b.setPositiveButton("确定", (d, w) -> {
                String val = input.getText().toString().trim();
                etCoreWord.setText(val);
                if (!Vault.putCoreWord(prefs, val)) {
                    toast("核心词加密存储失败");
                }
            });
            b.show();
        });
        row1.addView(etCoreWord);
        settings.addView(row1);

        // Custom Salt
        LinearLayout saltRow = new LinearLayout(this);
        saltRow.setOrientation(LinearLayout.HORIZONTAL);
        swSalt = new CheckBox(this);
        swSalt.setText("盐");
        swSalt.setTextColor(0xffa0ffa0);
        swSalt.setTextSize(13);
        swSalt.setChecked(prefs.getBoolean("useSalt", false));
        swSalt.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("useSalt", isChecked).commit());
        saltRow.addView(swSalt);
        etCustomSalt = new EditText(this);
        String cs = Vault.getCustomSalt(prefs);
        etCustomSalt.setText(cs != null ? cs : "");
        etCustomSalt.setTextColor(0xffd0ffd0);
        etCustomSalt.setTextSize(12);
        etCustomSalt.setPadding(dp(6), dp(4), dp(6), dp(4));
        etCustomSalt.setBackgroundColor(0x44000000);
        etCustomSalt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
        saltRow.addView(etCustomSalt);
        Button btnRandomSalt = new Button(this);
        btnRandomSalt.setText("随机");
        btnRandomSalt.setTextSize(11);
        btnRandomSalt.setBackgroundColor(0xff1a3a2a);
        btnRandomSalt.setTextColor(0xffa0ffa0);
        btnRandomSalt.setOnClickListener(v -> {
            String chars = "!@#$%^&*";
            Random r = new Random();
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i++) s.append(chars.charAt(r.nextInt(chars.length())));
            etCustomSalt.setText(s.toString());
            if (!Vault.putCustomSalt(prefs, s.toString())) {
                toast("盐加密存储失败");
            }
        });
        saltRow.addView(btnRandomSalt);
        settings.addView(saltRow);

        // Week dependency
        swWeekDep = new CheckBox(this);
        swWeekDep.setText("周依赖性");
        swWeekDep.setTextColor(0xffa0ffa0);
        swWeekDep.setTextSize(13);
        swWeekDep.setChecked(prefs.getBoolean("weekDependent", true));
        swWeekDep.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("weekDependent", isChecked).commit());
        settings.addView(swWeekDep);

        // Symbol options
        LinearLayout symRow = new LinearLayout(this);
        symRow.setOrientation(LinearLayout.HORIZONTAL);
        swDigits = new CheckBox(this);
        swDigits.setText("数字");
        swDigits.setTextColor(0xffa0ffa0);
        swDigits.setTextSize(13);
        swDigits.setChecked(prefs.getBoolean("rand_digits", true));
        swDigits.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("rand_digits", isChecked).commit());
        symRow.addView(swDigits);

        swUpper = new CheckBox(this);
        swUpper.setText("大写");
        swUpper.setTextColor(0xffa0ffa0);
        swUpper.setTextSize(13);
        swUpper.setChecked(prefs.getBoolean("rand_upper", true));
        swUpper.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("rand_upper", isChecked).commit());
        symRow.addView(swUpper);

        swLower = new CheckBox(this);
        swLower.setText("小写");
        swLower.setTextColor(0xffa0ffa0);
        swLower.setTextSize(13);
        swLower.setChecked(prefs.getBoolean("rand_lower", true));
        swLower.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("rand_lower", isChecked).commit());
        symRow.addView(swLower);
        settings.addView(symRow);

        LinearLayout symRow2 = new LinearLayout(this);
        symRow2.setOrientation(LinearLayout.HORIZONTAL);
        swSpecialCommon = new CheckBox(this);
        swSpecialCommon.setText("常见符号 !@#");
        swSpecialCommon.setTextColor(0xffa0ffa0);
        swSpecialCommon.setTextSize(12);
        swSpecialCommon.setChecked(prefs.getBoolean("rand_sc", true));
        swSpecialCommon.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("rand_sc", isChecked).commit());
        symRow2.addView(swSpecialCommon);

        swSpecialExt = new CheckBox(this);
        swSpecialExt.setText("扩展符号 <>[");
        swSpecialExt.setTextColor(0xffa0ffa0);
        swSpecialExt.setTextSize(12);
        swSpecialExt.setChecked(prefs.getBoolean("rand_su", false));
        swSpecialExt.setOnCheckedChangeListener((b, isChecked) -> prefs.edit().putBoolean("rand_su", isChecked).commit());
        symRow2.addView(swSpecialExt);
        settings.addView(symRow2);

        // Password length slider
        LinearLayout lenRow = new LinearLayout(this);
        lenRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblLen = new TextView(this);
        lblLen.setText("长度");
        lblLen.setTextColor(0xffa0ffa0);
        lblLen.setTextSize(13);
        lenRow.addView(lblLen);
        tvLen = new TextView(this);
        int savedLen = prefs.getInt("pwdLen", 16);
        tvLen.setText(String.valueOf(savedLen));
        tvLen.setTextColor(0xffd0ffd0);
        tvLen.setTextSize(13);
        tvLen.setPadding(dp(8), 0, dp(8), 0);
        lenRow.addView(tvLen);
        seekLen = new SeekBar(this);
        seekLen.setMax(64);
        seekLen.setProgress(savedLen);
        seekLen.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
        seekLen.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean f) {
                if (p < 4) p = 4;
                tvLen.setText(String.valueOf(p));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt("pwdLen", sb.getProgress()).commit();
            }
        });
        lenRow.addView(seekLen);

        // Strength indicator
        TextView tvStr = new TextView(this);
        tvStr.setTextColor(0xffffa0a0);
        tvStr.setTextSize(13);
        tvStr.setPadding(dp(8), 0, 0, 0);
        tvStr.setTag("strength");
        lenRow.addView(tvStr);
        settings.addView(lenRow);

        return settings;
    }

    /** 获取或创建站点随机盐（存于 SharedPreferences，非机密） */
    private byte[] getOrCreateSiteSalt(String siteId) throws Exception {
        String key = "siteSalt_" + siteId;
        String b64 = prefs.getString(key, null);
        if (b64 != null && !b64.isEmpty()) {
            return android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
        }
        byte[] s = PasswordGenerator.newSiteSalt();
        prefs.edit().putString(key, android.util.Base64.encodeToString(s, android.util.Base64.NO_WRAP)).commit();
        return s;
    }

    private void doGen() {
        String coreWord = etCoreWord.getText().toString().trim();
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int week = cal.get(Calendar.WEEK_OF_YEAR);
        String result = "";

        switch (currentMode) {
            case "哈希":
                if (coreWord.isEmpty()) { toast("请输入核心词"); return; }
                try {
                    byte[] s1 = getOrCreateSiteSalt("hashed");
                    result = PasswordGenerator.generateDerived(coreWord, "hashed", s1, year, week, 16);
                } catch (Exception e) {
                    toast("生成失败：" + e.getMessage());
                    return;
                }
                break;
            case "周期":
                String salt = swSalt.isChecked() ? etCustomSalt.getText().toString() : "";
                try {
                    byte[] s2 = getOrCreateSiteSalt("periodic");
                    result = PasswordGenerator.generateDerived(salt, "periodic", s2, year, week, 10);
                } catch (Exception e) {
                    toast("生成失败：" + e.getMessage());
                    return;
                }
                break;
            case "随机":
                int len = seekLen.getProgress();
                if (len < 4) len = 4;
                result = PasswordGenerator.generateRandom(len,
                    swUpper.isChecked(), swLower.isChecked(), swDigits.isChecked(),
                    swSpecialCommon.isChecked(), swSpecialExt.isChecked());
                break;
        }

        tvGenResult.setText(result);

        // Update strength
        LinearLayout parent = (LinearLayout) tvGenResult.getParent();
        if (parent != null) {
            View strView = parent.findViewWithTag("strength");
            if (strView instanceof TextView) {
                int score = PasswordGenerator.evaluateStrength(result);
                StringBuilder stars = new StringBuilder();
                for (int i = 0; i < 5; i++) stars.append(i < score ? "★" : "☆");
                ((TextView) strView).setText(stars.toString());
            }
        }
    }

    // ==================== Records Tab ====================
    private void buildRecTab() {
        LinearLayout pg = new LinearLayout(this);
        pg.setOrientation(LinearLayout.VERTICAL);

        // Search bar
        etSearch = new EditText(this);
        etSearch.setHint("搜索记录...");
        etSearch.setTextColor(0xffd0ffd0);
        etSearch.setHintTextColor(0x88a0ffa0);
        etSearch.setBackgroundColor(0x441a3a2a);
        etSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        etSearch.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        pg.addView(etSearch);

        space(pg, 8);

        // Record list
        recordList = new LinearLayout(this);
        recordList.setOrientation(LinearLayout.VERTICAL);
        refreshRecords();
        pg.addView(recordList);

        contentArea.addView(pg);
    }

    private void refreshRecords() {
        if (recordList == null) return;
        recordList.removeAllViews();
        if (!Vault.isUnlocked()) {
            recordList.removeAllViews();
            TextView lk = new TextView(this);
            lk.setText("熵库已锁定，请先输入主密码");
            lk.setTextColor(0x88a0ffa0);
            lk.setTextSize(14);
            lk.setGravity(Gravity.CENTER);
            lk.setPadding(0, dp(24), 0, dp(24));
            recordList.addView(lk);
            return;
        }
        String raw = prefs.getString(VaultMigrator.KEY_RECORDS, "");
        if (raw.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无记录，在生成页保存密码");
            empty.setTextColor(0x88a0ffa0);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            recordList.addView(empty);
            return;
        }

        String[] lines = raw.split("\n");
        String query = etSearch != null ? etSearch.getText().toString().toLowerCase() : "";

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String name = "";
            String detail = line;
            if (line.contains("\u2e41")) {
                // Encrypted line - try to decrypt just the name
                String[] parts = line.split("\u2e41");
                if (parts.length > 0) name = parts[0];
                detail = line;
            } else if (line.contains("\t") || line.contains(",")) {
                String sep = line.contains("\t") ? "\t" : ",";
                String[] parts = line.split(sep);
                name = parts.length > 0 ? parts[0].trim() : line;
            } else {
                name = line;
            }

            if (!query.isEmpty() && !name.toLowerCase().contains(query)) continue;

            final String fLine = line;
            final String fName = name;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0x661a3a2a);
            card.setPadding(dp(12), dp(8), dp(12), dp(8));

            TextView tvName = new TextView(this);
            tvName.setText(name);
            tvName.setTextColor(0xffd0ffd0);
            tvName.setTextSize(15);
            card.addView(tvName);

            TextView tvDetail = new TextView(this);
            tvDetail.setText(detail.length() > 40 ? detail.substring(0, 40) + "..." : detail);
            tvDetail.setTextColor(0x88a0ffa0);
            tvDetail.setTextSize(11);
            card.addView(tvDetail);

            // Action buttons
            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnEdit = new Button(this);
            btnEdit.setText("编辑");
            btnEdit.setTextSize(11);
            btnEdit.setBackgroundColor(0xff1a3a2a);
            btnEdit.setTextColor(0xffa0ffa0);
            btnEdit.setPadding(dp(8), dp(4), dp(8), dp(4));
            btnEdit.setOnClickListener(v -> editRecord(fLine));
            actRow.addView(btnEdit);

            Button btnDel = new Button(this);
            btnDel.setText("删除");
            btnDel.setTextSize(11);
            btnDel.setBackgroundColor(0xff3a1a1a);
            btnDel.setTextColor(0xffffa0a0);
            btnDel.setPadding(dp(8), dp(4), dp(8), dp(4));
            btnDel.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("确定删除「" + fName + "」？")
                    .setPositiveButton("删除", (d, w) -> delRecord(fLine))
                    .setNegativeButton("取消", null)
                    .show();
            });
            actRow.addView(btnDel);

            Button btnCopy = new Button(this);
            btnCopy.setText("复制密码");
            btnCopy.setTextSize(11);
            btnCopy.setBackgroundColor(0xff1a3a2a);
            btnCopy.setTextColor(0xffa0ffa0);
            btnCopy.setPadding(dp(8), dp(4), dp(8), dp(4));
            btnCopy.setOnClickListener(v -> {
                String pwd = extractPassword(fLine);
                if (pwd != null) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("pwd", pwd));
                    toast("密码已复制");
                }
            });
            actRow.addView(btnCopy);

            card.addView(actRow);
            recordList.addView(card);
            space(recordList, 6);
        }
    }

    private String extractPassword(String line) {
        if (!Vault.isUnlocked()) {
            toast("熵库已锁定");
            return null;
        }
        try {
            if (line.contains("\u2e41")) {
                String[] parts = line.split("\u2e41", 2);
                if (parts.length >= 2) {
                    String decrypted = Vault.decryptLine(parts[1]);
                    String[] fields = decrypted.split("\t");
                    return fields.length >= 2 ? fields[1] : fields[0];
                }
                return null;
            }
            String sep = line.contains("\t") ? "\t" : ",";
            String[] parts = line.split(sep);
            return parts.length >= 2 ? parts[1].trim() : null;
        } catch (CryptoHelper.CryptoException e) {
            toast("解密失败，数据可能已损坏");
            return null;
        }
    }

    private void addRecord(String nm, String pw, String nt, String tg) {
        String line = nm + "\t" + pw + "\t" + (nt != null ? nt : "") + "\t" + (tg != null ? tg : "");
        addRec(line);
    }

    private void addRec(String plainLine) {
        if (!Vault.isUnlocked()) {
            toast("熵库已锁定，无法写入");
            return;
        }
        String encLine;
        try {
            encLine = Vault.encryptLine(plainLine);
        } catch (CryptoHelper.CryptoException e) {
            toast("加密失败，未保存：" + e.getMessage());
            return;
        }
        String ex = prefs.getString(VaultMigrator.KEY_RECORDS, "");
        String newRec = (ex.isEmpty() ? "" : ex + "\n") + encLine;
        prefs.edit().putString(VaultMigrator.KEY_RECORDS, newRec).commit();
        refreshRecords();
    }

    private void addRecRaw(String encLine) {
        if (!Vault.isUnlocked()) {
            toast("熵库已锁定，无法写入");
            return;
        }
        String ex = prefs.getString(VaultMigrator.KEY_RECORDS, "");
        String newRec = (ex.isEmpty() ? "" : ex + "\n") + encLine;
        prefs.edit().putString(VaultMigrator.KEY_RECORDS, newRec).commit();
        refreshRecords();
    }

    private void delRecord(String line) {
        String ex = prefs.getString(VaultMigrator.KEY_RECORDS, "");
        StringBuilder sb = new StringBuilder();
        String[] lines = ex.split("\n");
        for (String l : lines) {
            if (!l.equals(line)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(l);
            }
        }
        prefs.edit().putString(VaultMigrator.KEY_RECORDS, sb.toString()).commit();
        refreshRecords();
    }

    private void editRecord(String oldLine) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("编辑记录");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText inpName = new EditText(this);
        inpName.setHint("名称");
        inpName.setTextColor(0xffd0ffd0);
        inpName.setHintTextColor(0x88a0ffa0);
        EditText inpPwd = new EditText(this);
        inpPwd.setHint("密码");
        inpPwd.setTextColor(0xffd0ffd0);
        inpPwd.setHintTextColor(0x88a0ffa0);

        // Extract old values
        String oldName = "", oldPwd = "";
        if (oldLine.contains("\u2e41")) {
            String[] parts = oldLine.split("\u2e41", 2);
            oldName = parts[0];
            try {
                String dec = Vault.decryptLine(parts[1]);
                String[] fields = dec.split("\t");
                oldPwd = fields.length >= 2 ? fields[1] : "";
            } catch (CryptoHelper.CryptoException e) {
                toast("解密失败，无法读取原密码");
            }
        } else {
            String sep = oldLine.contains("\t") ? "\t" : ",";
            String[] parts = oldLine.split(sep);
            oldName = parts[0].trim();
            oldPwd = parts.length >= 2 ? parts[1].trim() : "";
        }

        inpName.setText(oldName);
        inpPwd.setText(oldPwd);
        layout.addView(inpName);
        space(layout, 8);
        layout.addView(inpPwd);
        b.setView(layout);

        b.setPositiveButton("保存", (d, w) -> {
            String newName = inpName.getText().toString().trim();
            String newPwd = inpPwd.getText().toString().trim();
            if (newName.isEmpty() || newPwd.isEmpty()) { toast("名称和密码不能为空"); return; }
            delRecord(oldLine);
            addRecord(newName, newPwd, "", "");
            toast("已更新");
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void showSaveDialog(String pwd) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("保存密码");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText inpName = new EditText(this);
        inpName.setHint("名称（如 微信、淘宝）");
        inpName.setTextColor(0xffd0ffd0);
        inpName.setHintTextColor(0x88a0ffa0);
        layout.addView(inpName);

        TextView tvPwd = new TextView(this);
        tvPwd.setText("密码: " + pwd);
        tvPwd.setTextColor(0xff80ff80);
        tvPwd.setTextSize(14);
        tvPwd.setPadding(0, dp(8), 0, 0);
        layout.addView(tvPwd);

        b.setView(layout);
        b.setPositiveButton("保存", (d, w) -> {
            String name = inpName.getText().toString().trim();
            if (name.isEmpty()) { toast("请输入名称"); return; }
            addRecord(name, pwd, "", "");
            toast("已保存");
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ==================== Settings Tab ====================
    private void buildSetTab() {
        LinearLayout pg = new LinearLayout(this);
        pg.setOrientation(LinearLayout.VERTICAL);

        // Batch Import
        space(pg, 8);
        TextView ti = new TextView(this);
        ti.setText("批量导入");
        ti.setTextColor(0xffd0ffd0);
        ti.setTextSize(16);
        ti.setPadding(0, dp(8), 0, dp(4));
        pg.addView(ti);

        // Help card
        LinearLayout helpCard = new LinearLayout(this);
        helpCard.setOrientation(LinearLayout.VERTICAL);
        helpCard.setBackgroundColor(0x221a3a2a);
        helpCard.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView h1 = new TextView(this);
        h1.setText("① 格式：名称\\t密码\\t备注\\t标签");
        h1.setTextColor(0x88a0ffa0);
        h1.setTextSize(10);
        helpCard.addView(h1);
        TextView h2 = new TextView(this);
        h2.setText("② 支持逗号分隔：名称,密码");
        h2.setTextColor(0x88a0ffa0);
        h2.setTextSize(10);
        helpCard.addView(h2);
        TextView h3 = new TextView(this);
        h3.setText("③ 每行一条记录");
        h3.setTextColor(0x88a0ffa0);
        h3.setTextSize(10);
        helpCard.addView(h3);
        TextView h4 = new TextView(this);
        h4.setText("④ 重复名称自动跳过");
        h4.setTextColor(0x88a0ffa0);
        h4.setTextSize(10);
        helpCard.addView(h4);
        TextView h5 = new TextView(this);
        h5.setText("⑤ 导入加密文件：直接粘贴导出的加密备份.txt，自动识别");
        h5.setTextColor(0xffa0ffa0);
        h5.setTextSize(10);
        helpCard.addView(h5);
        pg.addView(helpCard);

        space(pg, 6);
        EditText etImport = new EditText(this);
        etImport.setHint("在此粘贴要导入的数据");
        etImport.setTextColor(0xffd0ffd0);
        etImport.setHintTextColor(0x88a0ffa0);
        etImport.setBackgroundColor(0x441a3a2a);
        etImport.setMinHeight(dp(80));
        etImport.setGravity(Gravity.TOP);
        pg.addView(etImport);

        Button btnImport = new Button(this);
        btnImport.setText("执行导入");
        btnImport.setTextSize(13);
        btnImport.setBackgroundColor(0xff1a3a2a);
        btnImport.setTextColor(0xffa0ffa0);
        btnImport.setOnClickListener(v -> {
            String text = etImport.getText().toString().trim();
            if (text.isEmpty()) { toast("请粘贴要导入的数据"); return; }
            if (!Vault.isUnlocked()) { toast("熵库已锁定，无法导入"); return; }
            String ex = prefs.getString(VaultMigrator.KEY_RECORDS, "");
            String[] lines = text.split("\n");
            int count = 0;
            for (String ln : lines) {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                // Auto-detect encrypted format
                if (ln.contains("\u2e41")) {
                    String[] inp = ln.split("\u2e41", 2);
                    if (inp.length > 0) {
                        String dupName = inp[0];
                        boolean dup = false;
                        if (!ex.isEmpty()) {
                            for (String op : ex.split("\n")) {
                                if (op.startsWith(dupName)) {
                                    String[] inp2 = op.split("\u2e41", 2);
                                    if (inp2.length > 0 && inp2[0].equals(dupName)) { dup = true; break; }
                                }
                            }
                        }
                        if (!dup) {
                            addRecRaw(ln);
                            count++;
                        }
                    }
                } else {
                    String sep = ln.contains("\t") ? "\t" : ",";
                    String[] parts = ln.split(sep);
                    String nm = parts[0].trim();
                    String pw = parts.length >= 2 ? parts[1].trim() : "";
                    if (nm.isEmpty() || pw.isEmpty()) continue;
                    // Check duplicate
                    boolean dup = false;
                    if (!ex.isEmpty()) {
                        for (String op : ex.split("\n")) {
                            String opName = "";
                            if (op.contains("\u2e41")) {
                                opName = op.split("\u2e41", 2)[0];
                            } else {
                                String s = op.contains("\t") ? "\t" : ",";
                                opName = op.split(s)[0].trim();
                            }
                            if (opName.equals(nm)) { dup = true; break; }
                        }
                    }
                    if (!dup) {
                        addRecord(nm, pw, "", "");
                        count++;
                    }
                }
            }
            toast("导入完成: " + count + " 条");
            etImport.setText("");
            refreshRecords();
        });
        pg.addView(btnImport);

        space(pg, 16);

        // Export Backup
        TextView te = new TextView(this);
        te.setText("导出备份");
        te.setTextColor(0xffd0ffd0);
        te.setTextSize(16);
        te.setPadding(0, dp(8), 0, dp(4));
        pg.addView(te);

        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        final Switch swEncExport = new Switch(this);
        swEncExport.setText("加密导出");
        swEncExport.setTextColor(0xffa0ffa0);
        swEncExport.setTextSize(13);
        exportRow.addView(swEncExport);

        Button btnExport = new Button(this);
        btnExport.setText("导出备份文件");
        btnExport.setTextSize(13);
        btnExport.setBackgroundColor(0xff1a3a2a);
        btnExport.setTextColor(0xffa0ffa0);
        btnExport.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        btnExport.setOnClickListener(v -> {
            boolean doEncrypt = swEncExport.isChecked();
            String fn = doEncrypt ? "PasswordNotebook_backup_enc.txt" : "PasswordNotebook_backup.txt";
            String raw = prefs.getString(VaultMigrator.KEY_RECORDS, "");
            try {
                if (!doEncrypt && !Vault.isUnlocked()) { toast("熵库已锁定，无法导出明文"); return; }
                File dir = new File("/sdcard/Download");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, fn);
                FileWriter fw = new FileWriter(f);
                if (doEncrypt) {
                    fw.write(raw);
                } else {
                    String[] lines = raw.split("\n");
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;
                        if (line.contains("\u2e41")) {
                            String[] parts = line.split("\u2e41", 2);
                            String dec = Vault.decryptLine(parts[1]);
                            fw.write(parts[0] + "\t" + dec);
                        } else {
                            fw.write(line);
                        }
                        fw.write("\n");
                    }
                }
                fw.close();
                toast("已导出: " + fn);
            } catch (Exception e) {
                toast("导出失败: " + e.getMessage());
            }
        });
        exportRow.addView(btnExport);
        pg.addView(exportRow);

        space(pg, 16);

        // About
        TextView ta = new TextView(this);
        ta.setText("关于");
        ta.setTextColor(0xffd0ffd0);
        ta.setTextSize(16);
        ta.setPadding(0, dp(8), 0, dp(4));
        pg.addView(ta);

        TextView ia = new TextView(this);
        ia.setText("密码の小本 v2.1\nAES-256-GCM 加密存储\n矩阵代码雨 · 赛博朋克风格");
        ia.setTextColor(0xffa0ffa0);
        ia.setTextSize(12);
        ia.setPadding(dp(8), dp(8), dp(8), dp(8));
        ia.setBackgroundColor(0x221a3a2a);
        ia.setGravity(Gravity.CENTER);
        pg.addView(ia);

        contentArea.addView(pg);
    }

    // ==================== Matrix Rain Background ====================
    class LiquidBackgroundView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
        private SurfaceHolder holder;
        private boolean running;
        private int cols;
        private float[] positions;
        private float[] speeds;
        private int[] lengths;
        private Paint headPaint, trailPaint;
        private Random r = new Random();
        private String chars = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$%&#@!?<>";

        public LiquidBackgroundView(Context context) {
            super(context);
            holder = getHolder();
            holder.addCallback(this);
            setZOrderOnTop(true);
            holder.setFormat(PixelFormat.TRANSLUCENT);

            headPaint = new Paint();
            headPaint.setColor(0xff80ff80);
            headPaint.setTextSize(dp(14));
            headPaint.setAntiAlias(true);
            headPaint.setFakeBoldText(true);

            trailPaint = new Paint();
            trailPaint.setColor(0x6080ff80);
            trailPaint.setTextSize(dp(14));
            trailPaint.setAntiAlias(true);
        }

        @Override
        public void surfaceCreated(SurfaceHolder h) {
            running = true;
            new Thread(this).start();
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hgt) {
            cols = w / dp(16) + 1;
            positions = new float[cols];
            speeds = new float[cols];
            lengths = new int[cols];
            for (int i = 0; i < cols; i++) {
                positions[i] = -r.nextInt(80) * dp(14);
                speeds[i] = 800 + r.nextInt(800); // 800~1600 px/s
                lengths[i] = 12 + r.nextInt(25);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            running = false;
        }

        @Override
        public void run() {
            long lastTime = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = (now - lastTime) / 1e9f;
                lastTime = now;

                if (!holder.getSurface().isValid()) continue;
                Canvas c = holder.lockCanvas();
                if (c == null) continue;

                c.drawColor(0xee0a0a0a);

                for (int i = 0; i < cols; i++) {
                    positions[i] += speeds[i] * dt;
                    if (positions[i] > getHeight() + lengths[i] * dp(14)) {
                        positions[i] = -r.nextInt(80) * dp(14);
                        speeds[i] = 800 + r.nextInt(800);
                        lengths[i] = 12 + r.nextInt(25);
                    }

                    float x = i * dp(16);
                    float y = positions[i];
                    for (int j = 0; j < lengths[i]; j++) {
                        if (y < 0) { y += dp(14); continue; }
                        if (y > getHeight()) break;
                        Paint p = (j == lengths[i] - 1) ? headPaint : trailPaint;
                        char ch = chars.charAt(r.nextInt(chars.length()));
                        // Fade trail
                        if (j < lengths[i] - 1) {
                            int alpha = (int) (80 * (1 - (float) j / lengths[i]));
                            trailPaint.setAlpha(alpha);
                        }
                        c.drawText(String.valueOf(ch), x, y, p);
                        y += dp(14);
                    }
                }

                holder.unlockCanvasAndPost(c);

                try { Thread.sleep(30); } catch (InterruptedException e) { break; }
            }
        }
    }
}