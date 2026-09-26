package dev.jason.gboardpatches.extension.macbridge;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the Mac's device token with a non-exportable Android Keystore key, so
 * a copied preferences file (or a backup) does not let another phone act as this
 * one.
 */
final class MacBridgeSecrets {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "gboard_patches_mac_bridge_token";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private MacBridgeSecrets() {
    }

    static String seal(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + sealed.length);
        buffer.put((byte) iv.length).put(iv).put(sealed);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    /** The token, or null when it cannot be decrypted (for example the key was reset). */
    static String open(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            byte[] iv = new byte[buffer.get() & 0xFF];
            buffer.get(iv);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry secretEntry) {
            return secretEntry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
