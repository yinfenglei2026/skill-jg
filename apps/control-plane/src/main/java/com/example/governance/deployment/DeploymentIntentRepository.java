package com.example.governance.deployment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentIntentRepository extends JpaRepository<DeploymentIntent, String> {
    Optional<DeploymentIntent> findByReleaseId(String releaseId);
}
