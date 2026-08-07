# Harbor and Cosign Artifact Verification Design

## Context

The production `RegistryArtifactVerifier` currently proves only that an anonymous OCI `HEAD` request returns the requested digest. The local profile deliberately emits `LOCAL_TEST_ATTESTATION` and does not claim production trust. The next production slice must authenticate to Harbor, verify a Cosign signature with a fixed public key, and require SLSA provenance that binds the artifact to the source declared in `capability.yaml`.

No live Harbor instance, robot account, signed image, or Cosign installation is available in the current environment. This change therefore delivers a production adapter and a fully offline-tested policy contract. Live Harbor/Cosign interoperability remains a separate acceptance gate.

## Goals

- Authenticate immutable OCI reads with a least-privilege Harbor robot account.
- Verify the exact digest returned by Harbor.
- Verify an artifact signature with a configured Cosign public key.
- Require SLSA provenance and strictly bind it to the artifact digest, allowed builder, source repository, and source revision.
- Persist registry, signature, and provenance evidence only after every check succeeds.
- Fail closed without exposing credentials, tokens, raw verifier output, or partial evidence.
- Preserve the existing `local` and `test` profile behavior.

## Non-Goals

- Provisioning Harbor or creating robot accounts.
- Building, pushing, signing, or attesting container images.
- Keyless Fulcio/Rekor verification or OIDC certificate identity policy.
- Harbor vulnerability scanning, replication, retention, or admission controller integration.
- Claiming live production interoperability without a real Harbor endpoint and signed artifact.

## Selected Approach

The control plane uses a Java Harbor client plus a Cosign CLI adapter. Java owns authentication flow, digest checks, orchestration, JSON parsing, policy decisions, error classification, and evidence persistence. Cosign owns signature and DSSE attestation cryptography.

This boundary avoids implementing signature verification in application code while retaining deterministic, typed policy behavior. Cosign is pinned to `v3.0.6`; the repository bootstrap script must download only the official platform asset and verify its published SHA-256 checksum before installation.

Pure CLI orchestration was rejected because it obscures Harbor authentication and typed policy failures. A pure Java Sigstore implementation was rejected because it adds unnecessary cryptographic and compatibility risk to the PoC.

## Domain Contract

`CapabilityPackage.ReleaseDescriptor` retains the source values that the parser already validates:

- `release.source.repository` is a nonblank absolute HTTPS Git repository URI.
- `release.source.revision` is a full lowercase Git object identifier of 40 or 64 hexadecimal characters.

Both fields become required for a release candidate. Exact strings are carried into a new `ArtifactVerificationRequest` with the immutable `ArtifactReference`. The verifier interface accepts that request and returns an immutable list of `VerificationEvidence` values.

The `local` and `test` verifiers return their existing single evidence item. The production verifier returns exactly three items after all checks pass:

| Type | Subject | Digest |
| --- | --- | --- |
| `REGISTRY_DIGEST` | Immutable OCI artifact reference | Artifact digest |
| `COSIGN_SIGNATURE` | SHA-256 fingerprint of the configured public key | Artifact digest |
| `SLSA_PROVENANCE` | Canonical builder and source summary | Artifact digest |

The provenance subject is a bounded canonical string composed from the exact builder ID, repository URI, and revision. Configuration and manifest validation reject values that cannot fit the existing 512-character evidence subject limit. No database migration is required.

## Components

### `HarborCosignArtifactVerifier`

The production-profile orchestrator performs the checks in a fixed order: registry allowlist, Harbor digest, Cosign signature, and SLSA provenance. It assembles evidence in memory and returns it only after the final policy check succeeds.

### `HarborRegistryClient`

The client performs an HTTPS `HEAD` against `/v2/<repository>/manifests/<digest>` with the existing OCI manifest media-type accept list. On a valid `401` challenge it:

