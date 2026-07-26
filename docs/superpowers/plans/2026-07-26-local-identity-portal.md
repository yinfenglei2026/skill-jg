# Local Identity And Portal Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exercise real local Keycloak JWTs and replace the Portal demo with a PKCE-authenticated React catalog backed by department-scoped governance APIs.

**Architecture:** Keep production identity isolated by provisioning synthetic users into the existing local Keycloak realm. Separate the browser PKCE client from a local-only Direct Access Grant smoke client, keep Spring as the authorization boundary, and split Portal session, API, view-model, and rendering responsibilities into testable modules.

**Tech Stack:** Keycloak 26, PowerShell 7, Spring Boot 3.2/Spring Security, React 19, TypeScript 5.9, Vite 7, Vitest 4, `oidc-client-ts`, Testing Library.

---

### Task 1: Lock The Local Keycloak Client Contract

**Files:**
- Modify: `infra/local/keycloak/realm-governance.json`
- Create: `apps/portal/test/keycloak-realm.test.mjs`
- Modify: `scripts/verify.ps1`

- [ ] **Step 1: Write the failing realm contract test**

Create a Node test that reads the checked-in realm JSON and requires a PKCE-only Portal client plus a separate local smoke client:

```javascript
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const realmPath = new URL('../../../infra/local/keycloak/realm-governance.json', import.meta.url);

test('separates browser PKCE from local direct-grant smoke authentication', async () => {
  const realm = JSON.parse(await readFile(realmPath, 'utf8'));
  const portal = realm.clients.find(({ clientId }) => clientId === 'governance-portal');
  const smoke = realm.clients.find(({ clientId }) => clientId === 'governance-smoke');

  assert.equal(portal.publicClient, true);
  assert.equal(portal.standardFlowEnabled, true);
  assert.equal(portal.directAccessGrantsEnabled, false);
  assert.equal(portal.attributes['pkce.code.challenge.method'], 'S256');
  assert.equal(smoke.publicClient, true);
  assert.equal(smoke.standardFlowEnabled, false);
  assert.equal(smoke.directAccessGrantsEnabled, true);
});
```

- [ ] **Step 2: Run the contract test and verify RED**

Run:

```powershell
node --test apps/portal/test/keycloak-realm.test.mjs
```

Expected: FAIL because `governance-smoke`, the Portal direct-grant flag, and the PKCE attribute do not yet exist.

- [ ] **Step 3: Add the explicit client settings**

Update the Portal client and add this sibling client without any secret:

```json
{
  "clientId": "governance-smoke",
  "name": "Governance Local Authentication Smoke Test",
  "enabled": true,
  "publicClient": true,
  "standardFlowEnabled": false,
  "directAccessGrantsEnabled": true
}
```

Set `directAccessGrantsEnabled` to `false` and `attributes.pkce.code.challenge.method` to `S256` on `governance-portal`.

- [ ] **Step 4: Run the contract test and verify GREEN**

Run `node --test apps/portal/test/keycloak-realm.test.mjs`.

Expected: one passing test.

- [ ] **Step 5: Include all dependency-free Portal contract tests in root verification**

Change the Portal section of `scripts/verify.ps1` to:

```powershell
node --test test/*.test.mjs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
```

Run `node --test apps/portal/test/*.test.mjs` and expect all tests to pass.

- [ ] **Step 6: Commit**

```powershell
git add infra/local/keycloak/realm-governance.json apps/portal/test/keycloak-realm.test.mjs scripts/verify.ps1
git commit -m "test: lock local identity client contract"
```

### Task 2: Provision Synthetic Identities And Validate JWT Claims

**Files:**
- Create: `scripts/local-identity.psm1`
- Create: `scripts/test-local-identity.ps1`
- Create: `scripts/provision-local-identities.ps1`
- Create: `scripts/smoke-local-auth.ps1`
- Modify: `scripts/start-local.ps1`
- Modify: `scripts/verify.ps1`
- Modify: `.env.example`
- Modify: `README.md`

