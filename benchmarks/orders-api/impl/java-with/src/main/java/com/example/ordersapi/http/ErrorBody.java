package com.example.ordersapi.http;

/** The API's error shape: {@code {"error":"<message>"}}. */
record ErrorBody(String error) {}
