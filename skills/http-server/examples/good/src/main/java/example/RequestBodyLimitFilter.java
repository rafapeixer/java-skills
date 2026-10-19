package example;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tomcat has no size limit for JSON bodies: maxPostSize covers only form parameters and
 * max-swallow-size covers only bodies Tomcat discards. This filter bounds every body.
 */
@Component
class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** Thrown while the controller reads the body, so Spring MVC renders it as a 413 problem. */
    static final class BodyTooLargeException extends ErrorResponseException {
        BodyTooLargeException() {
            super(HttpStatus.CONTENT_TOO_LARGE);
            getBody().setDetail("Request body is larger than " + MAX_BODY_BYTES + " bytes.");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (getContentLengthLong() > MAX_BODY_BYTES) {
                throw new BodyTooLargeException(); // declared too large: do not read a byte
            }
            return new LimitedInputStream(super.getInputStream()); // chunked: count while reading
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream in;
        private long remaining = MAX_BODY_BYTES;

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
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            remaining -= n;
            if (remaining < 0) {
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
}
