package com.example.orders.order;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** Keyset position (created_at, id) of the last order on a page, opaque to clients. */
record Cursor(OffsetDateTime createdAt, long id) {

    static Cursor after(Order order) {
        return new Cursor(order.createdAt(), order.id());
    }

    String encode() {
        Instant at = createdAt.toInstant();
        long micros = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1_000);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int colon = raw.indexOf(':');
            if (colon < 0) {
                throw new InvalidRequestException("invalid cursor");
            }
            long micros = Long.parseLong(raw.substring(0, colon));
            long id = Long.parseLong(raw.substring(colon + 1));
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS).atOffset(ZoneOffset.UTC), id);
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
            throw new InvalidRequestException("invalid cursor");
        }
    }
}
