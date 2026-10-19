package com.example.orders;

import com.example.orders.Dtos.ErrorBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Renders every error as {@code {"error": "..."}}. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return error(e.status(), e.getMessage());
    }

    @ExceptionHandler(UnknownSkuException.class)
    ResponseEntity<ErrorBody> unknownSku(UnknownSkuException e) {
        return error(HttpStatusCode.valueOf(422), e.getMessage());
    }

    @ExceptionHandler(PricingUnavailableException.class)
    ResponseEntity<ErrorBody> pricingUnavailable(PricingUnavailableException e) {
        log.warn("quote failed: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "request body is not valid JSON of the expected shape");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid value for '" + e.getName() + "'");
    }

    // Pool exhausted (connection timeout), statement timeout, failover, etc.: tell the
    // client to retry rather than reporting a server bug.
    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ErrorBody> databaseUnavailable(Exception e) {
        log.warn("database unavailable: {}", e.toString());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "database unavailable");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> other(Exception e) {
        // Spring MVC's own exceptions (404 no handler, 405, 415, ...) carry their status.
        if (e instanceof ErrorResponse response) {
            HttpStatusCode status = response.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String message = known != null ? known.getReasonPhrase().toLowerCase(Locale.ROOT) : "error";
            return ResponseEntity.status(status).headers(response.getHeaders()).body(new ErrorBody(message));
        }
        log.error("unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
    }

    private static ResponseEntity<ErrorBody> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(message));
    }
}
