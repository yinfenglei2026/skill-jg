package com.example.governance.release;

public class SegregationOfDutiesException extends RuntimeException {
    public SegregationOfDutiesException(String actor) {
        super("Review and approval must be performed by different actors: " + actor);
    }
}
