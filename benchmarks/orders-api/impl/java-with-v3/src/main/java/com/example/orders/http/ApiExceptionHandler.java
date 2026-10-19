package com.example.orders.http;

import com.example.orders.order.InvalidRequestException;
import com.example.orders.order.OrderNotFoundException;
import com.example.orders.order.PricingUnavailableException;
import com.example.orders.order.UnknownSkuException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as {"error": "..."}. Spring MVC's own exceptions keep the status the
 * framework chose (400, 404, 405, 415, ...); only failures nobody mapped become a 500.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record ErrorBody(String error) {
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalid(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Object> notFound(OrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "order not found", null);
    }

    @ExceptionHandler(UnknownSkuException.class)
    ResponseEntity<Object> unknownSku(UnknownSkuException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), null);
    }

    @ExceptionHandler(PricingUnavailableException.class)
    ResponseEntity<Object> pricingUnavailable(PricingUnavailableException e) {
        log.warn("quote failed: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "pricing service unavailable", "1");
    }

    @ExceptionHandler(BodyTooLargeException.class)
    ResponseEntity<Object> tooLarge(BodyTooLargeException e) {
        return error(HttpStatus.CONTENT_TOO_LARGE, "request body too large", null);
    }

    /** Full pool (borrow timeout), statement timeout, lost connection: shed, the client may retry. */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ResponseEntity<Object> databaseUnavailable(RuntimeException e) {
        log.warn("database unavailable: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "database unavailable", "1");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error", null);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof BodyTooLargeException) {
                return error(HttpStatus.CONTENT_TOO_LARGE, "request body too large", null);
            }
        }
        return error(HttpStatus.BAD_REQUEST, "malformed JSON body", null);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String message;
        if (statusCode.value() == 404) {
            message = "not found";
        } else if (ex instanceof ErrorResponse er && er.getBody().getDetail() != null) {
            message = er.getBody().getDetail();
        } else {
            HttpStatus known = HttpStatus.resolve(statusCode.value());
            message = known == null ? "error" : known.getReasonPhrase().toLowerCase();
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(message));
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message, @Nullable String retryAfter) {
        var builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(new ErrorBody(message));
    }
}
