# Local PoC Pre-Merge Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the local governance loop safe to integrate by binding artifact identity, supporting durable deployment retries, enforcing approval separation, preserving Skill import paths, and allowing only cluster DNS through the runtime default-deny policy.

**Architecture:** Keep one immutable release and one desired-state deployment intent per release, but move attempt persistence into a transactional component around non-transactional external adapter calls. Extend existing manifest and dependency records instead of introducing parallel package models. Enforce governance invariants in domain objects and prove every behavior through API/domain contracts.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, Flyway, H2/PostgreSQL, JUnit 5, MockMvc, PowerShell, Kubernetes Kustomize.

---

### Task 1: Bind Manifest And Request Artifact Identity

**Files:**
- Modify: `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackage.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackageParser.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactReference.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/release/ArtifactReferenceTest.java`

- [ ] **Step 1: Add failing identity mismatch tests**

Add an API test which generates a valid manifest for `support-agent` but registers `oci://registry.example.internal/other/support-agent@<canonical digest>` and expects `400 INVALID_CAPABILITY_MANIFEST`. Add `ArtifactReferenceTest` assertions that a base manifest URI matches its digest-qualified reference, while a different registry, repository, or explicit digest does not.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
& .\mvnw.cmd -B -pl apps/control-plane -Dtest=ArtifactReferenceTest,GovernanceApiTest test
```

Expected: the mismatch registration is created instead of rejected, or the wished-for matching API does not compile.

- [ ] **Step 3: Retain and compare artifact identity**

Change `ReleaseDescriptor` to retain `artifactUri` and make `validateRelease` return it. Add this behavior to `ArtifactReference`:

```java
public boolean matchesManifestUri(String manifestUri) {
    String base = "oci://" + registry + "/" + repository;
    return manifestUri.equals(base) || manifestUri.equals(value);
}
```

After parsing the request reference, reject when `artifact.matchesManifestUri(manifest.release().artifactUri())` is false. Keep the existing canonical/release digest checks.

- [ ] **Step 4: Run focused and complete control-plane tests**

Expected: all artifact and API tests pass.

- [ ] **Step 5: Commit artifact binding**

```powershell
git add apps/control-plane/src/main/java apps/control-plane/src/test/java
git commit -m "fix: bind release artifact identity"
```

### Task 2: Persist Deployment Failure And Support Retry

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentTransactions.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentService.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentIntent.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/deployment/LocalGitOpsReconciler.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/deployment/DeploymentIntentTest.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/DeploymentFailurePersistenceTest.java`

- [ ] **Step 1: Add failing retry and idempotency contracts**

Add a domain test proving a failed intent can restart with the same ID/digest and a new actor/time, while a mismatched release digest is rejected. Extend the ready-deployment API test to repeat POST and assert the same intent ID is returned without duplicate `DEPLOYMENT_REQUESTED` audit events.

- [ ] **Step 2: Add a failing adapter-failure integration test**

Use a test `RuntimeObserver` at the external port boundary which throws `DeploymentIntegrationUnavailableException` on the first observation and returns `READY` on the second. Assert the first POST returns `503`, GET exposes a `FAILED` intent and release, and the second POST reaches `READY`/`DEPLOYED` using the same intent ID.

- [ ] **Step 3: Run the deployment tests and verify RED**

Expected: failure state is rolled back, retry is rejected from `FAILED`, and duplicate ready POST conflicts.

- [ ] **Step 4: Separate transactions from adapters**

Create `DeploymentTransactions` with transactional `start`, `complete`, and `fail` methods. `start` creates or restarts the intent and commits `DEPLOYING`; it returns an already-ready result for a digest-matching deployed release. `DeploymentService.deploy` becomes non-transactional, calls adapters between `start` and `complete`, and calls `fail` before rethrowing adapter exceptions.

Use these intent operations:

```java
public void restart(Release release, Actor actor, Instant requestedAt) {
    if (!desiredDigest.equals(release.digest())) {
        throw new IllegalArgumentException("Deployment intent digest cannot change");
    }
    status = DeploymentStatus.RECONCILING;
    observedDigest = null;
    observedAt = null;
    requestedActor = actor.subject();
    this.requestedAt = requestedAt;
}

public void fail(Instant occurredAt) {
    status = DeploymentStatus.FAILED;
    observedAt = occurredAt;
}
```

- [ ] **Step 5: Run deployment and complete Java tests**

Expected: failure persistence, retry, idempotency, and all existing tests pass.

- [ ] **Step 6: Commit deployment attempts**

```powershell
git add apps/control-plane/src/main/java apps/control-plane/src/test/java
git commit -m "fix: persist retryable deployment attempts"
```

### Task 3: Enforce Reviewer And Approver Separation

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/SegregationOfDutiesException.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/Release.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/ApiExceptionHandler.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/release/ReleaseLifecycleTest.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Add failing domain and API tests**

