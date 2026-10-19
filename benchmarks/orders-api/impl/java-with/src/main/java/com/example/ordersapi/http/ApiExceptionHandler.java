package com.example.ordersapi.http;

import com.example.ordersapi.DeadlineExceededException;
import com.example.ordersapi.orders.InvalidRequestException;
import com.example.ordersapi.orders.OrderNotFoundException;
import com.example.ordersapi.pricing.PricingUnavailableException;
import com.example.ordersapi.pricing.UnknownSkuException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place exceptions become HTTP responses, all as {@code {"error":"..."}}. Spring MVC's
 * own exceptions (malformed JSON, wrong method, unknown route, bad path variable) keep their
 * 4xx status through {@link ResponseEntityExceptionHandler}; only the reshaping is ours.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorBody> invalid(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ErrorBody> notFound(OrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UnknownSkuException.class)
    ResponseEntity<ErrorBody> unknownSku(UnknownSkuException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ErrorBody> tooLarge(PayloadTooLargeException e) {
        return error(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler({PricingUnavailableException.class, DeadlineExceededException.class})
    ResponseEntity<ErrorBody> pricingUnavailable(RuntimeException e) {
        log.warn("quote failed: {}", e.getMessage(), e.getCause());
        return unavailable("pricing service unavailable");
    }

    /** Pool exhausted (500 ms borrow timeout), database down, or a query/transaction timeout. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class, TransactionTimedOutException.class})
    ResponseEntity<ErrorBody> databaseUnavailable(Exception e) {
        log.warn("database unavailable: {}", e.toString());
        return unavailable("database unavailable, retry later");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
    }

    /** Reshapes Spring MVC's ProblemDetail responses into the API's error body. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : reason(statusCode);
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .<Object>body(new ErrorBody(message));
    }

    static String reason(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "error " + status.value() : known.getReasonPhrase().toLowerCase();
    }

    private static ResponseEntity<ErrorBody> unavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(message));
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(message));
    }
}
