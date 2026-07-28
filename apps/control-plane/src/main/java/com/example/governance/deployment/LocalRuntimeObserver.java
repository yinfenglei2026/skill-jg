package com.example.governance.deployment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public class LocalRuntimeObserver implements RuntimeObserver {
    @Override
    public RuntimeObservation observe(DeploymentIntent intent) {
        return new RuntimeObservation(intent.desiredDigest(), DeploymentStatus.READY);
    }
}
