package example;

import java.time.Instant;

public record Reservation(long id, String guest, Instant expiresAt) {}
