package dev.jason.gboardpatches.extension.macbridge;

import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;

import java.util.Locale;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

/**
 * "Dictate to Mac": listens on the phone and types on the Mac. While the user
 * talks, what has been heard is shown over the keyboard and previewed on the Mac
 * (`partial`); Send stops listening and sends the whole dictation as one `text`,
 * which the Mac types into its focused app. Main thread only.
 */
final class MacBridgeDictation {
    private static final String TAG = "GboardMacBridge";
    private static final long PARTIAL_INTERVAL_MS = 150L;
    /** How long Send waits for the recognizer's last words before using what it has. */
    private static final long FINISH_TIMEOUT_MS = 4_000L;
    private static final int FALLBACK_PANEL_HEIGHT_DP = 260;

    interface Output {
        void sendText(String text, boolean pressEnter);

        void sendPartial(String text);

        void toast(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputMethodService ime;
    private final Output output;
    private final String macName;
    private final MacBridgeSpeech speech;
    private MacBridgeDictationPanel panel;
    private String language;
    private String latest = "";
    private boolean pressEnter;
    private boolean finishing;
    private long lastPartialAt;
    private final Runnable finishTimeout = this::finishWithLatest;

    private MacBridgeDictation(InputMethodService ime, Output output, String macName) {
        this.ime = ime;
        this.output = output;
        this.macName = macName;
        this.speech = new MacBridgeSpeech(ime);
    }

    /** Opens the panel and starts listening, or returns null when it can't be shown. */
    static MacBridgeDictation start(InputMethodService ime, Output output, String macName) {
        MacBridgeDictation dictation = new MacBridgeDictation(ime, output, macName);
        if (!dictation.showPanel()) {
            return null;
        }
        dictation.listen(keyboardLanguage(ime));
        return dictation;
    }

    boolean isActive() {
        return panel != null;
    }

    /** Drops the dictation (keyboard hidden, or another one started). */
    void cancel() {
        main.removeCallbacks(finishTimeout);
        speech.cancel();
        removePanel();
    }

    // --- Panel ----------------------------------------------------------------------

    private boolean showPanel() {
        try {
            Window window = ime.getWindow() == null ? null : ime.getWindow().getWindow();
            if (window == null) {
                return false;
            }
            View decor = window.getDecorView();
            MacBridgeDictationPanel created = new MacBridgeDictationPanel(ime, actions,
                    text(R.string.gboard_patches_mac_bridge_panel_hint),
                    text(R.string.gboard_patches_mac_bridge_panel_cancel),
                    text(R.string.gboard_patches_mac_bridge_panel_send_enter),
                    text(R.string.gboard_patches_mac_bridge_panel_send));
            View inputArea = findInputArea(decor);
            int screenHeight = ime.getResources().getDisplayMetrics().heightPixels;
            if (inputArea instanceof FrameLayout area && area.getHeight() > 0) {
                // Over the keyboard, inside the area Gboard keeps touchable. An input
                // area taller than a keyboard (space kept for popups) gets a bottom panel.
                int height = area.getHeight() <= screenHeight * 0.6f
                        ? ViewGroup.LayoutParams.MATCH_PARENT : dp(FALLBACK_PANEL_HEIGHT_DP);
                area.addView(created, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));
            } else if (decor instanceof FrameLayout frame) {
                int height = inputArea != null && inputArea.getHeight() > 0
                        ? inputArea.getHeight() : dp(FALLBACK_PANEL_HEIGHT_DP);
                frame.addView(created, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));
            } else {
                return false;
            }
            panel = created;
            panel.setStatus(format(R.string.gboard_patches_mac_bridge_panel_listening, macName));
            return true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not show the dictation panel", failure);
            return false;
        }
    }

    private static View findInputArea(View decor) {
        int id = Resources.getSystem().getIdentifier("inputArea", "id", "android");
        return id == 0 ? null : decor.findViewById(id);
    }

    private void removePanel() {
        MacBridgeDictationPanel current = panel;
        panel = null;
        if (current != null && current.getParent() instanceof ViewGroup parent) {
            parent.removeView(current);
        }
    }

