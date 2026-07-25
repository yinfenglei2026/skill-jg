package com.example.governance.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, java.util.UUID> {
    List<AuditEvent> findByDepartmentOrderByOccurredAtAsc(String department);
}
