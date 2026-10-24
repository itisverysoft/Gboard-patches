package dev.jason.gboardpatches.extension.macbridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

/**
 * Keyboard-side glue for Mac Bridge. While the keyboard is showing (and for a
 * minute after it hides) it stays connected to the paired Mac, so text sent from
 * the Mac lands on the clipboard, where Gboard offers it. The toolbar's "Send to
 * Mac" types the selected text, or the whole field, on the Mac, and "Dictate to
 * Mac" listens on the phone and types what was said on the Mac. There is no
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
    private static MacBridgeDictation dictation;
    /** Sends waiting for the Mac's confirmation before the phone's copy is removed. Main thread. */
    private static final Map<String, PendingClear> PENDING_CLEARS = new HashMap<>();

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
            cancelDictation();
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
            MacBridgeDevice device = readyDevice(context);
            if (device == null) {
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
            InputConnection connection = ime.getCurrentInputConnection();
            CharSequence selected = connection.getSelectedText(0);
            boolean fromSelection = selected != null && selected.length() > 0;
            String payload = limit(readFieldText(connection));
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
            String id = UUID.randomUUID().toString();
            if (MacBridgeStore.clearsAfterSend(context)) {
                PENDING_CLEARS.put(id, new PendingClear(payload, fromSelection,
                        ime.getCurrentInputEditorInfo()));
            }
            current.send(id, payload);
        } catch (Throwable failure) {
            logFailure("Send to Mac failed", failure);
        }
    }

    /** The Mac confirmed [id]: remove it from the phone if that's on and nothing changed. */
    private static void clearAfterDelivery(String id) {
        PendingClear pending = PENDING_CLEARS.remove(id);
        InputMethodService ime = service.get();
        if (pending == null || ime == null || !ime.isInputViewShown()) {
            return;
        }
        try {
            if (!pending.isSameField(ime.getCurrentInputEditorInfo())) {
                return;
            }
            InputConnection connection = ime.getCurrentInputConnection();
            if (connection != null) {
                clearIfUnchanged(connection, pending.text, pending.fromSelection);
            }
        } catch (Throwable failure) {
            logFailure("Could not clear the sent text", failure);
        }
    }

    /**
     * Removes [text] from the field only if it is still exactly what was sent: the
     * selection, or the whole field. Returns whether anything was removed.
     */
    static boolean clearIfUnchanged(InputConnection connection, String text, boolean fromSelection) {
        if (fromSelection) {
            CharSequence selected = connection.getSelectedText(0);
            if (selected == null || !text.contentEquals(selected)) {
                return false;
            }
            return connection.commitText("", 1);
        }
        if (!text.equals(readFieldText(connection))) {
            return false;
        }
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            CharSequence before = connection.getTextBeforeCursor(MAX_FIELD_READ_CHARS, 0);
            CharSequence after = connection.getTextAfterCursor(MAX_FIELD_READ_CHARS, 0);
            return connection.deleteSurroundingText(
                    before == null ? 0 : before.length(), after == null ? 0 : after.length());
        } finally {
            connection.endBatchEdit();
        }
    }

    private static final class PendingClear {
        final String text;
        final boolean fromSelection;
        final String packageName;
        final int fieldId;

        PendingClear(String text, boolean fromSelection, EditorInfo info) {
            this.text = text;
            this.fromSelection = fromSelection;
            this.packageName = info == null ? null : info.packageName;
            this.fieldId = info == null ? 0 : info.fieldId;
        }

        boolean isSameField(EditorInfo info) {
            return info != null && fieldId == info.fieldId
                    && (packageName == null ? info.packageName == null
                            : packageName.equals(info.packageName));
        }
    }

    /**
     * The toolbar's "Dictate to Mac": listens on the phone and types what was said
     * into the Mac's focused app. Tapping it again while listening cancels.
     */
    static void dictateFromKeyboard(Context fallbackContext) {
        InputMethodService ime = service.get();
        Context context = ime != null ? ime : fallbackContext;
        try {
            if (dictation != null && dictation.isActive()) {
                cancelDictation();
                return;
            }
            MacBridgeDevice device = readyDevice(context);
            if (device == null) {
                return;
            }
            if (ime == null || !ime.isInputViewShown()) {
                toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_no_field));
                return;
            }
            if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                toast(context, text(context,
                        R.string.gboard_patches_mac_bridge_toast_mic_permission));
                return;
            }
            if (!MacBridgeSpeech.isAvailable(ime)) {
                toast(context, text(context,
                        R.string.gboard_patches_mac_bridge_toast_no_recognizer));
                return;
            }
            MAIN.removeCallbacks(RELEASE);
            MacBridgeClient current = hold(context);
            if (current == null) {
                return;
            }
            dictation = MacBridgeDictation.start(ime, new MacBridgeDictation.Output() {
                @Override
                public void sendText(String text, boolean pressEnter) {
                    MacBridgeClient target = hold(ime);
                    if (target == null) {
                        return;
                    }
                    if (target.state() != MacBridgeClient.State.CONNECTED) {
                        GboardMacBridgeRuntime.toast(ime, format(ime,
                                R.string.gboard_patches_mac_bridge_toast_connecting, device.name));
                    }
                    target.sendText(limit(text), pressEnter);
                }

                @Override
                public void sendPartial(String text) {
                    current.sendPartial(limit(text));
                }

                @Override
                public void toast(String message) {
                    GboardMacBridgeRuntime.toast(ime, message);
                }
            }, device.name);
            if (dictation == null) {
                toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_no_panel));
            }
        } catch (Throwable failure) {
            logFailure("Dictate to Mac failed", failure);
        }
    }

    private static void cancelDictation() {
        MacBridgeDictation current = dictation;
        dictation = null;
        if (current != null) {
            current.cancel();
        }
    }

    /** The paired Mac when the bridge is on and paired, else null after telling the user why. */
    private static MacBridgeDevice readyDevice(Context context) {
        if (context == null) {
            return null;
        }
        MacBridgeDevice device = MacBridgeStore.device(context);
        if (!MacBridgeStore.isEnabled(context) || device == null) {
            toast(context, text(context, R.string.gboard_patches_mac_bridge_toast_not_paired));
            return null;
        }
        if (MacBridgeStore.token(context) == null) {
            toast(context, format(context,
                    R.string.gboard_patches_mac_bridge_toast_forgotten, device.name));
            return null;
        }
        return device;
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
        // Some editors (web views among them) answer with an empty extract even when the
        // field has text, so only trust a non-empty one and otherwise read around the cursor.
        if (extracted != null && extracted.text != null && extracted.text.length() > 0) {
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
            MAIN.post(() -> clearAfterDelivery(id));
            Context context = applicationContext;
            toast(context, format(context, inserted
                    ? R.string.gboard_patches_mac_bridge_toast_typed
                    : R.string.gboard_patches_mac_bridge_toast_copied, macName()));
        }

        @Override
        public void onSendExpired(String id) {
            MAIN.post(() -> PENDING_CLEARS.remove(id));
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

        @Override
        public void onTextResult(boolean delivered, String reason) {
            Context context = applicationContext;
            int message;
            if (delivered) {
                message = R.string.gboard_patches_mac_bridge_toast_typed;
            } else if (MacBridgeProtocol.REASON_SCREEN_LOCKED.equals(reason)) {
                message = R.string.gboard_patches_mac_bridge_toast_mac_locked;
            } else if (MacBridgeProtocol.REASON_NO_ACCESSIBILITY.equals(reason)) {
                message = R.string.gboard_patches_mac_bridge_toast_mac_accessibility;
            } else {
                message = R.string.gboard_patches_mac_bridge_toast_mac_not_typed;
            }
            toast(context, format(context, message, macName()));
        }

        @Override
        public void onTextExpired() {
            Context context = applicationContext;
            toast(context, format(context,
                    R.string.gboard_patches_mac_bridge_toast_unreachable, macName()));
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
