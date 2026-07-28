package com.example.governance.deployment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !local")
public class UnavailableRuntimeObserver implements RuntimeObserver {
    @Override
    public RuntimeObservation observe(DeploymentIntent intent) {
        throw new DeploymentIntegrationUnavailableException(
                "External runtime observation is not configured");
    }
}
