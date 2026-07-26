# Local Verifiable Governance PoC Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a locally repeatable governance loop from a validated, digest-bound capability manifest through review, approval, deployment intent, runtime observation, Portal workflow, and durable audit evidence.

**Architecture:** Add focused manifest, trust, deployment, and audit services around the existing Spring release aggregate. Use `test` and `local` profile adapters for deterministic evidence and reconciliation, while the default profile remains fail-closed behind production integration ports. Extend the Portal through a typed API client and small workflow components, then make the root verifier and CI enforce the complete build.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, Flyway, Jackson YAML, PostgreSQL/H2, React 19, TypeScript 5.9, Vite 7, Vitest 4, PowerShell, Docker Compose, Kubernetes Kustomize.

---

## Milestone 1: Stabilize Existing Security And Audit Behavior

### Task 1: Commit the OIDC expiration-listener fix

**Files:**
- Modify: `apps/portal/src/App.tsx`
- Modify: `apps/portal/src/oidc.ts`
- Test: `apps/portal/test/App.vitest.tsx`

- [ ] **Step 1: Assert that component cleanup unregisters the exact expiration handler**

Add this test to `App.vitest.tsx`:

```tsx
it('unregisters the access-token expiration handler on unmount', async () => {
  const session = authSession(user);
  const { unmount } = render(<App authSession={session} api={api()} />);
  await screen.findByText('No capabilities are registered for your department.');
  const handler = vi.mocked(session.events.addAccessTokenExpired).mock.calls[0][0];

  unmount();

  expect(session.events.removeAccessTokenExpired).toHaveBeenCalledWith(handler);
});
```

- [ ] **Step 2: Run the focused test and verify the committed baseline fails**

Run:

```powershell
Set-Location apps/portal
node node_modules/vitest/vitest.mjs run test/App.vitest.tsx
```

Expected: FAIL because the committed `AuthSession` contract treats `addAccessTokenExpired` as a cleanup-function factory.

- [ ] **Step 3: Apply the real oidc-client-ts event contract**

Use this contract in `App.tsx`:

```tsx
events: {
  addAccessTokenExpired(callback: () => void): void;
  removeAccessTokenExpired(callback: () => void): void;
};
```

Register and unregister the same function:

```tsx
const onExpired = () => {
  setUser(null);
  setCatalogState('signed-out');
};
authSession.events.addAccessTokenExpired(onExpired);
return () => {
  active = false;
  authSession.events.removeAccessTokenExpired(onExpired);
};
```

Expose both methods from `OidcSession`:

```ts
events: Pick<UserManager['events'], 'addAccessTokenExpired' | 'removeAccessTokenExpired'>;
```

- [ ] **Step 4: Run Portal tests and type checking**

Run:

```powershell
node node_modules/vitest/vitest.mjs run
node node_modules/typescript/bin/tsc --noEmit
```

Expected: all Portal tests pass and TypeScript exits with code 0.

- [ ] **Step 5: Commit only the three Portal files**

```powershell
git add apps/portal/src/App.tsx apps/portal/src/oidc.ts apps/portal/test/App.vitest.tsx
git commit -m "fix: unregister oidc expiration listener"
```

### Task 2: Persist denied state transitions in an independent transaction

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/audit/AuditService.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Extend the revoked-release API test with a denied-audit assertion**

After the rejected `deploying` request, add:

```java
mockMvc.perform(get("/api/v1/audit-events")
                .with(as("READ_ONLY", "auditor@example.internal")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.action == 'RELEASE_TRANSITION_DENIED')]", hasSize(1)))
        .andExpect(jsonPath("$[?(@.decision == 'DENY')]", hasSize(1)));
```

- [ ] **Step 2: Run the API test and verify it fails**

Run:

```powershell
.\mvnw.cmd -B -pl apps/control-plane -Dtest=GovernanceApiTest test
```

Expected: FAIL because an invalid transition currently throws before audit persistence.

- [ ] **Step 3: Add the independent audit writer**

Create `AuditService` with these methods:

```java
@Service
public class AuditService {
    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Actor actor, String action, String subject, String decision,
                       String digest, Instant occurredAt) {
        events.save(new AuditEvent(actor.subject(), actor.department(), action,
                subject, decision, digest, occurredAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeniedTransition(Actor actor, String releaseId, String digest,
                                       Instant occurredAt) {
        record(actor, "RELEASE_TRANSITION_DENIED", releaseId, "DENY", digest, occurredAt);
    }
}
```

