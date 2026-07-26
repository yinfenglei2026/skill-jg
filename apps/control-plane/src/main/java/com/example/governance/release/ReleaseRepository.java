package com.example.governance.release;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseRepository extends JpaRepository<Release, String> {
    List<Release> findByCapabilityIdOrderByCreatedAtDesc(String capabilityId);
}