- [ ] **Step 1: Write failing pure PowerShell tests**

Create `scripts/test-local-identity.ps1` that imports `local-identity.psm1`, builds an unsigned synthetic JWT payload, and verifies both the identity matrix and claim validation:

```powershell
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'local-identity.psm1') -Force

$identities = Get-GovernanceSyntheticIdentities
if ($identities.Count -ne 6) { throw 'Expected six synthetic identities.' }
if (($identities | Where-Object Username -eq 'owner.billing.test').Department -ne 'billing') {
    throw 'Billing isolation identity is missing.'
}

$payload = @{ sub = 'owner.customer.test'; department = 'customer-operations'; realm_access = @{ roles = @('owner') } }
$json = $payload | ConvertTo-Json -Compress -Depth 4
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$claims = Get-JwtPayload -AccessToken "header.$encoded.signature"
Assert-GovernanceJwtClaims -Claims $claims -ExpectedDepartment 'customer-operations' -ExpectedRole 'owner'
Write-Host 'Local identity helper tests passed.'
```

- [ ] **Step 2: Run the helper test and verify RED**

Run `pwsh -NoProfile -File scripts/test-local-identity.ps1`.

Expected: FAIL because `local-identity.psm1` does not exist.

- [ ] **Step 3: Implement the pure identity and JWT helpers**

Export these functions from `scripts/local-identity.psm1`:

```powershell
function Get-GovernanceSyntheticIdentities {
    @(
        [pscustomobject]@{ Username='owner.customer.test'; Role='owner'; Department='customer-operations' },
        [pscustomobject]@{ Username='reviewer.customer.test'; Role='reviewer'; Department='customer-operations' },
        [pscustomobject]@{ Username='approver.customer.test'; Role='approver'; Department='customer-operations' },
        [pscustomobject]@{ Username='operator.customer.test'; Role='operator'; Department='customer-operations' },
        [pscustomobject]@{ Username='reader.customer.test'; Role='read-only'; Department='customer-operations' },
        [pscustomobject]@{ Username='owner.billing.test'; Role='owner'; Department='billing' }
    )
}

function Get-JwtPayload {
    param([Parameter(Mandatory)][string]$AccessToken)
    $segments = $AccessToken.Split('.')
    if ($segments.Count -ne 3) { throw 'Access token is not a compact JWT.' }
    $value = $segments[1].Replace('-', '+').Replace('_', '/')
    $value += '=' * ((4 - $value.Length % 4) % 4)
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) | ConvertFrom-Json
}

function Assert-GovernanceJwtClaims {
    param($Claims, [string]$ExpectedDepartment, [string]$ExpectedRole)
    if ($Claims.department -ne $ExpectedDepartment) { throw 'JWT department claim did not match.' }
    if ($Claims.realm_access.roles -notcontains $ExpectedRole) { throw 'JWT realm role claim did not match.' }
}

Export-ModuleMember -Function Get-GovernanceSyntheticIdentities, Get-JwtPayload, Assert-GovernanceJwtClaims
```

- [ ] **Step 4: Run the helper test and verify GREEN**

Run `pwsh -NoProfile -File scripts/test-local-identity.ps1`.

Expected: `Local identity helper tests passed.` and exit code 0.

- [ ] **Step 5: Implement idempotent Keycloak provisioning**

Create `scripts/provision-local-identities.ps1`. It must:

- require `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`, and `KEYCLOAK_TEST_USER_PASSWORD`;
- reject values beginning with `replace-with-`;
- resolve the same Compose fallback used by `start-local.ps1`;
- authenticate `kcadm.sh` to `http://localhost:8080` in the master realm;
- create or update `governance-portal` and `governance-smoke` to match the checked-in PKCE/direct-grant contract, so an existing Keycloak volume does not retain stale client settings;
- query each username in the governance realm, create it only if missing, update `enabled=true` and `attributes.department`, set a non-temporary password, remove the other four governance roles, and add the intended role;
- suppress command output that could contain credentials.

