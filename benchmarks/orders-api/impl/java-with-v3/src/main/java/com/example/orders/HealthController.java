package com.example.orders;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness: never touches the database, so a database outage does not restart every instance. */
@RestController
class HealthController {

    private final ShutdownDrain drain;

    HealthController(ShutdownDrain drain) {
        this.drain = drain;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> health() {
        if (drain.draining()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "draining"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
