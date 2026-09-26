package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;

/**
 * The session with the paired Mac: newline-delimited JSON over pinned TLS. The
 * first line is a `hello` with the device token; the Mac answers `welcome` (or
 * `error: bad_token`). Both sides ping every ten seconds and a silent socket is
 * dropped after [READ_TIMEOUT_MS]. While active, a dropped connection is redialled
 * with backoff, and after repeated failures the Mac is looked up on the network by
 * its certificate fingerprint in case its IP address changed.
 *
 * Text sent to the Mac waits for the connection, but only as long as the Mac would
 * still type it; unacknowledged sends are resent with the same id after a
 * reconnect, and the Mac ignores ids it has already seen.
 *
 * Writes, heartbeats and timers run on one scheduler thread; each connection reads
 * on its own thread. [Listener] callbacks arrive on either, never the main thread.
 */
final class MacBridgeClient {
    enum State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        void onStateChanged(State state);

        /** Text the user sent from the Mac for this phone's clipboard. Each clip arrives once. */
        void onClip(String text);

        /** A send reached the Mac; [inserted]: it was also typed into the focused field. */
        void onSendDelivered(String id, boolean inserted);

        /** A send did not reach the Mac while it would still have been typed. */
        void onSendExpired(String id);

        /** The Mac no longer knows this phone (unpaired on the Mac). */
        void onTokenRejected();
    }

    private static final String TAG = "GboardMacBridge";
    private static final int READ_TIMEOUT_MS = 35_000;
    private static final long HEARTBEAT_MS = 10_000L;
    /** The Mac types text only while it is under a minute old; stop a little before that. */
    static final long SEND_FRESHNESS_MS = 55_000L;
    private static final int MAX_PENDING_SENDS = 8;
    private static final int MAX_SEEN_CLIPS = 32;
    private static final int REDISCOVER_EVERY_FAILURES = 2;
    private static final long REDISCOVER_MS = 3_000L;
    private static final long[] BACKOFF_MS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L};

    private final Context context;
    private final Listener listener;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "GboardMacBridge");
                thread.setDaemon(true);
                return thread;
            });

    private volatile MacBridgeDevice device;
    private volatile String token;
    private volatile boolean active;
    private volatile boolean authed;
    private volatile SSLSocket socket;
    private volatile Writer writer;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private int failures;
    private int reconnectAttempts;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> reconnect;

    private final Object sendsLock = new Object();
    private final Map<String, PendingSend> sends = new LinkedHashMap<>();
    private final ArrayDeque<String> seenClips = new ArrayDeque<>();

    MacBridgeClient(Context context, Listener listener) {
        Context application = context.getApplicationContext();
        this.context = application != null ? application : context;
        this.listener = listener;
    }

    static String deviceName() {
        String model = Build.MODEL;
        return (model == null || model.isEmpty() ? "Android phone" : model) + " (Gboard)";
    }

    State state() {
        if (!active) {
            return State.DISCONNECTED;
        }
        SSLSocket current = socket;
        return authed && current != null && !current.isClosed()
                ? State.CONNECTED : State.CONNECTING;
    }

    MacBridgeDevice device() {
        return device;
    }

    /** Points the client at the paired Mac and keeps it connected until [deactivate]. */
    synchronized void activate(MacBridgeDevice target, String tokenValue) {
        boolean sameTarget = device != null && device.id.equals(target.id)
                && tokenValue.equals(token);
        if (!sameTarget) {
            scheduler.execute(this::closeSocket);
            device = target;
            token = tokenValue;
            failures = 0;
        }
        if (!active) {
            active = true;
            reconnectAttempts = 0;
            cancelReconnect();
            notifyState();
        }
        connectAsync();
    }

    /** Says goodbye and stops dialling; the paired Mac stays configured. */
    synchronized void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        cancelReconnect();
        scheduler.execute(() -> {
            writeQuietly(MacBridgeProtocol.bye());
            closeSocket();
        });
        notifyState();
    }

    /** Forgets the Mac entirely (unpaired). Pending sends are dropped. */
    synchronized void reset() {
        deactivate();
        device = null;
        token = null;
        synchronized (sendsLock) {
            sends.clear();
        }
    }

    /**
     * Queues [text] for the Mac's clipboard and focused field, sending it now if
     * connected. It expires, with [Listener.onSendExpired], if the Mac doesn't
     * confirm it in time.
     */
    void send(String id, String text) {
        PendingSend pending = new PendingSend(id, text, SystemClock.elapsedRealtime());
        List<String> dropped = new ArrayList<>();
        synchronized (sendsLock) {
            sends.put(id, pending);
            while (sends.size() > MAX_PENDING_SENDS) {
                String oldest = sends.keySet().iterator().next();
                sends.remove(oldest);
                dropped.add(oldest);
            }
        }
        for (String expired : dropped) {
            listener.onSendExpired(expired);
        }
        scheduler.schedule(() -> expire(id), SEND_FRESHNESS_MS, TimeUnit.MILLISECONDS);
        if (state() == State.CONNECTED) {
            scheduler.execute(() -> writeSend(pending));
        } else {
            connectAsync();
        }
    }

    // --- Sending ------------------------------------------------------------------

    private void writeSend(PendingSend pending) {
        synchronized (sendsLock) {
            if (!sends.containsKey(pending.id)) {
                return;
            }
        }
        long age = SystemClock.elapsedRealtime() - pending.createdAt;
        writeQuietly(MacBridgeProtocol.clip(pending.id, pending.text, age));
    }

    private void flushSends() {
        List<PendingSend> waiting;
        synchronized (sendsLock) {
            waiting = new ArrayList<>(sends.values());
        }
        for (PendingSend pending : waiting) {
            writeSend(pending);
        }
    }

    private void expire(String id) {
        PendingSend removed;
        synchronized (sendsLock) {
            removed = sends.remove(id);
        }
        if (removed != null) {
            listener.onSendExpired(id);
        }
    }

    /** Scheduler thread only, so frames never interleave. */
    private void writeQuietly(String line) {
        Writer out = writer;
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException failure) {
            Log.i(TAG, "Send to the Mac failed; reconnecting", failure);
            closeSocket();
        }
    }

    // --- Connection -----------------------------------------------------------------

    private void connectAsync() {
        MacBridgeDevice target = device;
        String tokenValue = token;
        if (!active || target == null || tokenValue == null || socket != null
                || !connecting.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                runConnection(target, tokenValue);
            } finally {
                connecting.set(false);
            }
            if (active) {
                scheduleReconnect();
            }
        }, "GboardMacBridgeConnection");
        thread.setDaemon(true);
        thread.start();
    }

    /** One dial, handshake and read loop; returns when the connection ends. */
    private void runConnection(MacBridgeDevice target, String tokenValue) {
        SSLSocket opened;
        Writer out;
        try {
            opened = MacBridgeSockets.openPaired(target, READ_TIMEOUT_MS);
        } catch (IOException failure) {
            if (active) {
                Log.i(TAG, "Could not reach " + target.name + " at " + target.address()
                        + ": " + failure.getMessage());
                maybeRediscover(target);
            }
            return;
        }
        MacBridgeDevice current = device;
        if (!active || current == null || !current.id.equals(target.id)
                || !tokenValue.equals(token)) {
            // The pairing changed while this dial was in flight.
            MacBridgeSockets.closeQuietly(opened);
            return;
        }
        try {
            out = new OutputStreamWriter(opened.getOutputStream(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            MacBridgeSockets.closeQuietly(opened);
            return;
        }
        socket = opened;
        writer = out;
        authed = false;
        notifyState();
        try {
            scheduler.execute(() ->
                    writeQuietly(MacBridgeProtocol.hello(deviceName(), tokenValue)));
            readLoop(opened, target);
        } catch (IOException failure) {
            if (active) {
                Log.i(TAG, "Mac connection ended: " + failure.getMessage());
            }
        } finally {
            scheduler.execute(() -> {
                if (socket == opened) {
                    closeSocket();
                }
            });
            MacBridgeSockets.closeQuietly(opened);
            authed = false;
            notifyState();
        }
    }

    private void readLoop(SSLSocket opened, MacBridgeDevice target) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(opened.getInputStream(), StandardCharsets.UTF_8));
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                return;
            }
            if (line.length() > MacBridgeProtocol.MAX_FRAME_CHARS) {
                Log.w(TAG, "Oversized frame from the Mac; closing");
                return;
            }
            MacBridgeProtocol.Message message = MacBridgeProtocol.parse(line);
            if (message == null) {
                continue;
            }
            switch (message.type) {
                case MacBridgeProtocol.TYPE_WELCOME -> onWelcome(target, message.string("macName"));
                case MacBridgeProtocol.TYPE_CLIP -> onClip(message.string("id"), message.string("text"));
                case MacBridgeProtocol.TYPE_CLIP_ACK -> onClipAck(message.string("id"),
                        message.flag("inserted"));
                case MacBridgeProtocol.TYPE_PING ->
                        scheduler.execute(() -> writeQuietly(MacBridgeProtocol.pong()));
                case MacBridgeProtocol.TYPE_ERROR -> {
                    if (MacBridgeProtocol.REASON_BAD_TOKEN.equals(message.string("message"))) {
                        onTokenRejected();
                    } else {
                        Log.w(TAG, "The Mac reported " + message.string("message"));
                    }
                    return;
                }
                case MacBridgeProtocol.TYPE_BYE -> {
                    return;
                }
                default -> {
                    // Unknown or unhandled types (file offers, pongs) are ignored.
                }
            }
        }
    }

    private void onWelcome(MacBridgeDevice target, String macName) {
        authed = true;
        synchronized (this) {
            failures = 0;
            reconnectAttempts = 0;
        }
        if (macName != null && !macName.equals(target.name)) {
            MacBridgeDevice renamed = target.withName(macName);
            MacBridgeStore.updateDevice(context, renamed);
            if (device != null && device.id.equals(target.id)) {
                device = renamed;
            }
        }
        Log.i(TAG, "Connected to " + (macName != null ? macName : target.name));
        notifyState();
        scheduler.execute(() -> {
            startHeartbeat();
            flushSends();
        });
    }

    private void onClip(String id, String text) {
        if (id == null || text == null) {
            return;
        }
        boolean fresh;
        synchronized (seenClips) {
            fresh = !seenClips.contains(id);
            if (fresh) {
                seenClips.addLast(id);
                while (seenClips.size() > MAX_SEEN_CLIPS) {
                    seenClips.removeFirst();
                }
            }
        }
        // Acknowledge repeats too: the Mac resent it because our first ack got lost.
        scheduler.execute(() -> writeQuietly(MacBridgeProtocol.clipAck(id)));
        if (fresh) {
            listener.onClip(text);
        }
    }

    private void onClipAck(String id, boolean inserted) {
        if (id == null) {
            return;
        }
        PendingSend removed;
        synchronized (sendsLock) {
            removed = sends.remove(id);
        }
        if (removed != null) {
            listener.onSendDelivered(id, inserted);
        }
    }

    private void onTokenRejected() {
        Log.w(TAG, "The Mac rejected this phone's pairing token");
        synchronized (this) {
            active = false;
            cancelReconnect();
        }
        token = null;
        MacBridgeStore.clearToken(context);
        synchronized (sendsLock) {
            sends.clear();
        }
        listener.onTokenRejected();
    }

    /** After repeated failures, look for the Mac by fingerprint in case its address changed. */
    private void maybeRediscover(MacBridgeDevice target) {
        synchronized (this) {
            failures++;
            if (failures % REDISCOVER_EVERY_FAILURES != 0) {
                return;
            }
        }
        MacBridgeDiscovery.FoundMac found =
                MacBridgeDiscovery.find(context, target.id, REDISCOVER_MS);
        if (found == null || (found.host.equals(target.host) && found.port == target.port)) {
            return;
        }
        Log.i(TAG, target.name + " moved to " + found.host + ":" + found.port);
        MacBridgeDevice moved = target.withAddress(found.host, found.port);
        MacBridgeStore.updateDevice(context, moved);
        synchronized (this) {
            if (device != null && device.id.equals(target.id)) {
                device = moved;
                reconnectAttempts = 0;
            }
        }
    }

    /** Scheduler thread only. */
    private void startHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            if (authed) {
                writeQuietly(MacBridgeProtocol.ping());
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    /** Exponential backoff capped so a returning Mac is picked up within 15 s. */
    private synchronized void scheduleReconnect() {
        if (!active || device == null || token == null
                || (reconnect != null && !reconnect.isDone())) {
            return;
        }
        long delay = BACKOFF_MS[Math.min(reconnectAttempts, BACKOFF_MS.length - 1)];
        reconnectAttempts++;
        reconnect = scheduler.schedule(this::connectAsync, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnect != null) {
            reconnect.cancel(false);
            reconnect = null;
        }
    }

    /** Scheduler thread only. */
    private void closeSocket() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        authed = false;
        writer = null;
        SSLSocket old = socket;
        socket = null;
        MacBridgeSockets.closeQuietly(old);
    }

    private void notifyState() {
        try {
            listener.onStateChanged(state());
        } catch (RuntimeException failure) {
            Log.w(TAG, "Mac Bridge state listener failed", failure);
        }
    }

    private static final class PendingSend {
        final String id;
        final String text;
        final long createdAt;

        PendingSend(String id, String text, long createdAt) {
            this.id = id;
            this.text = text;
            this.createdAt = createdAt;
        }
    }
}
