package com.example.governance.deployment;

import com.example.governance.deployment.DeploymentTransactions.DeploymentStart;
import org.springframework.stereotype.Service;

@Service
public class DeploymentService {
    private final DeploymentTransactions transactions;
    private final GitOpsReconciler reconciler;
    private final RuntimeObserver observer;

    public DeploymentService(DeploymentTransactions transactions, GitOpsReconciler reconciler,
                             RuntimeObserver observer) {
        this.transactions = transactions;
        this.reconciler = reconciler;
        this.observer = observer;
    }

    public DeploymentIntent deploy(String releaseId) {
        DeploymentStart start = transactions.start(releaseId);
        if (!start.reconcile()) {
            return start.intent();
        }
        RuntimeObservation observation;
        try {
            reconciler.reconcile(start.intent());
            observation = observer.observe(start.intent());
        } catch (RuntimeException exception) {
            transactions.fail(releaseId);
            throw exception;
        }
        return transactions.complete(releaseId, observation);
    }

    public DeploymentIntent deployment(String releaseId) {
        return transactions.deployment(releaseId);
    }
}
