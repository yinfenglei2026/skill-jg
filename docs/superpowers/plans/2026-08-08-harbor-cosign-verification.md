# Harbor and Cosign Artifact Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace anonymous registry probing with a fail-closed production verifier that authenticates to Harbor, verifies a fixed-key Cosign signature, and enforces digest-, builder-, repository-, and revision-bound SLSA provenance.

**Architecture:** Carry immutable source identity from `capability.yaml` into an `ArtifactVerificationRequest`. A Java Harbor client performs the authenticated OCI digest check, a bounded process adapter invokes pinned Cosign `v3.0.6`, and a Java policy parser validates the returned SLSA v1 statement before the release transaction persists three evidence records atomically. Local and test profiles retain explicitly non-production evidence.

**Tech Stack:** Java 17, Spring Boot 3.2, Java `HttpClient`, Jackson, Cosign 3.0.6, OCI Distribution authentication, SLSA provenance v1, JUnit 5, AssertJ, Mockito, PowerShell 7.

---

## File Map

**Manifest and verification contract**

- Modify `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackage.java` to retain source repository/revision.
- Modify `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackageParser.java` to require and validate immutable source identity.
- Create `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerificationRequest.java` as the verifier input.
- Modify `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerifier.java`, `LocalArtifactVerifier.java`, `TestArtifactVerifier.java`, and `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java` for list-valued evidence.

**Production configuration and failure model**

- Create `ArtifactVerificationFailure.java`, `ArtifactVerificationSettings.java`, and `ProductionArtifactVerificationConfiguration.java` under `apps/control-plane/src/main/java/com/example/governance/release/`.
- Modify `ArtifactVerificationException.java`, `ApiExceptionHandler.java`, and `apps/control-plane/src/main/resources/application.yml`.

**Harbor integration**

- Create `RegistryHttpRequest.java`, `RegistryHttpResponse.java`, `RegistryHttpTransport.java`, `JdkRegistryHttpTransport.java`, `RegistryHttpClientFactory.java`, `HarborBearerChallenge.java`, and `HarborRegistryClient.java` under the release package.

**Cosign and provenance integration**

- Create `CommandResult.java`, `CommandRunner.java`, `ProcessCommandRunner.java`, `DockerAuthWorkspace.java`, `DockerAuthWorkspaceFactory.java`, `CosignVerification.java`, `CosignClient.java`, `SlsaProvenanceSummary.java`, `SlsaProvenancePolicy.java`, and `HarborCosignArtifactVerifier.java` under the release package.

**Tests and fixtures**

- Modify `CapabilityPackageParserTest.java`, `LocalArtifactVerifierTest.java`, `RegistryArtifactVerifierTest.java`, and `GovernanceApiTest.java`.
- Delete `RegistryArtifactVerifierTest.java` after equivalent production configuration tests exist.
- Create focused tests matching each new production class and JSON/PEM fixtures under `apps/control-plane/src/test/resources/artifact-verification/`.

**Tooling and documentation**

- Create `scripts/install-cosign.ps1` and `scripts/test-cosign-install.ps1`.
- Modify `scripts/verify.ps1`, `.github/workflows/ci.yml`, `README.md`, `docs/capability-schema.md`, `docs/architecture.md`, and `docs/poc-acceptance-report.md`.

### Task 1: Require and retain immutable source identity

**Files:**
- Modify: `apps/control-plane/src/test/java/com/example/governance/manifest/CapabilityPackageParserTest.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackage.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/manifest/CapabilityPackageParser.java`
- Modify: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Write failing parser tests**

Add assertions that the normative manifest yields the exact repository and revision. Add parameterized rejection cases for missing `source`, missing/blank repository, non-HTTPS/relative/credential-bearing repository URI, repository text longer than 384 characters, missing/blank revision, abbreviated revision, uppercase hexadecimal, and non-hex text.

Use these exact accessors in the positive test:

```java
assertThat(capabilityPackage.release().sourceRepository())
        .isEqualTo("https://git.example.internal/support/assistant.git");
assertThat(capabilityPackage.release().sourceRevision())
        .isEqualTo("5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42");
```

