package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds Macs running Rambler for Mac by browsing the Bonjour service
 * `_voxbridge._tcp`. The TXT record's `fp` is the Mac's certificate fingerprint,
 * which is also the id of a paired Mac, so a paired Mac is found again after its
 * IP address changes. Blocking; never on the main thread.
 */
final class MacBridgeDiscovery {
    private static final String TAG = "GboardMacBridge";
    private static final String SERVICE_TYPE = "_voxbridge._tcp.";

    static final class FoundMac {
        final String name;
        final String host;
        final int port;
        /** Certificate SHA-256 from the TXT record, or null. */
        final String fingerprint;

        FoundMac(String name, String host, int port, String fingerprint) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.fingerprint = fingerprint;
        }
    }

    private MacBridgeDiscovery() {
    }

    /** Browses for [durationMs] and returns every Mac resolved in that time. */
    static List<FoundMac> browse(Context context, long durationMs) {
        return browse(context, durationMs, null);
    }

    /** The Mac whose fingerprint is [fingerprint], or null if it isn't seen within [durationMs]. */
    static FoundMac find(Context context, String fingerprint, long durationMs) {
        List<FoundMac> found = browse(context, durationMs, fingerprint);
        for (FoundMac mac : found) {
            if (fingerprint.equals(mac.fingerprint)) {
                return mac;
            }
        }
        return null;
    }

    private static List<FoundMac> browse(Context context, long durationMs, String stopAtFingerprint) {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        if (nsd == null) {
            return new ArrayList<>();
        }
        Browser browser = new Browser(nsd, stopAtFingerprint);
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, browser);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Mac discovery could not start", failure);
            return new ArrayList<>();
        }
        try {
            browser.await(durationMs);
        } finally {
            try {
                nsd.stopServiceDiscovery(browser);
            } catch (RuntimeException ignored) {
                // Discovery already stopped or failed to start.
            }
        }
        return browser.results();
    }

    /**
     * Before Android 14 NsdManager resolves one service at a time and fails the rest
     * with FAILURE_ALREADY_ACTIVE, so services are resolved strictly in sequence.
     */
    private static final class Browser implements NsdManager.DiscoveryListener {
        private final NsdManager nsd;
        private final String stopAtFingerprint;
        private final Map<String, FoundMac> found = new LinkedHashMap<>();
        private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
        private boolean resolving;
        private boolean finished;

        Browser(NsdManager nsd, String stopAtFingerprint) {
            this.nsd = nsd;
            this.stopAtFingerprint = stopAtFingerprint;
        }

        synchronized void await(long durationMs) {
            long deadline = System.currentTimeMillis() + durationMs;
            long remaining = durationMs;
            while (!finished && remaining > 0) {
                try {
                    wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                remaining = deadline - System.currentTimeMillis();
            }
        }

        synchronized List<FoundMac> results() {
            finished = true;
            return new ArrayList<>(found.values());
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(TAG, "Mac discovery failed to start (code " + errorCode + ")");
            synchronized (this) {
                finished = true;
                notifyAll();
            }
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public synchronized void onServiceFound(NsdServiceInfo serviceInfo) {
            if (finished) {
                return;
            }
            queue.addLast(serviceInfo);
            resolveNext();
        }

        @Override
        public synchronized void onServiceLost(NsdServiceInfo serviceInfo) {
            found.remove(serviceInfo.getServiceName());
        }

        @SuppressWarnings("deprecation")
        private void resolveNext() {
            if (resolving || finished) {
                return;
            }
            NsdServiceInfo next = queue.pollFirst();
            if (next == null) {
                return;
            }
            resolving = true;
            try {
                nsd.resolveService(next, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        synchronized (Browser.this) {
                            resolving = false;
                            resolveNext();
                        }
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        synchronized (Browser.this) {
                            resolving = false;
                            record(info);
                            resolveNext();
                        }
                    }
                });
            } catch (RuntimeException failure) {
                resolving = false;
                Log.w(TAG, "Could not resolve " + next.getServiceName(), failure);
            }
        }

        @SuppressWarnings("deprecation")
        private void record(NsdServiceInfo info) {
            if (info.getHost() == null || finished) {
                return;
            }
            String fingerprint = null;
            byte[] raw = info.getAttributes().get("fp");
            if (raw != null) {
                fingerprint = new String(raw, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            }
            FoundMac mac = new FoundMac(info.getServiceName(), info.getHost().getHostAddress(),
                    info.getPort(), fingerprint);
            found.put(info.getServiceName(), mac);
            if (stopAtFingerprint != null && stopAtFingerprint.equals(fingerprint)) {
                finished = true;
                notifyAll();
            }
        }
    }
}
