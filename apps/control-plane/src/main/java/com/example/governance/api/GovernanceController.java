package com.example.governance.api;

import com.example.governance.audit.AuditEvent;
import com.example.governance.capability.Capability;
import com.example.governance.capability.CapabilityType;
import com.example.governance.release.Release;
import com.example.governance.release.ReleaseState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GovernanceController {
    private final GovernanceService service;

    public GovernanceController(GovernanceService service) {
        this.service = service;
    }

    @PostMapping("/capabilities")
    @ResponseStatus(HttpStatus.CREATED)
    CapabilityResponse createCapability(@Valid @RequestBody CreateCapabilityRequest request) {
        return CapabilityResponse.from(service.createCapability(request.name(), request.department(), request.type()));
    }

    @PostMapping("/capabilities/{capabilityId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    ReleaseResponse createRelease(@PathVariable String capabilityId, @Valid @RequestBody CreateReleaseRequest request) {
        return ReleaseResponse.from(service.createRelease(capabilityId, request.version(), request.digest()));
    }

    @PostMapping("/releases/{releaseId}/validate")
    ReleaseResponse validate(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.validate(releaseId));
    }

    @PostMapping("/releases/{releaseId}/review-required")
    ReleaseResponse reviewRequired(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.requireReview(releaseId));
    }

    @PostMapping("/releases/{releaseId}/approve")
    ReleaseResponse approve(@PathVariable String releaseId, @Valid @RequestBody ApproveReleaseRequest request) {
        return ReleaseResponse.from(service.approve(releaseId, request.actor(), request.digest()));
    }

    @PostMapping("/releases/{releaseId}/publish")
    ReleaseResponse publish(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.publish(releaseId));
    }

    @GetMapping("/audit-events")
    List<AuditEvent> auditEvents() {
        return service.auditEvents();
    }

    record CreateCapabilityRequest(@NotBlank String name, @NotBlank String department, CapabilityType type) {
    }

    record CreateReleaseRequest(@NotBlank String version, @NotBlank String digest) {
    }

    record ApproveReleaseRequest(@NotBlank String actor, @NotBlank String digest) {
    }

    record CapabilityResponse(String id, String department, CapabilityType type) {
        static CapabilityResponse from(Capability capability) {
            return new CapabilityResponse(capability.id(), capability.department(), capability.type());
        }
    }

    record ReleaseResponse(String capabilityId, String version, String digest, ReleaseState state) {
        static ReleaseResponse from(Release release) {
            return new ReleaseResponse(release.capabilityId(), release.version(), release.digest(), release.state());
        }
    }
}
