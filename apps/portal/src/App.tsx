import { useEffect, useMemo, useState } from 'react';
import type { Capability, GovernanceApi, Release } from './governance-api';

export type PortalUser = {
  access_token: string;
  profile: {
    sub?: string;
    department?: string;
    [claim: string]: unknown;
  };
};

export type AuthSession = {
  getUser(): Promise<PortalUser | null>;
  signinRedirect(): Promise<void>;
  signinRedirectCallback(): Promise<PortalUser>;
  signoutRedirect(): Promise<void>;
  events: {
    addAccessTokenExpired(callback: () => void): () => void;
  };
};

export type AppProps = {
  authSession: AuthSession;
  api: GovernanceApi;
};

type CatalogState = 'loading' | 'ready' | 'signed-out' | 'forbidden' | 'failed';

const stateLabels: Record<string, string> = {
  DRAFT: 'Draft',
  VALIDATING: 'Validation running',
  REVIEW_REQUIRED: 'Review required',
  APPROVED: 'Approved',
  PUBLISHED: 'Published',
  DEPLOYING: 'Deployment pending',
  DEPLOYED: 'Deployed',
  DEGRADED: 'Degraded',
  FAILED: 'Failed',
  REJECTED: 'Rejected',
  REVOKED: 'Revoked'
};

function displayName(capability: Capability): string {
  return capability.name?.trim() || capability.id;
}

function shortDigest(digest: string): string {
  return digest.length > 22 ? `${digest.slice(0, 19)}...` : digest;
}

