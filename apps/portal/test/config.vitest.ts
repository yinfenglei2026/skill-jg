import { describe, expect, it } from 'vitest';
import { createPortalConfig } from '../src/config';

describe('createPortalConfig', () => {
  it('normalizes the local API and issuer URLs', () => {
    expect(createPortalConfig({
      VITE_GOVERNANCE_API_BASE_URL: 'http://localhost:8080/api/v1/',
      VITE_GOVERNANCE_OIDC_AUTHORITY: 'http://localhost:8180/realms/governance/',
      VITE_GOVERNANCE_OIDC_CLIENT_ID: 'governance-portal',
      VITE_GOVERNANCE_OIDC_REDIRECT_URI: 'http://localhost:5173/'
    })).toEqual({
      apiBaseUrl: 'http://localhost:8080/api/v1',
      authority: 'http://localhost:8180/realms/governance',
      clientId: 'governance-portal',
      redirectUri: 'http://localhost:5173'
    });
  });

  it('requires each deployment setting', () => {
    expect(() => createPortalConfig({})).toThrow(/VITE_GOVERNANCE_API_BASE_URL/);
  });
});
