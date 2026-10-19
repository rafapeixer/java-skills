package example;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * With virtual threads Tomcat's thread pool no longer caps concurrency, so nothing else bounds
 * how many requests are in flight. This filter runs first: a permit or an immediate 503.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdmissionFilter extends OncePerRequestFilter {

    final Semaphore permits;

    // Little's law (see overload-protection): measured peak 6,000 req/s × (5 ms mean latency
    // + 35 ms longest stall) = 240, rounded up to 256. Re-measure when traffic or latency changes.
    AdmissionFilter(@Value("${orders.max-in-flight:256}") int maxInFlight) {
        this.permits = new Semaphore(maxInFlight);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/livez") || path.equals("/readyz"); // probes are never shed
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!permits.tryAcquire()) { // full: answer now, never queue
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
                    + "\"status\":503,\"detail\":\"Too many requests in flight, retry later.\"}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
