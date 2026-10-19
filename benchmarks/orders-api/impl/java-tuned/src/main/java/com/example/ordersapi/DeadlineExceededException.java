package com.example.ordersapi;

/** The request budget ran out before {@code operation} could be attempted. */
public class DeadlineExceededException extends RuntimeException {

    public DeadlineExceededException(String operation) {
        super("request deadline exceeded before " + operation);
    }
}
