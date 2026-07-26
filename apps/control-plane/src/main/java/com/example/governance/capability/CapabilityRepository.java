package com.example.governance.capability;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityRepository extends JpaRepository<Capability, String> {
    List<Capability> findByDepartmentOrderByIdAsc(String department);
}
