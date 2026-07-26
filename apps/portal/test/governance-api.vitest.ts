import { describe, expect, it, vi } from 'vitest';
import { createGovernanceApi, GovernanceApiError } from '../src/governance-api';

describe('governance API client', () => {
  it('sends a bearer token and accepts JSON catalog data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id: 'support-agent' }]), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }));
    const api = createGovernanceApi('http://localhost:8080/api/v1', fetchMock);

    await expect(api.listCapabilities('access-token')).resolves.toEqual([{ id: 'support-agent' }]);
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/v1/capabilities', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer access-token'
      }
    });
  });

  it('maps forbidden responses to a typed error without logging the token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('', { status: 403 }));
    const api = createGovernanceApi('http://localhost:8080/api/v1', fetchMock);

    await expect(api.listCapabilities('access-token')).rejects.toBeInstanceOf(GovernanceApiError);
    await expect(api.listCapabilities('access-token')).rejects.toMatchObject({ status: 403 });
  });
});
