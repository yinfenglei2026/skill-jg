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
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasRole('OWNER')")
    CapabilityResponse createCapability(@Valid @RequestBody CreateCapabilityRequest request) {
        return CapabilityResponse.from(service.createCapability(request.name(), request.department(), request.type()));
    }

    @PostMapping("/capabilities/{capabilityId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse createRelease(@PathVariable String capabilityId, @Valid @RequestBody CreateReleaseRequest request) {
        return ReleaseResponse.from(service.createRelease(capabilityId, request.version(), request.artifactReference()));
    }

    @GetMapping("/capabilities")
    @PreAuthorize("hasAnyRole('OWNER', 'REVIEWER', 'APPROVER', 'OPERATOR', 'READ_ONLY')")
    List<CapabilityResponse> capabilities() {
        return service.capabilities().stream().map(CapabilityResponse::from).toList();
    }

    @GetMapping("/capabilities/{capabilityId}")
    @PreAuthorize("hasAnyRole('OWNER', 'REVIEWER', 'APPROVER', 'OPERATOR', 'READ_ONLY')")
    CapabilityResponse capability(@PathVariable String capabilityId) {
        return CapabilityResponse.from(service.capability(capabilityId));
    }

    @GetMapping("/capabilities/{capabilityId}/releases")
    @PreAuthorize("hasAnyRole('OWNER', 'REVIEWER', 'APPROVER', 'OPERATOR', 'READ_ONLY')")
    List<ReleaseResponse> releases(@PathVariable String capabilityId) {
        return service.releases(capabilityId).stream().map(ReleaseResponse::from).toList();
    }

    @GetMapping("/releases/{releaseId}")
    @PreAuthorize("hasAnyRole('OWNER', 'REVIEWER', 'APPROVER', 'OPERATOR', 'READ_ONLY')")
    ReleaseResponse release(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.release(releaseId));
    }

    @PostMapping("/releases/{releaseId}/validate")
    @PreAuthorize("hasRole('REVIEWER')")
    ReleaseResponse validate(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.validate(releaseId));
    }

    @PostMapping("/releases/{releaseId}/review-required")
    @PreAuthorize("hasRole('REVIEWER')")
    ReleaseResponse reviewRequired(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.requireReview(releaseId));
    }

    @PostMapping("/releases/{releaseId}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    ReleaseResponse approve(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.approve(releaseId));
    }

    @PostMapping("/releases/{releaseId}/publish")
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse publish(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.publish(releaseId));
    }

    @PostMapping("/releases/{releaseId}/deploying")
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse deploying(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.deploying(releaseId));
    }

    @PostMapping("/releases/{releaseId}/deployed")
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse deployed(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.deployed(releaseId));
    }

    @PostMapping("/releases/{releaseId}/degraded")
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse degraded(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.degraded(releaseId));
    }

    @PostMapping("/releases/{releaseId}/failed")
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse failed(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.failed(releaseId));
    }

    @PostMapping("/releases/{releaseId}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    ReleaseResponse reject(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.reject(releaseId));
    }

    @PostMapping("/releases/{releaseId}/revoke")
    @PreAuthorize("hasRole('OPERATOR')")
    ReleaseResponse revoke(@PathVariable String releaseId) {
        return ReleaseResponse.from(service.revoke(releaseId));
    }

    @GetMapping("/audit-events")
    @PreAuthorize("hasAnyRole('OWNER', 'REVIEWER', 'APPROVER', 'OPERATOR', 'READ_ONLY')")
    List<AuditEvent> auditEvents() {
        return service.auditEvents();
    }

    record CreateCapabilityRequest(@NotBlank String name, @NotBlank String department, CapabilityType type) {
    }

    record CreateReleaseRequest(@NotBlank String version, @NotBlank String artifactReference) {
    }

    record CapabilityResponse(String id, String department, CapabilityType type) {
        static CapabilityResponse from(Capability capability) {
            return new CapabilityResponse(capability.id(), capability.department(), capability.type());
        }
    }

    record ReleaseResponse(String id, String capabilityId, String version, String artifactReference, String digest, ReleaseState state) {
        static ReleaseResponse from(Release release) {
            return new ReleaseResponse(release.id(), release.capabilityId(), release.version(), release.artifactReference(), release.digest(), release.state());
        }
    }
}
