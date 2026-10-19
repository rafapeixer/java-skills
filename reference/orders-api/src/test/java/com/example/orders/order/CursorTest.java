package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void roundTripKeepsMicrosecondsAndId() {
        // PostgreSQL timestamptz has microsecond precision; the cursor must not lose any of it.
        var at = OffsetDateTime.of(2026, 10, 18, 12, 30, 45, 123_456_000, ZoneOffset.ofHours(-3));

        Cursor decoded = Cursor.decode(new Cursor(at, 987_654_321L).encode());

        assertThat(decoded.createdAt().toInstant()).isEqualTo(at.toInstant());
        assertThat(decoded.id()).isEqualTo(987_654_321L);
    }

    @Test
    void encodedCursorIsUrlSafe() {
        var at = OffsetDateTime.of(2026, 10, 18, 12, 0, 0, 0, ZoneOffset.UTC);

        assertThat(new Cursor(at, Long.MAX_VALUE).encode()).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "bm9jb2xvbg", "YWJjOjEyMw", "MTIzOmFiYw", "OTk5OTk5OTk5OTk5OTk5OTk5OTk6MQ"})
    void garbageIsAnInvalidRequest(String cursor) {
        // "nocolon", "abc:123", "123:abc", "99999999999999999999:1"
        assertThatThrownBy(() -> Cursor.decode(cursor))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("invalid cursor");
    }
}