export function App({ authSession, api }: AppProps) {
  const [user, setUser] = useState<PortalUser | null>(null);
  const [catalogState, setCatalogState] = useState<CatalogState>('loading');
  const [catalogError, setCatalogError] = useState<Error | null>(null);
  const [capabilities, setCapabilities] = useState<Capability[]>([]);
  const [selectedCapabilityId, setSelectedCapabilityId] = useState<string | null>(null);
  const [releases, setReleases] = useState<Release[]>([]);
  const [releaseState, setReleaseState] = useState<'idle' | 'loading' | 'ready' | 'failed'>('idle');
  const [releaseError, setReleaseError] = useState<Error | null>(null);
  const [selectedReleaseId, setSelectedReleaseId] = useState<string | null>(null);

  const department = typeof user?.profile.department === 'string' ? user.profile.department : 'Department unavailable';
  const selectedCapability = useMemo(
    () => capabilities.find((capability) => capability.id === selectedCapabilityId) ?? null,
    [capabilities, selectedCapabilityId]
  );
  const selectedRelease = useMemo(
    () => releases.find((release) => release.id === selectedReleaseId) ?? releases[0] ?? null,
    [releases, selectedReleaseId]
  );

  useEffect(() => {
    let active = true;
    const params = new URLSearchParams(window.location.search);
    const load = async () => {
      try {
        const nextUser = params.has('code') && params.has('state')
          ? await authSession.signinRedirectCallback()
          : await authSession.getUser();
        if (!active) return;
        if (!nextUser) {
          setCatalogState('signed-out');
          return;
        }
        setUser(nextUser);
        setCatalogState('loading');
        const nextCapabilities = await api.listCapabilities(nextUser.access_token);
        if (!active) return;
        setCapabilities(nextCapabilities);
        setSelectedCapabilityId(null);
        setCatalogState('ready');
      } catch (error) {
        if (!active) return;
        setCatalogError(error instanceof Error ? error : new Error('Unable to load the catalog.'));
        setCatalogState(error instanceof Error && 'status' in error && error.status === 403 ? 'forbidden' : 'failed');
      }
    };
    void load();
    const removeExpiredHandler = authSession.events.addAccessTokenExpired(() => {
      setUser(null);
      setCatalogState('signed-out');
    });
    return () => {
      active = false;
      removeExpiredHandler();
    };
  }, [api, authSession]);

  useEffect(() => {
    if (!user || !selectedCapabilityId) {
      setReleases([]);
      setReleaseState('idle');
      setSelectedReleaseId(null);
      return;
    }
    let active = true;
    setReleaseState('loading');
    setReleaseError(null);
    setSelectedReleaseId(null);
    api.listReleases(user.access_token, selectedCapabilityId)
      .then((nextReleases) => {
        if (!active) return;
        setReleases(nextReleases);
        setReleaseState('ready');
      })
      .catch((error: unknown) => {
        if (!active) return;
        setReleaseError(error instanceof Error ? error : new Error('Unable to load releases.'));
        setReleaseState('failed');
      });
    return () => {
      active = false;
    };
  }, [api, selectedCapabilityId, user]);

  const signIn = () => void authSession.signinRedirect();
  const signOut = () => void authSession.signoutRedirect();

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Release governance</p>
          <h1>Governance Portal</h1>
        </div>
        {user ? (
          <div className="session-summary">
            <div>
              <strong>{user.profile.sub ?? 'Signed-in user'}</strong>
              <span>{department}</span>
            </div>
            <button type="button" className="button button-secondary" onClick={signOut}>Sign out</button>
          </div>
        ) : null}
      </header>

      {catalogState === 'signed-out' ? (
        <main className="auth-panel" aria-labelledby="welcome-title">
          <p className="eyebrow">Controlled access</p>
          <h2 id="welcome-title">Sign in to inspect governed releases</h2>
          <p>Your catalog is scoped to the department in your access token.</p>
          <button type="button" className="button button-primary" onClick={signIn}>Sign in</button>
        </main>
      ) : (
        <main className="workspace">
          <aside className="sidebar" aria-labelledby="capabilities-heading">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Department catalog</p>
                <h2 id="capabilities-heading">Capabilities</h2>
              </div>
              {user ? <span className="count-badge">{capabilities.length}</span> : null}
            </div>
            {catalogState === 'loading' ? <p className="status-text" aria-live="polite">Loading catalog...</p> : null}
            {catalogState === 'forbidden' ? <p className="status-text status-error" role="alert">You do not have access to this department.</p> : null}
            {catalogState === 'failed' ? <p className="status-text status-error" role="alert">{catalogError?.message ?? 'Unable to load the catalog.'}</p> : null}
            {catalogState === 'ready' && capabilities.length === 0 ? <p className="status-text">No capabilities are registered for your department.</p> : null}
            {capabilities.length > 0 ? (
              <nav aria-label="Capability list">
                <ul className="capability-list">
                  {capabilities.map((capability) => (
                    <li key={capability.id}>
                      <button
                        type="button"
                        className={`capability-item${selectedCapabilityId === capability.id ? ' is-selected' : ''}`}
                        aria-current={selectedCapabilityId === capability.id ? 'true' : undefined}
                        onClick={() => setSelectedCapabilityId(capability.id)}
                      >
                        <span>{displayName(capability)}</span>
                        <small>{capability.type ?? 'Capability'}</small>
                      </button>
                    </li>
                  ))}
                </ul>
              </nav>
            ) : null}
          </aside>

          <section className="content-panel" aria-labelledby="releases-heading">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Immutable artifacts</p>
                <h2 id="releases-heading">{selectedCapability ? displayName(selectedCapability) : 'Releases'}</h2>
              </div>
              {selectedCapability ? <span className="department-label">{selectedCapability.department}</span> : null}
            </div>
            {!selectedCapability ? <p className="status-text">Select a capability to inspect its releases.</p> : null}
            {releaseState === 'loading' ? <p className="status-text" aria-live="polite">Loading releases...</p> : null}
            {releaseState === 'failed' ? <p className="status-text status-error" role="alert">{releaseError?.message ?? 'Unable to load releases.'}</p> : null}
            {releaseState === 'ready' && releases.length === 0 ? <p className="status-text">No releases are registered for this capability.</p> : null}
            {releases.length > 0 ? (
              <>
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>Version</th><th>State</th><th>Digest</th></tr></thead>
                    <tbody>
                      {releases.map((release) => (
                        <tr key={release.id} className={selectedRelease?.id === release.id ? 'is-selected' : undefined}>
                          <td><button type="button" className="table-link" onClick={() => setSelectedReleaseId(release.id)}>{release.version}</button></td>
                          <td><span className={`state state-${release.state.toLowerCase()}`}>{stateLabels[release.state] ?? release.state}</span></td>
                          <td><code title={release.digest}>{shortDigest(release.digest)}</code></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {selectedRelease ? (
                  <article className="release-detail" aria-labelledby="release-detail-heading">
                    <div className="section-heading">
                      <div>
                        <p className="eyebrow">Release detail</p>
                        <h3 id="release-detail-heading">{selectedRelease.id}</h3>
                      </div>
                      <span className={`state state-${selectedRelease.state.toLowerCase()}`}>{stateLabels[selectedRelease.state] ?? selectedRelease.state}</span>
                    </div>
                    <dl className="detail-list">
                      <div><dt>Digest</dt><dd><code>{selectedRelease.digest}</code></dd></div>
                      {selectedRelease.artifactReference ? <div><dt>Artifact reference</dt><dd><code>{selectedRelease.artifactReference}</code></dd></div> : null}
                    </dl>
                  </article>
                ) : null}
              </>
            ) : null}
          </section>
        </main>
      )}
    </div>
  );
}
