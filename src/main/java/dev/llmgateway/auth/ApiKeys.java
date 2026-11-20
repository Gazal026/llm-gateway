package dev.llmgateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Key generation and hashing. Keys are high-entropy random strings, so a fast SHA-256 is appropriate
 * (unlike passwords, which need a slow hash such as bcrypt/argon2).
 */
public final class ApiKeys {

    private static final String PREFIX = "sk-gw-";
    private static final int RANDOM_BYTES = 32;
    private static final int DISPLAY_PREFIX_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** The first few characters, safe to show in UIs and logs so people can tell keys apart. */
    public static String displayPrefix(String rawKey) {
        return rawKey.length() <= DISPLAY_PREFIX_LENGTH ? rawKey : rawKey.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /** Constant-time comparison, so response timing doesn't leak how much of a key matched. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
