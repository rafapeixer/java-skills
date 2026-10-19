package com.example.ordersapi.http;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Errors raised outside Spring MVC (by Tomcat or a filter) are forwarded to /error; answer
 * them in the API's error shape instead of Boot's default body.
 */
@RestController
class FallbackErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ErrorBody> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatusCode status = HttpStatusCode.valueOf(
                code instanceof Integer value && value >= 400 ? value : HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(ApiExceptionHandler.reason(status)));
    }
}
