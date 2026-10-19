package com.example.orders;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * On SIGTERM, keeps serving while /healthz reports 503 so the load balancer stops routing here,
 * before Tomcat's graceful shutdown stops accepting connections.
 */
@Component
class ShutdownDrain implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShutdownDrain.class);

    private final Duration drain;
    private volatile boolean running;
    private volatile boolean draining;

    ShutdownDrain(@Value("${shutdown.drain}") Duration drain) {
        this.drain = drain;
    }

    boolean draining() {
        return draining;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        draining = true;
        log.info("draining for {} before graceful shutdown", drain);
        try {
            Thread.sleep(drain);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Highest phase: stops before the web server's graceful shutdown phase. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