- [ ] **Step 2: Run the parser test and observe RED**

Run the root verifier and Maven verification. Run the PostgreSQL upgrade only when `docker info` succeeds:

```powershell
./mvnw -B -pl apps/control-plane -Dtest=CapabilityPackageParserTest test
```

Expected: compilation fails because `ReleaseDescriptor` has no source accessors, then the new invalid-input cases fail after the accessors are added.

- [ ] **Step 3: Implement the source descriptor contract**

Change the release record to:

```java
public record ReleaseDescriptor(
        String digest,
        String artifactUri,
        String sourceRepository,
        String sourceRevision) {
}
```

In `validateRelease`, require `release.source`, restrict it to `repository` and `revision`, parse both with `requireNonBlankText`, require a normalized absolute HTTPS URI with host/path and no user info/query/fragment, and require `(?:[a-f0-9]{40}|[a-f0-9]{64})`. Return all four release values. Reject a future provenance evidence summary longer than 512 characters using the exact form `builder:<builder>|source:<repository>@<revision>` only in settings/policy tests; the parser itself limits repository to 384 characters so the final summary can be bounded after the builder is known.

- [ ] **Step 4: Update API manifest fixtures**

Add this source object to the `release` map produced by `GovernanceApiTest.manifest`:

```java
"source", Map.of(
        "repository", "https://git.example.internal/governance/" + capabilityId + ".git",
        "revision", "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42")
```

- [ ] **Step 5: Run focused and API tests GREEN**

Run:

```powershell
./mvnw -B -pl apps/control-plane -Dtest=CapabilityPackageParserTest,GovernanceApiTest test
```

Expected: both test classes pass with zero failures.

- [ ] **Step 6: Commit**

```powershell
git add apps/control-plane/src/main/java/com/example/governance/manifest apps/control-plane/src/test/java/com/example/governance/manifest apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java
git commit -m "feat: retain immutable release source"
```

### Task 2: Introduce the verification request and atomic evidence list

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerificationRequest.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerifier.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/LocalArtifactVerifier.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/TestArtifactVerifier.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java`
- Modify: `apps/control-plane/src/test/java/com/example/governance/release/LocalArtifactVerifierTest.java`

- [ ] **Step 1: Write RED contract tests**

Change `LocalArtifactVerifierTest` to construct a request and assert a singleton immutable evidence list. Add a test that `ArtifactVerificationRequest` rejects null artifact, blank repository, and blank revision.

- [ ] **Step 2: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=LocalArtifactVerifierTest test`.
Expected: compilation fails because the request record and list-valued verifier contract do not exist.

- [ ] **Step 3: Implement the exact port**

```java
public record ArtifactVerificationRequest(
        ArtifactReference artifact,
        String sourceRepository,
        String sourceRevision) {
    public ArtifactVerificationRequest {
        artifact = Objects.requireNonNull(artifact, "artifact");
        if (sourceRepository == null || sourceRepository.isBlank()) {
            throw new IllegalArgumentException("sourceRepository must not be blank");
        }
        if (sourceRevision == null || sourceRevision.isBlank()) {
            throw new IllegalArgumentException("sourceRevision must not be blank");
        }
    }
}
```

Change the port to `List<VerificationEvidence> verify(ArtifactVerificationRequest request)`. Local and test implementations return `List.of(existingEvidence)`. In `GovernanceService.createRelease`, construct the request from the parsed release descriptor, receive the complete list, and pass it directly to `Release.draft`.

- [ ] **Step 4: Run focused tests GREEN**

Run `./mvnw -B -pl apps/control-plane -Dtest=LocalArtifactVerifierTest,GovernanceApiTest test`.
Expected: zero failures; test/local API output still contains one explicitly non-production evidence record.

- [ ] **Step 5: Commit**

```powershell
git add apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/main/java/com/example/governance/api/GovernanceService.java apps/control-plane/src/test/java/com/example/governance/release/LocalArtifactVerifierTest.java
git commit -m "refactor: pass source identity to artifact verification"
```

