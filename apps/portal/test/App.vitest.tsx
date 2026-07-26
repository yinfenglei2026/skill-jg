/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App, type AuthSession } from '../src/App';
import type { GovernanceApi } from '../src/governance-api';

afterEach(() => cleanup());

function authSession(user: AuthSession['getUser'] extends () => Promise<infer T> ? T : never): AuthSession {
  return {
    getUser: vi.fn().mockResolvedValue(user),
    signinRedirect: vi.fn().mockResolvedValue(undefined),
    signinRedirectCallback: vi.fn(),
    signoutRedirect: vi.fn().mockResolvedValue(undefined),
    events: { addAccessTokenExpired: vi.fn().mockReturnValue(() => undefined) }
  };
}

function api(overrides: Partial<GovernanceApi> = {}): GovernanceApi {
  return {
    listCapabilities: vi.fn().mockResolvedValue([]),
    getCapability: vi.fn(),
    listReleases: vi.fn().mockResolvedValue([]),
    getRelease: vi.fn(),
    ...overrides
  };
}

const user = {
  access_token: 'access-token',
  profile: { sub: 'owner.customer.test', department: 'customer-operations' }
} as never;

describe('portal workflow', () => {
  it('offers sign-in while signed out', async () => {
    const session = authSession(null);
    render(<App authSession={session} api={api()} />);

    const signIn = await screen.findByRole('button', { name: 'Sign in' });
    await userEvent.click(signIn);
    expect(session.signinRedirect).toHaveBeenCalledOnce();
  });

  it('loads and renders capabilities for the authenticated department', async () => {
    const session = authSession(user);
    const governanceApi = api({
      listCapabilities: vi.fn().mockResolvedValue([{ id: 'support-agent', name: 'Support agent', department: 'customer-operations', type: 'AGENT' }])
    });
    render(<App authSession={session} api={governanceApi} />);

    expect(await screen.findByRole('button', { name: /Support agent/ })).toBeTruthy();
    expect(screen.getByText('customer-operations')).toBeTruthy();
  });

  it('loads releases and shows the complete immutable digest after selection', async () => {
    const session = authSession(user);
    const digest = `sha256:0f${'2'.repeat(62)}`;
    const governanceApi = api({
      listCapabilities: vi.fn().mockResolvedValue([{ id: 'support-agent', name: 'Support agent', department: 'customer-operations', type: 'AGENT' }]),
      listReleases: vi.fn().mockResolvedValue([{ id: 'support-agent:1.0.0', version: '1.0.0', state: 'PUBLISHED', digest, artifactReference: `oci://registry.example.internal/support-agent@${digest}` }])
    });
    render(<App authSession={session} api={governanceApi} />);

    await userEvent.click(await screen.findByRole('button', { name: /Support agent/ }));
    expect(await screen.findByText(digest)).toBeTruthy();
    expect(screen.getAllByText('Published').length).toBeGreaterThan(0);
  });

  it('explains department access denial', async () => {
    const session = authSession(user);
    const governanceApi = api({ listCapabilities: vi.fn().mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 })) });
    render(<App authSession={session} api={governanceApi} />);
    expect(await screen.findByText('You do not have access to this department.')).toBeTruthy();
  });

  it('renders an explicit empty catalog state', async () => {
    render(<App authSession={authSession(user)} api={api()} />);
    expect(await screen.findByText('No capabilities are registered for your department.')).toBeTruthy();
  });
});