- [ ] **Step 4: Route accepted and rejected audit records through `AuditService`**

Inject `AuditService` into `GovernanceService`. Wrap only domain transition failures:

```java
try {
    transition.apply(release, actor.subject(), now());
} catch (InvalidReleaseTransitionException exception) {
    auditService.recordDeniedTransition(actor, releaseId, release.digest(), now());
    throw exception;
}
auditService.record(actor, action, releaseId, decision, release.digest(), now());
```

Replace direct repository writes for accepted mutations with `auditService.record(...)` and retain the repository only for department-scoped reads.

- [ ] **Step 5: Run control-plane tests**

Run:

```powershell
.\mvnw.cmd -B -pl apps/control-plane verify
```

Expected: all control-plane tests pass, including one persisted denied transition.

- [ ] **Step 6: Commit the audit boundary**

```powershell
git add apps/control-plane/src/main/java/com/example/governance/audit/AuditService.java apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java
git commit -m "feat: audit denied release transitions"
```

## Milestone 2: Add Manifest, Trust, And Dependency Locking

### Task 3: Parse and canonicalize `v1alpha1` capability packages

**Files:**
- Modify: `apps/control-plane/pom.xml`
- Create: `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackage.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackageParser.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/manifest/InvalidCapabilityManifestException.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/manifest/CapabilityPackageParserTest.java`

- [ ] **Step 1: Write parser tests for a valid package and stable canonical digest**

The fixture must contain one Agent, `network.defaultDeny: true`, one pinned MCP dependency, and one pinned Skill dependency. Assert:

```java
CapabilityPackage parsed = parser.parse(manifest);

assertThat(parsed.apiVersion()).isEqualTo("governance.platform.example/v1alpha1");
assertThat(parsed.capability("support-agent").dependencies()).hasSize(2);
assertThat(parsed.canonicalDigest()).matches("sha256:[0-9a-f]{64}");
assertThat(parser.parse(reorderedManifest).canonicalDigest())
        .isEqualTo(parsed.canonicalDigest());
```

Also assert unsupported API versions, false `defaultDeny`, inline secret values, and unpinned dependency digests throw `InvalidCapabilityManifestException`.

- [ ] **Step 2: Run the parser test and verify it fails**

```powershell
.\mvnw.cmd -B -pl apps/control-plane -Dtest=CapabilityPackageParserTest test
```

Expected: FAIL because the manifest package does not exist.

- [ ] **Step 3: Add the managed YAML dependency**

Add to `apps/control-plane/pom.xml`:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

- [ ] **Step 4: Define a focused immutable package model**

`CapabilityPackage` exposes these types and accessors:

```java
public record CapabilityPackage(
        String apiVersion,
        String kind,
        Metadata metadata,
        ReleaseDescriptor release,
        List<CapabilityDefinition> capabilities,
        String canonicalDocument,
        String canonicalDigest) {
    public CapabilityDefinition capability(String id) {
        return capabilities.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new InvalidCapabilityManifestException(
                        "Manifest does not define capability: " + id));
    }
}
```

Define nested records for `Metadata`, `ReleaseDescriptor`, `CapabilityDefinition`, `DependencyDefinition`, `NetworkDefinition`, and `SecretDefinition`. `DependencyDefinition` contains `id`, `type`, `version`, `digest`, and `importPath`.

- [ ] **Step 5: Implement deterministic canonicalization and validation**

`CapabilityPackageParser.parse(String)` must:

```java
JsonNode root = yamlMapper.readTree(document);
requireText(root, "/apiVersion", SUPPORTED_API_VERSION);
requireText(root, "/kind", "CapabilityPackage");
validateCapabilities(root.path("spec").path("capabilities"));

ObjectNode digestInput = root.deepCopy();
((ObjectNode) digestInput.path("release")).remove("digest");
Object canonicalValue = jsonMapper.convertValue(digestInput, Object.class);
String canonical = canonicalMapper.writeValueAsString(canonicalValue);
String digest = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
```

Configure `canonicalMapper` with `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`. Convert parsing/validation failures into `InvalidCapabilityManifestException` with stable, non-secret messages.

- [ ] **Step 6: Run parser tests**

```powershell
.\mvnw.cmd -B -pl apps/control-plane -Dtest=CapabilityPackageParserTest test
```

Expected: all parser and canonicalization tests pass.

- [ ] **Step 7: Commit the manifest parser**

