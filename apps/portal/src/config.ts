export type PortalConfig = {
  apiBaseUrl: string;
  authority: string;
  clientId: string;
  redirectUri: string;
};

type PortalEnvironment = Record<string, string | undefined>;

function required(environment: PortalEnvironment, key: string): string {
  const value = environment[key]?.trim();
  if (!value) {
    throw new Error(`Missing required portal configuration: ${key}`);
  }
  return value;
}

function withoutTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

export function createPortalConfig(environment: PortalEnvironment): PortalConfig {
  return {
    apiBaseUrl: withoutTrailingSlash(required(environment, 'VITE_GOVERNANCE_API_BASE_URL')),
    authority: withoutTrailingSlash(required(environment, 'VITE_GOVERNANCE_OIDC_AUTHORITY')),
    clientId: required(environment, 'VITE_GOVERNANCE_OIDC_CLIENT_ID'),
    redirectUri: withoutTrailingSlash(required(environment, 'VITE_GOVERNANCE_OIDC_REDIRECT_URI'))
  };
}

export const portalConfig: PortalConfig = {
  get apiBaseUrl() {
    return createPortalConfig(import.meta.env).apiBaseUrl;
  },
  get authority() {
    return createPortalConfig(import.meta.env).authority;
  },
  get clientId() {
    return createPortalConfig(import.meta.env).clientId;
  },
  get redirectUri() {
    return createPortalConfig(import.meta.env).redirectUri;
  }
};
