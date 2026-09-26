package dev.jason.gboardpatches.extension.macbridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS connections to Rambler for Mac. The Mac's certificate is self-signed, so the
 * usual CA check can't apply: after pairing only the certificate whose SHA-256 is
 * the paired Mac's id is accepted; while pairing, the fingerprint from the pairing
 * link is required, or the first certificate seen is trusted and then pinned.
 */
final class MacBridgeSockets {
    static final int CONNECT_TIMEOUT_MS = 8_000;

    private MacBridgeSockets() {
    }

    /** Connected and handshaken with the paired Mac. Blocking. */
    static SSLSocket openPaired(MacBridgeDevice device, int readTimeoutMs) throws IOException {
        return open(device.host, device.port, device.id, readTimeoutMs);
    }

    /**
     * Connected and handshaken for pairing. With [expectedFingerprint] any other
     * certificate fails with [FingerprintMismatch]; without it, the certificate is
     * trusted on first use.
     */
    static SSLSocket openForPairing(String host, int port, String expectedFingerprint,
            int readTimeoutMs) throws IOException {
        return open(host, port, expectedFingerprint, readTimeoutMs);
    }

    /** SHA-256 of the certificate the Mac presented on [socket], lowercase hex. */
    static String peerFingerprint(SSLSocket socket) throws IOException {
        try {
            java.security.cert.Certificate[] chain = socket.getSession().getPeerCertificates();
            if (chain.length == 0) {
                throw new IOException("The Mac presented no certificate");
            }
            return sha256(chain[0].getEncoded());
        } catch (CertificateEncodingException failure) {
            throw new IOException("Unreadable Mac certificate", failure);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static SSLSocket open(String host, int port, String pinnedFingerprint,
            int readTimeoutMs) throws IOException {
        SSLSocket socket;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new PinningTrustManager(pinnedFingerprint)},
                    new SecureRandom());
            socket = (SSLSocket) context.getSocketFactory().createSocket();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IOException("TLS is unavailable", failure);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.startHandshake();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);
            return socket;
        } catch (SSLHandshakeException failure) {
            closeQuietly(socket);
            // The trust manager's exception arrives wrapped in the handshake failure.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof FingerprintMismatch mismatch) {
                    throw new FingerprintMismatchException(mismatch);
                }
            }
            throw failure;
        } catch (IOException failure) {
            closeQuietly(socket);
            throw failure;
        }
    }

    static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing left to do with a socket that won't close.
        }
    }

    /** The Mac presented a different certificate than the one it was paired with. */
    static final class FingerprintMismatchException extends IOException {
        FingerprintMismatchException(Throwable cause) {
            super("The Mac's certificate does not match", cause);
        }
    }

    private static final class FingerprintMismatch extends CertificateException {
        FingerprintMismatch() {
            super("Certificate fingerprint mismatch");
        }
    }

    private static final class PinningTrustManager implements X509TrustManager {
        private final String expectedFingerprint;

        PinningTrustManager(String expectedFingerprint) {
            this.expectedFingerprint = expectedFingerprint;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not accepted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Empty certificate chain");
            }
            if (expectedFingerprint != null
                    && !expectedFingerprint.equals(sha256(chain[0].getEncoded()))) {
                throw new FingerprintMismatch();
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
