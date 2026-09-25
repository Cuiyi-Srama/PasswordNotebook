package com.cuiyi.passwordnotebook.ui;

import android.app.Activity;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import javax.crypto.Cipher;

/**
 * A thin wrapper over the two fingerprint APIs Android has shipped.
 *
 * API 23 to 27 only have FingerprintManager, which speaks CryptoObject and has
 * no built in dialog. API 28 and up have BiometricPrompt, which does. Rather
 * than pull in the AndroidX biometric library, this class hides the split
 * behind one call and returns a small prompt object the caller drives.
 *
 * The CryptoObject path is what makes the fingerprint meaningful: the platform
 * will not release the Keystore key for the cipher unless the authentication
 * succeeded, so a spoofed callback cannot produce a usable key.
 */
public final class BiometricPromptFactory {

    /** What the caller gets back. */
    public interface Callback {
        /** Authentication passed; the cipher is now authorised to doFinal. */
        void onSucceeded(Cipher cipher);

        /** Authentication did not complete. The reason is user readable. */
        void onFailed(String reason);
    }

    /** A prompt that has been configured but not yet shown. */
    public interface BiometricPrompt {
        void authenticate(Cipher cipher, Callback callback);
    }

    private BiometricPromptFactory() {
        throw new AssertionError("no instance");
    }

    /**
     * Build a prompt for this device, or null when fingerprints are unavailable.
     */
    public static BiometricPrompt create(Activity activity, String title, String subtitle) {
        if (activity == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return new ModernPrompt(activity, title, subtitle);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new LegacyPrompt(activity, title, subtitle);
        }
        return null;
    }

    // ---------------- API 28+ ----------------

    private static final class ModernPrompt implements BiometricPrompt {

        private final Activity activity;
        private final String title;
        private final String subtitle;

        ModernPrompt(Activity activity, String title, String subtitle) {
            this.activity = activity;
            this.title = title;
            this.subtitle = subtitle;
        }

        @Override
        public void authenticate(Cipher cipher, final Callback callback) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder builder =
                        new android.hardware.biometrics.BiometricPrompt.Builder(activity)
                                .setTitle(title)
                                .setSubtitle(subtitle)
                                .setNegativeButton("取消", activity.getMainExecutor(),
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int w) {
                                                callback.onFailed("已取消");
                                            }
                                        });

                android.hardware.biometrics.BiometricPrompt prompt = builder.build();
                android.hardware.biometrics.BiometricPrompt.CryptoObject cryptoObject =
                        new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher);

                prompt.authenticate(cryptoObject, new CancellationSignal(),
                        activity.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                                Cipher authorised = result.getCryptoObject() == null
                                        ? null : result.getCryptoObject().getCipher();
                                if (authorised == null) {
                                    callback.onFailed("验证结果无效");
                                } else {
                                    callback.onSucceeded(authorised);
                                }
                            }

                            @Override
                            public void onAuthenticationFailed() {
                                // Fired for a single bad scan; the prompt stays up,
                                // so there is nothing useful to report yet.
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                callback.onFailed(message == null ? "验证失败" : message.toString());
                            }
                        });
            } catch (Exception e) {
                callback.onFailed("无法启动指纹验证：" + e.getMessage());
            }
        }
    }

    // ---------------- API 23 to 27 ----------------

    private static final class LegacyPrompt implements BiometricPrompt {

        private final Activity activity;
        private final String title;
        private final String subtitle;

        LegacyPrompt(Activity activity, String title, String subtitle) {
            this.activity = activity;
            this.title = title;
            this.subtitle = subtitle;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void authenticate(Cipher cipher, final Callback callback) {
            FingerprintManager manager =
                    (FingerprintManager) activity.getSystemService(Context.FINGERPRINT_SERVICE);
            if (manager == null) {
                callback.onFailed("此设备没有指纹硬件");
                return;
            }
            try {
                if (!manager.isHardwareDetected()) {
                    callback.onFailed("此设备没有指纹硬件");
                    return;
                }
                if (!manager.hasEnrolledFingerprints()) {
                    callback.onFailed("请先在系统设置里录入指纹");
                    return;
                }
            } catch (SecurityException e) {
                callback.onFailed("没有使用指纹的权限");
                return;
            }

            FingerprintManager.CryptoObject cryptoObject =
                    new FingerprintManager.CryptoObject(cipher);
            final CancellationSignal cancellationSignal = new CancellationSignal();

            manager.authenticate(cryptoObject, cancellationSignal, 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                FingerprintManager.AuthenticationResult result) {
                            Cipher authorised = result.getCryptoObject() == null
                                    ? null : result.getCryptoObject().getCipher();
                            if (authorised == null) {
                                callback.onFailed("验证结果无效");
                            } else {
                                callback.onSucceeded(authorised);
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // One bad scan; keep waiting.
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence message) {
                            callback.onFailed(message == null ? "验证失败" : message.toString());
                        }
                    }, new Handler(Looper.getMainLooper()));
        }
    }
}
