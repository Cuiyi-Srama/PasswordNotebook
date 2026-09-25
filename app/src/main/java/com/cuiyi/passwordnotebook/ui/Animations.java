package com.cuiyi.passwordnotebook.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/**
 * The two motion effects the app uses.
 *
 * Both are self-contained: they own their own handler state and cancel any
 * animation still in flight, so a rapid sequence of taps cannot leave a view
 * stuck at a half-applied scale.
 */
public final class Animations {

    private Animations() {
        throw new AssertionError("no instance");
    }

    /**
     * Press feedback: squash in, then overshoot slightly past 1.0 and settle.
     * Intended for buttons and list cards.
     */
    public static void pressFeedback(final View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate()
                .scaleX(Theme.PRESS_IN)
                .scaleY(Theme.PRESS_IN)
                .setDuration(Theme.PRESS_IN_MS)
                .setInterpolator(new OvershootInterpolator(Theme.PRESS_TENSION))
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (cancelled) {
                            return;
                        }
                        view.animate()
                                .scaleX(Theme.RELEASE_OUT)
                                .scaleY(Theme.RELEASE_OUT)
                                .setDuration(Theme.RELEASE_OUT_MS)
                                .setInterpolator(new OvershootInterpolator(Theme.RELEASE_TENSION))
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        view.animate().scaleX(1f).scaleY(1f)
                                                .setDuration(80L).setListener(null).start();
                                    }
                                })
                                .start();
                    }
                })
                .start();
    }

    /**
     * Types text into a TextView one character at a time, pulsing each glyph.
     *
     * A new call cancels the previous run, and the runnable removes itself
     * once finished, so nothing keeps referencing the view after it is gone.
     */
    public static final class Typewriter {

        private final TextView target;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable active;

        public Typewriter(TextView target) {
            this.target = target;
        }

        /** Start typing. Empty or null text clears the view immediately. */
        public void type(final String text) {
            cancel();
            target.setText("");
            if (text == null || text.isEmpty()) {
                return;
            }
            active = new Runnable() {
                @Override
                public void run() {
                    int shown = target.getText().length();
                    if (shown >= text.length()) {
                        active = null;
                        return;
                    }
                    target.setText(text.substring(0, shown + 1));
                    target.setScaleX(Theme.TYPING_PULSE);
                    target.setScaleY(Theme.TYPING_PULSE);
                    target.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(Theme.TYPING_PULSE_MS)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    handler.postDelayed(this, Theme.TYPING_STEP_MS);
                }
            };
            handler.post(active);
        }

        /** Jump straight to the full text, cancelling any in-flight typing. */
        public void finishWith(String text) {
            cancel();
            target.setText(text == null ? "" : text);
        }

        public void cancel() {
            if (active != null) {
                handler.removeCallbacks(active);
                active = null;
            }
        }
    }

    /**
     * Runs an action once the user has stopped changing an input for a while.
     * Used so typing in the search box does not rebuild the list per keystroke.
     */
    public static final class Debouncer {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable pending;

        public void submit(final Runnable action) {
            submit(action, Theme.SEARCH_DEBOUNCE_MS);
        }

        public void submit(final Runnable action, long delayMillis) {
            cancel();
            pending = action;
            handler.postDelayed(action, delayMillis);
        }

        public void cancel() {
            if (pending != null) {
                handler.removeCallbacks(pending);
                pending = null;
            }
        }
    }
}