### Task 3: Add typed failures and validated production settings

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerificationFailure.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerificationSettings.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/release/ArtifactVerificationException.java`
- Modify: `apps/control-plane/src/main/resources/application.yml`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/ArtifactVerificationSettingsTest.java`
- Modify: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Write settings and exception RED tests**

Cover every required setting, exact registry authority validation, absolute/readable executable/public-key/optional-CA paths, distinct nonempty builder URI values, credential redaction, and the 512-character provenance evidence summary bound. Assert `ArtifactVerificationException` exposes a failure enum while its message contains only the stable public text.

- [ ] **Step 2: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=ArtifactVerificationSettingsTest test`.
Expected: compilation fails because settings/failure types do not exist.

- [ ] **Step 3: Implement failure categories**

Create the enum with exactly:

```java
REGISTRY_NOT_ALLOWED,
REGISTRY_AUTH_FAILED,
REGISTRY_FORBIDDEN,
REGISTRY_UNAVAILABLE,
REGISTRY_PROTOCOL_INVALID,
DIGEST_MISMATCH,
COSIGN_UNAVAILABLE,
VERIFICATION_TIMEOUT,
VERIFIER_OUTPUT_INVALID,
SIGNATURE_INVALID,
PROVENANCE_MISSING,
PROVENANCE_INVALID,
PROVENANCE_POLICY_MISMATCH
```

Give each enum value a short non-secret message. Change the exception constructor to accept the enum and preserve it through `failure()`.

- [ ] **Step 4: Implement validated settings**

Use this immutable shape:

```java
public record ArtifactVerificationSettings(
        String registry,
        String username,
        char[] password,
        Path registryCaCert,
        Path cosignExecutable,
        Path cosignPublicKey,
        Set<URI> allowedBuilderIds,
        Duration registryTimeout,
        Duration cosignTimeout,
        long maxVerifierOutputBytes) {
}
```

Copy the password and builder set defensively. Defaults are registry timeout 10 seconds, Cosign timeout 30 seconds, and output cap 1 MiB. Never include the password in `toString`, equality diagnostics, or exception messages.

- [ ] **Step 5: Bind environment variables**

Extend `application.yml` with `HARBOR_USERNAME`, `HARBOR_PASSWORD`, `HARBOR_CA_CERT`, `COSIGN_EXECUTABLE`, `COSIGN_PUBLIC_KEY`, and `SLSA_ALLOWED_BUILDER_IDS` under `governance.artifact-verification`, retaining `HARBOR_REGISTRY`.

- [ ] **Step 6: Run GREEN and commit**

Run `./mvnw -B -pl apps/control-plane -Dtest=ArtifactVerificationSettingsTest,GovernanceApiTest test`.
Expected: zero failures and no production settings required under profile `test`.

```powershell
git add apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/main/resources/application.yml apps/control-plane/src/test
git commit -m "feat: validate artifact verification settings"
```

### Task 4: Implement Harbor Bearer authentication and digest policy

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/RegistryHttpRequest.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/RegistryHttpResponse.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/RegistryHttpTransport.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/HarborBearerChallenge.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/HarborRegistryClient.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/HarborBearerChallengeTest.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/HarborRegistryClientTest.java`

- [ ] **Step 1: Write challenge parser RED tests**

Test one valid case with realm/service/scope, case-insensitive `Bearer`, escaped quoted values, and arbitrary parameter order. Reject Basic, missing/duplicate realm, non-HTTPS realm, realm on another authority, wrong repository scope, push-only scope, duplicate parameters, unclosed quotes, and CR/LF injection.

- [ ] **Step 2: Write Harbor client RED tests with a scripted fake transport**

Assert the request sequence and headers for: direct `200`, `401` then token then authenticated `200`, `token` and `access_token` response fields, bad credentials, forbidden repository, malformed token JSON, missing/wrong digest header, timeout, interruption, exactly one retry for timeout/`5xx`, no retry for `401`/`403`/digest failures, disabled redirect handling, and no password/token in thrown messages.

- [ ] **Step 3: Run RED**

Run:

