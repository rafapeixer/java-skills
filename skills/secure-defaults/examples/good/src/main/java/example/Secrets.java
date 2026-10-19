package example;

import java.util.Map;
import java.util.Optional;

/** Secrets come from the environment the platform injects, never from source or a committed file. */
public final class Secrets {

    private Secrets() {}

    /** A missing secret stops startup instead of falling back to a default baked into the jar. */
    public static String required(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(name + " is not set"));
    }
}
