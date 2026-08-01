# PoC Acceptance Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a repeatable local PoC acceptance baseline with a live PostgreSQL upgrade check, role-aware Portal mutations, real Keycloak-to-Spring smoke evidence, and an honest acceptance matrix.

**Architecture:** Keep `scripts/verify.ps1` independent of Docker. Add an explicit disposable PostgreSQL harness, keep server authorization authoritative, and extend the Portal through a pure role/state action selector plus typed authenticated mutations. Record live evidence separately from static and unit checks.

**Tech Stack:** Java 17, Spring Boot 3.2, Flyway, PostgreSQL 16, JUnit 5, PowerShell 7, Docker Compose, React 19, TypeScript 5.9, Vitest 4, Keycloak 26.

---

### Task 1: Add a disposable PostgreSQL upgrade contract

**Files:**
- Modify: `compose.yaml`
- Modify: `.env.example`
- Create: `apps/control-plane/src/test/java/com/example/governance/migration/PostgresqlMigrationTest.java`
- Create: `scripts/test-postgres-upgrade.ps1`

- [x] **Step 1: Write the PostgreSQL-only failing migration test**

Enable the JUnit test only when `POSTGRES_MIGRATION_TEST_URL` is set. Migrate to V3, insert a capability, release, and Skill dependency fixture, migrate to latest, then assert version V4, preserved fixture data, and a nullable `dependency_import_path`.

```java
Flyway.configure().dataSource(url, username, password).target("3").load().migrate();
insertV3Fixture(url, username, password);
MigrateResult result = Flyway.configure().dataSource(url, username, password).load().migrate();
assertThat(result.targetSchemaVersion).isEqualTo("4");
assertFixtureAndNullableImportPath(url, username, password);
```

- [x] **Step 2: Run the focused test without a URL**

Run `.\mvnw.cmd -B -pl apps/control-plane -Dtest=PostgresqlMigrationTest test`.
Expected: build succeeds and the Docker-backed test is skipped.

- [x] **Step 3: Parameterize the PostgreSQL host port**

Use `127.0.0.1:${POSTGRES_HOST_PORT:-5432}:5432` in `compose.yaml` and add `POSTGRES_HOST_PORT=5432` to `.env.example`. Keep container port 5432.

- [x] **Step 4: Implement the isolated PowerShell harness**

Create a unique `governance-upgrade-$PID` Compose project, select a free loopback port, set generated process-local credentials, start only PostgreSQL, wait for health, run the focused Maven test, and execute `down --volumes` in `finally`. Validate the exact project-name prefix before cleanup and never read or overwrite `.env`.

- [x] **Step 5: Run the live upgrade test**

Run `.\scripts\test-postgres-upgrade.ps1`.
Expected: PostgreSQL 16 becomes healthy, V1-V3 and then V4 apply, assertions pass, and the disposable container and volume are removed.

- [x] **Step 6: Commit**

Commit as `test: verify live postgres upgrades`.

### Task 2: Extend the typed Portal API with mutations

**Files:**
- Modify: `apps/portal/src/governance-api.ts`
- Modify: `apps/portal/test/governance-api.vitest.ts`

- [x] **Step 1: Write failing authenticated POST tests**

Prove `transitionRelease` sends an authenticated POST, `deployRelease` posts to `/deployments`, and non-2xx responses retain their HTTP status.

```typescript
await api.transitionRelease('token', 'support-agent:1.0.0', 'approve');
expect(fetchImpl).toHaveBeenCalledWith(
  'http://localhost:8080/api/v1/releases/support-agent%3A1.0.0/approve',
  expect.objectContaining({ method: 'POST' })
);
```

- [x] **Step 2: Verify the focused test fails**

Run `node apps/portal/node_modules/vitest/vitest.mjs run apps/portal/test/governance-api.vitest.ts`.
Expected: missing mutation methods.

- [x] **Step 3: Implement mutation-capable request plumbing**

Allow `request` to merge an optional `RequestInit`. Add `ReleaseTransition`, `Deployment`, `transitionRelease(...)`, and `deployRelease(...)`; both methods use POST and parse JSON.

- [x] **Step 4: Run the focused tests and commit**

Expected: API-client tests pass. Commit as `feat: add portal governance mutations`.

### Task 3: Add pure role and release-state action selection

**Files:**
- Create: `apps/portal/src/release-actions.ts`
- Create: `apps/portal/test/release-actions.vitest.ts`

- [x] **Step 1: Write the failing action-matrix tests**

Cover REVIEWER/DRAFT -> validate, REVIEWER/VALIDATING -> review-required, APPROVER/REVIEW_REQUIRED -> approve, OPERATOR/APPROVED -> publish, OPERATOR/PUBLISHED -> deploy, and no actions for READ_ONLY or mismatched roles.

- [x] **Step 2: Verify module-not-found failure**

Run the focused Vitest file before creating the implementation.

- [x] **Step 3: Implement normalization and the explicit matrix**

