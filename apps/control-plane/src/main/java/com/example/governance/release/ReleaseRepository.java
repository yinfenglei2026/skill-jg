package com.example.governance.release;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReleaseRepository extends JpaRepository<Release, String> {
    List<Release> findByCapabilityIdOrderByCreatedAtDesc(String capabilityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select release from Release release where release.id = :id")
    Optional<Release> findByIdForUpdate(@Param("id") String id);
}
