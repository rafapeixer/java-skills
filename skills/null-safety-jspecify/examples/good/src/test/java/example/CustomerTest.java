package example;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NullUnmarked;
import org.junit.jupiter.api.Test;

// Unmarked on purpose: these tests pass null where the types forbid it, as unchecked callers can.
@NullUnmarked
class CustomerTest {

    @Test
    void theConstructorRejectsNullForNonNullComponents() {
        var e = assertThrows(NullPointerException.class, () -> new Customer(null, "a@example.com", null));
        assertEquals("id", e.getMessage());
        assertThrows(NullPointerException.class, () -> new Customer("c-1", null, null));
    }

    @Test
    void aMissingNicknameFallsBackToTheEmail() {
        assertEquals("a@example.com", new Customer("c-1", "a@example.com", null).displayName());
        assertEquals("Ana", new Customer("c-2", "b@example.com", "Ana").displayName());
    }

    @Test
    void absenceIsAnEmptyOptionalOrAnExplicitNull() {
        var directory = new CustomerDirectory();
        directory.add(new Customer("c-1", "a@example.com", "Ana"));
        directory.add(new Customer("c-2", "b@example.com", null));
        assertTrue(directory.find("missing").isEmpty());
        assertNull(directory.findOrNull("missing"));
        assertEquals("Hello, Ana", directory.greeting("c-1"));
        assertEquals("Hello, guest", directory.greeting("missing"));
        assertEquals(1, directory.withNickname().size());
    }
}
