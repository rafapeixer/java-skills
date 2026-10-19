package com.lurielabs.ordersapi.orders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.lurielabs.ordersapi.api.InvalidRequestException;

/** Keyset position (created_at, id) of the last order on a page, opaque to clients. */
record Cursor(Instant createdAt, long id) {

    static Cursor of(Order order) {
        return new Cursor(order.createdAt(), order.id());
    }

    String encode() {
        String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("expected 3 parts");
            }
            Instant createdAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            return new Cursor(createdAt, Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            throw new InvalidRequestException("invalid cursor");
        }
    }
}
