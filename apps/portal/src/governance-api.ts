export type Capability = {
  id: string;
  name?: string;
  department?: string;
  type?: string;
};

export type Release = {
  id: string;
  capabilityId?: string;
  version: string;
  digest: string;
  artifactReference?: string;
  state: string;
};

export type ReleaseTransition = 'validate' | 'review-required' | 'approve' | 'publish' | 'reject' | 'revoke';

export type Deployment = {
  id: string;
  releaseId: string;
  department: string;
  digest: string;
  observedDigest?: string | null;
  status: string;
  requestedActor: string;
  requestedAt: string;
  observedAt?: string | null;
};

export class GovernanceApiError extends Error {
  readonly status: number;

  constructor(status: number, message = `Governance API request failed with HTTP ${status}.`) {
    super(message);
    this.name = 'GovernanceApiError';
    this.status = status;
  }
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

async function request<T>(
  baseUrl: string,
  path: string,
  accessToken: string,
  fetchImpl: FetchLike,
  init: RequestInit = {}
): Promise<T> {
  const response = await fetchImpl(`${baseUrl}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`
    }
  });
  if (!response.ok) {
    throw new GovernanceApiError(response.status);
  }
  return response.json() as Promise<T>;
}

export type GovernanceApi = {
  listCapabilities(accessToken: string): Promise<Capability[]>;
  getCapability(accessToken: string, capabilityId: string): Promise<Capability>;
  listReleases(accessToken: string, capabilityId: string): Promise<Release[]>;
  getRelease(accessToken: string, releaseId: string): Promise<Release>;
  transitionRelease(accessToken: string, releaseId: string, transition: ReleaseTransition): Promise<Release>;
  deployRelease(accessToken: string, releaseId: string): Promise<Deployment>;
};

export function createGovernanceApi(baseUrl: string, fetchImpl: FetchLike = globalThis.fetch.bind(globalThis)): GovernanceApi {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, '');
  return {
    listCapabilities: (accessToken) => request<Capability[]>(normalizedBaseUrl, '/capabilities', accessToken, fetchImpl),
    getCapability: (accessToken, capabilityId) => request<Capability>(normalizedBaseUrl, `/capabilities/${encodeURIComponent(capabilityId)}`, accessToken, fetchImpl),
    listReleases: (accessToken, capabilityId) => request<Release[]>(normalizedBaseUrl, `/capabilities/${encodeURIComponent(capabilityId)}/releases`, accessToken, fetchImpl),
    getRelease: (accessToken, releaseId) => request<Release>(normalizedBaseUrl, `/releases/${encodeURIComponent(releaseId)}`, accessToken, fetchImpl),
    transitionRelease: (accessToken, releaseId, transition) => request<Release>(
      normalizedBaseUrl,
      `/releases/${encodeURIComponent(releaseId)}/${transition}`,
      accessToken,
      fetchImpl,
      { method: 'POST' }
    ),
    deployRelease: (accessToken, releaseId) => request<Deployment>(
      normalizedBaseUrl,
      `/releases/${encodeURIComponent(releaseId)}/deployments`,
      accessToken,
      fetchImpl,
      { method: 'POST' }
    )
  };
}
