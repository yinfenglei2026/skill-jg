/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
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
    events: {
      addAccessTokenExpired: vi.fn(),
      removeAccessTokenExpired: vi.fn()
    }
  };
}

function api(overrides: Partial<GovernanceApi> = {}): GovernanceApi {
  return {
    listCapabilities: vi.fn().mockResolvedValue([]),
    getCapability: vi.fn(),
    listReleases: vi.fn().mockResolvedValue([]),
    getRelease: vi.fn(),
    transitionRelease: vi.fn(),
    deployRelease: vi.fn(),
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

  it('unregisters the access-token expiration handler on unmount', async () => {
    const session = authSession(user);
    const { unmount } = render(<App authSession={session} api={api()} />);

    expect(await screen.findByText('No capabilities are registered for your department.')).toBeTruthy();
    const addAccessTokenExpired = vi.mocked(session.events.addAccessTokenExpired);
    expect(addAccessTokenExpired).toHaveBeenCalledOnce();
    const [onExpired] = addAccessTokenExpired.mock.calls[0];

    unmount();

    expect(session.events.removeAccessTokenExpired).toHaveBeenCalledWith(onExpired);
  });

  it('lets an approver confirm the full digest and refreshes the release after approval', async () => {
    const digest = `sha256:${'a'.repeat(64)}`;
    const approver = {
      access_token: 'approver-token',
      profile: {
        sub: 'approver.customer.test',
        department: 'customer-operations',
        realm_access: { roles: ['approver'] }
      }
    } as never;
    const reviewRequired = { id: 'support-agent:1.0.0', version: '1.0.0', state: 'REVIEW_REQUIRED', digest };
    const approved = { ...reviewRequired, state: 'APPROVED' };
    const listReleases = vi.fn()
      .mockResolvedValueOnce([reviewRequired])
      .mockResolvedValueOnce([approved]);
    const transitionRelease = vi.fn().mockResolvedValue(approved);
    const governanceApi = api({
      listCapabilities: vi.fn().mockResolvedValue([
        { id: 'support-agent', name: 'Support agent', department: 'customer-operations', type: 'AGENT' }
      ]),
      listReleases,
      transitionRelease
    });
    render(<App authSession={authSession(approver)} api={governanceApi} />);

    await userEvent.click(await screen.findByRole('button', { name: /Support agent/ }));
    const actions = await screen.findByRole('region', { name: 'Release actions' });
    expect(within(actions).getByText(digest)).toBeTruthy();
    await userEvent.click(within(actions).getByRole('button', { name: 'Approve' }));

    expect(transitionRelease).toHaveBeenCalledWith('approver-token', 'support-agent:1.0.0', 'approve');
    expect((await screen.findAllByText('Approved')).length).toBeGreaterThan(0);
    expect(listReleases).toHaveBeenCalledTimes(2);
  });

  it('keeps a successful transition when the follow-up refresh fails', async () => {
    const digest = `sha256:${'d'.repeat(64)}`;
    const approver = {
      access_token: 'approver-token',
      profile: {
        sub: 'approver.customer.test',
        department: 'customer-operations',
        realm_access: { roles: ['approver'] }
      }
    } as never;
    const reviewRequired = { id: 'support-agent:1.0.0', version: '1.0.0', state: 'REVIEW_REQUIRED', digest };
    const approved = { ...reviewRequired, state: 'APPROVED' };
    const listReleases = vi.fn()
      .mockResolvedValueOnce([reviewRequired])
      .mockRejectedValueOnce(new Error('refresh unavailable'));
    const governanceApi = api({
      listCapabilities: vi.fn().mockResolvedValue([
        { id: 'support-agent', name: 'Support agent', department: 'customer-operations', type: 'AGENT' }
      ]),
      listReleases,
      transitionRelease: vi.fn().mockResolvedValue(approved)
    });
    render(<App authSession={authSession(approver)} api={governanceApi} />);

    await userEvent.click(await screen.findByRole('button', { name: /Support agent/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));

    expect((await screen.findAllByText('Approved')).length).toBeGreaterThan(0);
    expect((await screen.findByRole('alert')).textContent)
      .toContain('The action succeeded, but release data could not be refreshed.');
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(listReleases).toHaveBeenCalledTimes(2);
  });

  it('does not render mutation controls for a read-only user', async () => {
    const readOnly = {
      ...user,
      profile: {
        ...user.profile,
        realm_access: { roles: ['read-only'] }
      }
    } as never;
    const governanceApi = api({
      listCapabilities: vi.fn().mockResolvedValue([
        { id: 'support-agent', name: 'Support agent', department: 'customer-operations', type: 'AGENT' }
      ]),
      listReleases: vi.fn().mockResolvedValue([
        { id: 'support-agent:1.0.0', version: '1.0.0', state: 'PUBLISHED', digest: `sha256:${'b'.repeat(64)}` }
      ])
    });
    render(<App authSession={authSession(readOnly)} api={governanceApi} />);

    await userEvent.click(await screen.findByRole('button', { name: /Support agent/ }));
    expect((await screen.findAllByText('Published')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('region', { name: 'Release actions' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Deploy' })).toBeNull();
  });

  it.each([
    [403, 'You are not authorized to perform this release action.'],
    [409, 'The release changed or this action conflicts with its current state.']
  ])('keeps release detail visible when a mutation returns HTTP %s', async (status, expectedMessage) => {
    const digest = `sha256:${'c'.repeat(64)}`;
    const operator = {
      access_token: 'operator-token',
      profile: {
        sub: 'operator.customer.test',
        department: 'customer-operations',
        realm_access: { roles: ['operator'] }
      }
    } as never;
    const governanceApi = api({
      listCapabilities: vi.fn().mockResolvedValue([
        { id: 'support-agent', name: 'Support agent', department: 'customer-operations', type: 'AGENT' }
      ]),
      listReleases: vi.fn().mockResolvedValue([
        { id: 'support-agent:1.0.0', version: '1.0.0', state: 'PUBLISHED', digest }
      ]),
      deployRelease: vi.fn().mockRejectedValue(Object.assign(new Error('request failed'), { status }))
    });
    render(<App authSession={authSession(operator)} api={governanceApi} />);

    await userEvent.click(await screen.findByRole('button', { name: /Support agent/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Deploy' }));

    expect((await screen.findByRole('alert')).textContent).toContain(expectedMessage);
    expect(screen.getAllByText(digest).length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: 'support-agent:1.0.0' })).toBeTruthy();
  });
});
