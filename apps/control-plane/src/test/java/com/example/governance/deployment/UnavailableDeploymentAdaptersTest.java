package com.example.governance.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UnavailableDeploymentAdaptersTest {
    @Test
    void gitops_reconciler_fails_closed_when_external_integration_is_unconfigured() {
        assertThatThrownBy(() -> new UnavailableGitOpsReconciler().reconcile(null))
                .isInstanceOf(DeploymentIntegrationUnavailableException.class)
                .hasMessage("External GitOps reconciliation is not configured");
    }

    @Test
    void runtime_observer_fails_closed_when_external_integration_is_unconfigured() {
        assertThatThrownBy(() -> new UnavailableRuntimeObserver().observe(null))
                .isInstanceOf(DeploymentIntegrationUnavailableException.class)
                .hasMessage("External runtime observation is not configured");
    }
}
