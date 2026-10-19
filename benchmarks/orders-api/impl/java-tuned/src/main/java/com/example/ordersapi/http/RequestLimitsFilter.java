package com.example.ordersapi.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First thing every request meets: an in-flight cap that answers 503 at once instead of
 * queueing, and a body size limit (Tomcat puts none on JSON bodies) that answers 413.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLimitsFilter extends OncePerRequestFilter {

    /**
     * Little's law, measured: one replica stays within its SLO up to about 6,000 req/s, where
     * the mean request takes about 5 ms (85% are 1 ms reads and writes, 15% are 30 ms quotes),
     * so about 30 are in flight. 256 absorbs a 35 ms stall (a G1 pause, a burst of JIT work)
     * at that rate without shedding, and bounds the queue at about 40 ms when overloaded.
     * Slow pricing is bounded separately (PricingClient, 3 s quote budget).
     */
    static final int MAX_IN_FLIGHT = 256;

    /** 100 items with 200-character SKUs is about 25 KB of JSON. */
    static final long MAX_BODY_BYTES = 64 * 1024;

    private final Semaphore admission = new Semaphore(MAX_IN_FLIGHT);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/healthz".equals(request.getRequestURI())) {
            chain.doFilter(request, response); // health checks are never shed
            return;
        }
        if (!admission.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            writeError(response, 503, "server overloaded, retry later");
            return;
        }
        try {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                writeError(response, 413, "request body is larger than " + MAX_BODY_BYTES + " bytes");
                return;
            }
            chain.doFilter(new LimitedRequest(request), response);
        } finally {
            admission.release();
        }
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Messages here are constants without quotes or backslashes, so no escaping is needed.
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /** Counts body bytes as they are read, for chunked bodies without a Content-Length. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private @Nullable ServletInputStream stream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream());
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream in;
        private long count;

        LimitedInputStream(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(int n) {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException(MAX_BODY_BYTES);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
