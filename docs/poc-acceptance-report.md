# PoC Acceptance Report

## Decision

**Overall status: PARTIAL.** The local governance control-plane baseline and disposable Harbor/Cosign interoperability matrix are repeatable for PostgreSQL migration, Keycloak JWT authentication, Portal component and real-browser behavior, and static runtime declarations. Real digest-bound approval, audit persistence, browser publication, and the local signed-artifact verification path were exercised. The full phase-one PoC is not accepted because external GitOps/runtime admission and the Agent + MCP + Skill golden path are not implemented.

This result authorizes continued engineering only. It is not a production-readiness or deployment approval.

## Run Evidence

- Date: 2026-08-06, Asia/Shanghai
- Branch: `feat/poc-acceptance`
- Evidence commit before this update: `0f84a6b0cd07ee11e213acab612a38bfad95c889`
- Local toolchain: Java 17.0.19, Node 24.15.0, npm 11.12.1, Docker Engine 29.6.2, Docker Compose 5.3.1, kubectl 1.36.1
- Components: Spring Boot 3.2.0, PostgreSQL 16.4, Keycloak 26.0.7, React 19.2.0, TypeScript 5.9.3, Vite 7.3.6, Vitest 4.1.8
- `scripts/verify.ps1`: PASS; Java 125 tests with 1 conditional PostgreSQL test skipped, Keycloak realm 2 tests, Portal 25 tests, TypeScript, production Vite build, Kubernetes network contracts, Compose config, and realm JSON
- `scripts/test-postgres-upgrade.ps1`: PASS; disposable PostgreSQL applied V1-V3, preserved the fixture through V4, accepted nullable Skill import paths, and removed its container/network/volume
- `scripts/smoke-local-auth.ps1`: PASS against real Keycloak and Spring; access-token and ID-token department/role claims matched without printing either token
- Real approval API: PASS; `acceptance-agent:1.0.0` reached `APPROVED`, the 71-character SHA-256 digest was unchanged, and the audit actor equaled the JWT `sub`
- Browser desktop/mobile: PASS in Chrome at 1440x900 and 390x844; APPROVER, OPERATOR, and READ_ONLY rendering matched role/state policy, OPERATOR published the digest-bound release from `APPROVED` to `PUBLISHED`, page-level overflow and clipping were absent, keyboard focus was visible, and sampled text contrast was at least 5.88:1
- Browser-discovered identity fix: PASS; local provisioning now converges the Keycloak `realm roles` mapper into the ID token so the OIDC profile used by the Portal contains the same realm roles enforced from the access token by Spring
- Offline Harbor/Cosign adapters: PASS; authenticated direct/Bearer digest lookup, custom CA transport, bounded process execution, temporary Docker auth cleanup, fixed-key signature invocation, strict SLSA source/builder policy, typed failures, and atomic three-record persistence are covered by local tests.
- Live Harbor/Cosign interoperability (2026-08-09): PASS for the disposable matrix. Harbor v2.15.2 (offline package SHA-256 `67517e0ba4a3f9db90731aa560dacbc0b24a0de04d5a1b428c7d32ea96656432`) reached nine healthy services over HTTPS; Cosign v3.0.6 matched SHA-256 `9b85a88ebff2d9dd30ff4984a6f61f2cedc232dd87d81fa7f2ff3c0ed96c241c`. The immutable fixture digest was `sha256:f87a92474a5ea962707dd4afa7ae35bc5681b0ac946a719f8491548e443dbb28`. The positive signature and SLSA attestation verification passed with exact builder `https://builder.example/internal`, repository `https://git.example.internal/team/app.git`, and revision `d4b266d0c0d5bdbfe26c38a68abbf9857848e2f0`; eight negative cases returned the expected typed failures (`REGISTRY_AUTH_FAILED`, `SIGNATURE_INVALID`, `PROVENANCE_INVALID`, `PROVENANCE_POLICY_MISMATCH`, and `REGISTRY_UNAVAILABLE`). Evidence was bound to test commit `d4b266d0c0d5bdbfe26c38a68abbf9857848e2f0` and diff SHA-256 `fe9a7a867c52b7042e4cf1448acc67f61bde5db88af704e9d68c202feb446979` before this documentation commit. Harbor containers, network, and volumes were removed after the run. Five user-requested runtime files, including disposable test key material, remain under `G:\project\skill-jg-runtime\harbor-live-2026-08-08`; therefore secret cleanup is explicitly an exception and this report makes no production-readiness claim.
- Hygiene: superseded clean worktree/branch `feat/task3-schema-contract-clean` removed after file/patch comparison; `stash@{0}` preserved

