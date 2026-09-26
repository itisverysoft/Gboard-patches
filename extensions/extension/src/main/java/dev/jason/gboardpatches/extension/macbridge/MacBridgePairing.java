package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

/**
 * Pairs with Rambler for Mac (protocol "Pairing"): connect over TLS, send a hello
 * carrying the code the Mac shows, receive a permanent device token, and store it
 * with the certificate fingerprint as the Mac's id. Blocking; never on the main
 * thread.
 */
final class MacBridgePairing {
    private static final String TAG = "GboardMacBridge";
    private static final int TIMEOUT_MS = 12_000;

    enum Failure { UNREACHABLE, WRONG_MAC, BAD_CODE, NOT_PAIRING, REFUSED, STORAGE }

    static final class Result {
        final MacBridgeDevice device;
        final Failure failure;

        private Result(MacBridgeDevice device, Failure failure) {
            this.device = device;
            this.failure = failure;
        }

        boolean isPaired() {
            return device != null;
        }
    }

    private MacBridgePairing() {
    }

    static Result pair(Context context, MacBridgePairingTarget target) {
        SSLSocket socket;
        try {
            socket = MacBridgeSockets.openForPairing(
                    target.host, target.port, target.fingerprint, TIMEOUT_MS);
        } catch (MacBridgeSockets.FingerprintMismatchException mismatch) {
            return new Result(null, Failure.WRONG_MAC);
        } catch (IOException failure) {
            Log.w(TAG, "Could not reach " + target.host + ":" + target.port, failure);
            return new Result(null, Failure.UNREACHABLE);
        }
        try {
            String fingerprint = MacBridgeSockets.peerFingerprint(socket);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(MacBridgeProtocol.pairingHello(MacBridgeClient.deviceName(), target.code));
            writer.write('\n');
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    return new Result(null, Failure.REFUSED);
                }
                MacBridgeProtocol.Message message = MacBridgeProtocol.parse(line);
                if (message == null) {
                    continue;
                }
                if (MacBridgeProtocol.TYPE_TOKEN.equals(message.type)) {
                    String token = message.string("token");
                    if (token == null) {
                        return new Result(null, Failure.REFUSED);
                    }
                    String name = firstNonNull(message.string("macName"), target.name, target.host);
                    MacBridgeDevice device =
                            new MacBridgeDevice(fingerprint, name, target.host, target.port);
                    if (!MacBridgeStore.savePairing(context, device, token)) {
                        return new Result(null, Failure.STORAGE);
                    }
                    Log.i(TAG, "Paired with " + name);
                    return new Result(device, null);
                }
                if (MacBridgeProtocol.TYPE_ERROR.equals(message.type)) {
                    String reason = message.string("message");
                    if (MacBridgeProtocol.REASON_BAD_CODE.equals(reason)) {
                        return new Result(null, Failure.BAD_CODE);
                    }
                    if (MacBridgeProtocol.REASON_NOT_PAIRING.equals(reason)) {
                        return new Result(null, Failure.NOT_PAIRING);
                    }
                    return new Result(null, Failure.REFUSED);
                }
            }
        } catch (SocketTimeoutException timeout) {
            return new Result(null, Failure.UNREACHABLE);
        } catch (IOException failure) {
            Log.w(TAG, "Pairing failed", failure);
            return new Result(null, Failure.REFUSED);
        } finally {
            MacBridgeSockets.closeQuietly(socket);
        }
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
