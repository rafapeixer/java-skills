package com.lurielabs.ordersapi.api;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tomcat puts no size limit on JSON bodies. 100 items fit easily in 64 KiB; a declared larger
 * body is refused before it is read, a chunked one is counted while it is read.
 */
@Component
class BodySizeLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"request body too large\"}");
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private ServletInputStream stream;

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
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
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

    static final class BodyTooLargeException extends RuntimeException {

        BodyTooLargeException() {
            super("request body too large");
        }
    }
}