```powershell
./mvnw -B -pl apps/control-plane -Dtest=HarborBearerChallengeTest,HarborRegistryClientTest test
```

Expected: compilation fails because Harbor protocol types do not exist.

- [ ] **Step 4: Implement transport records and challenge parser**

Use an immutable request containing method, URI, headers, body, and timeout; use an immutable response containing status, normalized multi-value headers, and a bounded UTF-8 body. The challenge parser must return `realm`, `service`, and exact pull scope and must never retain the raw header after parsing.

- [ ] **Step 5: Implement the Harbor state machine**

The client algorithm is exactly:

```text
HEAD manifest without credentials
  200 -> validate Docker-Content-Digest
  401 -> parse Bearer challenge
       -> GET realm?service=<service>&scope=repository:<repository>:pull with Basic robot credentials
       -> parse token/access_token
       -> HEAD manifest with Bearer token
       -> require 200 and exact Docker-Content-Digest
  403 -> REGISTRY_FORBIDDEN
  timeout/5xx -> retry the same idempotent request once, then REGISTRY_UNAVAILABLE
  everything else -> REGISTRY_PROTOCOL_INVALID
```

Encode query parameters with `URLEncoder` and never forward Basic or Bearer credentials to an authority other than the configured Harbor authority.

- [ ] **Step 6: Run GREEN and commit**

Run the two focused test classes; expect zero failures.

```powershell
git add apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/test/java/com/example/governance/release
git commit -m "feat: authenticate harbor digest reads"
```

### Task 5: Add bounded HTTPS transport and private CA support

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/RegistryHttpClientFactory.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/JdkRegistryHttpTransport.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/RegistryHttpClientFactoryTest.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/JdkRegistryHttpTransportTest.java`
- Create: `apps/control-plane/src/test/resources/artifact-verification/test-root-ca.pem`

- [ ] **Step 1: Write transport RED tests**

Use a loopback `HttpServer` to prove request method/header/body mapping, redirect refusal, response header preservation, timeout/interruption mapping, and a hard 64 KiB token-response limit. Test that the client factory uses system trust when CA is absent and a trust store containing only the configured PEM certificate when present. Reject invalid, empty, or multi-certificate CA files.

- [ ] **Step 2: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=RegistryHttpClientFactoryTest,JdkRegistryHttpTransportTest test`.
Expected: compilation fails because transport implementations do not exist.

- [ ] **Step 3: Implement HTTPS transport**

Build `HttpClient` with `Redirect.NEVER`, the configured connect timeout, and optional TLS 1.2/1.3 `SSLContext` from the single PEM CA. Reject every non-HTTPS URI before sending. Use bounded body handling for token responses and discard manifest `HEAD` bodies.

- [ ] **Step 4: Run GREEN and commit**

Run both focused test classes; expect zero failures.

```powershell
git add apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/test/java/com/example/governance/release apps/control-plane/src/test/resources/artifact-verification/test-root-ca.pem
git commit -m "feat: secure harbor registry transport"
```

### Task 6: Isolate Cosign credentials and process execution

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/CommandResult.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/CommandRunner.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/ProcessCommandRunner.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/DockerAuthWorkspace.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/DockerAuthWorkspaceFactory.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/ProcessCommandRunnerTest.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/DockerAuthWorkspaceTest.java`

- [ ] **Step 1: Write process/workspace RED tests**

Use the Java executable as a deterministic child process. Cover argument preservation without shell parsing, stdout/stderr separation, nonzero exit, 30-second configurable timeout using a short test timeout, interruption with interrupt restoration, 1 MiB combined output limit, minimal child environment, and cleanup of stdout/stderr files. For Docker auth, decode the generated `auth` value and prove it equals `username:password`, verify password absence from paths/toString/exceptions, owner-only permissions where supported, and recursive cleanup on every close path.

- [ ] **Step 2: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=ProcessCommandRunnerTest,DockerAuthWorkspaceTest test`.
Expected: compilation fails because process/workspace types do not exist.

- [ ] **Step 3: Implement the bounded process port**

Use this port:

