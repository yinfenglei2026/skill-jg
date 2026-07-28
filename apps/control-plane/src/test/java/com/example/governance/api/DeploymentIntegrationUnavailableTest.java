package com.example.governance.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.governance.deployment.DeploymentIntegrationUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DeploymentIntegrationUnavailableTest {
    @Test
    void maps_unconfigured_deployment_integrations_to_service_unavailable() {
        ResponseEntity<ApiError> response = new ApiExceptionHandler().deploymentIntegrationUnavailable(
                new DeploymentIntegrationUnavailableException("External integration is not configured"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DEPLOYMENT_INTEGRATION_UNAVAILABLE");
    }
}
