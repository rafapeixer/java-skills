package com.example.orders.http;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;

/** Writes {"error": ...} from filters, which run before Spring MVC's message converters. */
final class ErrorWriter {

    private ErrorWriter() {
    }

    /** {@code message} is a constant from this package, so it needs no JSON escaping. */
    static void write(HttpServletResponse response, int status, String message, @Nullable String retryAfter)
            throws IOException {
        response.setStatus(status);
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfter);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
