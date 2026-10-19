package example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Session tokens, reset links and API keys: unpredictable, stored hashed, compared in constant time. */
public final class Tokens {

    // One shared instance: SecureRandom is thread-safe. Not getInstanceStrong(): on Linux it reads /dev/random.
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** 256 random bits as 43 URL-safe characters, ready for a link or a header. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Store this, never the token: a leaked table then grants nothing. */
    public static String storedForm(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    /** Hashing both sides first gives isEqual two 32-byte inputs, so timing reveals nothing. */
    public static boolean matches(String presented, String storedHex) {
        return MessageDigest.isEqual(sha256(presented), HexFormat.of().parseHex(storedHex));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
