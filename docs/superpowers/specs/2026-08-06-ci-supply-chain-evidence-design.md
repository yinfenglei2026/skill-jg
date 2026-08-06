# CI Supply-Chain Evidence Design

**Date:** 2026-08-06

**Status:** Approved for specification review

## Goal

Make the GitHub Actions verification run produce secret-safe, immutable evidence for the exact source revision: a full-history secret scan, Java and Node dependency SBOMs, build metadata, and artifact hashes.

This slice is limited to CI evidence. Harbor authentication, OCI publishing, Cosign signatures, provenance attestations, GitOps reconciliation, and runtime changes remain in later slices.

## Architecture

The existing `verify` workflow remains the source of build truth. The job checks the complete Git history with the official Gitleaks CLI at pinned release `8.29.1`, verifying the downloaded Linux asset against that release's published checksums file. The existing build job then generates two CycloneDX JSON SBOMs, records deterministic build metadata, and uploads one immutable evidence artifact.

The scan has no access to repository secrets and writes only redacted reports. Synthetic values already required by local Compose and test fixtures are allowed only through narrowly scoped, documented Gitleaks rules; no directory-wide ignore is permitted. Any finding outside those exact fixtures fails the job.

## Evidence Contract

The uploaded `ci-evidence-${GITHUB_SHA}` artifact contains:

- `build-metadata.json`
- `sbom/control-plane-bom.json`
- `sbom/portal-bom.json`
- `secret-scan/gitleaks.sarif`
- `secret-scan/gitleaks.json`

`build-metadata.json` is schema version `1` and contains:

```json
{
  "schemaVersion": 1,
  "commitSha": "40 hexadecimal characters",
  "ref": "string",
  "workflow": "string",
  "runId": "string",
  "sourceDateEpoch": 0,
  "tools": {
    "java": "string",
    "maven": "string",
    "node": "string",
    "npm": "string"
  },
  "artifacts": [
    {
      "name": "control-plane-jar",
      "path": "apps/control-plane/target/control-plane-0.1.0-SNAPSHOT.jar",
      "sha256": "64 lowercase hexadecimal characters",
      "bytes": 0
    }
  ]
}
```

The artifact list includes the control-plane JAR, the Portal `dist` tree digest, and both SBOM files. The metadata excludes wall-clock generation time; `SOURCE_DATE_EPOCH` is used when present and otherwise derived from the commit timestamp. Artifact names include the full commit SHA, and `actions/upload-artifact@v4` supplies an independent artifact digest.

## Workflow Data Flow

1. `actions/checkout` uses `fetch-depth: 0` in the `verify` job so findings cover history, not only the tip commit.
2. The `verify` job's `secret-scan` step downloads the Linux x64 Gitleaks release, verifies the published checksum, runs `gitleaks git` with redaction, and writes SARIF plus JSON reports. The job exits nonzero for any non-allowlisted finding.
3. `verify` runs the current Maven, Portal, runtime policy, Compose and realm checks exactly as today.
4. After successful builds, a pinned CycloneDX Maven invocation writes `control-plane-bom.json`; the locked `@cyclonedx/cyclonedx-npm` development dependency reads `apps/portal/package-lock.json` and writes `portal-bom.json` with reproducible output.
5. `write-build-metadata.ps1` validates the commit SHA and hashes the JAR, Portal dist tree, and SBOM files in stable path order.
6. A final upload step uses `if-no-files-found: error`, a SHA-addressed artifact name, and retains the evidence for the existing CI retention policy.

## Failure Behavior

- A missing or mismatched Gitleaks checksum fails before scanning.
- A secret finding fails the `secret-scan` job; reports are still uploaded with `if: always()` and redaction enabled.
- Invalid JSON, an unsupported CycloneDX schema, an absent build output, an absent metadata field, or a hash mismatch fails the build job.
- No token, password, private key, or unredacted scanner output is written to logs or artifacts.
- The workflow does not claim image signing, registry trust, or production provenance; those controls are explicitly deferred.

## Testing and Acceptance

The slice is accepted only when all of the following pass:

- A static workflow contract test proves full-history checkout, checksum verification, redacted scanning, pinned tool versions, SHA-addressed artifact naming, and `if-no-files-found: error`.
- A metadata contract test proves deterministic output, exact SHA-256 formatting, stable Portal tree hashing, and failure on missing commit or artifact inputs.
- Gitleaks scans the repository with no findings outside the exact synthetic fixtures.
- Maven produces a valid CycloneDX JSON BOM containing direct and transitive runtime dependencies.
- npm produces a valid CycloneDX JSON BOM from the lockfile without requiring an uncommitted dependency change.
- The root verifier, Portal tests/build, `git diff --check`, and secret-path hygiene checks remain green.

## Explicit Non-Goals

This design does not add Harbor, Cosign, Sigstore trust roots, OCI image build/push, GitHub attestations, vulnerability thresholds, dependency update automation, or runtime admission policy. Those requirements are tracked as the next external-integration slice.
