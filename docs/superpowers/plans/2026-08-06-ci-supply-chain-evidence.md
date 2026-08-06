# CI Supply-Chain Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the GitHub Actions verification run produce a full-history secret scan, Java and Node CycloneDX SBOMs, deterministic build metadata, and one immutable SHA-addressed evidence artifact.

**Architecture:** Keep the existing single `verify` job as the build source of truth. Run Gitleaks before the existing checks, generate the Maven SBOM from a pinned CycloneDX plugin and the Portal SBOM from a pinned CycloneDX npm CLI, then write a machine-readable metadata file containing source identity and hashes. Upload all evidence with `actions/upload-artifact` and fail after upload if the scanner found a non-allowlisted secret.

**Tech Stack:** GitHub Actions, PowerShell 7, Maven, CycloneDX Maven Plugin 2.9.2, CycloneDX npm 4.2.1, Gitleaks 8.30.1, SHA-256.

---

### Task 1: Add metadata and workflow contract tests

**Files:**
- Create: `scripts/test-build-metadata.ps1`
- Create: `scripts/test-ci-supply-chain.ps1`

- [ ] **Step 1: Write the failing metadata contract test**

Create a temporary fixture with a JAR file, two SBOM JSON files, and a nested Portal `dist` tree. Invoke the not-yet-created `scripts/write-build-metadata.ps1` twice with the same commit/ref/tool inputs and assert byte-for-byte identical JSON, schema version `1`, a 40-character commit SHA, stable tree hash, file sizes, and SHA-256 values. Invoke it once with a missing artifact and assert a nonzero failure.

- [ ] **Step 2: Run the metadata contract test to verify it fails**

Run `pwsh -NoProfile -File scripts/test-build-metadata.ps1`.
Expected: FAIL because `scripts/write-build-metadata.ps1` does not exist.

- [ ] **Step 3: Write the failing CI contract test**

Read `.github/workflows/ci.yml` and assert it contains `fetch-depth: 0`, a pinned Gitleaks version and checksum verification, `--redact`, SARIF and JSON report paths, CycloneDX Maven/npm commands, `write-build-metadata.ps1`, `actions/upload-artifact@v4`, `ci-evidence-${{ github.sha }}`, and `if-no-files-found: error`. Assert that the workflow does not use `GITLEAKS_LICENSE`, unpinned `@main`, or a broad ignored path.

- [ ] **Step 4: Run the CI contract test to verify it fails**

Run `pwsh -NoProfile -File scripts/test-ci-supply-chain.ps1`.
Expected: FAIL because the existing workflow has none of the new evidence steps.

- [ ] **Step 5: Commit the red tests**

Run:

```powershell
git add scripts/test-build-metadata.ps1 scripts/test-ci-supply-chain.ps1
git commit -m "test: define ci supply-chain evidence contracts"
```

### Task 2: Implement deterministic build metadata

**Files:**
- Create: `scripts/write-build-metadata.ps1`
- Modify: `scripts/test-build-metadata.ps1`

- [ ] **Step 1: Implement input validation and hashing**

Add parameters for repository root, output path, JAR path, Portal dist path, both SBOM paths, commit SHA, ref, workflow, run ID, source date epoch, and tool versions. Require a 40-character lowercase hexadecimal SHA, existing files/directories, a nonnegative integer epoch, and nonempty tool strings. Hash files with `Get-FileHash -Algorithm SHA256`; hash a Portal tree by sorting normalized relative paths ordinally and hashing the UTF-8 sequence `relative-path + NUL + lowercase-file-sha256 + newline`.

- [ ] **Step 2: Write deterministic JSON**

Emit schema version `1`, source fields, tools, and an artifact array containing the JAR file, Portal tree, and the two SBOM files. Use `ConvertTo-Json -Depth 8` and UTF-8 output without a timestamp. Create the output directory before writing.

- [ ] **Step 3: Run the metadata contract test to verify it passes**

Run `pwsh -NoProfile -File scripts/test-build-metadata.ps1`.
Expected: `Build metadata tests passed.`

- [ ] **Step 4: Commit the implementation**

Run:

```powershell
git add scripts/write-build-metadata.ps1 scripts/test-build-metadata.ps1
git commit -m "feat: write deterministic build metadata"
```

### Task 3: Add CycloneDX SBOM generation

**Files:**
- Modify: `apps/control-plane/pom.xml`
- Modify: `scripts/test-ci-supply-chain.ps1`