1. Parses a single Bearer challenge.
2. Requires an HTTPS token realm on the configured Harbor authority.
3. Requests a token with robot account Basic authentication and `repository:<repository>:pull` scope.
4. Retries the original `HEAD` with the bearer token.
5. Requires HTTP `200` and an exact `Docker-Content-Digest` match.

Credentials are never sent to the manifest endpoint and are never forwarded across hosts. Redirects are disabled. The client retries once only for connection timeout or HTTP `5xx`; it does not retry authentication, authorization, digest, or protocol failures.

### `CosignClient`

The adapter launches an absolute Cosign executable with `ProcessBuilder`, never through a shell. It runs two commands against the immutable digest reference:

1. `cosign verify --key <public-key> --output json <artifact>`
2. `cosign verify-attestation --key <public-key> --type slsaprovenance1 --output json <artifact>`

When a private registry CA is configured, the adapter also supplies `--registry-cacert`. It never supplies `--allow-http-registry`, `--allow-insecure-registry`, or an insecure transparency-log bypass.

The robot credential is written to an operation-scoped Docker `config.json` in a restrictive temporary directory and supplied through `DOCKER_CONFIG`. The secret is not placed in process arguments. The adapter clears unrelated application secrets from the child environment, enforces a command timeout and output-size limit, captures stdout and stderr separately, and deletes the temporary directory in `finally`.

### `SlsaProvenancePolicy`

The policy parses Cosign JSON with Jackson and rejects malformed, missing, duplicate, or ambiguous statements. It requires exactly one acceptable in-toto statement with:

- predicate type `https://slsa.dev/provenance/v1`;
- a subject digest whose SHA-256 value equals the requested artifact digest;
- `runDetails.builder.id` exactly equal to one configured builder ID;
- one `buildDefinition.resolvedDependencies` entry whose URI exactly equals `release.source.repository`;
- that dependency's `gitCommit` digest exactly equal to `release.source.revision`.

Cosign's successful exit status is necessary but not sufficient. Java rechecks every policy-relevant claim before evidence is created.

## Configuration

The production profile requires:

| Environment variable | Purpose |
| --- | --- |
| `HARBOR_REGISTRY` | Exact allowed registry authority |
| `HARBOR_USERNAME` | Read-only robot account name |
| `HARBOR_PASSWORD` | Robot account secret |
| `COSIGN_EXECUTABLE` | Absolute path to pinned Cosign `v3.0.6` |
| `COSIGN_PUBLIC_KEY` | Absolute path to the read-only public key |
| `SLSA_ALLOWED_BUILDER_IDS` | Nonempty comma-separated exact builder URI allowlist |
| `HARBOR_CA_CERT` | Optional absolute path to an enterprise CA PEM file |

Startup fails before serving traffic when a required value is blank, a path is not absolute/readable, a builder ID is invalid or duplicated, or the registry authority contains a scheme, path, user information, query, or fragment. Production never falls back to anonymous access or the local verifier.

## Security Boundaries

- HTTPS is mandatory for manifest and token requests.
- Robot permissions are limited to repository pull/read operations.
- The public key is the trust root; the private signing key never enters the control plane.
- Harbor credentials are held only in configuration memory and the restrictive operation-scoped Docker auth file.
- Passwords, bearer tokens, Docker auth JSON, and raw Cosign output are excluded from logs and API errors.
- The child process receives a minimal environment and fixed argument structure.
- Command output is bounded to prevent memory exhaustion.
- Temporary credentials are deleted on success, verifier failure, timeout, interruption, and parse failure.
- Evidence is assembled atomically and persisted only with the release transaction after all checks pass.

## Failure Semantics

The external API retains HTTP `422` with code `UNVERIFIED_ARTIFACT`. Internally, `ArtifactVerificationException` carries a stable non-secret reason:

