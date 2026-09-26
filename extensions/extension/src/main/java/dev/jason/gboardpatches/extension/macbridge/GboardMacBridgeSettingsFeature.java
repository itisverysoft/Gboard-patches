package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

/** Pairing and status for Mac Bridge. Network work runs off the main thread. */
public final class GboardMacBridgeSettingsFeature implements GboardPatchesSettingsContract.Feature {
    private static final String TAG = "GboardMacBridge";
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int ANDROID_17_API = 37;
    private static final long DISCOVERY_MS = 4_000L;
    private static final int TEST_TIMEOUT_MS = 8_000;
    private static final String ENTER_ADDRESS_VALUE = "__enter_address__";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GboardMacBridgeSettings");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context textContext;

    public GboardMacBridgeSettingsFeature(Context context) {
        textContext = context;
    }

    @Override
    public String getEntryTitle() {
        return text(R.string.gboard_patches_mac_bridge_title);
    }

    @Override
    public String getEntrySummary() {
        return text(R.string.gboard_patches_mac_bridge_summary);
    }

    @Override
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context, GboardPatchesFeatureAvailability.FEATURE_MAC_BRIDGE);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        try {
            if (host == null || host.getContext() == null) {
                return errorScreen();
            }
            return buildScreen(host, host.getContext());
        } catch (Throwable failure) {
            logFailure("Failed to render Mac Bridge settings", failure);
            return errorScreen();
        }
    }

    private GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host, Context context) {
        MacBridgeDevice device = MacBridgeStore.device(context);
        boolean paired = device != null;
        boolean tokenValid = paired && MacBridgeStore.token(context) != null;
        boolean enabled = MacBridgeStore.isEnabled(context);

        List<GboardPatchesSettingsContract.StatusBlock> status = new ArrayList<>();
        if (!paired) {
            status.add(new GboardPatchesSettingsContract.StatusBlock(
                    text(R.string.gboard_patches_mac_bridge_status_unpaired_title),
                    text(R.string.gboard_patches_mac_bridge_status_unpaired_summary)));
        } else if (!tokenValid) {
            status.add(new GboardPatchesSettingsContract.StatusBlock(
                    format(R.string.gboard_patches_mac_bridge_status_forgotten_title, device.name),
                    text(R.string.gboard_patches_mac_bridge_status_forgotten_summary),
                    GboardPatchesSettingsContract.StatusTone.WARNING));
        } else {
            status.add(new GboardPatchesSettingsContract.StatusBlock(
                    format(R.string.gboard_patches_mac_bridge_status_paired_title, device.name),
                    format(R.string.gboard_patches_mac_bridge_status_paired_summary,
                            device.address(), connectionLabel(enabled)),
                    GboardPatchesSettingsContract.StatusTone.INFO));
        }

        List<GboardPatchesSettingsContract.Section> sections = new ArrayList<>();
        List<GboardPatchesSettingsContract.Row> featureRows = new ArrayList<>();
        featureRows.add(new GboardPatchesSettingsContract.ToggleRow(
                text(R.string.gboard_patches_mac_bridge_enable_title),
                text(R.string.gboard_patches_mac_bridge_enable_summary),
                tokenValid,
                enabled && tokenValid,
                value -> {
                    MacBridgeStore.setEnabled(context, value);
                    GboardMacBridgeRuntime.onPairingChanged(context);
                }));
        sections.add(new GboardPatchesSettingsContract.Section(
                text(R.string.gboard_patches_mac_bridge_section_feature), featureRows));

        List<GboardPatchesSettingsContract.Row> pairingRows = new ArrayList<>();
        pairingRows.add(new GboardPatchesSettingsContract.CommandRow(
                text(paired
                        ? R.string.gboard_patches_mac_bridge_pair_again_title
                        : R.string.gboard_patches_mac_bridge_find_title),
                text(R.string.gboard_patches_mac_bridge_find_summary),
                true,
                () -> withLocalNetwork(host, () -> discover(host))));
        pairingRows.add(new GboardPatchesSettingsContract.CommandRow(
                text(R.string.gboard_patches_mac_bridge_link_title),
                text(R.string.gboard_patches_mac_bridge_link_summary),
                true,
                () -> withLocalNetwork(host, () -> askForLink(host))));
        if (tokenValid) {
            pairingRows.add(new GboardPatchesSettingsContract.CommandRow(
                    text(R.string.gboard_patches_mac_bridge_test_title),
                    text(R.string.gboard_patches_mac_bridge_test_summary),
                    true,
                    () -> withLocalNetwork(host, () -> testConnection(host))));
        }
        if (paired) {
            pairingRows.add(new GboardPatchesSettingsContract.DangerRow(
                    text(R.string.gboard_patches_mac_bridge_unpair_title),
                    format(R.string.gboard_patches_mac_bridge_unpair_summary, device.name),
                    true,
                    () -> {
                        MacBridgeStore.forget(context);
                        GboardMacBridgeRuntime.onPairingChanged(context);
                        GboardPatchesSettingsContract.refresh(host);
                    },
                    text(R.string.gboard_patches_mac_bridge_unpair_title),
                    format(R.string.gboard_patches_mac_bridge_unpair_confirm, device.name)));
        }
        sections.add(new GboardPatchesSettingsContract.Section(
                text(R.string.gboard_patches_mac_bridge_section_pairing), pairingRows));

        List<GboardPatchesSettingsContract.Row> usageRows = new ArrayList<>();
        usageRows.add(new GboardPatchesSettingsContract.DetailRow(
                text(R.string.gboard_patches_mac_bridge_usage_send_title),
                text(R.string.gboard_patches_mac_bridge_usage_send_summary),
                true));
        usageRows.add(new GboardPatchesSettingsContract.DetailRow(
                text(R.string.gboard_patches_mac_bridge_usage_receive_title),
                text(R.string.gboard_patches_mac_bridge_usage_receive_summary),
                true));
        sections.add(new GboardPatchesSettingsContract.Section(
                text(R.string.gboard_patches_mac_bridge_section_usage), usageRows));

        return new GboardPatchesSettingsContract.Screen(
                getEntryTitle(),
                text(R.string.gboard_patches_header_badge),
                getEntryTitle(),
                text(R.string.gboard_patches_mac_bridge_header_summary),
                status,
                sections);
    }

    private String connectionLabel(boolean enabled) {
        if (!enabled) {
            return text(R.string.gboard_patches_mac_bridge_state_off);
        }
        return text(GboardMacBridgeRuntime.connectionState() == MacBridgeClient.State.CONNECTED
                ? R.string.gboard_patches_mac_bridge_state_connected
                : R.string.gboard_patches_mac_bridge_state_idle);
    }

    // --- Pairing flows -------------------------------------------------------------

    private void discover(GboardPatchesSettingsContract.FeatureHost host) {
        Context context = host.getContext();
        GboardPatchesSettingsContract.showMessage(host,
                text(R.string.gboard_patches_mac_bridge_searching));
        WORKER.execute(() -> {
            List<MacBridgeDiscovery.FoundMac> found =
                    MacBridgeDiscovery.browse(context, DISCOVERY_MS);
            MAIN.post(() -> showFoundMacs(host, found));
        });
    }

    private void showFoundMacs(GboardPatchesSettingsContract.FeatureHost host,
            List<MacBridgeDiscovery.FoundMac> found) {
        try {
            List<String> labels = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (int index = 0; index < found.size(); index++) {
                MacBridgeDiscovery.FoundMac mac = found.get(index);
                labels.add(mac.name + "  ·  " + mac.host);
                values.add(Integer.toString(index));
            }
            labels.add(text(R.string.gboard_patches_mac_bridge_enter_address));
            values.add(ENTER_ADDRESS_VALUE);
            GboardPatchesSettingsContract.showChoiceDialog(
                    host,
                    text(found.isEmpty()
                            ? R.string.gboard_patches_mac_bridge_found_none_title
                            : R.string.gboard_patches_mac_bridge_found_title),
                    labels.toArray(new String[0]),
                    values.toArray(new String[0]),
                    null,
                    ENTER_ADDRESS_VALUE,
                    () -> askForAddress(host),
                    value -> {
                        MacBridgeDiscovery.FoundMac mac = found.get(Integer.parseInt(value));
                        askForCode(host, new MacBridgePairingTarget(
                                mac.host, mac.port, mac.fingerprint, null, mac.name));
                    });
        } catch (Throwable failure) {
            logFailure("Unable to show discovered Macs", failure);
        }
    }

    private void askForAddress(GboardPatchesSettingsContract.FeatureHost host) {
        GboardPatchesSettingsContract.showTextInputDialog(
                host,
                text(R.string.gboard_patches_mac_bridge_address_title),
                "192.168.1.20:" + MacBridgePairingTarget.DEFAULT_PORT,
                "",
                value -> {
                    MacBridgePairingTarget target = MacBridgePairingTarget.fromAddress(value);
                    if (target == null) {
                        throw new IllegalArgumentException(
                                text(R.string.gboard_patches_mac_bridge_address_invalid));
                    }
                    MAIN.post(() -> askForCode(host, target));
                });
    }

    private void askForLink(GboardPatchesSettingsContract.FeatureHost host) {
        GboardPatchesSettingsContract.showTextInputDialog(
                host,
                text(R.string.gboard_patches_mac_bridge_link_title),
                "voxbridge://pair?host=…",
                "",
                value -> {
                    MacBridgePairingTarget target = MacBridgePairingTarget.fromLink(value);
                    if (target == null) {
                        throw new IllegalArgumentException(
                                text(R.string.gboard_patches_mac_bridge_link_invalid));
                    }
                    pair(host, target);
                });
    }

    private void askForCode(GboardPatchesSettingsContract.FeatureHost host,
            MacBridgePairingTarget target) {
        GboardPatchesSettingsContract.showTextInputDialog(
                host,
                format(R.string.gboard_patches_mac_bridge_code_title,
                        target.name != null ? target.name : target.host),
                "123456",
                "",
                value -> {
                    if (!MacBridgePairingTarget.isValidCode(value)) {
                        throw new IllegalArgumentException(
                                text(R.string.gboard_patches_mac_bridge_code_invalid));
                    }
                    pair(host, target.withCode(value.trim()));
                });
    }

    private void pair(GboardPatchesSettingsContract.FeatureHost host,
            MacBridgePairingTarget target) {
        Context context = host.getContext();
        GboardPatchesSettingsContract.showMessage(host,
                format(R.string.gboard_patches_mac_bridge_pairing,
                        target.name != null ? target.name : target.host));
        WORKER.execute(() -> {
            MacBridgePairing.Result result = MacBridgePairing.pair(context, target);
            MAIN.post(() -> {
                if (result.isPaired()) {
                    GboardMacBridgeRuntime.onPairingChanged(context);
                    GboardPatchesSettingsContract.showMessage(host,
                            format(R.string.gboard_patches_mac_bridge_paired, result.device.name));
                } else {
                    GboardPatchesSettingsContract.showMessage(host,
                            pairingFailure(result.failure, target));
                }
                GboardPatchesSettingsContract.refresh(host);
            });
        });
    }

    private String pairingFailure(MacBridgePairing.Failure failure,
            MacBridgePairingTarget target) {
        return switch (failure) {
            case UNREACHABLE -> format(R.string.gboard_patches_mac_bridge_error_unreachable,
                    target.host + ":" + target.port);
            case WRONG_MAC -> text(R.string.gboard_patches_mac_bridge_error_wrong_mac);
            case BAD_CODE -> text(R.string.gboard_patches_mac_bridge_error_bad_code);
            case NOT_PAIRING -> text(R.string.gboard_patches_mac_bridge_error_not_pairing);
            case STORAGE -> text(R.string.gboard_patches_mac_bridge_error_storage);
            case REFUSED -> text(R.string.gboard_patches_mac_bridge_error_refused);
        };
    }

    // --- Test connection -----------------------------------------------------------

    private void testConnection(GboardPatchesSettingsContract.FeatureHost host) {
        Context context = host.getContext();
        MacBridgeDevice device = MacBridgeStore.device(context);
        String token = MacBridgeStore.token(context);
        if (device == null || token == null) {
            GboardPatchesSettingsContract.refresh(host);
            return;
        }
        WORKER.execute(() -> {
            String message = testConnection(context, device, token);
            MAIN.post(() -> {
                GboardPatchesSettingsContract.showMessage(host, message);
                GboardPatchesSettingsContract.refresh(host);
            });
        });
    }

    /** One hello/welcome round trip, looking the Mac up again if its address moved. */
    private String testConnection(Context context, MacBridgeDevice device, String token) {
        MacBridgeDevice target = device;
        String result = helloRoundTrip(context, target, token);
        if (result == null) {
            MacBridgeDiscovery.FoundMac moved =
                    MacBridgeDiscovery.find(context, device.id, DISCOVERY_MS);
            if (moved != null && (!moved.host.equals(device.host) || moved.port != device.port)) {
                target = device.withAddress(moved.host, moved.port);
                MacBridgeStore.updateDevice(context, target);
                result = helloRoundTrip(context, target, token);
            }
        }
        return result != null ? result
                : format(R.string.gboard_patches_mac_bridge_error_unreachable, target.address());
    }

    /** A message for the user, or null when the Mac could not be reached at all. */
    private String helloRoundTrip(Context context, MacBridgeDevice device, String token) {
        SSLSocket socket;
        try {
            socket = MacBridgeSockets.openPaired(device, TEST_TIMEOUT_MS);
        } catch (MacBridgeSockets.FingerprintMismatchException mismatch) {
            return text(R.string.gboard_patches_mac_bridge_error_wrong_mac);
        } catch (IOException unreachable) {
            return null;
        }
        try {
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(MacBridgeProtocol.hello(MacBridgeClient.deviceName(), token));
            writer.write('\n');
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    return text(R.string.gboard_patches_mac_bridge_error_refused);
                }
                MacBridgeProtocol.Message message = MacBridgeProtocol.parse(line);
                if (message == null) {
                    continue;
                }
                if (MacBridgeProtocol.TYPE_WELCOME.equals(message.type)) {
                    String macName = message.string("macName");
                    if (macName != null && !macName.equals(device.name)) {
                        MacBridgeStore.updateDevice(context, device.withName(macName));
                    }
                    writer.write(MacBridgeProtocol.bye());
                    writer.write('\n');
                    writer.flush();
                    return format(R.string.gboard_patches_mac_bridge_test_ok,
                            macName != null ? macName : device.name);
                }
                if (MacBridgeProtocol.TYPE_ERROR.equals(message.type)) {
                    if (MacBridgeProtocol.REASON_BAD_TOKEN.equals(message.string("message"))) {
                        MacBridgeStore.clearToken(context);
                        return format(R.string.gboard_patches_mac_bridge_toast_forgotten, device.name);
                    }
                    return text(R.string.gboard_patches_mac_bridge_error_refused);
                }
            }
        } catch (IOException failure) {
            return text(R.string.gboard_patches_mac_bridge_error_refused);
        } finally {
            MacBridgeSockets.closeQuietly(socket);
        }
    }

    // --- Helpers -------------------------------------------------------------------

    /** Android 17 asks apps targeting it for local network access at runtime. */
    private void withLocalNetwork(GboardPatchesSettingsContract.FeatureHost host,
            Runnable action) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        boolean required = Build.VERSION.SDK_INT >= ANDROID_17_API
                && context.getApplicationInfo().targetSdkVersion >= ANDROID_17_API;
        if (!required || context.checkSelfPermission(LOCAL_NETWORK_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            safely(action);
            return;
        }
        GboardPatchesSettingsContract.requestRuntimePermission(host, LOCAL_NETWORK_PERMISSION,
                granted -> {
                    if (granted) {
                        safely(action);
                    } else {
                        GboardPatchesSettingsContract.showMessage(host,
                                text(R.string.gboard_patches_mac_bridge_error_permission));
                    }
                });
    }

    private GboardPatchesSettingsContract.Screen errorScreen() {
        return new GboardPatchesSettingsContract.Screen(
                getEntryTitle(),
                text(R.string.gboard_patches_header_badge),
                getEntryTitle(),
                "",
                Collections.singletonList(new GboardPatchesSettingsContract.StatusBlock(
                        text(R.string.gboard_patches_mac_bridge_error_title),
                        text(R.string.gboard_patches_mac_bridge_error_summary),
                        GboardPatchesSettingsContract.StatusTone.WARNING)),
                Collections.emptyList());
    }

    private String text(int resourceId) {
        return GboardSettingsText.get(textContext, resourceId);
    }

    private String format(int resourceId, Object... args) {
        return GboardSettingsText.format(textContext, resourceId, args);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            logFailure("Mac Bridge settings action failed", failure);
        }
    }

    private static void logFailure(String message, Throwable failure) {
        try {
            Log.w(TAG, message, failure);
        } catch (Throwable ignored) {
            // Settings diagnostics cannot affect the host activity.
        }
    }
}
