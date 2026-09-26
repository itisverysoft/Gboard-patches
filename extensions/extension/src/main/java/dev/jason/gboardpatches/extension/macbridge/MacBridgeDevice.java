package dev.jason.gboardpatches.extension.macbridge;

/**
 * The paired Mac. [id] is the SHA-256 of its TLS certificate, so the Mac can be
 * recognised on the network after its IP address changes.
 */
final class MacBridgeDevice {
    final String id;
    final String name;
    final String host;
    final int port;

    MacBridgeDevice(String id, String name, String host, int port) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
    }

    MacBridgeDevice withName(String newName) {
        return new MacBridgeDevice(id, newName, host, port);
    }

    MacBridgeDevice withAddress(String newHost, int newPort) {
        return new MacBridgeDevice(id, name, newHost, newPort);
    }

    String address() {
        return host + ":" + port;
    }
}