```java
interface CommandRunner {
    CommandResult run(
            List<String> command,
            Map<String, String> environment,
            Path workspace,
            Duration timeout,
            long maxOutputBytes);
}

record CommandResult(int exitCode, String stdout, String stderr) {
}
```

`ProcessCommandRunner` clears the child environment, adds only `DOCKER_CONFIG` plus the minimum platform variables required to launch an absolute executable, redirects stdout/stderr to workspace files to avoid pipe deadlock, kills the process tree on timeout/output overflow, bounds bytes before reading text, and never includes captured output in thrown exceptions.

- [ ] **Step 4: Implement operation-scoped Docker auth**

Write exactly one `config.json` with `auths.<registry>.auth = base64(username + ":" + password)`. Create the directory and file with owner-only permissions where the filesystem supports POSIX or ACL attributes. `close()` zeroes the in-memory password copy and recursively removes the workspace.

- [ ] **Step 5: Run GREEN and commit**

Run both focused tests; expect zero failures and no leftover temporary directories.

```powershell
git add apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/test/java/com/example/governance/release
git commit -m "feat: isolate cosign process credentials"
```

### Task 7: Verify fixed-key signatures and strict SLSA provenance

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/CosignVerification.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/CosignClient.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/SlsaProvenanceSummary.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/SlsaProvenancePolicy.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/CosignClientTest.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/SlsaProvenancePolicyTest.java`
- Create: `apps/control-plane/src/test/resources/artifact-verification/cosign-signature-valid.json`
- Create: `apps/control-plane/src/test/resources/artifact-verification/cosign-attestation-slsa-v1-valid.json`
- Create: `apps/control-plane/src/test/resources/artifact-verification/cosign-public-key.pem`

- [ ] **Step 1: Write Cosign command RED tests**

With a recording fake runner/workspace, assert exactly two commands, in order:

```text
<absolute-cosign> verify --key <absolute-public-key> --output json [--registry-cacert <absolute-ca>] <digest-reference>
<absolute-cosign> verify-attestation --key <absolute-public-key> --type slsaprovenance1 --output json [--registry-cacert <absolute-ca>] <digest-reference>
```

Assert no shell, username, password, bearer token, HTTP/insecure flags, or transparency-log bypass appears. Map launch failure, timeout, malformed/empty signature JSON, signature exit failure, empty attestation, and attestation exit failure to the exact typed categories. Verify the SHA-256 public-key fingerprint is computed from decoded PEM DER bytes.

- [ ] **Step 2: Write SLSA policy RED tests**

The valid fixture contains one base64-encoded in-toto statement with predicate type `https://slsa.dev/provenance/v1`, matching SHA-256 subject, allowed `runDetails.builder.id`, and one resolved dependency whose URI/revision match the request. Mutate each field independently and cover missing/malformed payload, wrong digest algorithm/value, missing/duplicate/ambiguous statements, wrong predicate, disallowed builder, wrong repository, wrong `gitCommit`, oversized evidence summary, and unknown extra fields. Unknown fields are ignored; every policy-relevant mismatch fails closed.

