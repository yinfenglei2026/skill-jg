# PoC Acceptance Closure Design

## Goal

Turn the merged local governance baseline into a repeatable acceptance candidate. The
acceptance run must exercise PostgreSQL upgrade behavior, real Keycloak-issued JWTs,
one role-authorized Portal mutation, and the existing static and automated gates. It
must also state which phase-one and production controls remain unmet.

This slice does not add Harbor/Cosign verification, a production GitOps controller,
K3s runtime integration, Vault, Model Gateway, or production observability. Those are
separate follow-on slices after the local acceptance baseline is reliable.

## Acceptance Boundaries

The normal root verifier remains usable without a running Docker engine. Docker-backed
acceptance checks are explicit because they create disposable local containers and take
longer than unit and contract tests.

The live PostgreSQL migration check creates a uniquely named Compose project, applies
Flyway migrations only through V3, inserts representative existing data, applies V4,
and verifies that the data remains readable and the nullable Skill import-path column
exists. Cleanup removes only the uniquely named disposable project and volume.

The local identity smoke check continues to use synthetic `*.test` users and the
`governance-smoke` direct-grant client. It never prints access tokens or stores local
passwords in Git.

## Portal Workflow

The Portal reads normalized realm roles from the authenticated user's JWT profile and
derives actions from both role and release state. The server remains authoritative:
hidden controls improve usability, while direct unauthorized requests must still fail
with `403`.

The minimum accepted actions are:

- REVIEWER: validate DRAFT, require review from VALIDATING, or reject an eligible release.
- APPROVER: approve REVIEW_REQUIRED.
- OPERATOR: publish APPROVED, deploy PUBLISHED, and revoke an eligible release.

Every action is an authenticated POST through the typed API client. Success refreshes
the selected release and capability release list. Conflicts, forbidden responses, and
other failures remain visible without losing the selected release. The complete digest
is shown beside the action controls so the operator can verify the immutable subject.

## Evidence And Reporting

An acceptance report maps `FND`, `API`, `WEB`, `RUN`, `INF`, and `VER` criteria from
`docs/implementation-plan.md` to one of `PASS`, `PARTIAL`, `EXCEPTION`, or `NOT RUN`.
Each non-pass row names the missing integration or evidence and its consequence. The
report records the tested commit, component versions, commands, and results without
secrets.

The report may call the repository a local PoC acceptance baseline. It must not claim
production readiness, high availability, verified artifact provenance, or live GitOps
and runtime enforcement.

## Verification

The slice is complete when all of the following hold:

1. The existing root verifier passes unchanged in purpose.
2. The disposable PostgreSQL V3-to-V4 upgrade test passes against PostgreSQL 16.
3. Local Keycloak issues a JWT with the expected department and role, and Spring accepts it.
4. Portal unit tests cover role/action selection, authenticated mutations, refresh, and errors.
5. A browser run proves sign-in and one permitted mutation at desktop and mobile widths.
6. The acceptance report accurately records unmet external and production controls.

