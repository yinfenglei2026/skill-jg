import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const realm = JSON.parse(
  await readFile(new URL('../../../infra/local/keycloak/realm-governance.json', import.meta.url), 'utf8')
);

const portal = realm.clients.find(({ clientId }) => clientId === 'governance-portal');
const smoke = realm.clients.find(({ clientId }) => clientId === 'governance-smoke');

test('portal uses Authorization Code with PKCE and disables Direct Access Grants', () => {
  assert.ok(portal, 'governance-portal client must exist');
  assert.equal(portal.publicClient, true);
  assert.equal(portal.standardFlowEnabled, true);
  assert.equal(portal.attributes?.['pkce.code.challenge.method'], 'S256');
  assert.equal(portal.directAccessGrantsEnabled, false);
});

test('smoke client is public and only enables Direct Access Grants', () => {
  assert.ok(smoke, 'governance-smoke client must exist');
  assert.equal(smoke.publicClient, true);
  assert.equal(smoke.standardFlowEnabled, false);
  assert.equal(smoke.directAccessGrantsEnabled, true);
});