- [ ] **Step 3: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=CosignClientTest,SlsaProvenancePolicyTest test`.
Expected: compilation fails because Cosign/policy classes do not exist.

- [ ] **Step 4: Implement Cosign verification**

Return:

```java
record CosignVerification(
        String publicKeyFingerprint,
        String signatureJson,
        String attestationJson) {
}
```

Require a nonempty JSON array for signature output and let Cosign's default `--check-claims=true` enforce the image digest. Do not expose stdout/stderr in public errors. Always close the auth workspace in `finally`.

- [ ] **Step 5: Implement provenance parsing and policy**

Decode each `payload`, parse the in-toto statement, and accept exactly one statement satisfying all configured/requested values. Return a summary containing the exact builder URI, repository, and revision. Format persisted subject as `builder:<builder>|source:<repository>@<revision>` and enforce 512 characters before returning.

- [ ] **Step 6: Run GREEN and commit**

Run both focused tests; expect zero failures.

```powershell
git add apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/test/java/com/example/governance/release apps/control-plane/src/test/resources/artifact-verification
git commit -m "feat: enforce cosign slsa provenance"
```

### Task 8: Wire the production verifier and three evidence records

**Files:**
- Create: `apps/control-plane/src/main/java/com/example/governance/release/HarborCosignArtifactVerifier.java`
- Create: `apps/control-plane/src/main/java/com/example/governance/release/ProductionArtifactVerificationConfiguration.java`
- Delete: `apps/control-plane/src/main/java/com/example/governance/release/RegistryArtifactVerifier.java`
- Delete: `apps/control-plane/src/test/java/com/example/governance/release/RegistryArtifactVerifierTest.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/HarborCosignArtifactVerifierTest.java`
- Create: `apps/control-plane/src/test/java/com/example/governance/release/ProductionArtifactVerificationConfigurationTest.java`

- [ ] **Step 1: Write orchestrator RED tests**

Assert fixed call order, immediate stop after each injected failure, no partial return, and exact successful evidence order/content:

```java
assertThat(evidence).extracting(VerificationEvidence::type)
        .containsExactly("REGISTRY_DIGEST", "COSIGN_SIGNATURE", "SLSA_PROVENANCE");
```

The registry subject is the immutable artifact reference, signature subject is `key-sha256:<fingerprint>`, provenance subject is the bounded summary, and every evidence digest is the requested digest.

- [ ] **Step 2: Write Spring configuration RED tests**

Use `ApplicationContextRunner` with profile `prod`. Assert startup failure for every missing/invalid setting, production bean type `HarborCosignArtifactVerifier` when valid, and no production beans under `test` or `local`. Use temporary readable executable/key/CA fixtures; no command or network request runs during context creation.

- [ ] **Step 3: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=HarborCosignArtifactVerifierTest,ProductionArtifactVerificationConfigurationTest test`.
Expected: compilation fails because production orchestrator/configuration do not exist.

- [ ] **Step 4: Implement orchestration and beans**

The verifier checks registry equality before any I/O, then calls Harbor, Cosign, and SLSA policy in order. Create evidence only after the policy returns. Configuration builds settings, TLS client/transport, Harbor client, process runner, workspace factory, Cosign client, policy, and verifier under `@Profile("!test & !local")`.

- [ ] **Step 5: Run GREEN and commit**

Run focused tests plus `LocalArtifactVerifierTest`; expect zero failures.

```powershell
git add -A apps/control-plane/src/main/java/com/example/governance/release apps/control-plane/src/test/java/com/example/governance/release
git commit -m "feat: compose harbor cosign verification"
```

### Task 9: Prove atomic API persistence and sanitized failures

**Files:**
- Create: `apps/control-plane/src/test/java/com/example/governance/api/ProductionEvidencePersistenceTest.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/api/ApiExceptionHandler.java`
- Modify: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`

- [ ] **Step 1: Write API RED tests with a mocked verifier**

Create a separate `@SpringBootTest`/`@AutoConfigureMockMvc` class using profile `test` and `@MockBean ArtifactVerifier`. One test returns the three production-shaped evidence records and asserts the API persists/serializes all three in order. Parameterize every failure enum, make the verifier throw it, assert HTTP `422`, code `UNVERIFIED_ARTIFACT`, stable sanitized message, no secret fixture text, and an empty release list afterward.

- [ ] **Step 2: Run RED**

Run `./mvnw -B -pl apps/control-plane -Dtest=ProductionEvidencePersistenceTest test`.
Expected: failures expose old untyped messages or list wiring gaps.

- [ ] **Step 3: Complete API mapping and transaction behavior**

Return the enum's public message from `ApiExceptionHandler`; never serialize causes or external response/process output. Keep verification before `Release.draft` and `releases.save`, so any exception exits the transaction without release/evidence/audit persistence.

- [ ] **Step 4: Run GREEN and commit**

Run `ProductionEvidencePersistenceTest,GovernanceApiTest`; expect zero failures.

```powershell
git add apps/control-plane/src/main/java/com/example/governance/api apps/control-plane/src/test/java/com/example/governance/api
git commit -m "test: prove atomic verification evidence"
```

### Task 10: Pin Cosign tooling, update CI contracts and document the boundary

**Files:**
- Create: `scripts/install-cosign.ps1`
- Create: `scripts/test-cosign-install.ps1`
- Modify: `scripts/verify.ps1`
- Modify: `scripts/test-ci-supply-chain.ps1`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`
- Modify: `docs/capability-schema.md`
- Modify: `docs/architecture.md`
- Modify: `docs/poc-acceptance-report.md`

