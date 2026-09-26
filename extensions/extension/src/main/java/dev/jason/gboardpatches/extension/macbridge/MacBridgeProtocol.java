package dev.jason.gboardpatches.extension.macbridge;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The VoxBridge wire protocol spoken by Rambler for Mac (protocol version 1): one
 * UTF-8 JSON object per line over TLS. Only the subset this keyboard uses is
 * modelled; unknown message types are ignored, as the protocol requires.
 */
final class MacBridgeProtocol {
    static final int VERSION = 1;
    /** Longest line accepted from the Mac; anything longer means a broken peer. */
    static final int MAX_FRAME_CHARS = 256 * 1024;

    static final String TYPE_HELLO = "hello";
    static final String TYPE_WELCOME = "welcome";
    static final String TYPE_TOKEN = "token";
    static final String TYPE_ERROR = "error";
    static final String TYPE_CLIP = "clip";
    static final String TYPE_CLIP_ACK = "clipAck";
    static final String TYPE_PING = "ping";
    static final String TYPE_PONG = "pong";
    static final String TYPE_BYE = "bye";

    static final String REASON_BAD_TOKEN = "bad_token";
    static final String REASON_BAD_CODE = "bad_code";
    static final String REASON_NOT_PAIRING = "not_pairing";

    static final String PLATFORM = "android";
    static final String APP_VERSION = "gboard-patches";

    private MacBridgeProtocol() {
    }

    /** Phone → Mac, the first line of every paired connection. */
    static String hello(String deviceName, String token) {
        return message(TYPE_HELLO,
                "deviceName", deviceName,
                "appVersion", APP_VERSION,
                "platform", PLATFORM,
                "token", token);
    }

    /** Phone → Mac while pairing: the code shown on the Mac instead of a token. */
    static String pairingHello(String deviceName, String code) {
        return message(TYPE_HELLO,
                "deviceName", deviceName,
                "appVersion", APP_VERSION,
                "platform", PLATFORM,
                "nonce", code);
    }

    /**
     * Phone → Mac: text for the Mac's clipboard, also typed into the Mac's focused
     * field. The Mac only types it while [ageMs] (since the user tapped Send) is
     * under a minute, so a late delivery never lands in whatever is focused then.
     */
    static String clip(String id, String text, long ageMs) {
        return message(TYPE_CLIP,
                "id", id,
                "text", text,
                "insert", Boolean.TRUE,
                "age", Math.max(0L, ageMs));
    }

    /** Phone → Mac: the clip [id] is on this phone's clipboard (repeats are acked too). */
    static String clipAck(String id) {
        return message(TYPE_CLIP_ACK, "id", id);
    }

    static String ping() {
        return message(TYPE_PING);
    }

    static String pong() {
        return message(TYPE_PONG);
    }

    static String bye() {
        return message(TYPE_BYE);
    }

    /** One line from the Mac, or null for a line that is not a JSON object. */
    static Message parse(String line) {
        if (line == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(line.trim());
            return new Message(json.optString("type", ""), json);
        } catch (JSONException ignored) {
            return null;
        }
    }

    /** A message line: [type], [protoVersion], then key/value pairs (null values left out). */
    private static String message(String type, Object... keyValues) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type);
            json.put("protoVersion", VERSION);
            for (int index = 0; index + 1 < keyValues.length; index += 2) {
                json.putOpt((String) keyValues[index], keyValues[index + 1]);
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return json.toString();
    }

    static final class Message {
        final String type;
        private final JSONObject json;

        Message(String type, JSONObject json) {
            this.type = type;
            this.json = json;
        }

        /** A string field, or null when missing or empty. */
        String string(String key) {
            String value = json.optString(key, "");
            return value.isEmpty() ? null : value;
        }

        boolean flag(String key) {
            return json.optBoolean(key, false);
        }
    }
}
