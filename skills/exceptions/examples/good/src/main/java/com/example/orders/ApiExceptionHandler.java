package com.example.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that knows which exception becomes which HTTP status. Extending
 * ResponseEntityExceptionHandler renders Spring MVC's own exceptions (bad parameters,
 * unsupported media type) as RFC 9457 problem details too.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException e) {
        return problem(HttpStatus.CONFLICT, "Insufficient stock", e.getMessage());
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    ProblemDetail inventoryUnavailable(InventoryUnavailableException e) {
        log.warn("dependency failure", e); // logged here, once, with the cause chain
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable", "try again later");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled exception", e);
        // Never echo e.getMessage(): it can carry SQL, hostnames or secrets.
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "unexpected error");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
