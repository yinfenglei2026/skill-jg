package com.example.governance.deployment;

public interface GitOpsReconciler {
    void reconcile(DeploymentIntent intent);
}