```powershell
git add apps/control-plane/pom.xml apps/control-plane/src/main/java/com/example/governance/manifest apps/control-plane/src/test/java/com/example/governance/manifest
git commit -m "feat: validate capability package manifests"
```

### Task 4: Persist dependency locks and trust evidence during release registration

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/ReleaseDependency.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/VerificationEvidence.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/LocalArtifactVerifier.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/DependencyResolver.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerifier.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/TestArtifactVerifier.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/RegistryArtifactVerifier.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/Release.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceController.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/ApiExceptionHandler.java`
- Create: `apps/control-plane/src/main/resources/db/migration/V2__release_package_evidence.sql`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Add a registration test that requires manifest evidence and pinned dependencies**

Register the MCP dependency first, then register an Agent package. Assert the response contains:

```java
.andExpect(jsonPath("$.manifestDigest").value(agentDigest))
.andExpect(jsonPath("$.dependencies[0].capabilityId").value("records-mcp"))
.andExpect(jsonPath("$.dependencies[0].digest").value(mcpDigest))
.andExpect(jsonPath("$.evidence[0].type").value("TEST_ATTESTATION"));
```

Add negative tests for a missing dependency, a mismatched dependency digest, and an Agent-to-MCP-to-Agent cycle.

- [ ] **Step 2: Run the focused API tests and verify they fail**

```powershell
.\mvnw.cmd -B -pl apps/control-plane -Dtest=GovernanceApiTest test
```

Expected: FAIL because registration does not accept or persist a manifest.

- [ ] **Step 3: Add embeddable dependency and evidence records**

Use JPA embeddable classes with protected no-argument constructors. `ReleaseDependency` has this shape:

```java
@Embeddable
public class ReleaseDependency {
    @Column(name = "dependency_capability_id", nullable = false)
    private String capabilityId;
    @Column(name = "dependency_type", nullable = false)
    private String type;
    @Column(name = "dependency_version", nullable = false)
    private String version;
    @Column(name = "dependency_digest", nullable = false)
    private String digest;

    protected ReleaseDependency() {
    }

    public ReleaseDependency(String capabilityId, String type, String version, String digest) {
        this.capabilityId = capabilityId;
        this.type = type;
        this.version = version;
        this.digest = digest;
    }

    public String capabilityId() { return capabilityId; }
    public String type() { return type; }
    public String version() { return version; }
    public String digest() { return digest; }
}
```

`VerificationEvidence` follows the same construction pattern with this complete state and API:

```java
@Embeddable
public class VerificationEvidence {
    @Column(name = "evidence_type", nullable = false)
    private String type;
    @Column(name = "evidence_subject", nullable = false)
    private String subject;
    @Column(name = "evidence_digest", nullable = false)
    private String digest;

    protected VerificationEvidence() {
    }

    public VerificationEvidence(String type, String subject, String digest) {
        this.type = type;
        this.subject = subject;
        this.digest = digest;
    }

    public String type() { return type; }
    public String subject() { return subject; }
    public String digest() { return digest; }
}
```

- [ ] **Step 4: Make artifact verification return evidence**

Change the port to:

```java
public interface ArtifactVerifier {
    VerificationEvidence verify(ArtifactReference artifact);
}
```

`TestArtifactVerifier` returns:

```java
return new VerificationEvidence("TEST_ATTESTATION", artifact.value(), artifact.digest());
```

`RegistryArtifactVerifier` keeps its existing HEAD and digest check, then returns:

```java
return new VerificationEvidence("REGISTRY_DIGEST", artifact.value(), artifact.digest());
```

Change its profile to `@Profile("!test & !local")`. Add `LocalArtifactVerifier` with `@Profile("local")`; require `governance.artifact-verification.allowed-registry`, reject any other registry, perform no network request, and return:

```java
return new VerificationEvidence("LOCAL_TEST_ATTESTATION", artifact.value(), artifact.digest());
```

- [ ] **Step 5: Resolve hosted capability dependencies and reject cycles**

`DependencyResolver.resolve(CapabilityPackage, String capabilityId)` must:

```java
List<ReleaseDependency> locks = definition.dependencies().stream()
        .filter(item -> !"SKILL".equals(item.type()))
        .map(this::requireMatchingRelease)
        .toList();