`normalizeRoles(profile)` accepts Keycloak `realm_access.roles` and flat `roles`, normalizes hyphens to underscores and uppercase, and deduplicates. `actionsFor(roles, state)` uses a data table and returns stable action IDs, labels, and transition/deployment kind.

- [x] **Step 4: Run focused tests and commit**

Expected: all action cases pass. Commit as `feat: derive portal release actions`.

### Task 4: Expose role-appropriate actions in the Portal

**Files:**
- Modify: `apps/portal/src/App.tsx`
- Modify: `apps/portal/src/styles.css`
- Modify: `apps/portal/test/App.vitest.tsx`

- [x] **Step 1: Write failing component tests**

Render an APPROVER on REVIEW_REQUIRED, verify the full digest beside Approve, click it, and assert mutation plus refresh. Add forbidden/conflict cases that preserve the selected release and show an alert. Add a READ_ONLY case with no mutation controls.

- [x] **Step 2: Verify the component tests fail**

Expected: no action control and no mutation call.

- [x] **Step 3: Implement stable mutation state and refresh**

Derive roles with `normalizeRoles`, render `actionsFor`, disable controls while pending, refresh releases on success while preserving selection, and render concise 403/409/general alerts without clearing release detail.

- [x] **Step 4: Add responsive action styling**

Use one unframed action band below the digest, wrapping command labels, radii no greater than 8px, and stable control heights. Do not introduce nested cards.

- [x] **Step 5: Run Portal tests and build, then commit**

Run `npm test` and `npm run build` in `apps/portal`.
Expected: Vitest, TypeScript, and Vite pass. Commit as `feat: expose governed release actions`.

### Task 5: Run real local identity and browser acceptance

**Files:**
- Modify: `scripts/start-local.ps1`
- Modify: `scripts/test-local-identity.ps1`
- Modify: `README.md`

- [x] **Step 1: Add a failing port-consistency contract**

Require startup to reject a `SPRING_DATASOURCE_URL` port that differs from `POSTGRES_HOST_PORT`, with both setting names in the error.

- [x] **Step 2: Implement the fail-fast check**

Parse the JDBC URI, default an omitted PostgreSQL port to 5432, validate `POSTGRES_HOST_PORT` as an integer, and compare before Compose starts.

- [x] **Step 3: Run script contract tests**

Run `scripts/test-local-identity.ps1` without containers. Expected: all tests pass.

- [x] **Step 4: Start the local stack with untracked synthetic configuration**

Use non-placeholder local passwords and matching host/JDBC ports. Start PostgreSQL, Keycloak, the local-profile control plane, and Vite. Never commit `.env` or `.env.local`.

- [x] **Step 5: Run Keycloak-to-Spring smoke**

Run `scripts/smoke-local-auth.ps1`. Expected: Keycloak JWT claims match and the catalog API returns 200 without printing the token.

- [ ] **Step 6: Run desktop and mobile browser checks**

At desktop and mobile viewports, sign in as a synthetic role user, select a release, perform one permitted mutation, verify unauthorized controls remain absent, and inspect for overflow or overlap. Screenshots must contain no secrets.

Blocked on 2026-08-02: the external browser rejected localhost access because its admin-enforced policy could not be verified, and no in-app browser instance was available. The real approver JWT/API mutation and audit binding passed; viewport evidence remains NOT RUN.

- [x] **Step 7: Document commands and commit**

Document the disposable migration test and live acceptance sequence in README. Commit as `chore: make local acceptance reproducible`.

### Task 6: Record acceptance evidence and close hygiene

**Files:**
- Create: `docs/poc-acceptance-report.md`
- Modify: `docs/implementation-plan.md`

- [ ] **Step 1: Collect evidence**

Record commit SHA, component versions, test counts, migration result, JWT smoke result, and viewport results without secrets.

- [ ] **Step 2: Map every criterion**

Add one row per FND, API, WEB, RUN, INF, and VER criterion. Use only PASS, PARTIAL, EXCEPTION, or NOT RUN. Every non-pass row names the consequence and next slice.

- [ ] **Step 3: Reconcile old worktree state**

Compare `feat/task3-schema-contract-clean` at file and patch level. Remove it only if its unique behavior is present or superseded. Preserve `stash@{0}` throughout this slice.

- [ ] **Step 4: Run final gates**

Run `.\scripts\verify.ps1`, `.\scripts\test-postgres-upgrade.ps1`, and `git diff --check`.
Expected: all exit 0.

- [ ] **Step 5: Review and commit evidence**

Confirm no secret or production-readiness claim is present. Commit as `docs: record poc acceptance evidence`.

### Task 7: Gate the next external-integration slice

**Files:**
- No code changes in this plan.

- [ ] **Step 1: Decide local acceptance**

Proceed only when migration, identity, Portal, and root gates pass and exceptions are accurately recorded.

- [ ] **Step 2: Start the Harbor/Cosign design cycle**

The next design covers Harbor authentication, registry failure semantics, Cosign signatures, provenance policy, trust roots, audit evidence, and negative tests. It remains outside this branch.
