import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App, type AuthSession } from './App';
import { portalConfig } from './config';
import { createGovernanceApi } from './governance-api';
import { createOidcSession } from './oidc';
import './styles.css';

function PortalRoot() {
  try {
    const config = {
      apiBaseUrl: portalConfig.apiBaseUrl,
      authority: portalConfig.authority,
      clientId: portalConfig.clientId,
      redirectUri: portalConfig.redirectUri
    };
    return <App authSession={createOidcSession(config) as AuthSession} api={createGovernanceApi(config.apiBaseUrl)} />;
  } catch (error) {
    return (
      <main className="auth-panel" aria-labelledby="configuration-error-title">
        <p className="eyebrow">Portal configuration</p>
        <h1 id="configuration-error-title">Portal unavailable</h1>
        <p>{error instanceof Error ? error.message : 'Required portal configuration is missing.'}</p>
      </main>
    );
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PortalRoot />
  </StrictMode>
);
