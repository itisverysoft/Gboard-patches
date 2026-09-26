package dev.jason.gboardpatches.extension.macbridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.UUID;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

/**
 * Keyboard-side glue for Mac Bridge. While the keyboard is showing (and for a
 * minute after it hides) it stays connected to the paired Mac, so text sent from
 * the Mac lands on the clipboard, where Gboard offers it. The toolbar's "Send to
 * Mac" types the selected text, or the whole field, on the Mac. There is no
 * service: when the keyboard is gone the Mac holds clips for ten minutes and
 * delivers them the next time it shows.
 *
 * Lifecycle entry points run on the main thread and must never throw into Gboard.
 */
public final class GboardMacBridgeRuntime {
    private static final String TAG = "GboardMacBridge";
    private static final long LINGER_MS = 60_000L;
    /** Keeps a line well under the Mac's 256 KiB frame limit even when fully escaped. */
    private static final int MAX_SEND_CHARS = 40_000;
    private static final int MAX_FIELD_READ_CHARS = MAX_SEND_CHARS + 1;
    private static final String CLIP_LABEL = "Mac Bridge";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable RELEASE = GboardMacBridgeRuntime::release;
    private static final Object LOCK = new Object();

    private static WeakReference<InputMethodService> service = new WeakReference<>(null);
    private static volatile Boolean featurePresent;
    private static MacBridgeClient client;
    private static Context applicationContext;

    private GboardMacBridgeRuntime() {
    }

    /** After Loup;->onStartInputView: hold the Mac connection while the keyboard shows. */
    public static void onInputViewStarted(Object receiver) {
        try {
            if (!(receiver instanceof InputMethodService ime) || !hasFeature(ime)) {
                return;
            }
            service = new WeakReference<>(ime);
            MAIN.removeCallbacks(RELEASE);
            hold(ime);
        } catch (Throwable failure) {
            logFailure("Mac Bridge input-view hook failed", failure);
        }
    }

    /** After Loup;->onWindowHidden: let the connection linger, then let it go. */
    public static void onInputWindowHidden() {
        try {
            if (client() == null) {
                return;
            }
            MAIN.removeCallbacks(RELEASE);
            MAIN.postDelayed(RELEASE, LINGER_MS);
        } catch (Throwable failure) {
            logFailure("Mac Bridge window-hidden hook failed", failure);
        }
    }

