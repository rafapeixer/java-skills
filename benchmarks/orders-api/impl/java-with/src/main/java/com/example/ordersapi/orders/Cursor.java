package com.example.ordersapi.orders;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

/**
 * Keyset position in a customer's order list: the (created_at, id) of the last order on the
 * previous page. Encoded opaquely so clients do not depend on its shape.
 */
record Cursor(Instant createdAt, long id) {

    String encode() {
        String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3) {
                throw new InvalidRequestException("invalid cursor");
            }
            Instant createdAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
            return new Cursor(createdAt, Long.parseLong(parts[2]));
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            // NumberFormatException is an IllegalArgumentException, as are Base64 errors.
            throw new InvalidRequestException("invalid cursor");
        }
    }
}
