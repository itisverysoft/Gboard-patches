package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * The paired Mac and whether the bridge is on. The Mac's id is its certificate's
 * SHA-256, which is all later connections need to pin it. Kept in its own
 * preferences file, apart from the shared Patches settings, so a settings backup
 * never carries the pairing; the token itself is additionally sealed by
 * [MacBridgeSecrets].
 */
final class MacBridgeStore {
    private static final String TAG = "GboardMacBridge";
    private static final String PREF_FILE = "gboard_patches_mac_bridge";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ID = "device_id";
    private static final String KEY_NAME = "device_name";
    private static final String KEY_HOST = "device_host";
    private static final String KEY_PORT = "device_port";
    private static final String KEY_TOKEN = "device_token";

    private MacBridgeStore() {
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** The paired Mac, or null when there is none. */
    static MacBridgeDevice device(Context context) {
        SharedPreferences preferences = preferences(context);
        String id = preferences.getString(KEY_ID, null);
        String host = preferences.getString(KEY_HOST, null);
        int port = preferences.getInt(KEY_PORT, -1);
        if (id == null || host == null || port <= 0) {
            return null;
        }
        return new MacBridgeDevice(id, preferences.getString(KEY_NAME, host), host, port);
    }

    /** The device token, or null when the Mac rejected it or it can't be decrypted. */
    static String token(Context context) {
        String sealed = preferences(context).getString(KEY_TOKEN, null);
        if (sealed == null) {
            return null;
        }
        // The keyboard asks on every show; decrypt through the Keystore only once.
        String[] cached = tokenCache;
        if (cached != null && sealed.equals(cached[0])) {
            return cached[1];
        }
        String token = MacBridgeSecrets.open(sealed);
        if (token != null) {
            tokenCache = new String[]{sealed, token};
        }
        return token;
    }

    /** {sealed, plain} of the last decrypted token. */
    private static volatile String[] tokenCache;

    /** Stores a freshly paired Mac and turns the bridge on. */
    static boolean savePairing(Context context, MacBridgeDevice device, String token) {
        try {
            String sealedToken = MacBridgeSecrets.seal(token);
            return preferences(context).edit()
                    .putString(KEY_ID, device.id)
                    .putString(KEY_NAME, device.name)
                    .putString(KEY_HOST, device.host)
                    .putInt(KEY_PORT, device.port)
                    .putString(KEY_TOKEN, sealedToken)
                    .putBoolean(KEY_ENABLED, true)
                    .commit();
        } catch (Exception failure) {
            Log.w(TAG, "Could not store the Mac pairing", failure);
            return false;
        }
    }

    /** Refreshes the Mac's name or address; never touches its secrets. */
    static void updateDevice(Context context, MacBridgeDevice device) {
        SharedPreferences preferences = preferences(context);
        if (!device.id.equals(preferences.getString(KEY_ID, null))) {
            return;
        }
        preferences.edit()
                .putString(KEY_NAME, device.name)
                .putString(KEY_HOST, device.host)
                .putInt(KEY_PORT, device.port)
                .apply();
    }

    /** The Mac no longer knows this phone: keep the entry so the screen can say so. */
    static void clearToken(Context context) {
        preferences(context).edit().remove(KEY_TOKEN).apply();
    }

    static void forget(Context context) {
        preferences(context).edit()
                .remove(KEY_ID)
                .remove(KEY_NAME)
                .remove(KEY_HOST)
                .remove(KEY_PORT)
                .remove(KEY_TOKEN)
                .putBoolean(KEY_ENABLED, false)
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        Context application = context.getApplicationContext();
        return (application != null ? application : context)
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }
}
