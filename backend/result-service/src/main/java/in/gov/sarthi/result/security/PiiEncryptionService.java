package in.gov.sarthi.result.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Column-level encryption for PII at rest (roll numbers, grievance
 * text) so a raw database dump or backup isn't plaintext PII by itself.
 *
 * Deliberately a plain static utility, not a Spring bean: it's used
 * from inside JPA {@code AttributeConverter}s (see RollNumberConverter,
 * FreeTextConverter), which Hibernate instantiates via a no-arg
 * constructor rather than through Spring's dependency injection —
 * wiring Spring-managed converters into Hibernate needs extra
 * SpringBeanContainer configuration that adds risk for no real benefit
 * here, since this class holds no per-request state anyway.
 *
 * Two modes, because they trade off differently:
 *
 *  - {@link #encryptDeterministic}: AES-ECB with a fixed key, so the
 *    same plaintext always produces the same ciphertext. This is what
 *    lets {@code findByRollNumberOrderByCreatedAtDesc} keep working as
 *    an equality lookup against encrypted data — Hibernate applies the
 *    same converter to query parameters as to stored values. The known
 *    tradeoff: ECB leaks *equality* (two identical roll numbers look
 *    identical in ciphertext too), acceptable for a value that must
 *    stay exact-match searchable at this scale. A production system at
 *    real scale should use a proper searchable-encryption or
 *    tokenization scheme instead (e.g. a separate deterministic HMAC
 *    "lookup" column alongside randomized ciphertext) rather than ECB.
 *  - {@link #encryptRandom}: AES-GCM with a fresh random IV per call —
 *    semantically secure (the same plaintext produces different
 *    ciphertext every time), used for text that's never searched by
 *    exact match, like grievance descriptions.
 */
public final class PiiEncryptionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private PiiEncryptionService() {}

    private static SecretKeySpec deriveKey() {
        String rawKey = System.getenv().getOrDefault("PII_ENCRYPTION_KEY", "dev-only-insecure-pii-key-change-me-before-deploying");
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hashed, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive PII encryption key", e);
        }
    }

    public static String encryptDeterministic(String plaintext) {
        if (plaintext == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey());
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failure", e);
        }
    }

    public static String decryptDeterministic(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey());
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failure", e);
        }
    }

    public static String encryptRandom(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failure", e);
        }
    }

    public static String decryptRandom(String stored) {
        if (stored == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failure", e);
        }
    }
}
