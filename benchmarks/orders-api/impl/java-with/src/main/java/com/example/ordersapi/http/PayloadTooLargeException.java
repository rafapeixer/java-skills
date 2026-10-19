package com.example.ordersapi.http;

/** A chunked request body grew past the limit while the controller was reading it. */
class PayloadTooLargeException extends RuntimeException {

    PayloadTooLargeException(long maxBytes) {
        super("request body is larger than " + maxBytes + " bytes");
    }
}
