package com.example.governance.deployment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public class LocalGitOpsReconciler implements GitOpsReconciler {
    @Override
    public void reconcile(DeploymentIntent intent) {
        intent.reconciling();
    }
}
