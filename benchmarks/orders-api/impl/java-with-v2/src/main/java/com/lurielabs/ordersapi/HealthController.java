package com.lurielabs.ordersapi;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness only: it never touches the database, so a database outage does not restart every replica. */
@RestController
class HealthController {

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
