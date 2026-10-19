package com.example.ordersapi;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for the load balancer. It deliberately does not check Postgres or pricing: a
 * database outage would otherwise mark every replica unhealthy at once.
 */
@RestController
class HealthController {

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