- [ ] **Step 1: Write RED PowerShell contracts**

Assert the installer pins `3.0.6`, uses only official GitHub release URLs, supports Linux/Windows amd64, verifies SHA-256 before moving the executable, rejects unsupported platforms, and cleans failed downloads. Pin these published hashes:

```text
cosign-linux-amd64      c956e5dfcac53d52bcf058360d579472f0c1d2d9b69f55209e256fe7783f4c74
cosign-windows-amd64.exe 9b85a88ebff2d9dd30ff4984a6f61f2cedc232dd87d81fa7f2ff3c0ed96c241c
```

Extend the CI contract to require the installer and `cosign version` but reject `latest`, `--allow-http-registry`, `--allow-insecure-registry`, `--insecure-ignore-tlog`, `--registry-password`, and `--registry-token` in workflow/application commands.

- [ ] **Step 2: Run RED contracts**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-cosign-install.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-ci-supply-chain.ps1
```

Expected: both fail because the installer and workflow step do not exist.

- [ ] **Step 3: Implement and verify the installer**

`install-cosign.ps1` accepts only `-InstallDirectory`, derives the supported asset from OS/architecture, downloads to a new temporary directory, compares lowercase `Get-FileHash -Algorithm SHA256` to the fixed table, sets executable permission on Linux, atomically moves the binary, runs `<binary> version`, and removes temporary files in `finally`.

Run the installer locally to a temporary directory and execute `cosign version`. Expected: version output contains `v3.0.6`. Do not add the downloaded binary to Git.

- [ ] **Step 4: Add CI/tooling contracts**

Call `test-cosign-install.ps1` from `scripts/verify.ps1`. In CI, install Cosign to `$RUNNER_TEMP/cosign`, run its version command, and export `COSIGN_EXECUTABLE`; do not attempt registry verification without secrets or a signed fixture.

- [ ] **Step 5: Update documentation**

Document required source fields, all production environment variables, robot read-only permissions, fixed-key trust, three evidence types, failure categories, custom CA behavior, and absence of insecure flags. In `docs/poc-acceptance-report.md`, record offline adapter tests while keeping live Harbor/Cosign acceptance `PARTIAL`; change ordered next work to live Harbor negative/positive interoperability followed by GitOps/K3s.

- [ ] **Step 6: Run focused contracts GREEN**

Run both PowerShell contract scripts. Expected: each prints its success message and exits `0`.

- [ ] **Step 7: Run complete verification**

Run:

```powershell
./scripts/verify.ps1
./mvnw -B -pl apps/control-plane verify
git diff --check
git status --short
```

Then run:

```powershell
docker info *> $null
if ($LASTEXITCODE -eq 0) {
    ./scripts/test-postgres-upgrade.ps1
} else {
    Write-Warning 'PostgreSQL upgrade verification skipped because Docker Engine is unavailable.'
}
```

Expected: build metadata/CI/Cosign contracts pass; Maven has zero failures with only the conditional PostgreSQL skip when Docker is unavailable; Portal has 25 passing tests; runtime policy checks pass; PostgreSQL upgrade passes when Docker is available or is explicitly reported skipped; `git diff --check` is clean; status lists only intended source/test/docs changes before commit.

- [ ] **Step 8: Commit and request review**

```powershell
git add scripts .github/workflows/ci.yml README.md docs apps/control-plane
git commit -m "docs: record offline harbor cosign verification"
```

Run `git show --check --stat HEAD`, request code review, and preserve the branch/worktree without merge or push. Do not upgrade live Harbor/Cosign acceptance to PASS until the real interoperability matrix in the design document is executed.
