package example;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A nickname is optional; the id and email are not, and the constructor enforces that at runtime too. */
public record Customer(String id, String email, @Nullable String nickname) {

    public Customer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
    }

    public String displayName() {
        String nick = nickname;
        return nick != null ? nick : email;
    }
}
