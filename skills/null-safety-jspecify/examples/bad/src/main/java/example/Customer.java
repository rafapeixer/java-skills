package example;

import org.jspecify.annotations.Nullable;

// MISTAKE: no compact constructor. Unmarked callers (JSON mappers, old code, reflection) still pass null,
// and the record stores it; the NullPointerException surfaces far from where the null came in.
public record Customer(String id, String email, @Nullable String nickname) {

    public String displayName() {
        // MISTAKE: dereferencing a @Nullable component; NullAway would reject this line.
        return nickname.isBlank() ? email : nickname;
    }
}
