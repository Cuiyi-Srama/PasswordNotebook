package com.cuiyi.passwordnotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.cuiyi.passwordnotebook.data.Entry;
import com.cuiyi.passwordnotebook.data.LegacyReader;
import com.cuiyi.passwordnotebook.crypto.BiometricKeyStore;
import com.cuiyi.passwordnotebook.data.Vault;
import com.cuiyi.passwordnotebook.gen.PasswordFactory;
import com.cuiyi.passwordnotebook.ui.Animations;
import com.cuiyi.passwordnotebook.ui.BiometricPromptFactory;
import com.cuiyi.passwordnotebook.ui.BiometricPromptFactory.BiometricPrompt;
import com.cuiyi.passwordnotebook.ui.CyberRainView;
import com.cuiyi.passwordnotebook.ui.FilePicker;
import com.cuiyi.passwordnotebook.ui.Theme;
import java.io.File;
import javax.crypto.Cipher;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The single screen, hosting three tabs.
 *
 * Structure notes:
 *  - The rain animation lives in CyberRainView and is paused whenever the
 *    activity is not in front.
 *  - All storage access goes through Vault; this class never touches the key.
 *  - The optional device-credential gate is a user preference stored in plain
 *    SharedPreferences, because it protects nothing on its own: it only decides
 *    whether to ask the system to confirm the user before unlocking.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_DEVICE_CREDENTIAL = 1001;
    private static final String PREFS = "pwd_nb_prefs";
    private static final String KEY_DEVICE_GATE = "device_gate";
    private static final String KEY_LENGTH = "length";
    private static final String KEY_GEN_UPPER = "gen_upper";
    private static final String KEY_GEN_LOWER = "gen_lower";
    private static final String KEY_GEN_DIGITS = "gen_digits";
    private static final String KEY_GEN_COMMON = "gen_common";
    private static final String KEY_GEN_EXTENDED = "gen_extended";
    private static final String PLAIN_EXPORT_NAME = "PasswordNotebook_plain.csv";
    private static final String SEALED_EXPORT_NAME = "PasswordNotebook_backup.txt";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private SharedPreferences prefs;
    private CyberRainView rain;
    private LinearLayout contentArea;
    private TextView[] tabViews;
    private int activeTab;

    // search
    private EditText searchField;
    private LinearLayout recordContainer;
    private Animations.Debouncer searchDebouncer;

    // generator
    private TextView passwordView;
    private TextView strengthView;
    private TextView lengthLabel;
    private SeekBar lengthSlider;
    private Switch switchUpper;
    private Switch switchLower;
    private Switch switchDigits;
    private Switch switchCommon;
    private Switch switchExtended;
    private Animations.Typewriter typewriter;
    private int passwordLength = 16;
    // core word mode
    private EditText coreWordField;
    private EditText siteSaltField;
    private TextView periodLabel;
    private int periodYear;
    private int periodWeek;
    private boolean periodicMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        searchDebouncer = new Animations.Debouncer();
        buildShell();
        gate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rain != null) {
            rain.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rain != null) {
            rain.pause();
        }
    }

    /**
     * Lock only when the activity is genuinely going away.
     *
     * Locking in onPause looked safer but broke the app: showing a dialog can
     * pause the activity, so the key was wiped the instant the unlock prompt
     * appeared and the user could never get back in. onStop still covers the
     * cases that matter (home, recents, screen off) without tripping on our own
     * dialogs.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            Vault.lock();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rain != null) {
            rain.release();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEVICE_CREDENTIAL) {
            boolean granted = resultCode == RESULT_OK;
            prefs.edit().putBoolean(KEY_DEVICE_GATE, granted).apply();
            toast(granted ? "已启用系统验证" : "已关闭系统验证");
            if (activeTab == 2) {
                showTab(2);
            }
        }
    }

    // ---------------- shell ----------------

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG_DEEP);

        rain = new CyberRainView(this);
        root.addView(rain, new FrameLayout.LayoutParams(-1, -1));

        // Scrim between the rain and the interface. Without this the glyphs sit
        // directly behind the text and make it unreadable, which reads as the
        // screen being broken rather than as a background.
        View scrim = new View(this);
        scrim.setBackgroundColor(Theme.SCRIM);
        scrim.setClickable(false);
        scrim.setFocusable(false);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Theme.SURFACE_STRONG);
        tabs.setPadding(dp(8), dp(8), dp(8), dp(8));

        String[] names = {"生成", "记录", "设置"};
        tabViews = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setTextSize(Theme.SIZE_BODY);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(8), dp(10), dp(8), dp(10));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            tab.setTextColor(Theme.TEXT_SECONDARY);
            tab.setClickable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Animations.pressFeedback(v);
                    showTab(index);
                }
            });
            tabViews[i] = tab;
            tabs.addView(tab);
        }
        column.addView(tabs);

        ScrollView scroller = new ScrollView(this);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(dp(12), dp(10), dp(12), dp(24));
        scroller.addView(contentArea);
        column.addView(scroller);

        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showTab(0);
    }

    private void showTab(int index) {
        activeTab = index;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setTextColor(i == index ? Theme.TEXT_ACCENT : Theme.TEXT_SECONDARY);
        }
        contentArea.removeAllViews();
        if (!Vault.isUnlocked()) {
            // Reached when the vault was locked while the activity stayed
            // visible. Offer the way back in rather than a dead-end message.
            contentArea.addView(hint(Vault.exists(this)
                    ? "已锁定。输入主密码继续，或使用指纹解锁。"
                    : "还没有创建密码库。"));
            contentArea.addView(space(10));
            contentArea.addView(fullButton("解锁", Theme.TEXT_ACCENT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Animations.pressFeedback(v);
                            gate();
                        }
                    }));
            return;
        }
        if (index == 0) {
            buildGeneratorTab();
        } else if (index == 1) {
            buildRecordsTab();
        } else {
            buildSettingsTab();
        }
    }

    // ---------------- unlock gate ----------------

    private void gate() {
        if (!Vault.exists(this)) {
            askCreateMasterPassword();
        } else {
            askUnlock();
        }
    }

    private void askCreateMasterPassword() {
        final EditText first = passwordInput("主密码（至少 8 位）");
        final EditText second = passwordInput("再输入一次");
        LinearLayout box = column();
        box.addView(first);
        box.addView(second);
        box.addView(hint("主密码用于加密整个数据库，不会被保存。忘记就无法恢复。\n\n"
                + "之后可以在“设置”里导入旧版备份。"));

        new AlertDialog.Builder(this)
                .setTitle("设置主密码")
                .setCancelable(false)
                .setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String a = first.getText().toString();
                        String b = second.getText().toString();
                        if (a.length() < 8) {
                            toast("主密码至少 8 位");
                            askCreateMasterPassword();
                            return;
                        }
                        if (!a.equals(b)) {
                            toast("两次输入不一致");
                            askCreateMasterPassword();
                            return;
                        }
                        char[] password = a.toCharArray();
                        try {
                            int iterations = Vault.create(MainActivity.this, password);
                            toast("已创建，本机迭代 " + iterations + " 次");
                            showTab(0);
                        } catch (Exception e) {
                            toast("创建失败：" + e.getMessage());
                            askCreateMasterPassword();
                        } finally {
                            com.cuiyi.passwordnotebook.crypto.KeyDerivation.wipe(password);
                        }
                    }
                })
                .show();
    }

    private void askUnlock() {
        final EditText input = passwordInput("主密码");
        LinearLayout box = column();
        box.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("解锁")
                .setCancelable(false)
                .setView(box)
                .setPositiveButton("解锁", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        attemptUnlock(input.getText().toString(), true);
                    }
                });

        // Offer the biometric route only when the user has enrolled it, so the
        // dialog does not promise something that would immediately fail.
        if (BiometricKeyStore.isSupported() && BiometricKeyStore.hasWrappedKey(this)) {
            builder.setNeutralButton("指纹解锁", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    unlockWithBiometric();
                }
            });
        } else {
            builder.setNeutralButton("恢复备份", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    askRestoreBackup();
                }
            });
        }
        builder.show();
    }

    private void attemptUnlock(final String password, boolean offerEnrolment) {
        char[] chars = password.toCharArray();
        try {
            boolean ok = Vault.unlock(this, chars);
            if (ok) {
                toast("已解锁");
                showTab(0);
                if (offerEnrolment && BiometricKeyStore.isSupported()
                        && !BiometricKeyStore.hasWrappedKey(this)) {
                    offerBiometricEnrolment();
                }
            } else {
                toast("主密码错误");
                askUnlock();
            }
        } catch (Exception e) {
            toast("无法读取数据库：" + e.getMessage());
        } finally {
            com.cuiyi.passwordnotebook.crypto.KeyDerivation.wipe(chars);
        }
    }

    // ---------------- biometric unlock ----------------

    /**
     * Ask the user whether to turn on fingerprint unlock.
     *
     * Offered right after a successful password unlock, which is the only
     * moment the vault key is available to wrap.
     */
    private void offerBiometricEnrolment() {
        new AlertDialog.Builder(this)
                .setTitle("启用指纹解锁？")
                .setMessage("开启后，打开应用可直接用指纹进入，不必每次输入主密码。\n\n"
                        + "主密码仍然有效，也仍然用于导出和恢复备份。\n\n"
                        + "代价：能通过你手机指纹的人就能打开这个密码库。")
                .setPositiveButton("启用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        enrolBiometric();
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    private void enrolBiometric() {
        try {
            final Cipher cipher = BiometricKeyStore.cipherForEnrolment();
            BiometricPrompt prompt = BiometricPromptFactory.create(this,
                    "启用指纹解锁", "验证指纹以保存解锁凭据");
            if (prompt == null) {
                toast("此设备不支持指纹验证");
                return;
            }
            prompt.authenticate(cipher, new BiometricPromptFactory.Callback() {
                @Override
                public void onSucceeded(Cipher authorised) {
                    try {
                        byte[] vaultKey = Vault.currentKey();
                        if (vaultKey == null) {
                            toast("解锁状态已失效，请重新输入主密码");
                            return;
                        }
                        BiometricKeyStore.storeWrappedKey(MainActivity.this, vaultKey, authorised);
                        toast("指纹解锁已启用");
                    } catch (Exception e) {
                        toast("保存失败：" + e.getMessage());
                    }
                }

                @Override
                public void onFailed(String reason) {
                    toast(reason);
                }
            });
        } catch (Exception e) {
            toast("无法启用：" + e.getMessage());
        }
    }

    private void unlockWithBiometric() {
        try {
            final Cipher cipher = BiometricKeyStore.cipherForUnlock(this);
            BiometricPrompt prompt = BiometricPromptFactory.create(this,
                    "指纹解锁", "验证指纹以打开密码库");
            if (prompt == null) {
                toast("此设备不支持指纹验证");
                askUnlock();
                return;
            }
            prompt.authenticate(cipher, new BiometricPromptFactory.Callback() {
                @Override
                public void onSucceeded(Cipher authorised) {
                    byte[] recovered = BiometricKeyStore.recoverVaultKey(
                            MainActivity.this, authorised);
                    if (recovered == null) {
                        toast("指纹凭据已失效，请用主密码解锁");
                        askUnlock();
                        return;
                    }
                    try {
                        boolean ok = Vault.unlockWithKey(MainActivity.this, recovered);
                        if (ok) {
                            toast("已解锁");
                            showTab(0);
                        } else {
                            BiometricKeyStore.clear(MainActivity.this);
                            toast("指纹凭据与密码库不匹配，已清除");
                            askUnlock();
                        }
                    } catch (Exception e) {
                        toast("解锁失败：" + e.getMessage());
                        askUnlock();
                    } finally {
                        com.cuiyi.passwordnotebook.crypto.KeyDerivation.wipe(recovered);
                    }
                }

                @Override
                public void onFailed(String reason) {
                    toast(reason);
                    askUnlock();
                }
            });
        } catch (Exception e) {
            toast("无法使用指纹：" + e.getMessage());
            BiometricKeyStore.clear(this);
            askUnlock();
        }
    }

    // ---------------- generator tab ----------------

    private void buildGeneratorTab() {
        LinearLayout root = column();

        TextView modeButton = button(periodicMode ? "当前：核心词生成（点击切到随机）"
                : "当前：随机生成（点击切到核心词）", Theme.TEXT_ACCENT);
        modeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                periodicMode = !periodicMode;
                showTab(0);
            }
        });
        root.addView(modeButton);
        root.addView(space(10));

        passwordView = new TextView(this);
        passwordView.setText("--------");
        passwordView.setTextSize(Theme.SIZE_PASSWORD);
        passwordView.setTextColor(Theme.TEXT_ACCENT);
        passwordView.setTypeface(android.graphics.Typeface.MONOSPACE);
        passwordView.setGravity(Gravity.CENTER);
        passwordView.setPadding(dp(8), dp(18), dp(8), dp(18));
        passwordView.setBackground(glassCard());
        root.addView(passwordView);
        typewriter = new Animations.Typewriter(passwordView);

        strengthView = new TextView(this);
        strengthView.setTextSize(Theme.SIZE_BODY);
        strengthView.setTextColor(Theme.TEXT_ACCENT);
        strengthView.setGravity(Gravity.CENTER);
        strengthView.setPadding(0, dp(6), 0, dp(6));
        root.addView(strengthView);

        LinearLayout actions = row();
        actions.addView(weightedButton("重新生成", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                regenerate();
            }
        }));
        actions.addView(weightedButton("复制", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                copyCurrent();
            }
        }));
        actions.addView(weightedButton("保存", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askSaveCurrent();
            }
        }));
        root.addView(actions);

        if (periodicMode) {
            root.addView(buildPeriodicControls());
        } else {
            root.addView(buildRandomControls());
        }

        root.addView(section("长度"));
        LinearLayout lengthRow = row();
        TextView lengthCaption = new TextView(this);
        lengthCaption.setText("字符数");
        lengthCaption.setTextColor(Theme.TEXT_PRIMARY);
        lengthCaption.setTextSize(Theme.SIZE_BODY);
        lengthRow.addView(lengthCaption);
        lengthLabel = new TextView(this);
        lengthLabel.setText(String.valueOf(passwordLength));
        lengthLabel.setTextColor(Theme.TEXT_ACCENT);
        lengthLabel.setTextSize(20f);
        lengthLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        lengthLabel.setPadding(dp(10), 0, 0, 0);
        lengthRow.addView(lengthLabel);
        root.addView(lengthRow);

        lengthSlider = new SeekBar(this);
        lengthSlider.setMax(48);
        lengthSlider.setProgress(Math.max(0, passwordLength - 8));
        lengthSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                passwordLength = progress + 8;
                lengthLabel.setText(String.valueOf(passwordLength));
                if (fromUser) {
                    regenerate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt(KEY_LENGTH, passwordLength).apply();
            }
        });
        root.addView(lengthSlider);

        contentArea.addView(root);
        regenerate();
    }

    private View buildRandomControls() {
        LinearLayout box = column();
        box.addView(section("字符集"));
        switchUpper = addSwitch(box, "大写 A-Z", KEY_GEN_UPPER, true);
        switchLower = addSwitch(box, "小写 a-z", KEY_GEN_LOWER, true);
        switchDigits = addSwitch(box, "数字 0-9", KEY_GEN_DIGITS, true);
        switchCommon = addSwitch(box, "常见符号", KEY_GEN_COMMON, true);
        switchExtended = addSwitch(box, "扩展符号", KEY_GEN_EXTENDED, false);
        return box;
    }

    private View buildPeriodicControls() {
        LinearLayout box = column();
        box.addView(section("核心词"));
        box.addView(hint("核心词只存在你的记忆里。它一旦泄露，用这种方式生成的所有密码都会跟着泄露。"));

        coreWordField = textInput("核心词");
        coreWordField.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onChanged() {
                regenerate();
            }
        });
        box.addView(coreWordField);

        box.addView(space(8));
        box.addView(section("站点标识（可选）"));
        siteSaltField = textInput("例如域名或应用名，让不同站点得到不同密码");
        siteSaltField.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onChanged() {
                regenerate();
            }
        });
        box.addView(siteSaltField);

        box.addView(space(8));
        LinearLayout periodRow = row();
        final TextView periodText = new TextView(this);
        periodText.setText(periodYear + " W" + periodWeek);
        periodText.setTextColor(Theme.TEXT_PRIMARY);
        periodText.setGravity(Gravity.CENTER);
        periodText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView previous = button("◀", Theme.TEXT_ACCENT);
        previous.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -2));
        previous.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                shiftPeriod(-1);
                periodText.setText(periodYear + " W" + periodWeek);
                regenerate();
            }
        });
        final TextView next = button("▶", Theme.TEXT_ACCENT);
        next.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -2));
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                shiftPeriod(1);
                periodText.setText(periodYear + " W" + periodWeek);
                regenerate();
            }
        });
        periodRow.addView(previous);
        periodRow.addView(periodText);
        periodRow.addView(next);
        box.addView(periodRow);
        return box;
    }

    private void shiftPeriod(int delta) {
        periodWeek += delta;
        if (periodWeek < 1) {
            periodWeek = 52;
            periodYear--;
        } else if (periodWeek > 52) {
            periodWeek = 1;
            periodYear++;
        }
    }

    private void regenerate() {
        if (passwordView == null || typewriter == null) {
            return;
        }
        String password;
        try {
            if (periodicMode) {
                String core = coreWordField == null ? "" : coreWordField.getText().toString();
                String site = siteSaltField == null ? "" : siteSaltField.getText().toString();
                if (core.trim().isEmpty()) {
                    typewriter.finishWith("请输入核心词");
                    strengthView.setText("");
                    return;
                }
                byte[] salt = siteSalt(site);
                int[] period = PasswordFactory.currentPeriod();
                password = PasswordFactory.periodic(core, site, salt,
                        periodYear == 0 ? period[0] : periodYear,
                        periodWeek == 0 ? period[1] : periodWeek,
                        passwordLength);
            } else {
                password = PasswordFactory.random(passwordLength,
                        switchUpper.isChecked(), switchLower.isChecked(),
                        switchDigits.isChecked(), switchCommon.isChecked(),
                        switchExtended.isChecked());
            }
        } catch (IllegalArgumentException e) {
            typewriter.finishWith("请至少启用一种字符类型");
            strengthView.setText("");
            return;
        } catch (Exception e) {
            typewriter.finishWith("生成失败");
            strengthView.setText("");
            return;
        }
        typewriter.type(password);
        int score = PasswordFactory.strength(password);
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            stars.append(i < score ? "★" : "☆");
        }
        strengthView.setText(stars.toString());
    }

    /** Per-site salt, created on first use and stored in the clear. */
    private byte[] siteSalt(String site) {
        String key = "site_salt_" + (site == null || site.isEmpty() ? "default" : site);
        String stored = prefs.getString(key, null);
        if (stored != null) {
            try {
                return Base64.decode(stored, Base64.NO_WRAP);
            } catch (IllegalArgumentException ignored) {
                // fall through and mint a new one
            }
        }
        byte[] salt = PasswordFactory.newSiteSalt();
        prefs.edit().putString(key, Base64.encodeToString(salt, Base64.NO_WRAP)).apply();
        return salt;
    }

    private void copyCurrent() {
        String value = passwordView.getText().toString();
        if (value.isEmpty() || value.startsWith("请") || value.equals("--------")) {
            toast("先生成密码");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("password", value));
        toast("已复制");
    }

    private void askSaveCurrent() {
        final String value = passwordView.getText().toString();
        if (value.isEmpty() || value.startsWith("请") || value.equals("--------")) {
            toast("先生成密码");
            return;
        }
        final EditText title = textInput("名称");
        final EditText note = textInput("备注（可选）");
        final EditText tag = textInput("标签（可选）");
        LinearLayout box = column();
        box.addView(title);
        box.addView(note);
        box.addView(tag);
        new AlertDialog.Builder(this)
                .setTitle("保存密码")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Entry entry = new Entry(
                                title.getText().toString().trim(),
                                value,
                                note.getText().toString().trim(),
                                tag.getText().toString().trim());
                        if (entry.title.isEmpty()) {
                            entry.title = "未命名";
                        }
                        try {
                            Vault.add(MainActivity.this, entry);
                            toast("已保存");
                        } catch (Exception e) {
                            toast("保存失败：" + e.getMessage());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- records tab ----------------

    private void buildRecordsTab() {
        LinearLayout root = column();

        searchField = textInput("搜索名称、标签或备注");
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                final String query = s.toString();
                searchDebouncer.submit(new Runnable() {
                    @Override
                    public void run() {
                        renderRecords(query);
                    }
                });
            }
        });
        root.addView(searchField);

        recordContainer = new LinearLayout(this);
        recordContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(recordContainer);

        contentArea.addView(root);
        renderRecords("");
    }

    private void renderRecords(String query) {
        if (recordContainer == null) {
            return;
        }
        recordContainer.removeAllViews();
        List<Entry> all = Vault.entries();
        String needle = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (Entry entry : all) {
            if (!needle.isEmpty() && !matches(entry, needle)) {
                continue;
            }
            recordContainer.addView(recordCard(entry));
            recordContainer.addView(space(8));
            shown++;
        }
        if (shown == 0) {
            recordContainer.addView(hint(all.isEmpty() ? "暂无记录" : "没有匹配的记录"));
        }
    }

    private boolean matches(Entry entry, String needle) {
        return contains(entry.title, needle)
                || contains(entry.tag, needle)
                || contains(entry.note, needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private View recordCard(final Entry entry) {
        LinearLayout card = column();
        card.setBackground(glassCard());
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);

        TextView title = new TextView(this);
        title.setText(entry.displayTitle());
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(Theme.SIZE_TITLE);
        card.addView(title);

        if (entry.tag != null && !entry.tag.isEmpty()) {
            TextView tag = new TextView(this);
            tag.setText(entry.tag);
            tag.setTextColor(Theme.TEXT_ACCENT);
            tag.setTextSize(Theme.SIZE_SMALL);
            card.addView(tag);
        }
        if (entry.note != null && !entry.note.isEmpty()) {
            TextView note = new TextView(this);
            note.setText(entry.note);
            note.setTextColor(Theme.TEXT_SECONDARY);
            note.setTextSize(Theme.SIZE_SMALL);
            card.addView(note);
        }
        if (entry.updatedAt > 0L) {
            TextView stamp = new TextView(this);
            stamp.setText(formatTime(entry.updatedAt));
            stamp.setTextColor(Theme.TEXT_MUTED);
            stamp.setTextSize(Theme.SIZE_TINY);
            card.addView(stamp);
        }

        final TextView masked = new TextView(this);
        masked.setText("••••••••");
        masked.setTextColor(Theme.TEXT_ACCENT);
        masked.setTextSize(Theme.SIZE_BODY);
        masked.setTypeface(android.graphics.Typeface.MONOSPACE);
        masked.setPadding(0, dp(6), 0, dp(6));
        masked.setClickable(true);
        masked.setOnClickListener(new View.OnClickListener() {
            private boolean revealed;

            @Override
            public void onClick(View v) {
                revealed = !revealed;
                masked.setText(revealed ? entry.password : "••••••••");
            }
        });
        card.addView(masked);

        LinearLayout actions = row();
        actions.addView(weightedButton("复制", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("password", entry.password));
                toast("已复制");
            }
        }));
        actions.addView(weightedButton("编辑", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askEditEntry(entry);
            }
        }));
        actions.addView(weightedButton("删除", Theme.TEXT_DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askDeleteEntry(entry);
            }
        }));
        card.addView(actions);

        // Long press shows the password without a second tap.
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                boolean revealing = masked.getText().toString().startsWith("•");
                masked.setText(revealing ? entry.password : "••••••••");
                toast(revealing ? "已显示密码" : "已隐藏密码");
                return true;
            }
        });
        return card;
    }

    private void askEditEntry(final Entry entry) {
        final EditText title = textInput("名称");
        title.setText(entry.title);
        final EditText password = textInput("密码");
        password.setText(entry.password);
        final EditText note = textInput("备注");
        note.setText(entry.note);
        final EditText tag = textInput("标签");
        tag.setText(entry.tag);

        LinearLayout box = column();
        box.addView(title);
        box.addView(password);
        box.addView(note);
        box.addView(tag);

        new AlertDialog.Builder(this)
                .setTitle("编辑")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        entry.title = title.getText().toString().trim();
                        entry.password = password.getText().toString();
                        entry.note = note.getText().toString().trim();
                        entry.tag = tag.getText().toString().trim();
                        entry.updatedAt = System.currentTimeMillis();
                        try {
                            Vault.replaceAll(MainActivity.this, Vault.entries());
                            toast("已更新");
                        } catch (Exception e) {
                            toast("保存失败：" + e.getMessage());
                        }
                        renderRecords(currentQuery());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askDeleteEntry(final Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除「" + entry.displayTitle() + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Vault.remove(MainActivity.this, entry);
                            toast("已删除");
                        } catch (Exception e) {
                            toast("删除失败：" + e.getMessage());
                        }
                        renderRecords(currentQuery());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String currentQuery() {
        return searchField == null ? "" : searchField.getText().toString();
    }

    // ---------------- settings tab ----------------

    private void buildSettingsTab() {
        LinearLayout root = column();

        root.addView(section("安全"));
        LinearLayout gateRow = row();
        gateRow.setBackground(glassCard());
        gateRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView gateLabel = new TextView(this);
        gateLabel.setText("解锁前要求系统验证");
        gateLabel.setTextColor(Theme.TEXT_PRIMARY);
        gateLabel.setTextSize(Theme.SIZE_BODY);
        gateLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        gateRow.addView(gateLabel);
        final Switch gateSwitch = new Switch(this);
        gateSwitch.setChecked(prefs.getBoolean(KEY_DEVICE_GATE, false));
        gateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (!checked) {
                    prefs.edit().putBoolean(KEY_DEVICE_GATE, false).apply();
                    return;
                }
                KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguard == null || !keyguard.isKeyguardSecure()) {
                    toast("请先在系统里设置锁屏密码或指纹");
                    button.setChecked(false);
                    return;
                }
                Intent confirm = keyguard.createConfirmDeviceCredentialIntent(
                        "验证身份", "请验证系统锁屏以启用保护");
                if (confirm == null) {
                    toast("当前设备无法完成验证");
                    button.setChecked(false);
                    return;
                }
                startActivityForResult(confirm, REQUEST_DEVICE_CREDENTIAL);
            }
        });
        gateRow.addView(gateSwitch);
        root.addView(gateRow);
        root.addView(hint("系统验证只是进入前的一道门，真正解开数据库的仍然是你的主密码。"));

        root.addView(space(6));
        root.addView(buildBiometricRow());
        root.addView(space(6));
        root.addView(fullButton("立即锁定", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                Vault.lock();
                toast("已锁定");
                gate();
            }
        }));

        root.addView(section("数据"));
        root.addView(fullButton("导入旧版备份", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askImportLegacy();
            }
        }));
        root.addView(space(6));
        root.addView(fullButton("导出加密备份", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                exportSealed();
            }
        }));
        root.addView(space(6));
        root.addView(fullButton("恢复加密备份", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askRestoreBackup();
            }
        }));
        root.addView(space(6));
        root.addView(fullButton("导出明文 CSV（危险）", Theme.TEXT_DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askExportPlain();
            }
        }));

        root.addView(section("关于"));
        root.addView(hint("密码小本 v5\n\n"
                + "数据使用 AES-256-GCM 加密，密钥由主密码通过 PBKDF2-HMAC-SHA256 派生，"
                + "盐值随机、迭代次数按本机性能校准。主密码不保存，忘记则无法恢复。\n\n"
                + "应用不申请任何权限，不联网。备份文件本身是加密的。"));

        contentArea.addView(root);
    }

    // ---------------- import and export ----------------

    /**
     * Import from a pasted blob or from a file on disk.
     *
     * Two entry points rather than one, because the old build only offered a
     * paste box and users could not find where their backup had gone. The file
     * route reads the text itself so a 9 KB backup does not have to survive the
     * system clipboard.
     */
    /** Fingerprint toggle for the settings tab. */
    private View buildBiometricRow() {
        LinearLayout row = row();
        row.setBackground(glassCard());
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView label = new TextView(this);
        label.setText(BiometricKeyStore.isSupported()
                ? "指纹解锁" : "指纹解锁（此设备不支持）");
        label.setTextColor(Theme.TEXT_PRIMARY);
        label.setTextSize(Theme.SIZE_BODY);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(label);

        final Switch toggle = new Switch(this);
        toggle.setEnabled(BiometricKeyStore.isSupported());
        toggle.setChecked(BiometricKeyStore.hasWrappedKey(this));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (!BiometricKeyStore.isSupported()) {
                    return;
                }
                if (checked) {
                    enrolBiometric();
                } else {
                    BiometricKeyStore.clear(MainActivity.this);
                    toast("指纹解锁已关闭");
                }
            }
        });
        row.addView(toggle);
        return row;
    }

    private void askImportLegacy() {
        new AlertDialog.Builder(this)
                .setTitle("导入旧版数据")
                .setMessage("支持：旧版加密备份、旧版逐行加密文本、Tab/逗号/中文逗号分隔的明文，"
                        + "以及“名称一行、密码一行”的双行格式。重复条目会自动跳过。")
                .setPositiveButton("选择文件", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pickBackupFile();
                    }
                })
                .setNeutralButton("粘贴文本", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        askPasteImport();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickBackupFile() {
        FilePicker.show(this, new FilePicker.Listener() {
            @Override
            public void onPicked(File file) {
                readAndImport(file);
            }
        });
    }

    private void readAndImport(File file) {
        try {
            String text = readText(file);
            if (text.trim().isEmpty()) {
                toast("文件是空的");
                return;
            }
            confirmImport(text, file.getName());
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
        }
    }

    private String readText(File file) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            java.io.InputStreamReader reader = new java.io.InputStreamReader(in, UTF8);
            StringBuilder sb = new StringBuilder((int) Math.max(256, file.length()));
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    private void askPasteImport() {
        final EditText input = textInput("粘贴备份内容");
        // Tall enough to see a full 38-entry backup without scrolling blind.
        input.setMinLines(10);
        input.setMaxLines(14);
        input.setGravity(Gravity.TOP);
        input.setTextSize(Theme.SIZE_SMALL);

        LinearLayout box = column();
        box.addView(input);
        box.addView(hint("提示：内容较长时用“选择文件”导入更可靠。"));

        new AlertDialog.Builder(this)
                .setTitle("粘贴导入")
                .setView(box)
                .setPositiveButton("解析并导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmImport(input.getText().toString(), "粘贴内容");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Parse first, then tell the user what was found before writing anything.
     *
     * The old flow wrote straight to storage, so a wrong guess about the format
     * either silently imported garbage or reported a bare failure with no clue
     * about why. Showing the counts first makes a bad parse obvious.
     */
    private void confirmImport(String text, String sourceName) {
        LegacyReader.Result parsed = LegacyReader.parse(text);
        if (parsed.entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没有解析到记录")
                    .setMessage("来源：" + sourceName + "\n\n"
                            + parsed.describe() + "\n\n"
                            + "可能的原因：文件被截断、内容不是本应用的备份，"
                            + "或者复制时只复制了一部分。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        StringBuilder preview = new StringBuilder();
        preview.append("来源：").append(sourceName).append("\n\n");
        preview.append(parsed.describe()).append("\n\n");
        preview.append("前几条：\n");
        int shown = Math.min(5, parsed.entries.size());
        for (int i = 0; i < shown; i++) {
            Entry entry = parsed.entries.get(i);
            preview.append("  ").append(i + 1).append(". ")
                    .append(entry.displayTitle()).append("\n");
        }
        if (parsed.entries.size() > shown) {
            preview.append("  …还有 ").append(parsed.entries.size() - shown).append(" 条\n");
        }

        final LegacyReader.Result toImport = parsed;
        new AlertDialog.Builder(this)
                .setTitle("确认导入")
                .setMessage(preview.toString())
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        commitImport(toImport);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void commitImport(LegacyReader.Result parsed) {
        try {
            int added = Vault.addAllSkippingDuplicates(this, parsed.entries);
            int duplicates = parsed.entries.size() - added;
            toast("导入 " + added + " 条" + (duplicates > 0 ? "，跳过重复 " + duplicates : ""));
            showTab(1);
        } catch (Exception e) {
            toast("写入失败：" + e.getMessage());
        }
    }

    private void runLegacyImport(String text) {
        if (text == null || text.trim().isEmpty()) {
            toast("内容为空");
            return;
        }
        LegacyReader.Result parsed = LegacyReader.parse(text);
        if (parsed.entries.isEmpty()) {
            toast("没有解析到记录（" + parsed.describe() + "）");
            return;
        }
        try {
            int added = Vault.addAllSkippingDuplicates(this, parsed.entries);
            int skipped = parsed.entries.size() - added;
            toast("导入 " + added + " 条" + (skipped > 0 ? "，跳过重复 " + skipped : ""));
            showTab(1);
        } catch (Exception e) {
            toast("写入失败：" + e.getMessage());
        }
    }

    private void exportSealed() {
        try {
            String sealed = Vault.exportSealed(this);
            File target = writeTextAnywhere(SEALED_EXPORT_NAME, sealed);
            showExportDone("加密备份已导出", target, true);
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    /** Report the full path so the user can actually find the file again. */
    private void showExportDone(String title, File target, boolean safeToShare) {
        String extra = safeToShare
                ? "这份文件本身是加密的，可以安全地放进网盘。"
                : "这份文件是明文的，用完请立即删除。";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("位置：\n" + target.getAbsolutePath() + "\n\n" + extra)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void askExportPlain() {
        new AlertDialog.Builder(this)
                .setTitle("导出明文？")
                .setMessage("明文 CSV 包含所有密码，任何能读到该文件的应用都能看到它们。\n\n"
                        + "只在需要迁移到其他密码管理器时使用，用完立即删除。")
                .setPositiveButton("我知道风险，导出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exportPlain();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportPlain() {
        try {
            StringBuilder csv = new StringBuilder();
            csv.append("title,password,note,tag,updatedAt\n");
            for (Entry entry : Vault.entries()) {
                csv.append(csvField(entry.title)).append(',')
                        .append(csvField(entry.password)).append(',')
                        .append(csvField(entry.note)).append(',')
                        .append(csvField(entry.tag)).append(',')
                        .append(entry.updatedAt).append('\n');
            }
            File target = writeTextAnywhere(PLAIN_EXPORT_NAME, csv.toString());
            showExportDone("明文 CSV 已导出", target, false);
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void askRestoreBackup() {
        final EditText input = textInput("粘贴加密备份的全文");
        input.setMinLines(6);
        input.setGravity(Gravity.TOP);
        final EditText password = passwordInput("该备份的主密码");

        LinearLayout box = column();
        box.addView(input);
        box.addView(password);
        box.addView(hint("导入会覆盖当前数据库中的所有记录。"));

        new AlertDialog.Builder(this)
                .setTitle("恢复加密备份")
                .setCancelable(false)
                .setView(box)
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        char[] chars = password.getText().toString().toCharArray();
                        try {
                            int count = Vault.importSealed(MainActivity.this,
                                    input.getText().toString(), chars);
                            toast("已恢复 " + count + " 条记录");
                            showTab(1);
                        } catch (Exception e) {
                            toast("恢复失败：" + e.getMessage());
                            gate();
                        } finally {
                            com.cuiyi.passwordnotebook.crypto.KeyDerivation.wipe(chars);
                        }
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        gate();
                    }
                })
                .show();
    }

    /**
     * Where to put exports.
     *
     * Public Download is the friendliest place, but on newer Android releases
     * writing there can fail even with the legacy flag, so the app falls back
     * to its own external directory rather than reporting a bare failure. The
     * caller shows whichever path was actually used.
     */
    private List<File> exportCandidates() {
        List<File> candidates = new ArrayList<File>();
        candidates.add(new File("/sdcard/Download"));
        try {
            File external = getExternalFilesDir(null);
            if (external != null) {
                candidates.add(external);
            }
        } catch (Exception ignored) {
            // no external dir on this device
        }
        candidates.add(getFilesDir());
        return candidates;
    }

    /**
     * Write the first location that accepts the data.
     *
     * @return the file that was written
     * @throws Exception with the last failure reason when every location fails
     */
    private File writeTextAnywhere(String fileName, String text) throws Exception {
        Exception lastFailure = null;
        for (File dir : exportCandidates()) {
            if (!dir.exists() && !dir.mkdirs()) {
                continue;
            }
            if (!dir.canWrite()) {
                continue;
            }
            File target = new File(dir, fileName);
            try {
                FileOutputStream out = new FileOutputStream(target);
                try {
                    OutputStreamWriter writer = new OutputStreamWriter(out, UTF8);
                    writer.write(text);
                    writer.flush();
                } finally {
                    out.close();
                }
                return target;
            } catch (Exception e) {
                lastFailure = e;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new Exception("没有可写入的目录");
    }

    // ---------------- small helpers ----------------

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Theme.TEXT_SECONDARY);
        view.setTextSize(Theme.SIZE_BODY);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Theme.TEXT_SECONDARY);
        view.setTextSize(Theme.SIZE_SMALL);
        view.setLineSpacing(dp(4), 1f);
        view.setPadding(dp(4), dp(8), dp(4), dp(8));
        return view;
    }

    private View space(int heightDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(heightDp)));
        return space;
    }

    private TextView button(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(Theme.SIZE_BODY);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(glassCard());
        view.setClickable(true);
        return view;
    }

    /** Same button, but sized to share a row equally. */
    private TextView weightedButton(String text, int color, View.OnClickListener listener) {
        TextView view = button(text, color);
        view.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        view.setOnClickListener(listener);
        return view;
    }

    /** Same button, spanning the full width. */
    private TextView fullButton(String text, int color, View.OnClickListener listener) {
        TextView view = button(text, color);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        view.setOnClickListener(listener);
        return view;
    }

    private EditText textInput(String hintText) {
        EditText field = new EditText(this);
        field.setHint(hintText);
        field.setTextColor(Theme.TEXT_PRIMARY);
        field.setHintTextColor(Theme.TEXT_SECONDARY);
        field.setTextSize(Theme.SIZE_BODY);
        field.setBackground(glassField());
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private EditText passwordInput(String hintText) {
        EditText field = textInput(hintText);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private Switch addSwitch(LinearLayout parent, String label, final String prefKey,
                             boolean defaultValue) {
        LinearLayout row = row();
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(Theme.TEXT_PRIMARY);
        caption.setTextSize(Theme.SIZE_BODY);
        caption.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(caption);

        final Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(prefKey, defaultValue));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.edit().putBoolean(prefKey, checked).apply();
                regenerate();
            }
        });
        row.addView(toggle);
        parent.addView(row);
        return toggle;
    }

    private GradientDrawable glassCard() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Theme.SURFACE);
        drawable.setCornerRadius(dp(Theme.RADIUS_CARD));
        drawable.setStroke(dp(Theme.STROKE_WIDTH), Theme.SURFACE_STROKE);
        return drawable;
    }

    private GradientDrawable glassField() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Theme.FIELD);
        drawable.setCornerRadius(dp(Theme.RADIUS_FIELD));
        return drawable;
    }

    private String formatTime(long millis) {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        return format.format(new java.util.Date(millis));
    }

    private int dp(int value) {
        return Theme.dp(this, value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Adapter so simple watchers do not need to implement all three methods. */
    private abstract static class SimpleWatcher implements TextWatcher {
        public abstract void onChanged();

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            onChanged();
        }
    }
}
