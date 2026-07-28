package com.example.governance.deployment;

public interface RuntimeObserver {
    RuntimeObservation observe(DeploymentIntent intent);
}