Status meanings: PASS is repeatably demonstrated; PARTIAL has meaningful automated evidence but does not meet the full criterion; EXCEPTION is an acknowledged unimplemented control; NOT RUN has no execution evidence.

Unless a row states otherwise, the platform engineering team owns every non-PASS item until it is assigned in the next slice.

## Acceptance Matrix

| Criterion | Status | Evidence, consequence, and next slice |
| --- | --- | --- |
| FND-01 | PARTIAL | README documents prerequisites and the root verifier exercises the installed tools, but it does not enforce Java 17 or Node 24 versions. Consequence: an unsupported version can reach later failures. Next: add explicit version gates. |
| FND-02 | PASS | One root command ran server, Portal, identity, manifest, Compose, and Kubernetes static checks. |
| FND-03 | PARTIAL | CI now generates locked Java/Node CycloneDX SBOMs and deterministic commit/build metadata, then uploads a SHA-addressed immutable evidence artifact. Local contract and generator checks pass, but this branch has not yet completed a remote GitHub Actions run. Consequence: hosted artifact retention/digest evidence remains unobserved. Next: run CI on this branch and record the artifact digest. |
| FND-04 | PASS | Architecture, schema, release states, digest rules, and PoC limitations are checked in and cross-referenced. |
| FND-05 | PARTIAL | CI now performs a full-history, redacted Gitleaks scan with a narrow exact-value synthetic-fixture policy; the workflow and policy are contract-tested. The local environment could not reach the release download endpoint, so its first hosted scan remains unobserved. Consequence: scanner execution evidence is pending. Next: run CI on this branch and inspect the redacted reports. |
| API-01 | PASS | Parser/API tests cover Agent/MCP `v1alpha1`, strict fields, stable typed errors, and OCI artifact contracts. |
| API-02 | PASS | Dependency locks, missing/mismatched dependencies, Skill import paths, and capability cycles are tested. |
| API-03 | PASS | Approval is bound to the canonical candidate digest and is not inherited across releases. |
| API-04 | PASS | State, failure, retry, rejection, revocation, segregation-of-duties, and denied-transition tests pass. |
| API-05 | PASS | Publication/deployment reject draft, unapproved, revoked, or digest-mismatched releases. |
| API-06 | PARTIAL | Governance mutations and domain denials persist actor/action/subject/decision/time/digest, and real PostgreSQL audit binding to JWT `sub` passed. Wrong-role requests rejected by method security are not audited. Consequence: authorization-denial history is incomplete. Next: add an authorization-denial audit filter/listener. |
| API-07 | EXCEPTION | No restricted catalog/download CLI exists. Consequence: the CLI boundary cannot be verified. Next: design read-only credentials and digest-addressed download APIs. |
| WEB-01 | PARTIAL | Portal shows catalog, state, full digest, and artifact reference, but not source, approval history, GitOps, or runtime trace. Consequence: users cannot trace the full release lifecycle. Next: add an evidence/deployment timeline after external adapters exist. |
| WEB-02 | PASS | Component tests prove the full digest is shown beside approval, success refreshes data, and 409 preserves detail with an alert. |
| WEB-03 | EXCEPTION | Dependency locks are returned by the API but not rendered in the Portal. Consequence: reviewers cannot inspect Skill vs MCP dependencies visually. Next: add a typed dependency section. |
| WEB-04 | EXCEPTION | Permission, secret-reference, and network diffs are not rendered. Consequence: approval context is incomplete. Next: add sanitized manifest-diff views. |
| WEB-05 | PASS | Real Chrome sessions proved APPROVER has no action in `APPROVED`, OPERATOR receives `Publish`/`Revoke`, publication refreshes the release to `PUBLISHED` with `Deploy`/`Revoke`, and READ_ONLY receives no mutation region; API tests continue to prove direct unauthorized requests return 403. |
| WEB-06 | PASS | Chrome at 1440x900 and 390x844 showed no page-level horizontal overflow, clipped control text, or visual overlap. The narrow release table scrolls within its own boundary, action controls remain inside the viewport, keyboard focus has a clearly visible outline, and sampled text contrast was at least 5.88:1. |
| RUN-01 | PARTIAL | Control-plane/local adapters enforce exact approval and digest; no live admission controller or external reconciler exists. Consequence: a real cluster has no authoritative admission enforcement. Next: implement signed GitOps admission. |
| RUN-02 | PARTIAL | Rendered examples use immutable images, resources, service accounts, probes, and gVisor; workloads were not started in K3s. Consequence: startup and health behavior are unproven. Next: execute the examples in the PoC cluster. |
| RUN-03 | EXCEPTION | No live Agent-to-MCP/Skill invocation or undeclared dependency denial exists. Consequence: the core golden path is absent. Next: implement runtime dependency resolution and audit. |
| RUN-04 | PARTIAL | Default-deny/DNS/allowlist declarations pass static checks; live network enforcement was not exercised. Consequence: CNI enforcement may differ from rendered intent. Next: add positive and negative cluster traffic tests. |
| RUN-05 | EXCEPTION | Model Gateway and direct-provider egress tests are absent. Consequence: provider credential isolation is unproven. Next: integrate the Gateway and egress denial. |
| RUN-06 | PARTIAL | Registry errors and unavailable external deployment adapters fail closed; live Gateway/MCP/health failure behavior is absent. Consequence: cross-service degraded states are unproven. Next: add typed integration failure tests. |
| RUN-07 | EXCEPTION | Production logs/metrics/traces and redaction are not integrated. Consequence: cross-plane diagnosis is unavailable. Next: define and emit correlation attributes. |
| INF-01 | PARTIAL | Kubernetes declarations render without edits, but prerequisite services and cluster provisioning are external/manual. Consequence: a fresh environment cannot be reproduced from this repository alone. Next: add reproducible environment bootstrap. |
| INF-02 | EXCEPTION | External GitOps reconciliation and drift reporting are not configured. Consequence: deployment intent cannot reach a real cluster. Next: integrate a GitOps controller/repository. |
| INF-03 | NOT RUN | Allowlist-removal and unrestricted-namespace failure were not exercised on a cluster. Consequence: network-policy readiness cannot be asserted. Next: add CNI-backed negative tests. |
| INF-04 | NOT RUN | Least-privilege service-account declarations exist, but no live RBAC denial tests ran. Consequence: runtime privilege boundaries are unproven. Next: execute `kubectl auth can-i` and workload probes. |
| INF-05 | PARTIAL | Git ignores local keys/kubeconfigs and manifests contain references only; no Git/OCI secret scan or external secret delivery ran. Consequence: bundle hygiene and secret delivery remain unverified. Next: scan release bundles and integrate the configured secret mechanism. |
| INF-06 | NOT RUN | Node/service loss was not exercised. Consequence: PoC disruption behavior is unknown. Next: run and document two-node failure drills without an HA claim. |
| VER-01 | EXCEPTION | No automated build-to-GitOps-to-Agent/MCP golden path exists. Consequence: full PoC acceptance is blocked. Next: implement it after supply-chain/runtime integration. |
| VER-02 | PARTIAL | Canonical digest and dependency mismatch tests pass, but no post-approval OCI/GitOps mutation scenario runs end to end. Consequence: external tamper resistance is unproven. Next: add signed-artifact mutation tests. |
| VER-03 | PARTIAL | Invalid state/role tests and static default-deny checks pass; secret access, direct-provider access, and CLI mutation denial are absent. Consequence: several negative security boundaries are unproven. Next: add cluster/CLI negative suites. |
| VER-04 | PARTIAL | Registry unavailability and unconfigured deployment adapters are covered; Gateway, MCP, GitOps drift, and readiness injection are absent. Consequence: most cross-service failure states are unverified. Next: add external failure scenarios. |
| VER-05 | PARTIAL | Revocation, retry, and audit-history behavior are tested; rollback through real GitOps is absent. Consequence: operational rollback safety is unproven. Next: add previous-approved-digest rollback. |
| VER-06 | PARTIAL | This report records versions, commit, digest shape, migration outcome, and test totals, but the real approval/audit-subject check is not a checked-in command and the root verifier does not emit commit/digest metadata. Consequence: another engineer cannot reproduce every evidence item from one documented sequence. Next: add a secret-safe approval smoke script and machine-readable verification summary. |
| VER-07 | PASS | Every unmet control is listed here; README and architecture explicitly reject production-readiness claims. |

## Ordered Next Work

1. Run the configured CI evidence workflow on this branch and record its immutable artifact digest and redacted scan reports.
2. Integrate external GitOps and K3s admission/reconciliation, then execute live network/RBAC/failure tests.
3. Add Model Gateway, Agent + MCP + Skill invocation, observability, rollback, and the restricted read-only CLI.