At the domain level, have actor `dual-role` enter `REVIEW_REQUIRED` and assert its approval throws `SegregationOfDutiesException`. At the API level, use the same JWT subject as reviewer and approver, expect `409 SEGREGATION_OF_DUTIES`, keep the release in `REVIEW_REQUIRED`, and require one durable `RELEASE_APPROVAL_DENIED` audit record.

- [ ] **Step 2: Run focused tests and verify RED**

Expected: self-approval currently succeeds.

- [ ] **Step 3: Enforce and map the invariant**

Before the approval transition, locate the transition whose target is `REVIEW_REQUIRED` and reject the same actor. Map the exception to `409` with code `SEGREGATION_OF_DUTIES`. In `GovernanceService.approve`, persist the denial through the existing independent audit service before rethrowing.

- [ ] **Step 4: Run focused and complete Java tests**

Expected: self-approval is rejected and normal distinct-actor approval remains green.

- [ ] **Step 5: Commit approval separation**

```powershell
git add apps/control-plane/src/main/java apps/control-plane/src/test/java
git commit -m "fix: separate review and approval actors"
```

### Task 4: Preserve Skill Import Paths In Dependency Locks

**Files:**
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/ReleaseDependency.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/DependencyResolver.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceController.java`
- Create: `apps/control-plane/src/main/resources/db/migration/V4__skill_dependency_import_path.sql`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Change the API test to require `importPath`**

Replace the current `doesNotExist()` assertion for the Skill dependency with:

```java
.andExpect(jsonPath("$.dependencies[1].importPath")
        .value("skills/ticket-triage/SKILL.md"));
```

Also assert the MCP dependency has no import path.

- [ ] **Step 2: Run the focused API test and verify RED**

Expected: the Skill response omits `importPath`.

- [ ] **Step 3: Persist and expose the field**

Add nullable `dependency_import_path` through Flyway and `ReleaseDependency`. Pass `DependencyDefinition.importPath()` from `DependencyResolver`. Add `importPath` to `DependencyResponse`.

- [ ] **Step 4: Run API and complete Java tests**

Expected: migration validation, persistence reloads, and API assertions pass.

- [ ] **Step 5: Commit the complete dependency lock**

```powershell
git add apps/control-plane
git commit -m "fix: preserve skill import paths"
```

### Task 5: Allow Kubernetes DNS Through Default-Deny Egress

**Files:**
- Create: `scripts/test-runtime-network.ps1`
- Modify: `scripts/verify.ps1`
- Modify: `infra/k8s/base/runtime-network.yaml`

- [ ] **Step 1: Add a failing rendered-network contract**

Create a script which runs `kubectl kustomize infra/k8s/base` and requires all of these patterns:

```powershell
'name:\s+default-deny-all'
'kubernetes.io/metadata.name:\s+kube-system'
'k8s-app:\s+kube-dns'
'protocol:\s+UDP[\s\S]*port:\s+53'
'protocol:\s+TCP[\s\S]*port:\s+53'
```

Call the script from `verify.ps1` instead of duplicating runtime-network assertions.

- [ ] **Step 2: Run the focused contract and verify RED**

Run:

```powershell
.\scripts\test-runtime-network.ps1
```

Expected: FAIL because the rendered base has no DNS egress policy.

- [ ] **Step 3: Add the scoped DNS policy**

Add a `runtime-dns-egress` NetworkPolicy selecting all runtime pods, limited to the `kube-system` namespace and `k8s-app: kube-dns` pods on UDP/TCP port 53.

- [ ] **Step 4: Run the focused contract and Kustomize render**

Expected: the contract passes and `kubectl kustomize infra/k8s/base` exits 0.

- [ ] **Step 5: Commit DNS egress**

```powershell
git add infra/k8s/base/runtime-network.yaml scripts/test-runtime-network.ps1 scripts/verify.ps1
git commit -m "fix: allow runtime dns resolution"
```

### Task 6: Final Verification And Integration Readiness

**Files:**
- Modify only if verification exposes a regression in a file already covered above.

- [ ] **Step 1: Run the root verifier**

```powershell
.\scripts\verify.ps1
```

Expected: Java tests, identity contracts, Portal tests/type/build, runtime network contract, Compose rendering, and realm validation pass.

- [ ] **Step 2: Review repository state**

```powershell
git diff --check HEAD~5..HEAD
git status --short
git log --oneline -8
```

Expected: no whitespace errors, clean worktree, and isolated commits for the design and five fixes.

- [ ] **Step 3: Compare integration branches without mutating the dirty checkout**

```powershell
git rev-list --left-right --count feat/platform-poc...feat/local-poc-loop
git diff --name-status feat/platform-poc..feat/local-poc-loop
```

Expected: the candidate contains the complete reviewed implementation; integration is deferred if the checked-out `feat/platform-poc` still has overlapping uncommitted changes.