Use the identities returned by `Get-GovernanceSyntheticIdentities`; do not duplicate the matrix.

- [ ] **Step 6: Implement the opt-in real JWT smoke script**

Create `scripts/smoke-local-auth.ps1` with parameters defaulting to:

```powershell
param(
    [string]$Issuer = $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI,
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080/api/v1',
    [string]$Username = 'owner.customer.test',
    [string]$Password = $env:KEYCLOAK_TEST_USER_PASSWORD,
    [string]$ExpectedDepartment = 'customer-operations',
    [string]$ExpectedRole = 'owner'
)
```

POST form data to `$Issuer/protocol/openid-connect/token` with `client_id=governance-smoke`, `grant_type=password`, username, and password. Validate claims with the module, call `GET $ApiBaseUrl/capabilities` with `Authorization: Bearer ...`, require HTTP 200, and print only `Local JWT authentication smoke test passed.`

- [ ] **Step 7: Integrate provisioning and deterministic tests**

After Keycloak discovery succeeds, invoke `provision-local-identities.ps1` from `start-local.ps1`. Add the following non-secret entry to `.env.example`:

```text
KEYCLOAK_TEST_USER_PASSWORD=replace-with-local-test-user-password
```

Invoke `test-local-identity.ps1` from `scripts/verify.ps1`. Document the live smoke command in `README.md`, explicitly labeling Direct Access Grant as local-test-only.

- [ ] **Step 8: Run tests and commit**

Run:

```powershell
pwsh -NoProfile -File scripts/test-local-identity.ps1
.\scripts\verify.ps1
```

Commit:

```powershell
git add .env.example README.md scripts
git commit -m "feat: provision synthetic local identities"
```

### Task 3: Allow Only The Configured Portal Browser Origin

**Files:**
- Modify: `apps/control-plane/src/test/java/com/example/governance/api/GovernanceApiTest.java`
- Modify: `apps/control-plane/src/main/java/com/example/governance/security/SecurityConfiguration.java`
- Modify: `apps/control-plane/src/main/resources/application.yml`
- Modify: `apps/control-plane/src/test/resources/application-test.yml`
- Modify: `.env.example`

- [ ] **Step 1: Write the failing CORS preflight test**

Add an `options` static import and this test:

```java
@Test
void allows_preflight_only_from_the_configured_portal_origin() throws Exception {
    mockMvc.perform(options("/api/v1/capabilities")
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

    mockMvc.perform(options("/api/v1/capabilities")
                    .header("Origin", "http://untrusted.example")
                    .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\mvnw.cmd -pl apps/control-plane -Dtest=GovernanceApiTest#allows_preflight_only_from_the_configured_portal_origin test
```

Expected: FAIL because no CORS source is configured.

- [ ] **Step 3: Implement the narrow CORS source**

Inject `governance.portal.allowed-origin` into `SecurityConfiguration`, call `http.cors(Customizer.withDefaults())`, and expose a `UrlBasedCorsConfigurationSource` that:

```java
CorsConfiguration configuration = new CorsConfiguration();
configuration.setAllowedOrigins(List.of(allowedOrigin));
configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
configuration.setMaxAge(3600L);
source.registerCorsConfiguration("/api/**", configuration);
```

Set `governance.portal.allowed-origin: ${GOVERNANCE_PORTAL_ALLOWED_ORIGIN:http://localhost:5173}` in `application.yml`, use the same local value in `application-test.yml`, and document `GOVERNANCE_PORTAL_ALLOWED_ORIGIN` in `.env.example`.

- [ ] **Step 4: Run focused and full Java tests**

Run the focused command again, then `.\mvnw.cmd -pl apps/control-plane test`.

Expected: all Java tests pass.

- [ ] **Step 5: Commit**

```powershell
git add .env.example apps/control-plane
git commit -m "feat: allow the configured portal origin"
```

### Task 4: Build The Portal OIDC And Governance API Clients

