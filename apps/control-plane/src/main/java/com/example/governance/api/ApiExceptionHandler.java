package com.example.governance.api;

import com.example.governance.release.InvalidArtifactReferenceException;
import com.example.governance.release.InvalidReleaseTransitionException;
import com.example.governance.release.SegregationOfDutiesException;
import com.example.governance.release.ArtifactVerificationException;
import com.example.governance.deployment.DeploymentIntegrationUnavailableException;
import com.example.governance.manifest.InvalidCapabilityManifestException;
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

    @ExceptionHandler(SegregationOfDutiesException.class)
    ResponseEntity<ApiError> segregationOfDuties(SegregationOfDutiesException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SEGREGATION_OF_DUTIES", exception.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(InvalidArtifactReferenceException.class)
    ResponseEntity<ApiError> invalidArtifactReference(InvalidArtifactReferenceException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_ARTIFACT_REFERENCE", exception.getMessage()));
    }

    @ExceptionHandler(ArtifactVerificationException.class)
    ResponseEntity<ApiError> artifactVerificationFailed(ArtifactVerificationException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("UNVERIFIED_ARTIFACT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidCapabilityManifestException.class)
    ResponseEntity<ApiError> invalidCapabilityManifest(InvalidCapabilityManifestException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_CAPABILITY_MANIFEST", exception.getMessage()));
    }

    @ExceptionHandler(DeploymentIntegrationUnavailableException.class)
    ResponseEntity<ApiError> deploymentIntegrationUnavailable(
            DeploymentIntegrationUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("DEPLOYMENT_INTEGRATION_UNAVAILABLE", exception.getMessage()));
    }
}