assertAcyclic(capabilityId, locks, new LinkedHashSet<>());
return locks;
```

For `SKILL`, create a lock directly from its pinned metadata. For Agent/MCP dependencies, load `capabilityId:version`, require the stored digest to match, and recursively traverse its stored locks. Throw `InvalidCapabilityManifestException` for missing, mismatched, or cyclic dependencies.

- [ ] **Step 6: Persist the canonical package and locks**

Add to `Release`:

```java
@Lob
@Column(name = "canonical_manifest", nullable = false, updatable = false)
private String canonicalManifest;

@Column(name = "manifest_digest", nullable = false, updatable = false, length = 71)
private String manifestDigest;

@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "release_dependencies", joinColumns = @JoinColumn(name = "release_id"))
private List<ReleaseDependency> dependencies = new ArrayList<>();

@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "release_evidence", joinColumns = @JoinColumn(name = "release_id"))
private List<VerificationEvidence> evidence = new ArrayList<>();
```

Extend `Release.draft(...)` to require the canonical manifest, manifest digest, locks, and evidence.

- [ ] **Step 7: Extend release registration and API responses**

Change the request to:

```java
record CreateReleaseRequest(
        @NotBlank String version,
        @NotBlank String artifactReference,
        @NotBlank String manifest) {
}
```

In `GovernanceService.createRelease`, parse the package, require metadata namespace/version and the selected capability to match the API route, require the canonical digest to match the artifact digest, resolve dependencies, verify the artifact, and persist all results.

Extend `ReleaseResponse` with `manifestDigest`, `dependencies`, and `evidence`. Map `InvalidCapabilityManifestException` to HTTP 400 and code `INVALID_CAPABILITY_MANIFEST`.

- [ ] **Step 8: Add the Flyway migration**

`V2__release_package_evidence.sql` creates:

```sql
ALTER TABLE releases ADD COLUMN canonical_manifest TEXT;
ALTER TABLE releases ADD COLUMN manifest_digest VARCHAR(71);
UPDATE releases SET canonical_manifest = '{}', manifest_digest = digest;
ALTER TABLE releases ALTER COLUMN canonical_manifest SET NOT NULL;
ALTER TABLE releases ALTER COLUMN manifest_digest SET NOT NULL;

CREATE TABLE release_dependencies (
    release_id VARCHAR(256) NOT NULL REFERENCES releases(id),
    dependency_order INTEGER NOT NULL,
    dependency_capability_id VARCHAR(128) NOT NULL,
    dependency_type VARCHAR(16) NOT NULL,
    dependency_version VARCHAR(64) NOT NULL,
    dependency_digest VARCHAR(71) NOT NULL,
    PRIMARY KEY (release_id, dependency_order)
);

