package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.governance.capability.Capability;
import com.example.governance.capability.CapabilityRepository;
import com.example.governance.capability.CapabilityType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReleaseOptimisticLockingTest {
    private static final String DIGEST = "sha256:0f" + "4".repeat(62);

    @Autowired
    private CapabilityRepository capabilities;

    @Autowired
    private ReleaseRepository releases;

    @Test
    void rejects_a_stale_release_update() {
        capabilities.saveAndFlush(new Capability("support-agent", "customer-operations", CapabilityType.AGENT));
        releases.saveAndFlush(Release.draft("support-agent", "1.0.0",
                "oci://registry.example.internal/governance/support-agent@" + DIGEST,
                DIGEST, Instant.parse("2026-07-26T00:00:00Z")));

        Release firstCopy = releases.findById("support-agent:1.0.0").orElseThrow();
        Release staleCopy = releases.findById("support-agent:1.0.0").orElseThrow();

        firstCopy.validationPassed("reviewer", Instant.parse("2026-07-26T00:01:00Z"));
        releases.saveAndFlush(firstCopy);
        staleCopy.validationPassed("reviewer", Instant.parse("2026-07-26T00:01:00Z"));

        assertThatThrownBy(() -> releases.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