    /** Whether the toolbar should offer "Send to Mac". */
    static boolean isToolbarAvailable(Context context) {
        try {
            return hasFeature(context) && MacBridgeStore.isEnabled(context)
                    && MacBridgeStore.device(context) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The toolbar's "Send to Mac": the selection, or the whole field when nothing
     * is selected, goes to the Mac's clipboard and is typed into its focused field.
     */
    static void sendFromKeyboard(Context fallbackContext) {
        InputMethodService ime = service.get();
        Context context = ime != null ? ime : fallbackContext;
        try {
            if (context == null) {
                return;
            }
            MacBridgeDevice device = MacBridgeStore.device(context);
            if (!MacBridgeStore.isEnabled(context) || device == null) {
                toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_not_paired));
                return;
            }
            if (MacBridgeStore.token(context) == null) {
                toast(context, format(context,
                        R.string.gboard_patches_mac_bridge_toast_forgotten, device.name));
                return;
            }
            if (ime == null || ime.getCurrentInputConnection() == null) {
                toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_no_field));
                return;
            }
            if (isPasswordField(ime.getCurrentInputEditorInfo())) {
                toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_password));
                return;
            }
            String payload = limit(readFieldText(ime.getCurrentInputConnection()));
            if (payload.trim().isEmpty()) {
                toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_nothing));
                return;
            }
            MAIN.removeCallbacks(RELEASE);
            MacBridgeClient current = hold(context);
            if (current == null) {
                return;
            }
            if (current.state() != MacBridgeClient.State.CONNECTED) {
                toast(context, format(context,
                        R.string.gboard_patches_mac_bridge_toast_connecting, device.name));
            }
            current.send(UUID.randomUUID().toString(), payload);
        } catch (Throwable failure) {
            logFailure("Send to Mac failed", failure);
        }
    }

    /** The settings screen changed the pairing: drop the old session. */
    static void onPairingChanged(Context context) {
        synchronized (LOCK) {
            if (client != null) {
                client.reset();
            }
        }
        InputMethodService ime = service.get();
        if (ime != null && ime.isInputViewShown()) {
            MAIN.post(() -> hold(ime));
        }
    }

    static MacBridgeClient.State connectionState() {
        MacBridgeClient current = client();
        return current == null ? MacBridgeClient.State.DISCONNECTED : current.state();
    }

    // --- Connection lifecycle --------------------------------------------------------

    /** Connects to the paired Mac if the bridge is on; returns the client, or null. */
    private static MacBridgeClient hold(Context context) {
        if (!MacBridgeStore.isEnabled(context)) {
            release();
            return null;
        }
        MacBridgeDevice device = MacBridgeStore.device(context);
        String token = device == null ? null : MacBridgeStore.token(context);
        if (device == null || token == null) {
            release();
            return null;
        }
        MacBridgeClient current = ensureClient(context);
        current.activate(device, token);
        return current;
    }

    private static void release() {
        MacBridgeClient current = client();
        if (current != null) {
            current.deactivate();
        }
    }

    private static MacBridgeClient client() {
        synchronized (LOCK) {
            return client;
        }
    }

    private static MacBridgeClient ensureClient(Context context) {
        synchronized (LOCK) {
            if (client == null) {
                Context application = context.getApplicationContext();
                applicationContext = application != null ? application : context;
                client = new MacBridgeClient(applicationContext, new Events());
            }
            return client;
        }
    }

    private static boolean hasFeature(Context context) {
        Boolean present = featurePresent;
        if (present == null) {
            present = GboardPatchesFeatureAvailability.hasFeature(
                    context, GboardPatchesFeatureAvailability.FEATURE_MAC_BRIDGE);
            featurePresent = present;
        }
        return present;
    }

    // --- Reading the field ---------------------------------------------------------

    static String readFieldText(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            return selected.toString();
        }
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = MAX_FIELD_READ_CHARS;
        ExtractedText extracted = connection.getExtractedText(request, 0);
        if (extracted != null && extracted.text != null) {
            return extracted.text.toString();
        }
        CharSequence before = connection.getTextBeforeCursor(MAX_FIELD_READ_CHARS, 0);
        CharSequence after = connection.getTextAfterCursor(MAX_FIELD_READ_CHARS, 0);
        return (before == null ? "" : before.toString()) + (after == null ? "" : after.toString());
    }

    static boolean isPasswordField(EditorInfo info) {
        if (info == null) {
            return false;
        }
        int inputClass = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    static String limit(String value) {
        if (value.length() <= MAX_SEND_CHARS) {
            return value;
        }
        int end = MAX_SEND_CHARS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    // --- Feedback --------------------------------------------------------------------

    private static final class Events implements MacBridgeClient.Listener {
        @Override
        public void onStateChanged(MacBridgeClient.State state) {
        }

        @Override
        public void onClip(String text) {
            MAIN.post(() -> {
                Context context = applicationContext;
                try {
                    ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text));
                        toast(context, format(context,
                                R.string.gboard_patches_mac_bridge_toast_clip, macName()));
                    }
                } catch (Throwable failure) {
                    logFailure("Could not put the Mac's text on the clipboard", failure);
                }
            });
        }

        @Override
        public void onSendDelivered(String id, boolean inserted) {
            Context context = applicationContext;
            toast(context, format(context, inserted
                    ? R.string.gboard_patches_mac_bridge_toast_typed
                    : R.string.gboard_patches_mac_bridge_toast_copied, macName()));
        }

        @Override
        public void onSendExpired(String id) {
            Context context = applicationContext;
            toast(context, format(context,
                    R.string.gboard_patches_mac_bridge_toast_unreachable, macName()));
        }

        @Override
        public void onTokenRejected() {
            Context context = applicationContext;
            toast(context, format(context,
                    R.string.gboard_patches_mac_bridge_toast_forgotten, macName()));
        }
    }

    private static String macName() {
        MacBridgeClient current = client();
        MacBridgeDevice device = current == null ? null : current.device();
        return device == null ? "Mac" : device.name;
    }

    private static String text(Context context, int resourceId) {
        return GboardSettingsText.get(context, resourceId);
    }

    private static String format(Context context, int resourceId, Object... args) {
        return GboardSettingsText.format(context, resourceId, args);
    }

    private static void toast(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        Context application = context.getApplicationContext();
        Context target = application != null ? application : context;
        MAIN.post(() -> {
            try {
                Toast.makeText(target, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Feedback cannot affect the keyboard.
            }
        });
    }

    private static void logFailure(String message, Throwable failure) {
        try {
            Log.w(TAG, message, failure);
        } catch (Throwable ignored) {
            // Logging cannot affect Gboard's input path.
        }
    }
}
