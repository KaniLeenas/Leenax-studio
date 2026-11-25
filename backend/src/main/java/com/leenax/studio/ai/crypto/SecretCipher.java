package com.leenax.studio.ai.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for API keys.
 *
 * Blob layout: [1 byte format version][12 byte IV][ciphertext + 16 byte tag].
 * The account id is bound in as associated data, so a ciphertext copied onto a
 * different account row fails to decrypt.
 *
 * The 256-bit master key comes from the LEENAX_MASTER_KEY environment variable
 * and never touches the database.
 */
public final class SecretCipher {

    private static final byte FORMAT = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("Master key must be exactly 32 bytes (256 bits)");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public static SecretCipher fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("LEENAX_MASTER_KEY is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("LEENAX_MASTER_KEY is not valid Base64");
        }
        return new SecretCipher(bytes);
    }

    public byte[] encrypt(String plaintext, String associatedData) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + ct.length).put(FORMAT).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(byte[] blob, String associatedData) {
        if (blob == null || blob.length < 1 + IV_BYTES + 16 || blob[0] != FORMAT) {
            throw new IllegalStateException("Stored key is in an unknown format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] pt = cipher.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Stored key could not be decrypted. Was LEENAX_MASTER_KEY changed?");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /** Last four characters, for display ("••••1234"). */
    public static String hint(String apiKey) {
        String k = apiKey.strip();
        if (k.startsWith("{")) {
            // A key file (JSON): its end is always "}", so show the end of the key's id instead.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"private_key_id\"\\s*:\\s*\"([^\"]{8,})\"").matcher(k);
            return m.find() ? m.group(1).substring(m.group(1).length() - 4) : "";
        }
        return k.length() <= 8 ? "" : k.substring(k.length() - 4);
    }
}