**Files:**
- Modify: `apps/portal/package.json`
- Modify: `apps/portal/package-lock.json`
- Modify: `apps/portal/vite.config.ts`
- Create: `apps/portal/src/config.ts`
- Create: `apps/portal/src/oidc.ts`
- Create: `apps/portal/src/governance-api.ts`
- Create: `apps/portal/test/config.vitest.ts`
- Create: `apps/portal/test/governance-api.vitest.ts`

- [ ] **Step 1: Install focused browser dependencies**

Run from `apps/portal`:

```powershell
npm install oidc-client-ts@3.3.0
npm install --save-dev jsdom@26.1.0 @testing-library/react@16.3.0 @testing-library/user-event@14.6.1
```

Do not add a second OAuth/OIDC library.

Change the package test script to `"test": "vitest run"` so every `.vitest.ts` and `.vitest.tsx` contract runs.

- [ ] **Step 2: Write failing configuration and API tests**

The configuration test must call a wished-for `createPortalConfig` with an explicit environment object and require normalized URLs plus `governance-portal` as the client. The API test must inject a fake fetch function, call `listCapabilities('access-token')`, and assert:

```typescript
expect(requestInit.headers).toEqual({
  Accept: 'application/json',
  Authorization: 'Bearer access-token'
});
```

It must also require a typed `GovernanceApiError` with status `403` when fetch returns a forbidden response.

- [ ] **Step 3: Run the tests and verify RED**

Run `npm test -- config governance-api` from `apps/portal`.

Expected: FAIL because the modules do not exist.

- [ ] **Step 4: Implement runtime configuration**

`createPortalConfig` accepts a record of environment strings and returns:

```typescript
export type PortalConfig = {
  apiBaseUrl: string;
  authority: string;
  clientId: string;
  redirectUri: string;
};
```

Require all four values, remove trailing slashes from API base and authority, and provide `portalConfig` from `import.meta.env`.

- [ ] **Step 5: Implement OIDC session creation**

Create one `UserManager` using:

```typescript
new UserManager({
  authority: config.authority,
  client_id: config.clientId,
  redirect_uri: config.redirectUri,
  post_logout_redirect_uri: config.redirectUri,
  response_type: 'code',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage })
});
```

Export an interface that exposes `getUser`, `signinRedirect`, `signinRedirectCallback`, `signoutRedirect`, and the access-token-expired event so React tests can inject a fake session.

- [ ] **Step 6: Implement the typed governance client**

Define `Capability`, `Release`, and `GovernanceApiError`. Each request must set `Accept` and Bearer authorization, parse only successful JSON, map `403` and `404` by status, and never log the token. Expose `listCapabilities`, `getCapability`, `listReleases`, and `getRelease`.

- [ ] **Step 7: Run tests, build, and commit**

Run:

```powershell
npm test
npm run build
```

Commit:

```powershell
git add apps/portal
git commit -m "feat: add portal oidc and governance clients"
```

### Task 5: Replace The Demo With An Authenticated React Catalog

**Files:**
- Delete: `apps/portal/src/main.mjs`
- Create: `apps/portal/src/main.tsx`
- Create: `apps/portal/src/App.tsx`
- Create: `apps/portal/src/styles.css`
- Delete: `apps/portal/src/release-view-model.mjs`
- Create: `apps/portal/src/release-view-model.ts`
- Modify: `apps/portal/index.html`
- Create: `apps/portal/test/App.vitest.tsx`
- Delete: `apps/portal/test/release-view-model.vitest.mjs`
- Delete: `apps/portal/test/release-view-model.test.mjs`
- Create: `apps/portal/test/release-view-model.vitest.ts`

- [ ] **Step 1: Write failing React workflow tests**

Use jsdom and injected fake session/API dependencies. Cover separate tests for:

- signed-out state renders a `Sign in` command and invokes `signinRedirect`;
- authenticated state loads and renders department capabilities;
- selecting a capability loads releases and keeps the full immutable digest visible;
- API `403` renders `You do not have access to this department.`;
- an empty catalog renders `No capabilities are registered for your department.`