- [ ] **Step 1: Add the pinned Maven plugin**

Configure `org.cyclonedx:cyclonedx-maven-plugin:2.9.2` with `makeAggregateBom` bound to `verify`, JSON format, schema `1.6`, reproducible output, test scope excluded, output name `control-plane-bom`, and output directory `target/sbom`.

- [ ] **Step 2: Add the pinned npm command contract**

The workflow contract must require `npx --yes @cyclonedx/cyclonedx-npm@4.2.1 --package-lock-only --output-format JSON --output-reproducible --validate` and an output path under `artifacts/sbom/portal-bom.json`.

- [ ] **Step 3: Run Maven and npm SBOM generation locally**

Run `./mvnw -B -pl apps/control-plane verify`, then from `apps/portal` run `npm ci --ignore-scripts` and the pinned CycloneDX command. Validate both output files are JSON and contain a nonempty `components` array.

- [ ] **Step 4: Commit the SBOM generation**

Run:

```powershell
git add apps/control-plane/pom.xml scripts/test-ci-supply-chain.ps1
git commit -m "feat: generate cyclonedx dependency sboms"
```

### Task 4: Add full-history Gitleaks and evidence artifact upload

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.gitleaks.toml`
- Modify: `scripts/test-ci-supply-chain.ps1`

- [ ] **Step 1: Pin and verify Gitleaks**

Use Gitleaks `8.30.1` Linux x64 release with the official SHA-256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`. Download it from the release URL, verify with `sha256sum -c`, and invoke the extracted binary with `gitleaks git --redact --config .gitleaks.toml`.

- [ ] **Step 2: Add the narrow synthetic-value policy**

Create `.gitleaks.toml` with exact regex allowlist entries only for the repository's documented `verify-*` Compose passwords and `change-me` example values. Do not allowlist directories, file extensions, or arbitrary high-entropy matches.

- [ ] **Step 3: Extend the workflow**

Change checkout to full history. Add a redacted Gitleaks step producing `artifacts/secret-scan/gitleaks.sarif` and `gitleaks.json`, with `continue-on-error: true`; add Maven and npm SBOM commands; call `scripts/write-build-metadata.ps1` with GitHub context and tool versions; upload `ci-evidence-${{ github.sha }}` with `if: always()` and `if-no-files-found: error`; finally fail if the Gitleaks step outcome was not success.

- [ ] **Step 4: Run the CI contract test to verify it passes**

Run `pwsh -NoProfile -File scripts/test-ci-supply-chain.ps1`.
Expected: `CI supply-chain contract tests passed.`

- [ ] **Step 5: Run Gitleaks locally**

Download the pinned Windows x64 release and its official `gitleaks_8.30.1_checksums.txt` file. Extract the exact checksum line for `gitleaks_8.30.1_windows_x64.zip`, compare it to `Get-FileHash -Algorithm SHA256`, then run `gitleaks dir --redact --config .gitleaks.toml .` against the repository. Expected: no findings and exit code `0`.

- [ ] **Step 6: Commit the workflow**

Run:

```powershell
git add .github/workflows/ci.yml .gitleaks.toml scripts/test-ci-supply-chain.ps1
git commit -m "ci: publish secret scan and build evidence"
```

### Task 5: Integrate root verification and documentation

**Files:**
- Modify: `scripts/verify.ps1`
- Modify: `docs/poc-acceptance-report.md`
- Modify: `docs/implementation-plan.md`

- [ ] **Step 1: Run local metadata and workflow contracts from the root verifier**

Invoke both contract scripts from `scripts/verify.ps1` before the existing application tests, and stop on a nonzero exit code.

- [ ] **Step 2: Update acceptance evidence**

Change FND-03 and FND-05 from PARTIAL to PASS only for CI evidence that is locally reproducible; keep external registry signing and runtime integration open. Add the exact contract commands, SBOM paths, Gitleaks version/checksum, and the CI artifact naming rule.

- [ ] **Step 3: Run the complete verification suite**

Run `scripts/verify.ps1`, `scripts/test-postgres-upgrade.ps1`, `git diff --check`, and tracked-secret hygiene checks. Expected: all exit `0`; Maven tests retain zero failures with the existing integration skip.

- [ ] **Step 4: Commit and review**

Run:

```powershell
git add scripts/verify.ps1 docs/poc-acceptance-report.md docs/implementation-plan.md
git commit -m "docs: record ci supply-chain evidence"
```

Request a final code review, then preserve this branch/worktree without merge or push.
