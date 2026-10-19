package com.example.orders;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

/**
 * Keyset pagination position: the (created_at, id) of the last order on the previous page.
 * Opaque to clients (base64url).
 */
record Cursor(Instant createdAt, long id) {

    String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep < 0) {
                throw ApiException.badRequest("invalid cursor");
            }
            return new Cursor(Instant.parse(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw ApiException.badRequest("invalid cursor");
        }
    }
}
