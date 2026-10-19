package com.lurielabs.ordersapi.api;

import com.lurielabs.ordersapi.pricing.PricingUnavailableException;
import com.lurielabs.ordersapi.pricing.UnknownSkuException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place errors become HTTP responses, all as {"error": "..."}. Spring MVC's own
 * exceptions (malformed JSON, bad path variable, unknown route, wrong method) keep their 4xx
 * status through the base class; only unexpected exceptions become a generic 500.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record ErrorBody(String error) {
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> invalid(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> tooLarge(BodySizeLimitFilter.BodyTooLargeException e) {
        return error(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> unknownSku(UnknownSkuException e) {
        return error(HttpStatusCode.valueOf(422), e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> pricingUnavailable(PricingUnavailableException e) {
        log.warn("pricing unavailable: {}", e.getMessage(), e.getCause());
        return unavailable("pricing service unavailable");
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> overloaded(OverloadedException e) {
        log.warn(e.getMessage());
        return unavailable("service overloaded, retry later");
    }

    /** Pool exhausted (500 ms borrow timeout), statement_timeout, lock or serialization failures. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, TransientDataAccessException.class})
    ResponseEntity<ErrorBody> databaseUnavailable(Exception e) {
        log.warn("database unavailable: {}", e.toString());
        return unavailable("database unavailable, retry later");
    }

    @ExceptionHandler
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    /** Renders the framework's exceptions, which arrive here as a ProblemDetail, in our shape. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : reason(statusCode);
        return ResponseEntity.status(statusCode).headers(headers).body(new ErrorBody(message));
    }

    private static ResponseEntity<ErrorBody> unavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorBody(message));
    }

    private static ResponseEntity<ErrorBody> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(message));
    }

    private static String reason(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "error " + status.value();
    }
}