CREATE TABLE release_evidence (
    release_id VARCHAR(256) NOT NULL REFERENCES releases(id),
    evidence_order INTEGER NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    evidence_subject VARCHAR(512) NOT NULL,
    evidence_digest VARCHAR(71) NOT NULL,
    PRIMARY KEY (release_id, evidence_order)
);
```

Match JPA `@OrderColumn` names to `dependency_order` and `evidence_order`.

- [ ] **Step 9: Run all control-plane tests and commit**

```powershell
.\mvnw.cmd -B -pl apps/control-plane verify
git add apps/control-plane
git commit -m "feat: persist manifest locks and trust evidence"
```

Expected: all tests pass and Flyway validates both migrations.

## Milestone 3: Add Deployment Intent And Local Runtime Observation

### Task 5: Replace user-asserted deployment states with a deployment intent service

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentStatus.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentIntent.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentIntentRepository.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/GitOpsReconciler.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/RuntimeObserver.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/LocalGitOpsReconciler.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/LocalRuntimeObserver.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/UnavailableGitOpsReconciler.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/UnavailableRuntimeObserver.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/deployment/DeploymentService.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceController.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Create: `apps/control-plane/src/main/resources/db/migration/V3__deployment_intents.sql`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Write the API golden-path deployment test**

After publishing a valid release:

```java
mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/deployments")
                .with(as("OPERATOR", "operator@example.internal")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.releaseId").value("support-agent:1.0.0"))
        .andExpect(jsonPath("$.digest").value(digest))
        .andExpect(jsonPath("$.status").value("READY"));

mockMvc.perform(get("/api/v1/releases/support-agent:1.0.0/deployment")
                .with(as("READ_ONLY", "reader@example.internal")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observedDigest").value(digest));
```

Assert deployment of `DRAFT`, unapproved, and revoked releases returns conflict.

- [ ] **Step 2: Run the focused test and verify it fails**

```powershell
.\mvnw.cmd -B -pl apps/control-plane -Dtest=GovernanceApiTest test
```

Expected: FAIL because deployment intent endpoints do not exist.

- [ ] **Step 3: Define the deployment aggregate and ports**

Use statuses `PENDING`, `RECONCILING`, `READY`, `FAILED`, and `DRIFTED`. `DeploymentIntent` stores `id`, `releaseId`, `department`, desired digest, observed digest, status, requested actor/time, and observed time.

Define ports:

```java
public interface GitOpsReconciler {
    void reconcile(DeploymentIntent intent);
}

public interface RuntimeObserver {
    RuntimeObservation observe(DeploymentIntent intent);
}

public record RuntimeObservation(String digest, DeploymentStatus status) {
}
```

- [ ] **Step 4: Implement deterministic local/test adapters**

Both adapters use `@Profile({"local", "test"})`. The reconciler changes the intent to `RECONCILING`. The observer returns:

```java
return new RuntimeObservation(intent.desiredDigest(), DeploymentStatus.READY);
```

No adapter uses Kubernetes or writes to the Git working tree.

Add default-profile fail-closed adapters using `@Profile("!test & !local")`. `UnavailableGitOpsReconciler.reconcile` throws `DeploymentIntegrationUnavailableException("External GitOps reconciliation is not configured")`; `UnavailableRuntimeObserver.observe` throws the same typed exception with `"External runtime observation is not configured"`. Map this exception to HTTP 503 and code `DEPLOYMENT_INTEGRATION_UNAVAILABLE`.

- [ ] **Step 5: Implement the deployment orchestration transaction**

`DeploymentService.deploy(String releaseId)` must:

```java
Release release = governanceService.requirePublishedReleaseForDeployment(releaseId);
DeploymentIntent intent = DeploymentIntent.pending(release, actor, clock.instant());
repository.save(intent);
auditService.record(actor, "DEPLOYMENT_REQUESTED", release.id(), "ALLOW",
        release.digest(), clock.instant());
release.deploying(actor.subject(), clock.instant());
auditService.record(actor, "RELEASE_DEPLOYING", release.id(), "DEPLOYING",
        release.digest(), clock.instant());
reconciler.reconcile(intent);
RuntimeObservation observation = observer.observe(intent);
intent.observe(observation, clock.instant());
if (observation.status() == DeploymentStatus.READY
        && release.digest().equals(observation.digest())) {
    release.deployed(actor.subject(), clock.instant());
    auditService.record(actor, "RELEASE_DEPLOYED", release.id(), "DEPLOYED",
            release.digest(), clock.instant());
} else {
    release.failed(actor.subject(), clock.instant());
    auditService.record(actor, "RELEASE_FAILED", release.id(), "FAILED",
            release.digest(), clock.instant());
}
return intent;
```

- [ ] **Step 6: Replace direct state-reporting endpoints**

Remove the public `deploying`, `deployed`, `degraded`, and `failed` endpoints. Add:

```java
@PostMapping("/releases/{releaseId}/deployments")
@ResponseStatus(HttpStatus.CREATED)
@PreAuthorize("hasRole('OPERATOR')")
DeploymentResponse deploy(@PathVariable String releaseId) {
    return DeploymentResponse.from(deploymentService.deploy(releaseId));
}

@GetMapping("/releases/{releaseId}/deployment")
@PreAuthorize("hasAnyRole('OWNER','REVIEWER','APPROVER','OPERATOR','READ_ONLY')")
DeploymentResponse deployment(@PathVariable String releaseId) {
    return DeploymentResponse.from(deploymentService.deployment(releaseId));
}
```

- [ ] **Step 7: Add the deployment migration**

Create `deployment_intents` with a unique `release_id`, department, desired/observed digests, status, actor, requested time, observed time, and optimistic-lock version. Index department and status.

- [ ] **Step 8: Run control-plane verification and commit**

```powershell
.\mvnw.cmd -B -pl apps/control-plane verify
git add apps/control-plane
git commit -m "feat: reconcile local deployment intents"
```

Expected: API golden path ends in `READY`/`DEPLOYED`; unauthorized and invalid deployment tests pass.

## Milestone 4: Expose The Closed Loop In The Portal

### Task 6: Add role-aware release workflow, evidence, deployment, and audit views

**Files:**
- Modify: `apps/portal/src/governance-api.ts`
- Create: `apps/portal/src/release-actions.ts`
- Create: `apps/portal/src/ReleaseWorkflow.tsx`
- Create: `apps/portal/src/ReleaseEvidence.tsx`
- Create: `apps/portal/src/AuditPanel.tsx`
- Modify: `apps/portal/src/App.tsx`
- Modify: `apps/portal/src/styles.css`
- Modify: `apps/portal/test/governance-api.vitest.ts`
- Modify: `apps/portal/test/App.vitest.tsx`
- Create: `apps/portal/test/release-actions.vitest.ts`

- [ ] **Step 1: Write role-to-action unit tests**

Assert:

```ts
expect(actionsFor(['REVIEWER'], 'DRAFT')).toEqual(['validate']);
expect(actionsFor(['REVIEWER'], 'VALIDATING')).toEqual(['review-required', 'reject']);
expect(actionsFor(['APPROVER'], 'REVIEW_REQUIRED')).toEqual(['approve']);
expect(actionsFor(['OPERATOR'], 'APPROVED')).toEqual(['publish']);
expect(actionsFor(['OPERATOR'], 'PUBLISHED')).toEqual(['deploy', 'revoke']);
expect(actionsFor(['READ_ONLY'], 'PUBLISHED')).toEqual([]);
```

- [ ] **Step 2: Write API-client mutation and Bearer-header tests**

For `transitionRelease('token', releaseId, 'approve')`, assert fetch receives:

```ts
expect(fetchMock).toHaveBeenCalledWith(
  `${baseUrl}/releases/${encodeURIComponent(releaseId)}/approve`,
  expect.objectContaining({
    method: 'POST',
    headers: expect.objectContaining({ Authorization: 'Bearer token' })
  })
);
```

Add equivalent coverage for deployment creation, deployment read, and audit-event read.

- [ ] **Step 3: Run the new Portal tests and verify they fail**

```powershell
Set-Location apps/portal
node node_modules/vitest/vitest.mjs run test/release-actions.vitest.ts test/governance-api.vitest.ts
```

Expected: FAIL because the actions and mutation client do not exist.

- [ ] **Step 4: Extend the typed API client**

Add `ReleaseDependency`, `VerificationEvidence`, `Deployment`, and `AuditEvent` types. Add methods:

```ts
transitionRelease(token, releaseId, action): Promise<Release>;
deployRelease(token, releaseId): Promise<Deployment>;
getDeployment(token, releaseId): Promise<Deployment | null>;
listAuditEvents(token): Promise<AuditEvent[]>;
```

Change `request` to merge caller options while always setting `Accept` and `Authorization`. Treat deployment `404` as `null` only inside `getDeployment`; preserve typed errors everywhere else.

- [ ] **Step 5: Implement pure role and state action selection**

`release-actions.ts` exports:

```ts
export type ReleaseAction = 'validate' | 'review-required' | 'approve' | 'publish' | 'deploy' | 'reject' | 'revoke';

export function actionsFor(roles: string[], state: string): ReleaseAction[] {
  const set = new Set(roles.map((role) => role.toUpperCase().replaceAll('-', '_')));
  const actions: ReleaseAction[] = [];
  if (set.has('REVIEWER') && state === 'DRAFT') actions.push('validate');
  if (set.has('REVIEWER') && state === 'VALIDATING') actions.push('review-required', 'reject');
  if (set.has('APPROVER') && state === 'REVIEW_REQUIRED') actions.push('approve');
  if (set.has('OPERATOR') && state === 'APPROVED') actions.push('publish');
  if (set.has('OPERATOR') && state === 'PUBLISHED') actions.push('deploy', 'revoke');
  return actions;
}
```

- [ ] **Step 6: Implement focused workflow components**

`ReleaseWorkflow` receives `release`, `roles`, `busyAction`, and `onAction`; it renders only `actionsFor(...)` buttons and an `aria-live` mutation status. `ReleaseEvidence` renders full digest, dependencies, evidence type/subject, and deployment desired/observed digest. `AuditPanel` renders actor, action, decision, subject, and timestamp in a table.

Use text buttons because these are named governance commands, not generic toolbar symbols. Keep all full digests visible in details and allow wrapping through CSS rather than truncating approval context.

- [ ] **Step 7: Wire roles and refresh behavior into `App`**

Read roles from either `profile.realm_access.roles` or `profile.roles`:

```ts
function userRoles(profile: PortalUser['profile']): string[] {
  const realm = profile.realm_access;
  const values = typeof realm === 'object' && realm !== null && 'roles' in realm
    ? (realm as { roles?: unknown }).roles
    : profile.roles;
  return Array.isArray(values) ? values.filter((role): role is string => typeof role === 'string') : [];
}
```

After a successful transition, replace the selected release in state. After deployment, update both the release to the returned `DEPLOYED` state and the deployment panel. Reload audit events after each successful mutation. Render conflict and forbidden errors without exposing response bodies or tokens.

- [ ] **Step 8: Add component tests for hidden and successful actions**

Test that `READ_ONLY` sees no Approve button, `APPROVER` sees Approve for `REVIEW_REQUIRED`, and clicking it calls `transitionRelease('access-token', id, 'approve')` then renders the updated `APPROVED` state.

- [ ] **Step 9: Run Portal tests, type checking, and build**

```powershell
node node_modules/vitest/vitest.mjs run
node node_modules/typescript/bin/tsc --noEmit
$env:ESBUILD_BINARY_PATH=(Resolve-Path 'node_modules/@esbuild/win32-x64/esbuild.exe').Path
node node_modules/vite/bin/vite.js build
```

Expected: all tests pass, TypeScript exits 0, and Vite creates `dist`.

- [ ] **Step 10: Commit the Portal workflow**

```powershell
git add apps/portal/src apps/portal/test
git commit -m "feat: add role-aware governance workflow"
```

## Milestone 5: Make The Loop Reproducible And Enforced

### Task 7: Align local ports and profiles

**Files:**
- Modify: `compose.yaml`
- Modify: `.env.example`
- Create: `apps/control-plane/src/main/resources/application-local.yml`
- Modify: `scripts/start-local.ps1`
- Modify: `README.md`
- Test: `scripts/test-local-identity.ps1`

- [ ] **Step 1: Add contract assertions for a configurable PostgreSQL host port and local profile**

Extend `test-local-identity.ps1`:

```powershell
$compose = Get-Content (Join-Path $repositoryRoot 'compose.yaml') -Raw
if ($compose -notmatch 'POSTGRES_HOST_PORT:-5432') {
    throw 'Compose must expose a configurable PostgreSQL host port.'
}
$startLocal = Get-Content (Join-Path $repositoryRoot 'scripts/start-local.ps1') -Raw
if ($startLocal -notmatch 'SPRING_PROFILES_ACTIVE.*local') {
    throw 'Local startup must select the local Spring profile.'
}
```

- [ ] **Step 2: Run the helper contract and verify it fails**

```powershell
.\scripts\test-local-identity.ps1
```

Expected: FAIL because the Compose port is fixed and local profile is not selected.

- [ ] **Step 3: Parameterize the Compose port and example environment**

Change the mapping to:

```yaml
ports:
  - "127.0.0.1:${POSTGRES_HOST_PORT:-5432}:5432"
```

Add:

```dotenv
POSTGRES_HOST_PORT=5432
SPRING_PROFILES_ACTIVE=local
```

Document that a workstation with PostgreSQL on `5432` must set both `POSTGRES_HOST_PORT=15432` and `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/governance`.

- [ ] **Step 4: Add fail-fast port consistency to `start-local.ps1`**

Parse the JDBC URI and require its port to equal `POSTGRES_HOST_PORT`:

```powershell
$jdbcUri = [Uri]($env:SPRING_DATASOURCE_URL -replace '^jdbc:', '')
if ($jdbcUri.Port -ne [int]$env:POSTGRES_HOST_PORT) {
    throw 'SPRING_DATASOURCE_URL port must match POSTGRES_HOST_PORT.'
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_PROFILES_ACTIVE)) {
    $env:SPRING_PROFILES_ACTIVE = 'local'
}
```

- [ ] **Step 5: Add local adapter configuration**

`application-local.yml` sets:

```yaml
governance:
  artifact-verification:
    mode: local
  deployment:
    mode: local
```

Select local components with `@Profile("local")`; select production adapters with `@Profile("!test & !local")`.

- [ ] **Step 6: Run local contracts and Compose rendering**

```powershell
.\scripts\test-local-identity.ps1
$env:POSTGRES_ADMIN_USERNAME='verify-admin'
$env:POSTGRES_ADMIN_PASSWORD='verify-admin-password'
$env:GOVERNANCE_DB_USERNAME='verify-governance'
$env:GOVERNANCE_DB_PASSWORD='verify-governance-password'
$env:KEYCLOAK_DB_USERNAME='verify-keycloak'
$env:KEYCLOAK_DB_PASSWORD='verify-keycloak-password'
$env:KEYCLOAK_ADMIN_USERNAME='verify-admin'
$env:KEYCLOAK_ADMIN_PASSWORD='verify-keycloak-password'
$env:POSTGRES_HOST_PORT='15432'
docker compose config | Out-Null
```

Expected: helper and Compose rendering pass without printing secrets.

- [ ] **Step 7: Commit local configuration**

```powershell
git add compose.yaml .env.example apps/control-plane/src/main/resources/application-local.yml scripts/start-local.ps1 scripts/test-local-identity.ps1 README.md
git commit -m "chore: make local poc ports reproducible"
```

### Task 8: Enforce full Portal build, secret scanning, and the golden path

**Files:**
- Create: `scripts/test-no-secrets.mjs`
- Modify: `scripts/verify.ps1`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`
- Modify: `docs/implementation-plan.md`
- Test: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Add one complete API golden-path test**

The test registers MCP and Agent manifests, verifies locked dependency/evidence, moves the Agent through review and approval, publishes, creates deployment, observes `READY`, reads `DEPLOYED`, and checks audit actions in order. It must also change one dependency digest and assert registration fails until the artifact/manifest digest is recomputed and approved as a new release.

Use explicit assertions for `RELEASE_REGISTERED`, `RELEASE_APPROVED`, `DEPLOYMENT_REQUESTED`, and `RELEASE_DEPLOYED` rather than only asserting the audit count.

- [ ] **Step 2: Implement the tracked-file secret scanner**

`test-no-secrets.mjs` obtains tracked files with `git ls-files -z`, skips binary data and documented `.example`/synthetic fixtures, and rejects these patterns:

```js
const forbidden = [
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /\bAKIA[0-9A-Z]{16}\b/,
  /\bgh[opusr]_[A-Za-z0-9_]{30,}\b/,
  /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/
];
```

Print only file names and matched rule labels, never matched secret text. Exit 1 on findings and print `Tracked-file secret scan passed.` otherwise.

- [ ] **Step 3: Make root verification install locked dependencies without lifecycle scripts**

In `verify.ps1`, before Portal tests:

```powershell
if (-not (Test-Path -LiteralPath (Join-Path $portal 'node_modules/vitest/vitest.mjs'))) {
    Push-Location $portal
    try {
        npm ci --ignore-scripts
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}
```

Then run:

```powershell
node node_modules/vitest/vitest.mjs run
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vite/bin/vite.js build
node ../../scripts/test-no-secrets.mjs
```

Retain the dependency-free Keycloak realm test as an additional contract.

- [ ] **Step 4: Strengthen CI**

The Portal step becomes:

```yaml
- name: Verify portal
  working-directory: apps/portal
  run: |
    npm ci
    npm test
    npm run build
- name: Scan tracked files for secrets
  run: node scripts/test-no-secrets.mjs
```

Keep Maven, Kustomize, Compose, and realm JSON validation.

- [ ] **Step 5: Update project status documentation**

README must state that the local profile demonstrates manifest validation, local test attestation, deployment intent, and runtime observation. It must separately list Harbor/Cosign, external GitOps, K3s, Vault, Model Gateway, and production observability as unmet production adapters.

In `docs/implementation-plan.md`, add a dated progress section that marks only demonstrated local acceptance items. Do not mark RUN, INF, or VER external-environment criteria complete.

- [ ] **Step 6: Run the full verification suite**

```powershell
.\scripts\verify.ps1
git diff --check
git status --short
```

Expected: Java tests, Portal tests/type/build, identity contracts, secret scan, Kustomize, Compose, and realm validation pass. `git status` lists only intentional plan implementation files before commit.

- [ ] **Step 7: Commit the delivery gates**

```powershell
git add scripts/test-no-secrets.mjs scripts/verify.ps1 .github/workflows/ci.yml README.md docs/implementation-plan.md apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java
git commit -m "test: verify local governance golden path"
```

## Final Review Gate

- [ ] Run `git log --oneline` and confirm each milestone has an isolated commit.
- [ ] Run `git diff --check HEAD~8..HEAD` and inspect the complete implementation diff.
- [ ] Run `./scripts/verify.ps1` from a fresh shell.
- [ ] Start the local profile with non-placeholder `.env` values and run `scripts/smoke-local-auth.ps1`.
- [ ] Exercise Portal sign-in and one role-appropriate mutation in the browser at desktop and mobile viewports.
- [ ] Confirm documentation never labels local attestations or local reconciliation as production security or production GitOps evidence.
