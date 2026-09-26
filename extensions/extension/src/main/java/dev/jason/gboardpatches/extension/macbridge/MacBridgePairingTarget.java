package dev.jason.gboardpatches.extension.macbridge;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where to pair: the Mac's pairing link (the text inside the QR code Rambler for
 * Mac shows), a Mac found on the network, or an address typed by hand. A link
 * carries the certificate fingerprint and the code; the other two ask for the code
 * and trust the certificate on first use.
 */
final class MacBridgePairingTarget {
    static final int DEFAULT_PORT = 5679;
    private static final String SCHEME = "voxbridge";
    private static final String PAIR_HOST = "pair";

    final String host;
    final int port;
    /** SHA-256 of the Mac's certificate, lowercase hex, or null to trust on first use. */
    final String fingerprint;
    /** The 6-digit code, or null when the user still has to type it. */
    final String code;
    final String name;

    MacBridgePairingTarget(String host, int port, String fingerprint, String code, String name) {
        this.host = host;
        this.port = port;
        this.fingerprint = fingerprint;
        this.code = code;
        this.name = name;
    }

    MacBridgePairingTarget withCode(String newCode) {
        return new MacBridgePairingTarget(host, port, fingerprint, newCode, name);
    }

    /** Parses a voxbridge://pair?host=…&port=…&nonce=…[&fp=…][&name=…] link, or null. */
    static MacBridgePairingTarget fromLink(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            if (!SCHEME.equalsIgnoreCase(uri.getScheme())
                    || !PAIR_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getRawQuery() == null) {
                return null;
            }
            Map<String, String> params = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                int separator = part.indexOf('=');
                if (separator > 0) {
                    params.put(decode(part.substring(0, separator)),
                            decode(part.substring(separator + 1)));
                }
            }
            String host = trimmed(params.get("host"));
            int port = parsePort(params.get("port"));
            String code = trimmed(params.get("nonce"));
            if (host == null || code == null || port <= 0) {
                return null;
            }
            String fingerprint = trimmed(params.get("fp"));
            return new MacBridgePairingTarget(host, port,
                    fingerprint == null ? null : fingerprint.toLowerCase(Locale.ROOT),
                    code, trimmed(params.get("name")));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Parses "host" or "host:port" typed by the user, or null. */
    static MacBridgePairingTarget fromAddress(String raw) {
        String value = trimmed(raw);
        if (value == null || value.contains("/") || value.contains(" ")) {
            return null;
        }
        String host = value;
        int port = DEFAULT_PORT;
        int separator = value.lastIndexOf(':');
        if (separator > 0 && value.indexOf(':') == separator) {
            host = value.substring(0, separator);
            port = parsePort(value.substring(separator + 1));
        }
        if (host.isEmpty() || port <= 0) {
            return null;
        }
        return new MacBridgePairingTarget(host, port, null, null, null);
    }

    static boolean isValidCode(String code) {
        return code != null && code.trim().matches("\\d{6}");
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String decode(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value, "UTF-8");
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
