# Live Harbor/Cosign Interoperability Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute a disposable local Harbor/Cosign interoperability matrix against the production verification adapters and record honest live evidence.

**Architecture:** Pin the official Harbor v2.15.2 online installer and Cosign v3.0.6. Run Harbor over HTTPS on `harbor.localhost:9443` with a disposable CA, create a scoped read-only robot account, push one immutable image, attach a fixed-key signature and SLSA v1 attestation, then invoke the Java production verifier for positive and negative cases. Every resource is isolated under a dedicated runtime directory and removed after evidence capture.

**Tech Stack:** Docker Desktop, Harbor 2.15.2, Docker Compose 5.3.1, Cosign 3.0.6, Java 17, PowerShell, Git Bash, OpenSSL container.

---

### Task 1: Preflight and pinned artifacts

**Files:**
- Create: `docs/superpowers/plans/2026-08-08-live-harbor-cosign-interoperability.md`
- Runtime only: `G:/project/skill-jg-runtime/harbor-live-2026-08-08/`

- [ ] Verify Docker daemon, Compose, Git Bash, Docker ports `9443`/`9080`, disk space, and official Harbor release API metadata.
- [ ] Download `harbor-online-installer-v2.15.2.tgz`, verify SHA-256 `88f6a7436b31890e8e472972a7433d36b7d6a36de9adeb86337fdc9fe7fb5fa3`, and keep the archive outside Git.
- [ ] Verify the Cosign v3.0.6 Linux/Windows hashes against the official release API before installation.

### Task 2: HTTPS Harbor bootstrap

**Runtime only:** `harbor.yml`, CA, server certificate/key, installer directory.

- [ ] Generate a disposable CA and `harbor.localhost` certificate with SAN using a pinned OpenSSL container.
- [ ] Configure Harbor hostname `harbor.localhost`, HTTPS port `9443`, admin password from a process-only generated value, database password from a process-only generated value, and persistent data under the runtime directory.
- [ ] Run `prepare` and `install.sh` through Git Bash, wait for all Harbor containers to become healthy, and verify `https://harbor.localhost:9443/api/v2.0/health` with the CA.

### Task 3: Robot account and immutable fixture

**Runtime only:** test project, robot credentials, Cosign key pair, image build context and provenance predicate.

- [ ] Create a private Harbor project and project-scoped read-only robot account through the Harbor API; retain credentials only in process variables.
- [ ] Build a deterministic minimal OCI image, push it by tag, resolve its digest, and use the exact digest reference for every subsequent command.
- [ ] Generate a fixed Cosign key pair, sign the digest, and attach a SLSA v1 provenance predicate whose builder, repository and revision match the request.
- [ ] Verify the signature and attestation independently with Cosign before invoking Java.

### Task 4: Production adapter live matrix

**Files:**
- Create: `apps/control-plane/src/test/java/com/example/governance/release/LiveHarborCosignInteropTest.java`
- Create: `scripts/test-live-harbor-cosign.ps1`

- [x] Add a test gated by `LIVE_HARBOR_COSIGN=true` that constructs the production Harbor transport/client, Cosign client, SLSA policy and verifier from absolute paths and process-only credentials.
- [x] Positive case: assert three evidence types and exact digest/source/builder/revision binding.
- [x] Negative cases: wrong password, unauthorized project, wrong public key, missing attestation, builder mismatch, repository mismatch, revision mismatch, and Harbor unavailable; assert typed failures and no evidence returned.
- [x] Run the gated test with the disposable fixture and write a redacted JSON summary containing statuses, failure categories, digest, and test commit only.

### Task 5: Cleanup and evidence

- [x] Stop Harbor and remove only the named Compose project, volumes, and network. Runtime/key cleanup has a documented user-requested exception.
- [x] Confirm no Harbor containers/volumes remain, record the five retained runtime files, and verify the branch contains only intended scripts/tests/docs.
- [x] Update `docs/poc-acceptance-report.md` with live evidence and retain `PARTIAL` for the broader PoC; do not claim production readiness.
- [x] Run `scripts/verify.ps1` and commit the live harness/evidence update without pushing.

## Execution Status (2026-08-09)

- [x] Task 1 preflight and release-asset verification completed. Docker Engine 29.6.2 and Compose 5.3.1 were available; the Harbor online installer and both Cosign v3.0.6 platform hashes matched official GitHub release metadata.
- [x] A disposable CA and `localhost` server certificate were generated with SANs for `localhost` and `127.0.0.1`; the full Harbor v2.15.2 configuration was rendered successfully with runtime-only secrets.
- [x] Harbor `prepare` completed and generated the Compose configuration.
- [x] Harbor services started from the verified offline image archive. After correcting Windows bind-mount paths and the generated registry certificate file, all nine Harbor services became healthy and `https://harbor.localhost:9443/api/v2.0/health` returned `status: healthy`.
- [x] A private `livecosign` project, project-scoped Robot, deterministic `FROM scratch` OCI fixture, and immutable manifest digest `sha256:e2f2891aaf186802295606ca01174caa70ea5800f1aab4a64654ab54ac71a675` were created. The fixture was pushed with an admin-only process credential and read back through the Robot path.
- [x] Cosign v3.0.6 Windows executable was verified at the supplied absolute path with SHA-256 `9b85a88ebff2d9dd30ff4984a6f61f2cedc232dd87d81fa7f2ff3c0ed96c241c` and `cosign version` reported `v3.0.6`.
- [x] The live Harbor/Cosign matrix passed: the positive signature/SLSA verification matched the exact digest, builder, repository, and revision; eight negative cases returned the expected typed failures. Evidence was bound to test commit `d4b266d0c0d5bdbfe26c38a68abbf9857848e2f0` and diff SHA-256 `fe9a7a867c52b7042e4cf1448acc67f61bde5db88af704e9d68c202feb446979`.
- [x] The named Compose project, nine containers, network, and anonymous volumes were brought down and removed. Five runtime files, including disposable test key material, remain at `G:\project\skill-jg-runtime\harbor-live-2026-08-08` because the user requested that cleanup exception.
- [ ] Full runtime secret cleanup remains pending until those user-requested files are explicitly released for deletion.
- [x] `git diff --check` and `scripts/verify.ps1` passed; the branch is ready for a local commit without pushing.
