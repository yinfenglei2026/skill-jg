package com.example.governance.deployment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !local")
public class UnavailableGitOpsReconciler implements GitOpsReconciler {
    @Override
    public void reconcile(DeploymentIntent intent) {
        throw new DeploymentIntegrationUnavailableException(
                "External GitOps reconciliation is not configured");
    }
}
