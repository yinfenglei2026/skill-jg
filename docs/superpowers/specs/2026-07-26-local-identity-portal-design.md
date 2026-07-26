# Local Identity And Portal Integration Design

## Goal

Provide a repeatable, non-production identity environment that exercises real JWT issuance and lets the Portal authenticate with Authorization Code and PKCE before loading department-scoped capability and release data.

## Scope

This slice includes:

- synthetic Keycloak users for all five governance roles and two departments;
- a local-only token client for automated API smoke tests;
- a real JWT smoke test against the running control plane;
- Portal sign-in and sign-out through Keycloak Authorization Code with PKCE;
- Portal catalog and release views backed by the existing list/get APIs;
- loading, empty, unauthenticated, forbidden, and request-failure states;
- configurable issuer, client ID, API base URL, and allowed browser origin.

This slice does not include query pagination/filtering, Harbor authentication, Cosign verification, production identity-provider integration, or copying production users and credentials.

## Identity Boundaries

The local `governance` realm remains isolated from any real identity environment. Synthetic identities use names ending in `.test` and departments that contain no real employee data:

| User | Realm role | Department |
| --- | --- | --- |
| `owner.customer.test` | `owner` | `customer-operations` |
| `reviewer.customer.test` | `reviewer` | `customer-operations` |
| `approver.customer.test` | `approver` | `customer-operations` |
| `operator.customer.test` | `operator` | `customer-operations` |
| `reader.customer.test` | `read-only` | `customer-operations` |
| `owner.billing.test` | `owner` | `billing` |

Passwords are never committed. A provisioning script reads `KEYCLOAK_TEST_USER_PASSWORD` from the local process environment and uses Keycloak administration tooling to create or update users, department attributes, passwords, and realm-role assignments idempotently.

Two OIDC clients have separate responsibilities:

- `governance-portal` remains a public browser client with Standard Flow and PKCE. It does not enable Direct Access Grants.
- `governance-smoke` is a local-only public client with Direct Access Grants. Only the smoke script uses it to obtain a token for a synthetic user. It is not referenced by Portal code.

The control plane continues to accept JWTs from the configured issuer and map `realm_access.roles` plus the `department` claim. Existing Mock JWT tests remain the fast authorization layer; the new smoke test proves the complete Keycloak-to-Spring path.

## Local Provisioning Flow

`scripts/start-local.ps1` retains responsibility for starting PostgreSQL and Keycloak and waiting for realm discovery. It then invokes a focused identity provisioning script before launching Spring Boot.

The provisioning script:

1. validates that `KEYCLOAK_TEST_USER_PASSWORD` is set and is not a documented placeholder;
2. authenticates `kcadm.sh` against the local master realm using the existing bootstrap administrator environment variables;
3. creates each synthetic user if absent;
4. sets the `department` attribute, enabled state, non-temporary password, and exactly one governance realm role;
5. is safe to run repeatedly against an existing local volume.

`.env.example` documents the synthetic password variable but contains no usable password.

## Real JWT Smoke Test

`scripts/smoke-local-auth.ps1` is an explicit integration check, separate from unit tests. It reads the local issuer, API base URL, synthetic username, and password from environment variables.

The script requests a token from `governance-smoke`, validates that the response contains an access token, calls `GET /api/v1/capabilities`, and requires HTTP 200. It then decodes the JWT payload locally and asserts the expected department and role claims. It never prints the token or password.

This test is included in local verification only when an opt-in environment flag is set, because CI does not currently start Docker services. The default repository verification remains deterministic without external containers.

## Portal Architecture

The Portal becomes a small React application rather than a hard-coded DOM demo. Responsibilities are split into focused modules:

- OIDC configuration and session lifecycle;
- authenticated governance API client;
- catalog/release view-model mapping;
- React application states and navigation.

The Portal uses `oidc-client-ts` for Authorization Code and PKCE rather than implementing OAuth cryptography manually. Runtime configuration comes from Vite environment variables:

- `VITE_GOVERNANCE_API_BASE_URL`;
- `VITE_GOVERNANCE_OIDC_AUTHORITY`;
- `VITE_GOVERNANCE_OIDC_CLIENT_ID`;
- `VITE_GOVERNANCE_OIDC_REDIRECT_URI`.

No access token is placed in source, HTML, local storage, URL query parameters, or visible UI. The OIDC client uses session storage. API requests attach the current access token in the `Authorization` header and retry only after an explicit session refresh; authorization failures are rendered as stable states rather than hidden.

After login, the application loads the department-scoped capability list. Selecting a capability loads its releases and displays immutable digest, version, state, and artifact reference. Sign-out clears the local OIDC session and returns to the signed-out screen.

## Browser-Origin Policy

The control plane adds configurable CORS support for the Portal origin. Local configuration allows only `http://localhost:5173`; methods and headers are limited to those needed by the API, including `Authorization` and `Content-Type`. CORS does not replace server authorization, and direct cross-department requests continue to return `403`.

No wildcard origin is allowed when credentials or authorization headers are used.

## Error Handling

The Portal distinguishes:

- no authenticated session;
- session-expired or login-callback failure;
- initial loading;
- empty department catalog;
- API `403`;
- API `404` after a stale selection;
- network or server failure.

User-visible errors do not include tokens, response headers, stack traces, or raw Keycloak payloads. The API client exposes typed status information to React components while retaining diagnostic details only in development logging.

Provisioning and smoke scripts fail fast with actionable messages for missing environment values, unavailable Keycloak discovery, token acquisition failure, or unexpected claims.

## Testing Strategy

Implementation follows red-green-refactor cycles:

- Keycloak realm contract tests validate the two clients and required department mapper without secrets.
- PowerShell smoke helpers are structured so claim validation can be tested without contacting Keycloak; a live invocation proves the complete path when local services are enabled.
- Spring MockMvc tests validate the configured CORS preflight and preserve authorization behavior.
- Portal Vitest tests cover API authorization headers, response/error mapping, OIDC configuration, catalog rendering, and release selection.
- A production Portal build verifies TypeScript and Vite integration.
- The repository verification script continues to run Java tests, Portal tests, manifest checks, Kubernetes checks, and Compose rendering.

The live local acceptance check requires:

1. Keycloak discovery returns HTTP 200;
2. provisioning completes without printing secrets;
3. the smoke client issues a token with the expected role and department;
4. the authenticated catalog request returns HTTP 200;
5. the Portal build completes with no embedded fixed token.

## Security Notes

- All identities and credentials are synthetic and local-only.
- Direct Access Grants exist only on the smoke client and are never enabled on the Portal client.
- The browser uses PKCE and no client secret.
- Local service ports remain bound to `127.0.0.1`.
- No production issuer, user export, token, password, or client secret enters Git or test output.
- Passing this slice proves local integration mechanics, not production identity readiness.
