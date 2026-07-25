package com.example.governance.api;

import com.example.governance.release.InvalidArtifactReferenceException;
import com.example.governance.release.InvalidReleaseTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidReleaseTransitionException.class)
    ResponseEntity<ApiError> invalidTransition(InvalidReleaseTransitionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("INVALID_RELEASE_TRANSITION", exception.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(InvalidArtifactReferenceException.class)
    ResponseEntity<ApiError> invalidArtifactReference(InvalidArtifactReferenceException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_ARTIFACT_REFERENCE", exception.getMessage()));
    }
}
