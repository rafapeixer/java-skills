package example;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** User passwords: a slow, salted, versioned hash from Spring Security, never a plain digest. */
public final class Passwords {

    // Writes {bcrypt}..., reads every id Spring Security knows, so the algorithm can change later.
    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private Passwords() {}

    public static String hash(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    public static boolean verify(String rawPassword, String storedHash) {
        return ENCODER.matches(rawPassword, storedHash);
    }

    /** True when the stored hash uses an older algorithm or cost: re-hash it after a successful login. */
    public static boolean needsRehash(String storedHash) {
        return ENCODER.upgradeEncoding(storedHash);
    }
}