- `REGISTRY_NOT_ALLOWED`
- `REGISTRY_AUTH_FAILED`
- `REGISTRY_FORBIDDEN`
- `REGISTRY_UNAVAILABLE`
- `REGISTRY_PROTOCOL_INVALID`
- `DIGEST_MISMATCH`
- `COSIGN_UNAVAILABLE`
- `VERIFICATION_TIMEOUT`
- `VERIFIER_OUTPUT_INVALID`
- `SIGNATURE_INVALID`
- `PROVENANCE_MISSING`
- `PROVENANCE_INVALID`
- `PROVENANCE_POLICY_MISMATCH`

API messages identify the failed category without including remote response bodies, command lines containing secrets, or verifier payloads. Authentication, digest, signature, and policy failures are not retried. Interrupted threads restore their interrupt flag.

## Testing Strategy

### Manifest and Domain Tests

- Retain repository and revision in `ReleaseDescriptor`.
- Reject missing, blank, non-HTTPS, relative, credential-bearing, or malformed repository values.
- Reject abbreviated, uppercase, or non-hex revisions.
- Verify production requests include the exact parsed source values.
- Preserve local and test evidence behavior through the new request/list contract.

### Harbor Client Tests

Use a fake transport or loopback HTTP server with no external network dependency. Cover anonymous `200`, valid `401` Bearer challenge and token exchange, invalid or multiple challenges, wrong token realm, failed authentication, forbidden repository, exact digest match, missing/wrong digest header, disabled redirects, timeout, `5xx` retry limit, and custom CA wiring.

Production configuration requires robot credentials even if a test endpoint returns anonymous `200`; anonymous success is tested only as protocol behavior and never as a production configuration fallback.

### Cosign Adapter Tests

Use a fake command runner that returns fixture stdout/stderr and records arguments and environment. Cover both required commands, absolute executable and key paths, custom CA, nonzero exits, timeout, interruption, oversized output, stdout/stderr separation, minimal child environment, absence of secrets in arguments/errors, and temporary auth cleanup on every exit path.

### Provenance Policy Tests

Use fixed Cosign JSON fixtures for a valid SLSA v1 statement and independent mutations of subject algorithm/digest, predicate type, builder, source URI, revision, missing fields, malformed base64/JSON, duplicate statements, and conflicting matches. Every mutation must fail closed with the expected internal category.

### API and Transaction Tests

- Persist all three evidence records after a complete success.
- Return `422 UNVERIFIED_ARTIFACT` for each failure category.
- Verify no release or partial evidence remains after any failure.
- Verify response serialization exposes only the three stable evidence records.
- Verify `test` and `local` profiles do not require production Harbor/Cosign configuration.

### Repository Verification

Run the existing `scripts/verify.ps1` suite, Maven tests and package, Portal tests and build, runtime network policy checks, build metadata contracts, CI supply-chain evidence contracts, and the PostgreSQL migration test when Docker is available.

## Acceptance Boundary

This slice is complete when the production adapter, strict policy, configuration validation, security constraints, evidence persistence, documentation, and offline test matrix pass. The acceptance report must describe the implementation as offline-verified and keep live Harbor/Cosign integration `PARTIAL` until a real Harbor robot account, signed image, provenance attestation, private CA path if applicable, and failure-injection run are observed.

The live follow-up must prove successful private pull, signature verification, provenance verification, bad credentials, unauthorized repository, wrong public key, missing attestation, builder mismatch, source mismatch, revision mismatch, and registry unavailability.

## References

- Harbor system robot accounts: <https://goharbor.io/docs/2.12.0/administration/robot-accounts/>
- Sigstore registry support: <https://docs.sigstore.dev/cosign/system_config/registry_support/>
- Cosign signature verification: <https://docs.sigstore.dev/cosign/verifying/verify/>
- Cosign attestation verification: <https://docs.sigstore.dev/cosign/verifying/attestation/>
- Cosign `v3.0.6` release: <https://github.com/sigstore/cosign/releases/tag/v3.0.6>
- SLSA provenance v1.1: <https://slsa.dev/spec/v1.1/provenance>
