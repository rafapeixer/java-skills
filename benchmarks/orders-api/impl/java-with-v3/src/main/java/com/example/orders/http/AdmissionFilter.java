package com.example.orders.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps in-flight requests per instance. With virtual threads nothing else does: without this
 * a spike admits every connection until the 768 MiB heap is gone.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdmissionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdmissionFilter.class);

    // Little's law: ~1,000 req/s per instance at peak x (5 ms typical latency + 250 ms stall,
    // e.g. a full DB pool's 500 ms borrow timeout or a slow pricing call) ~ 255 in flight.
    static final int MAX_IN_FLIGHT = 256;

    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);
    private final LongAdder rejected = new LongAdder();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // A shed health check would get a busy but healthy instance taken out of service.
        return "/healthz".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!permits.tryAcquire()) { // full: answer now, never queue
            rejected.increment();
            if (rejected.sum() % 1000 == 1) {
                log.warn("admission cap {} reached, {} requests shed so far", MAX_IN_FLIGHT, rejected.sum());
            }
            ErrorWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server busy", "1");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
