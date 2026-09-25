package com.cuiyi.passwordnotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
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

    private static final String PREFS = "pwd_nb_prefs";
    private static final String KEY_HIDE_ON_BG = "hide_on_bg";
    private static final String KEY_PERIOD_ROLL = "period_roll";
    /** Grace period offered as an alternative to the default always-reask. */
    private static final String KEY_BG_GRACE = "bg_grace";
    private static final long BG_GRACE_MS = 30000L;
    /**
     * Shown on the about page.
     *
     * Hand maintained: this project is built with the SDK tools directly rather
     * than Gradle, so there is no generated BuildConfig to read it from. Kept
     * next to the version passed to aapt2 link so the two stay in step.
     */
    private static final String APP_VERSION = "6.7";

    private static final String KEY_LENGTH = "length";

    /** Whether the recomputable mode uses a device local salt instead of a derived one. */
    private static final String KEY_RANDOM_SALT = "random_salt";
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
    /** Blank layer shown over everything while the app is backgrounded. */
    private View privacyShield;
    private long backgroundedAt;
    /** Pending lock for the end of the grace window, or null. */
    private Runnable graceExpiry;
    private final android.os.Handler mainHandler = new android.os.Handler();

    /** Single background thread for periodic derivation. */
    private final java.util.concurrent.ExecutorService generateExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Incremented per request so a late result cannot overwrite a newer one. */
    private long generateRequest;
    /** Animates the "生成中" label while a derivation is in flight. */
    private Runnable generatingRunnable;
    /** Coalesces keystrokes into one derivation. */
    private Animations.Debouncer generateDebouncer;
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
    /**
     * Set while a preset is being applied.
     *
     * Each setChecked would otherwise fire its listener and launch a separate
     * derivation, so four switches meant four PBKDF2 runs for one tap. The flag
     * coalesces them into a single regeneration at the end.
     */
    private boolean suppressCharsetRegenerate;

    /** Line that explains the size of the currently enabled alphabet. */
    private TextView charsetHint;

    /** Line that says which common cases the current length suits. */
    private TextView lengthHint;

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

    /**
     * Recomputable mode only. Off means the salt is derived, so the password
     * survives losing the device; on means it does not.
     */
    private boolean randomSiteSalt;
    /** Whether the weekly rollover selector is shown at all. */
    private boolean periodicRollEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Blocks screenshots, screen recording and the thumbnail the system
        // keeps for the recents screen. Without it the vault contents are one
        // hardware-button press away from being copied out of the device.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        periodicRollEnabled = prefs.getBoolean(KEY_PERIOD_ROLL, false);
        randomSiteSalt = prefs.getBoolean(KEY_RANDOM_SALT, false);
        // The length was written to preferences on every drag but never read
        // back, so a chosen length silently reverted to the default on the next
        // launch. Clamped here as well because a value stored by an older build
        // could sit outside the range the slider now offers.
        passwordLength = Math.max(PasswordFactory.MIN_LENGTH,
                Math.min(PasswordFactory.MAX_LENGTH,
                        prefs.getInt(KEY_LENGTH, passwordLength)));
        searchDebouncer = new Animations.Debouncer();
        generateDebouncer = new Animations.Debouncer();
        buildShell();
        gate();
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
    /**
     * Covers the interface the moment the app stops being the foreground task,
     * so the recents thumbnail and the first frame after switching back show a
     * blank screen rather than a list of passwords.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (rain != null) {
            rain.pause();
        }
        if (!isChangingConfigurations()) {
            backgroundedAt = System.currentTimeMillis();
            showPrivacyShield();
            scheduleGraceExpiry();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rain != null) {
            rain.resume();
        }
        if (privacyShield != null && privacyShield.getVisibility() == View.VISIBLE) {
            cancelGraceExpiry();
            maybeDropShield();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) {
            return;
        }
        // Locking here unconditionally made the grace period dead code: onStop
        // fires as soon as the app leaves the foreground, so the key was gone
        // before onResume could ever decide the gap was short enough. The key
        // is kept only while the grace period is enabled, and onResume still
        // locks once the window has passed.
        if (!prefs.getBoolean(KEY_BG_GRACE, false)) {
            Vault.lock();
        }
    }

    /**
     * Removes the shield, re-asking for the master password first unless the
     * user opted into the grace period and the gap was short enough.
     *
     * Default is re-ask on every background trip: a password manager that keeps
     * showing the vault after an app switch is one shoulder-surf at a cafe away
     * from leaking everything.
     */
    private void maybeDropShield() {
        long away = System.currentTimeMillis() - backgroundedAt;
        boolean graceEnabled = prefs.getBoolean(KEY_BG_GRACE, false);

        if (graceEnabled && away < BG_GRACE_MS && Vault.isUnlocked()) {
            // Short trip away and the key is still in memory: just uncover.
            hidePrivacyShield();
            return;
        }

        hidePrivacyShield();
        Vault.lock();
        gate();
    }

    /**
     * Locks the vault when the grace period runs out.
     *
     * onResume covers the normal case, but if the user never comes back the key
     * would otherwise sit in memory indefinitely. This posts a lock for the end
     * of the window; the runnable is removed whenever the app resumes in time.
     */
    private void scheduleGraceExpiry() {
        if (graceExpiry != null) {
            mainHandler.removeCallbacks(graceExpiry);
            graceExpiry = null;
        }
        if (!prefs.getBoolean(KEY_BG_GRACE, false)) {
            return;
        }
        graceExpiry = new Runnable() {
            @Override
            public void run() {
                graceExpiry = null;
                Vault.lock();
            }
        };
        mainHandler.postDelayed(graceExpiry, BG_GRACE_MS);
    }

    private void cancelGraceExpiry() {
        if (graceExpiry != null) {
            mainHandler.removeCallbacks(graceExpiry);
            graceExpiry = null;
        }
    }

    private void showPrivacyShield() {
        if (privacyShield == null) {
            return;
        }
        privacyShield.setVisibility(View.VISIBLE);
        privacyShield.bringToFront();
    }

    private void hidePrivacyShield() {
        if (privacyShield != null) {
            privacyShield.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rain != null) {
            rain.release();
        }
        stopGeneratingIndicator();
        if (generateDebouncer != null) {
            generateDebouncer.cancel();
        }
        generateExecutor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        FilePicker.handleResult(this, requestCode, resultCode, data,
                new FilePicker.Listener() {
                    @Override
                    public void onPicked(String text, String displayName) {
                        readAndImport(text, displayName);
                    }
                });
    }

    // ---------------- shell ----------------

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG_DEEP);

        rain = new CyberRainView(this);
        root.addView(rain, new FrameLayout.LayoutParams(-1, -1));

        // Created here but kept hidden until the app leaves the foreground. It
        // sits on top of the rain so nothing of the vault is visible in the
        // recents thumbnail.
        privacyShield = new View(this);
        privacyShield.setBackgroundColor(Theme.BG_DEEP);
        privacyShield.setVisibility(View.GONE);
        privacyShield.setClickable(true);
        root.addView(privacyShield, new FrameLayout.LayoutParams(-1, -1));

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

        // Push the interface clear of the status bar and the gesture nav bar.
        // Drawing edge to edge left the tab row under the clock and the
        // notification icons, which made the tabs hard to hit and hid them
        // behind anything the system drew on top. Uses the platform API only:
        // the project deliberately has no AndroidX dependency, so it cannot
        // call ViewCompat / WindowInsetsCompat here.
        final LinearLayout insetColumn = column;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = 0;
                int bottom = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                insetColumn.setPadding(0, top, 0, 0);
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) insetColumn.getLayoutParams();
                if (params != null) {
                    params.bottomMargin = bottom;
                    insetColumn.setLayoutParams(params);
                }
                return insets;
            }
        });

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
                + "之后可以在“设置”里导入数据。"));

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
        final boolean biometricReady = BiometricKeyStore.isSupported()
                && BiometricKeyStore.hasWrappedKey(this);
        if (biometricReady) {
            builder.setNeutralButton("指纹解锁", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Keep the dialog: the system prompt shows over it, and if
                    // the fingerprint is cancelled the password field is still
                    // there without needing a re-open.
                    unlockWithBiometric(null);
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
        final AlertDialog unlockDialog = builder.show();
        // Fire the biometric prompt unprompted instead of waiting for a tap:
        // the fingerprint is the intended path and the text field is only the
        // fallback, so the common case must not cost an extra tap. The short
        // delay lets the dialog finish animating in before the system prompt
        // covers it, which otherwise looks like two dialogs fighting.
        if (biometricReady) {
            unlockDialog.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    unlockWithBiometric(unlockDialog);
                }
            }, 300L);
        }
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

    /**
     * Runs the fingerprint flow.
     *
     * The unlock dialog is passed in so it can be dismissed once the prompt
     * succeeds. Without that, the dialog stayed on screen after a successful
     * fingerprint because setCancelable(false) keeps the framework from closing
     * it, and the user was left staring at a prompt for a password they no
     * longer needed to type.
     */
    private void unlockWithBiometric(final AlertDialog dialogToDismiss) {
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
                        return;
                    }
                    try {
                        boolean ok = Vault.unlockWithKey(MainActivity.this, recovered);
                        if (ok) {
                            if (dialogToDismiss != null) {
                                dialogToDismiss.dismiss();
                            }
                            hidePrivacyShield();
                            toast("已解锁");
                            showTab(0);
                        } else {
                            BiometricKeyStore.clear(MainActivity.this);
                            toast("指纹凭据与密码库不匹配，已清除");
                        }
                    } catch (Exception e) {
                        toast("解锁失败：" + e.getMessage());
                    } finally {
                        com.cuiyi.passwordnotebook.crypto.KeyDerivation.wipe(recovered);
                    }
                }

                @Override
                public void onFailed(String reason) {
                    // Leaving the dialog up is the point here: the password
                    // field is still the fallback the user needs.
                    toast(reason);
                }
            });
        } catch (Exception e) {
            toast("无法使用指纹：" + e.getMessage());
            BiometricKeyStore.clear(this);
        }
    }

    // ---------------- generator tab ----------------

    private void buildGeneratorTab() {
        LinearLayout root = column();

        // Name the two modes by what they give the user, not by how they work:
        // "random" and "core word" are implementation words, while "可重建" is
        // the property that actually decides which one to pick.
        TextView modeButton = button(periodicMode
                ? "当前：可重建密码　→　点此改为完全随机"
                : "当前：完全随机　→　点此改为可重建密码", Theme.TEXT_ACCENT);
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

        // Fixed height so a longer password cannot shove the controls below it
        // around. Without this every regeneration reflowed the page and the
        // buttons visibly jumped. Long values shrink instead of wrapping onto
        // an ever-growing number of lines.
        ScrollView passwordBox = new ScrollView(this);
        passwordBox.setBackground(glassCard());
        passwordBox.setFillViewport(true);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(Theme.PASSWORD_BOX_DP));
        passwordBox.setLayoutParams(boxParams);
        passwordBox.setVerticalScrollBarEnabled(false);

        passwordView = new TextView(this);
        passwordView.setText("--------");
        passwordView.setTextSize(Theme.SIZE_PASSWORD);
        passwordView.setTextColor(Theme.TEXT_ACCENT);
        passwordView.setTypeface(android.graphics.Typeface.MONOSPACE);
        passwordView.setGravity(Gravity.CENTER);
        passwordView.setPadding(dp(8), dp(12), dp(8), dp(12));
        passwordBox.addView(passwordView, new ScrollView.LayoutParams(-1, -2));
        root.addView(passwordBox);
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
        // buildCharsetControls() runs inside both branches above and binds
        // charsetHint to the freshly created view, so no extra wiring is needed
        // here: switching mode rebuilds the whole tab through showTab().

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

        lengthHint = new TextView(this);
        lengthHint.setTextColor(Theme.TEXT_MUTED);
        lengthHint.setTextSize(Theme.SIZE_TINY);
        lengthHint.setPadding(0, dp(4), 0, 0);
        root.addView(lengthHint);
        updateLengthHint();

        lengthSlider = new SeekBar(this);
        // The slider used to start at 8, so a six digit bank card or phone PIN
        // could not be produced at all even though the generator itself had
        // always accepted four. MIN_LENGTH now drives both ends, so the control
        // and the factory can never disagree again.
        lengthSlider.setMax(PasswordFactory.MAX_LENGTH - PasswordFactory.MIN_LENGTH);
        lengthSlider.setProgress(passwordLength - PasswordFactory.MIN_LENGTH);
        lengthSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                passwordLength = progress + PasswordFactory.MIN_LENGTH;
                lengthLabel.setText(String.valueOf(passwordLength));
                updateLengthHint();
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
        box.addView(section("用哪些字符"));
        box.addView(buildCharsetControls());
        return box;
    }

    /**
     * Character class pickers, presets included.
     *
     * Shared by both modes. They used to exist only in the random mode, which
     * meant a recomputable password was always the full alphabet and a bank
     * card PIN of six digits could not be produced at all.
     *
     * The presets exist because turning four switches off by hand every time is
     * exactly the kind of chore that makes a feature go unused.
     */
    private View buildCharsetControls() {
        LinearLayout box = column();
        // The heading is drawn by the caller. This method used to add its own,
        // and the recomputable branch added a second one above it, so the page
        // showed "用哪些字符" twice in a row.

        // Four short chips on one line. The earlier two by two grid added a
        // second row of tall tiles whose captions repeated what the switches
        // below already say, so the presets took more room than the controls
        // they were meant to shortcut. Short labels fit without wrapping, which
        // was the only reason the grid existed.
        LinearLayout presets = row();
        presets.addView(presetChip("纯数字", false, false, true, false, false));
        presets.addView(presetChip("纯字母", true, true, false, false, false));
        presets.addView(presetChip("字母+数字", true, true, true, false, false));
        presets.addView(presetChip("全部", true, true, true, true, true));
        box.addView(presets);
        box.addView(space(6));

        box.addView(space(2));
        switchUpper = addSwitch(box, "大写 A-Z", KEY_GEN_UPPER, true);
        switchLower = addSwitch(box, "小写 a-z", KEY_GEN_LOWER, true);
        switchDigits = addSwitch(box, "数字 0-9", KEY_GEN_DIGITS, true);
        switchCommon = addSwitch(box, "常见符号", KEY_GEN_COMMON, true);
        switchExtended = addSwitch(box, "扩展符号", KEY_GEN_EXTENDED, false);

        // Only meaningful once the switch can actually be flipped off; the
        // random mode's class count check already refuses impossible lengths.
        charsetHint = new TextView(this);
        charsetHint.setTextColor(Theme.TEXT_MUTED);
        charsetHint.setTextSize(Theme.SIZE_TINY);
        charsetHint.setPadding(0, dp(6), 0, 0);
        box.addView(charsetHint);
        updateCharsetHint();
        return box;
    }

    /**
     * One preset chip.
     *
     * Single line and shallower than a normal button so the row of four reads
     * as a shortcut above the switches rather than as a block of its own. Equal
     * weight keeps them the same width even though the labels differ.
     */
    private TextView presetChip(String label, final boolean upper, final boolean lower,
                                final boolean digits, final boolean common,
                                final boolean extended) {
        TextView view = button(label, Theme.TEXT_ACCENT);
        view.setTextSize(Theme.SIZE_TINY);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dp(4), 0, dp(4), 0);
        view.setBackground(glassCard());
        view.setMinHeight(0);
        LinearLayout.LayoutParams params = weightedParams();
        params.height = dp(32);
        view.setLayoutParams(params);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                applyCharsetPreset(upper, lower, digits, common, extended);
            }
        });
        return view;
    }

    /**
     * Apply a preset to every class switch at once.
     *
     * Guarded so the resulting change notifications do not each kick off a
     * derivation: the switches are set while a flag is raised, then a single
     * regenerate runs once the final state is in place.
     */
    private void applyCharsetPreset(boolean upper, boolean lower, boolean digits,
                                    boolean common, boolean extended) {
        if (switchUpper == null) {
            return;
        }
        suppressCharsetRegenerate = true;
        switchUpper.setChecked(upper);
        switchLower.setChecked(lower);
        switchDigits.setChecked(digits);
        switchCommon.setChecked(common);
        switchExtended.setChecked(extended);
        suppressCharsetRegenerate = false;
        persistCharset();
        updateCharsetHint();
        regenerate();
    }

    /** Writes the current class selection to preferences. */
    private void persistCharset() {
        if (switchUpper == null) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_GEN_UPPER, switchUpper.isChecked())
                .putBoolean(KEY_GEN_LOWER, switchLower.isChecked())
                .putBoolean(KEY_GEN_DIGITS, switchDigits.isChecked())
                .putBoolean(KEY_GEN_COMMON, switchCommon.isChecked())
                .putBoolean(KEY_GEN_EXTENDED, switchExtended.isChecked())
                .apply();
    }

    /**
     * Explains the consequence of a narrow alphabet.
     *
     * A six digit PIN has a million possibilities. No number of PBKDF2 rounds
     * changes that, because the rounds protect the core word and not the output
     * space, so an attacker who knows the core word is what they are after can
     * simply enumerate the result. The line is informational and only appears
     * when the alphabet really is small.
     */
    private void updateCharsetHint() {
        if (charsetHint == null || switchUpper == null) {
            return;
        }
        String alphabet = PasswordFactory.buildAlphabet(
                switchUpper.isChecked(), switchLower.isChecked(), switchDigits.isChecked(),
                switchCommon.isChecked(), switchExtended.isChecked());
        if (alphabet.isEmpty()) {
            charsetHint.setText("至少启用一种字符类型");
            charsetHint.setTextColor(Theme.TEXT_DANGER);
            return;
        }
        charsetHint.setTextColor(Theme.TEXT_MUTED);
        if (alphabet.length() <= 10) {
            charsetHint.setText("可用字符只有 " + alphabet.length()
                    + " 种。适合银行卡/PIN，但密码本身容易被穷举，请靠长度取胜。");
        } else {
            charsetHint.setText("共 " + alphabet.length() + " 种字符可选。");
        }
    }

    /**
     * Controls for the recomputable mode.
     *
     * The two inputs answer different questions and the copy says so explicitly:
     * the core word is the secret the user must keep, and the usage label is a
     * public hint that makes each destination produce a different password. An
     * earlier version called the second one “站点标识” with examples that all
     * assumed websites, which read as if the two fields overlapped and left
     * non-web uses like bank cards or device PINs without an obvious answer.
     *
     * The week selector is hidden until the user turns it on. Almost nobody
     * wants a password that silently changes every Monday, and showing an
     * unexplained “W39” by default only invited confusion.
     */
    private View buildPeriodicControls() {
        LinearLayout box = column();

        box.addView(sectionWithInfo("① 核心词", "核心词",
                "只有你知道的一句话。\n\n"
                        + "记住它，就能重新算出下面这些密码。\n"
                        + "不要填写你在其他任何地方用过的密码。\n"
                        + "这句话不要写进任何云笔记，也别告诉别人。"));

        coreWordField = textInput("例如：一句只有你懂的话");
        // Each keystroke would otherwise start a 350 000 round derivation. The
        // debouncer waits for a pause in typing so only the settled value is
        // derived, which is what keeps typing smooth.
        coreWordField.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onChanged() {
                schedulePeriodicRegenerate();
            }
        });
        box.addView(coreWordField);

        box.addView(space(12));
        box.addView(sectionWithInfoOptional("② 这是给谁用的", "这是给谁用的",
                "可以是网站、App、银行卡、门禁、设备……任何要用密码的地方。\n\n"
                        + "这一项不需要保密，但每个地方要填不一样的内容。\n"
                        + "换一个地方，算出来的密码就完全不同。\n\n"
                        + "留空也可以，但那样所有用途都会算出同一个密码。"));

        siteSaltField = textInput("例如：招商银行 / 淘宝 / iPhone 解锁");
        siteSaltField.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onChanged() {
                schedulePeriodicRegenerate();
            }
        });
        box.addView(siteSaltField);

        box.addView(space(12));
        box.addView(section("③ 用哪些字符"));
        // Reuse the same pickers as the random mode so the checkboxes carry one
        // meaning across the app, rather than two subtly different ones.
        // buildCharsetControls() no longer draws a heading of its own.
        box.addView(buildCharsetControls());
        if (charsetHint != null) {
            charsetHint.setVisibility(View.VISIBLE);
        }

        box.addView(space(12));
        addSpaced(box, buildSaltToggle());

        // The weekly rollover sits last because it is a deliberate choice: the
        // switch carries its own one-line explanation, and the icon spells out
        // the consequence for anyone who wants it.
        addSpaced(box, buildPeriodToggle());
        if (periodicRollEnabled) {
            addSpaced(box, buildPeriodSelector());
        }
        return box;
    }

    /**
     * Chooses how the recomputable mode salts its derivation.
     *
     * Off (derived salt) is the default because it is the only setting that
     * keeps the promise the mode makes. On is offered for the case where the
     * user cares more about offline resistance than about portability, and the
     * description says plainly what it costs: the password becomes tied to this
     * device.
     */
    private View buildSaltToggle() {
        LinearLayout card = row();
        card.setBackground(glassCard());
        card.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("使用随机盐（更安全）");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(Theme.SIZE_BODY);
        titleRow.addView(title);
        titleRow.addView(infoIcon("使用随机盐",
                "关闭（默认）：盐值由核心词和用途标识算出，不保存任何东西。\n"
                        + "只要记得核心词、用途和周次，换手机、换应用也能算出同一个密码。\n\n"
                        + "开启：盐值在本机随机生成并保存，无法从核心词推出。\n"
                        + "攻击者更难离线破解，但代价是\u2014\u2014"
                        + "盐值只存在这台手机上，换了手机、卸了应用、清了数据，"
                        + "这些密码就再也算不出来了。\n\n"
                        + "不上传、不同步，盐值不会出现在备份文件里。"));
        labels.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText(randomSiteSalt
                ? "换手机后无法重建"
                : "换手机也能重建");
        sub.setTextColor(randomSiteSalt ? Theme.TEXT_DANGER : Theme.TEXT_MUTED);
        sub.setTextSize(Theme.SIZE_TINY);
        labels.addView(sub);
        card.addView(labels);

        final Switch toggle = new Switch(this);
        toggle.setChecked(randomSiteSalt);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (checked == randomSiteSalt) {
                    return;
                }
                randomSiteSalt = checked;
                prefs.edit().putBoolean(KEY_RANDOM_SALT, checked).apply();
                // The salt is part of the derivation input, so flipping this
                // changes every recomputable password. Say so before the user
                // discovers it by comparing against a saved record.
                toast(checked
                        ? "已改为随机盐：换手机后这些密码无法重建"
                        : "已改为确定盐：换手机也能重建");
                showTab(0);
            }
        });
        card.addView(toggle);
        return card;
    }

    /**
     * Optional weekly rollover.
     *
     * Off by default and clearly labelled, because a password that changes on
     * its own every week is a niche need and a silent source of lockouts.
     */
    private View buildPeriodToggle() {
        LinearLayout row = row();
        row.setBackground(glassCard());
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("密码每周自动变化");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(Theme.SIZE_BODY);
        titleRow.addView(title);
        titleRow.addView(infoIcon("密码每周自动变化",
                "开启后，同一个用途在这周和下周会得到不同密码。\n\n"
                        + "适合公司 WiFi 这类需要定期换密码的场景。\n"
                        + "日常账号建议保持关闭：关闭时一个用途永远对应同一个密码，"
                        + "不会因为忘记哪一周而算不出来。"));
        labels.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText("同一用途，这周与下周得到不同密码");
        sub.setTextColor(Theme.TEXT_MUTED);
        sub.setTextSize(Theme.SIZE_TINY);
        labels.addView(sub);
        row.addView(labels);

        final Switch toggle = new Switch(this);
        toggle.setChecked(periodicRollEnabled);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                periodicRollEnabled = checked;
                prefs.edit().putBoolean(KEY_PERIOD_ROLL, checked).apply();
                // Rebuild so the selector appears or disappears with the switch.
                showTab(0);
            }
        });
        row.addView(toggle);
        return row;
    }

    /**
     * Week picker, shown only when rollover is on.
     *
     * The label spells out what the number means instead of printing a bare
     * “W39”, and the buttons say what they do.
     */
    /**
     * A one-line label with the explanation tucked behind it.
     *
     * The generator page used to print two or three grey paragraphs under every
     * heading. They were useful once and noise afterwards, and they were the
     * main reason the page read as cluttered. Here the summary stays visible
     * and the detail appears on tap, so nothing is lost and the default view is
     * quiet.
     *
     * @param extra optional control shown beneath the detail once expanded
     */
    private View infoIcon(final String summary, final String detail) {
        TextView icon = new TextView(this);
        icon.setText("ⓘ");
        icon.setTextColor(Theme.TEXT_ACCENT);
        icon.setTextSize(15f);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(dp(10), dp(4), dp(2), dp(4));
        icon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(summary)
                        .setMessage(detail)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
        return icon;
    }

    /**
     * Explains what kind of password the current length suits.
     *
     * The generator used to hide its lower bound entirely: the slider simply
     * started at eight with no hint that shorter values existed. Making the
     * bound visible is only half of it; the other half is saying which common
     * cases live at which length.
     */
    private void updateLengthHint() {
        if (lengthHint == null) {
            return;
        }
        lengthHint.setText(lengthComment(passwordLength));
        // The low end is tinted differently on purpose. It is the one range
        // where a casual reader might take the friendly wording as a
        // recommendation, so it has to look like a caveat and not like praise.
        lengthHint.setTextColor(passwordLength <= 6
                ? Theme.TEXT_SECONDARY
                : Theme.TEXT_MUTED);
    }

    /**
     * Plain language for what a given length is good for.
     *
     * The wording is deliberately informal, but the ordering never changes: the
     * short end stays marked as a niche, and the very long end is a joke rather
     * than a suggestion. A hint that read as "longer is better, keep going"
     * would be actively unhelpful because almost nothing accepts those values.
     */
    private String lengthComment(int length) {
        if (length <= 4) {
            return "老式设备的老长度，能过校验就行";
        }
        if (length == 5) {
            return "少见，某些旧系统还在用";
        }
        if (length == 6) {
            return "银行卡、锁屏密码的标准长度";
        }
        if (length <= 8) {
            return "不少网站的底线要求";
        }
        if (length <= 10) {
            return "网站常规长度，够用";
        }
        if (length <= 12) {
            return "主流推荐长度";
        }
        if (length <= 16) {
            return "很扎实，大多数地方都能用";
        }
        if (length <= 20) {
            return "相当长，安全性很充足";
        }
        if (length <= 24) {
            return "很稳，不过恐怕有些网站要先抱怨一句";
        }
        if (length <= 32) {
            return "密码管理器的最爱";
        }
        if (length <= 40) {
            return "手动输入大概是场修行";
        }
        if (length <= 48) {
            return "基本只能靠粘贴了";
        }
        if (length <= 64) {
            return "这个长度已经能当加密密钥用了";
        }
        if (length <= 80) {
            return "真的有网站会接受吗？";
        }
        if (length <= 99) {
            return "已经开始离谱，但还没到极限";
        }
        if (length <= 110) {
            return "这么长的密码你要干啥！";
        }
        if (length <= 120) {
            return "粘贴都会嫌它占地方";
        }
        // MAX_LENGTH is 128, so this is the last stop. Kept as a plain branch
        // rather than a fallback so an out of range value cannot slip through
        // silently if the limits are ever widened.
        return "128 位，好吧，你赢了";
    }

    private View buildPeriodSelector() {
        LinearLayout outer = column();
        outer.setBackground(glassCard());
        outer.setPadding(dp(12), dp(10), dp(12), dp(10));

        final TextView caption = new TextView(this);
        caption.setText(periodCaption());
        caption.setTextColor(Theme.TEXT_PRIMARY);
        caption.setTextSize(Theme.SIZE_BODY);
        caption.setGravity(Gravity.CENTER);
        outer.addView(caption);

        LinearLayout buttons = row();
        TextView previous = button("上一周", Theme.TEXT_ACCENT);
        previous.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                shiftPeriod(-1);
                caption.setText(periodCaption());
                regenerate();
            }
        });
        TextView reset = button("回到本周", Theme.TEXT_SECONDARY);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                // 0/0 tells the factory to use the current period, so this also
                // re-follows the week automatically from then on.
                periodYear = 0;
                periodWeek = 0;
                caption.setText(periodCaption());
                regenerate();
            }
        });
        TextView next = button("下一周", Theme.TEXT_ACCENT);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                shiftPeriod(1);
                caption.setText(periodCaption());
                regenerate();
            }
        });
        buttons.addView(previous);
        buttons.addView(reset);
        buttons.addView(next);
        outer.addView(buttons);
        return outer;
    }

    /** Human readable form of the selected period. */
    private String periodCaption() {
        if (!periodicRollEnabled) {
            return "";
        }
        if (periodYear == 0 && periodWeek == 0) {
            int[] now = PasswordFactory.currentPeriod();
            return "本周（" + now[0] + " 年第 " + now[1] + " 周）";
        }
        return periodYear + " 年第 " + periodWeek + " 周";
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
        if (periodicMode) {
            regeneratePeriodicAsync();
            return;
        }
        // Random is instant, so it stays on the main thread.
        String password;
        try {
            password = PasswordFactory.random(passwordLength,
                    switchUpper.isChecked(), switchLower.isChecked(),
                    switchDigits.isChecked(), switchCommon.isChecked(),
                    switchExtended.isChecked());
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            typewriter.finishWith(message == null || message.isEmpty()
                    ? "无法生成：参数不合法" : message);
            strengthView.setText("");
            return;
        } catch (Exception e) {
            typewriter.finishWith("生成失败：" + e.getMessage());
            strengthView.setText("");
            return;
        }
        applyGeneratedPassword(password);
    }

    /**
     * Runs the derivation once typing pauses.
     *
     * Only used by the text fields. Buttons and the slider call regenerate()
     * directly because there the user has already committed to one action and a
     * delay would just feel unresponsive.
     */
    private void schedulePeriodicRegenerate() {
        generateDebouncer.submit(new Runnable() {
            @Override
            public void run() {
                regenerate();
            }
        }, Theme.GENERATE_DEBOUNCE_MS);
    }

    /**
     * Periodic derivation on a worker thread.
     *
     * PBKDF2 at 350 000 rounds costs a few hundred milliseconds, which is long
     * enough to drop a visible amount of frames when run on the UI thread: the
     * page froze mid-tap and the animation stuttered. The work now runs on a
     * single background executor and the interface shows a counting hint in the
     * meantime, so the wait is legible instead of looking like a hang.
     *
     * Results are tagged with a request id. Typing quickly during a slow
     * derivation would otherwise let an older result land after a newer one and
     * leave the wrong password on screen.
     */
    private void regeneratePeriodicAsync() {
        final String core = coreWordField == null ? "" : coreWordField.getText().toString();
        final String site = siteSaltField == null ? "" : siteSaltField.getText().toString();
        if (core.trim().isEmpty()) {
            typewriter.finishWith("请输入核心词");
            strengthView.setText("");
            return;
        }
        final int[] period = PasswordFactory.currentPeriod();
        final int year = periodYear == 0 ? period[0] : periodYear;
        final int week = periodWeek == 0 ? period[1] : periodWeek;
        final int length = passwordLength;
        final byte[] salt = siteSalt(site, core);
        final long requestId = ++generateRequest;
        // Read the checkboxes on the UI thread and hand plain booleans to the
        // worker: touching a View from a background thread is undefined
        // behaviour even for a read this simple.
        final boolean upper = switchUpper == null || switchUpper.isChecked();
        final boolean lower = switchLower == null || switchLower.isChecked();
        final boolean digits = switchDigits == null || switchDigits.isChecked();
        final boolean common = switchCommon == null || switchCommon.isChecked();
        final boolean extended = switchExtended != null && switchExtended.isChecked();

        showGeneratingIndicator();
        generateExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String result = null;
                String error = null;
                try {
                    result = PasswordFactory.periodic(core, site, salt, year, week, length,
                            upper, lower, digits, common, extended);
                } catch (IllegalArgumentException e) {
                    error = e.getMessage() == null ? "参数不合法" : e.getMessage();
                } catch (Exception e) {
                    error = "生成失败：" + e.getMessage();
                }
                final String finalResult = result;
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (requestId != generateRequest) {
                            // A newer request superseded this one.
                            return;
                        }
                        if (finalResult != null) {
                            applyGeneratedPassword(finalResult);
                        } else {
                            typewriter.finishWith(finalError == null ? "生成失败" : finalError);
                            strengthView.setText("");
                        }
                    }
                });
            }
        });
    }

    /** Paints a finished password and its strength rating. */
    private void applyGeneratedPassword(String password) {
        stopGeneratingIndicator();
        typewriter.type(password);
        fitPasswordText(password);
        int score = PasswordFactory.strength(password);
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            stars.append(i < score ? "★" : "☆");
        }
        strengthView.setText(stars.toString());
    }

    /**
     * Placeholder shown while the derivation runs.
     *
     * A static label would read as a frozen app, so the dots advance on a timer
     * and the existing typewriter pulse keeps the movement consistent with the
     * rest of the interface.
     */
    private void showGeneratingIndicator() {
        if (generatingRunnable != null) {
            mainHandler.removeCallbacks(generatingRunnable);
        }
        generatingRunnable = new Runnable() {
            private int dots;

            @Override
            public void run() {
                StringBuilder text = new StringBuilder(Theme.GENERATING_LABEL);
                for (int i = 0; i < dots; i++) {
                    text.append('.');
                }
                typewriter.finishWith(text.toString());
                dots = (dots + 1) % 4;
                mainHandler.postDelayed(this, 220L);
            }
        };
        mainHandler.post(generatingRunnable);
    }

    private void stopGeneratingIndicator() {
        if (generatingRunnable != null) {
            mainHandler.removeCallbacks(generatingRunnable);
            generatingRunnable = null;
        }
    }

    /**
     * Shrinks the password text until it fits the reserved box.
     *
     * The box height is fixed, so without this a long password would either be
     * clipped or scroll, both of which are worse than a smaller font. The size
     * only ever steps down to SIZE_PASSWORD_MIN; past that the box scrolls.
     */
    private void fitPasswordText(String password) {
        if (passwordView == null) {
            return;
        }
        int length = password == null ? 0 : password.length();
        float size = Theme.SIZE_PASSWORD;
        if (length > Theme.PASSWORD_FULL_FIT) {
            // Scale down proportionally with how far past the comfortable
            // length we are, clamped so the text never becomes unreadable.
            float ratio = (float) Theme.PASSWORD_FULL_FIT / length;
            size = Math.max(Theme.SIZE_PASSWORD_MIN, Theme.SIZE_PASSWORD * ratio);
        }
        passwordView.setTextSize(size);
    }

    /**
     * Salt for the recomputable mode.
     *
     * Two behaviours, chosen by the user because they trade a real property:
     *
     *   deterministic - derived from the core word and the usage label, nothing
     *                   stored. The same three inputs always reproduce the
     *                   password, on any device, which is the entire point of
     *                   the mode. An attacker who is testing core word guesses
     *                   can do so offline, so the iteration count carries the
     *                   cost of a weak core word.
     *
     *   random        - a fresh salt stored on this device only. Harder to
     *                   attack offline, but the salt lives nowhere else, so
     *                   losing the device means the password can never be
     *                   derived again. Users who pick this are warned.
     *
     * The random path also reaches back to salts written by older builds so an
     * existing password keeps deriving after the upgrade.
     */
    private byte[] siteSalt(String site, String coreWord) {
        if (!randomSiteSalt) {
            byte[] derived = PasswordFactory.deterministicSiteSalt(coreWord, site);
            if (derived != null) {
                return derived;
            }
            // No core word yet; the caller shows "请输入核心词" before this can
            // matter, but a null here would be a crash, so keep it total.
        }
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

        root.addView(space(6));
        addSpaced(root, buildBiometricRow());
        addSpaced(root, buildBackgroundRow());
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
        root.addView(fullButton("导入数据", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askImportLegacy();
            }
        }));
        root.addView(hint("从文件或粘贴内容导入。支持旧版加密备份、逐行加密文本、"
                + "Tab/逗号分隔的明文，以及“名称一行、密码一行”的双行格式。重复条目自动跳过。"));
        root.addView(space(6));
        root.addView(fullButton("导出加密备份", Theme.TEXT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                exportSealed();
            }
        }));
        root.addView(space(6));
        root.addView(fullButton("从备份恢复整库（覆盖）", Theme.TEXT_DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askRestoreBackup();
            }
        }));
        root.addView(hint("“从备份恢复整库”会清空当前全部记录再写入备份内容，请谨慎操作。"));
        root.addView(space(6));
        root.addView(fullButton("导出明文 CSV（危险）", Theme.TEXT_DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animations.pressFeedback(v);
                askExportPlain();
            }
        }));

        root.addView(section("关于"));
        root.addView(hint("密码小本 " + APP_VERSION + "\n\n"
                + "数据使用 AES-256-GCM 加密，密钥由主密码通过 PBKDF2-HMAC-SHA256 派生，"
                + "迭代次数按本机性能校准。主密码不保存，忘记则无法恢复。\n\n"
                + "可重建密码的派生输入只有核心词、用途标识和周次，不含任何本机数据："
                + "只要记住这三样，换设备、换应用也能算出同一个密码。\n\n"
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
    /**
     * Controls what happens when the app comes back from the background.
     *
     * The default is to re-ask every time. The switch trades a little of that
     * for convenience by allowing a short grace period, which is what most
     * people actually want when they glance at a message and come straight
     * back. Anything longer than the window still forces a re-ask.
     */
    private View buildBackgroundRow() {
        LinearLayout row = row();
        row.setBackground(glassCard());
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView label = new TextView(this);
        label.setText("切回时 30 秒内免验证");
        label.setTextColor(Theme.TEXT_PRIMARY);
        label.setTextSize(Theme.SIZE_BODY);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(label);

        final Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(KEY_BG_GRACE, false));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.edit().putBoolean(KEY_BG_GRACE, checked).apply();
                toast(checked
                        ? "30 秒内切回不需验证，超过则需重新验证"
                        : "每次切回都需要验证");
            }
        });
        row.addView(toggle);
        return row;
    }

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
                .setTitle("导入数据")
                .setMessage("选择导入方式。两种方式效果相同，文件方式更适合长内容。")
                .setPositiveButton("选择文件", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pickBackupFile();
                    }
                })
                .setNeutralButton("粘贴内容", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        askPasteImport();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickBackupFile() {
        try {
            FilePicker.show(this);
        } catch (Exception e) {
            toast("无法打开文件选择器：" + e.getMessage());
        }
    }

    /** Called from onActivityResult once the system picker returns a document. */
    private void readAndImport(String text, String displayName) {
        if (text == null) {
            toast("读取失败，换一个文件试试");
            return;
        }
        if (text.trim().isEmpty()) {
            toast("文件是空的");
            return;
        }
        confirmImport(text, displayName == null ? "所选文件" : displayName);
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

    /**
     * Gives a stacked card the same breathing room its neighbours have.
     *
     * Used by the settings and generator cards, which are added straight to a
     * vertical container. Without it two adjacent cards each draw their own
     * rounded border with nothing between them and the pair reads as one thick
     * outline instead of two separate rows.
     */
    private void addSpaced(LinearLayout parent, View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(Theme.SPACE_BLOCK);
        card.setLayoutParams(params);
        parent.addView(card);
    }

    /**
     * Margin for a control that shares its row with others.
     *
     * Only the weight based buttons get a gap, and it is applied to the button
     * instead of the row. A container wide gutter would also separate label
     * pairs such as "字符数 16" that are meant to read as one line, and a
     * transparent spacer view would compete with the button's layout weight and
     * shrink every button in the row.
     */
    private LinearLayout.LayoutParams weightedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        int gap = dp(Theme.SPACE_INLINE);
        params.setMargins(gap / 2, 0, gap / 2, 0);
        return params;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Theme.TEXT_SECONDARY);
        view.setTextSize(Theme.SIZE_BODY);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    /**
     * Section heading with the help icon on the same line.
     *
     * The help used to be a separate row under the heading, printing a one line
     * summary that the dialog then repeated in full. That made every field two
     * rows of text for no extra information, so the summary is gone and only the
     * icon remains, moved up beside the label where it reads as part of the
     * heading rather than as body copy.
     */
    private View sectionWithInfo(String title, String dialogTitle, String detail) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(18), 0, dp(8));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Theme.TEXT_SECONDARY);
        label.setTextSize(Theme.SIZE_BODY);
        line.addView(label);

        line.addView(infoIcon(dialogTitle, detail));
        return line;
    }

    /** Section heading carrying an optional marker, e.g. for a field that may be left blank. */
    private View sectionWithInfoOptional(String title, String dialogTitle, String detail) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(18), 0, dp(8));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Theme.TEXT_SECONDARY);
        label.setTextSize(Theme.SIZE_BODY);
        line.addView(label);

        TextView optional = new TextView(this);
        optional.setText("可选");
        optional.setTextColor(Theme.TEXT_MUTED);
        optional.setTextSize(Theme.SIZE_TINY);
        optional.setPadding(dp(8), dp(2), 0, 0);
        line.addView(optional);

        line.addView(infoIcon(dialogTitle, detail));
        return line;
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
        view.setPadding(dp(10), dp(11), dp(10), dp(11));
        view.setBackground(glassCard());
        view.setClickable(true);
        // A minimum height keeps short and long labels the same size, so a row
        // of buttons lines up instead of looking like stray boxes.
        view.setMinHeight(dp(44));
        return view;
    }

    /** Same button, but sized to share a row equally. */
    private TextView weightedButton(String text, int color, View.OnClickListener listener) {
        TextView view = button(text, color);
        view.setLayoutParams(weightedParams());
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
                // Presets drive several switches in one tap; while that is in
                // progress each notification must stay silent or the page would
                // derive once per switch instead of once per tap.
                if (suppressCharsetRegenerate) {
                    return;
                }
                updateCharsetHint();
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