    private final MacBridgeDictationPanel.Actions actions = new MacBridgeDictationPanel.Actions() {
        @Override
        public void onCancel() {
            cancel();
        }

        @Override
        public void onSend(boolean enter) {
            if (finishing || panel == null) {
                return;
            }
            finishing = true;
            pressEnter = enter;
            panel.setBusy(text(R.string.gboard_patches_mac_bridge_panel_sending));
            speech.stop();
            main.postDelayed(finishTimeout, FINISH_TIMEOUT_MS);
        }

        @Override
        public void onToggleLanguage() {
            if (finishing) {
                return;
            }
            listen(MacBridgeSpeech.LANGUAGE_BENGALI.equals(language)
                    ? MacBridgeSpeech.LANGUAGE_ENGLISH : MacBridgeSpeech.LANGUAGE_BENGALI);
        }
    };

    // --- Speech ---------------------------------------------------------------------

    private void listen(String newLanguage) {
        language = newLanguage;
        latest = "";
        if (panel != null) {
            panel.setLanguageLabel(MacBridgeSpeech.LANGUAGE_BENGALI.equals(language) ? "বাং" : "EN");
            panel.setTranscript("");
        }
        speech.start(language, callback);
    }

    private final MacBridgeSpeech.Callback callback = new MacBridgeSpeech.Callback() {
        @Override
        public void onPartial(String text) {
            latest = text;
            if (panel != null) {
                panel.setTranscript(text);
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                lastPartialAt = now;
                output.sendPartial(text);
            }
        }

        @Override
        public void onFinal(String text) {
            if (!finishing) {
                // The recognizer gave up on its own; keep what was heard on screen.
                finishing = true;
                pressEnter = false;
            }
            deliver(text != null ? text : latest);
        }

        @Override
        public void onError(int code) {
            main.removeCallbacks(finishTimeout);
            removePanel();
            output.toast(errorMessage(code));
        }

        @Override
        public void onLevel(float level) {
            if (panel != null) {
                panel.setLevel(level);
            }
        }
    };

    private void finishWithLatest() {
        speech.cancel();
        deliver(latest);
    }

    private void deliver(String text) {
        main.removeCallbacks(finishTimeout);
        removePanel();
        String dictation = text == null ? "" : text.trim();
        if (dictation.isEmpty()) {
            output.toast(text(R.string.gboard_patches_mac_bridge_toast_not_heard));
            return;
        }
        output.sendText(dictation, pressEnter);
    }

    private String errorMessage(int code) {
        return switch (code) {
            case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                    text(R.string.gboard_patches_mac_bridge_toast_speech_network);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    text(R.string.gboard_patches_mac_bridge_toast_mic_permission);
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    text(R.string.gboard_patches_mac_bridge_toast_speech_language);
            default -> format(R.string.gboard_patches_mac_bridge_toast_speech_failed, code);
        };
    }

    // --- Helpers --------------------------------------------------------------------

    /** Bengali when the keyboard is set to Bengali, English otherwise. */
    static String keyboardLanguage(InputMethodService ime) {
        try {
            InputMethodManager manager = ime.getSystemService(InputMethodManager.class);
            InputMethodSubtype subtype = manager == null ? null
                    : manager.getCurrentInputMethodSubtype();
            if (subtype != null) {
                String tag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? subtype.getLanguageTag() : "";
                if (tag == null || tag.isEmpty()) {
                    tag = subtype.getLocale();
                }
                if (tag != null && tag.toLowerCase(Locale.ROOT).startsWith("bn")) {
                    return MacBridgeSpeech.LANGUAGE_BENGALI;
                }
            }
        } catch (RuntimeException ignored) {
            // English it is.
        }
        return MacBridgeSpeech.LANGUAGE_ENGLISH;
    }

    private String text(int resourceId) {
        return GboardSettingsText.get(ime, resourceId);
    }

    private String format(int resourceId, Object... args) {
        return GboardSettingsText.format(ime, resourceId, args);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ime.getResources().getDisplayMetrics()));
    }
}