Query controls by role/name, not CSS selectors.

- [ ] **Step 2: Run the React tests and verify RED**

Run `npm test -- App`.

Expected: FAIL because `App.tsx` does not exist.

- [ ] **Step 3: Implement the minimal React workflow**

`App` owns only UI state. It accepts an `authSession` and `api` interface for tests, resolves an OIDC callback when the URL contains `code` and `state`, and otherwise loads the current user. After authentication it loads capabilities, then releases for the selected capability.

Render an operational layout with:

- compact header containing product name, current subject/department when available, and sign-out;
- capability navigation list;
- release table with version, state, and shortened visual digest;
- selected release detail that shows the complete digest and artifact reference;
- stable loading, empty, forbidden, unauthenticated, and failure regions with `aria-live` where appropriate.

Do not add mutation controls in this slice.

- [ ] **Step 4: Complete the TypeScript entry and restrained styles**

Mount with `createRoot`, import `styles.css`, and change `index.html` to a single `<div id="root"></div>` plus `/src/main.tsx`. Use a neutral work-focused palette, 6px-or-less radii, responsive grid constraints, visible focus styles, and no gradients or decorative cards.

- [ ] **Step 5: Run Portal tests and build**

Run:

```powershell
npm test
npm run build
```

Expected: all Vitest tests pass and Vite produces `dist`.

- [ ] **Step 6: Commit**

```powershell
git add apps/portal
git commit -m "feat: connect portal catalog to governance api"
```

### Task 6: Prove The Complete Local Authentication Path

**Files:**
- Modify: `README.md`
- Modify: `scripts/verify.ps1` only if final deterministic checks are missing

- [ ] **Step 1: Run the deterministic repository verification**

Run `.\scripts\verify.ps1`.

Expected: Java tests, dependency-free Node contracts, PowerShell helper tests, Kubernetes rendering, and Compose rendering all pass.

- [ ] **Step 2: Start local dependencies with synthetic secrets**

Use process-local values matching `.env.example`, including a non-placeholder `KEYCLOAK_TEST_USER_PASSWORD`. Start PostgreSQL and Keycloak, wait for `http://127.0.0.1:8081/realms/governance/.well-known/openid-configuration`, and run `scripts/provision-local-identities.ps1`.

Expected: provisioning completes without printing any password or token.

- [ ] **Step 3: Start the control plane and run the real JWT smoke test**

Start Spring Boot with the local datasource, issuer, allowed Portal origin, and registry allowlist environment variables. Wait for `http://127.0.0.1:8080/actuator/health`, then run:

```powershell
pwsh -NoProfile -File scripts/smoke-local-auth.ps1
```

Expected: `Local JWT authentication smoke test passed.`

- [ ] **Step 4: Build and manually inspect the Portal**

Run `npm run build` and start Vite on an available loopback port. Verify the sign-in redirect targets the local governance realm, complete login with one synthetic user, confirm the catalog request carries a Bearer token, and inspect desktop/mobile layouts for overlap and text overflow.

- [ ] **Step 5: Scan for committed secrets and fixed tokens**

Run:

```powershell
rg -n "local-test-user-password|Bearer eyJ|KEYCLOAK_TEST_USER_PASSWORD=" --glob '!docs/superpowers/**' .
git diff --check
```

Expected: no committed usable password, access token, or formatting errors.

- [ ] **Step 6: Update documentation and commit final integration notes**

Document PKCE sign-in, synthetic-user provisioning, live smoke prerequisites, and the local-only Direct Access Grant boundary in `README.md`.

```powershell
git add README.md scripts/verify.ps1
git commit -m "docs: document local identity integration"
```

- [ ] **Step 7: Request final review and run final verification**

Run `.\scripts\verify.ps1`, `npm test`, and `npm run build` fresh. Dispatch a final spec-compliance review followed by a code-quality review. Resolve every finding before branch completion.
