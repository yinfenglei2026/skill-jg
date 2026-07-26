import {
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings
} from 'oidc-client-ts';
import { portalConfig, type PortalConfig } from './config';

export type OidcSession = Pick<
  UserManager,
  'getUser' | 'signinRedirect' | 'signinRedirectCallback' | 'signoutRedirect'
> & {
  events: Pick<UserManager['events'], 'addAccessTokenExpired' | 'removeAccessTokenExpired'>;
};

export type PortalUser = User;

export function createOidcSession(config: PortalConfig = portalConfig): OidcSession {
  const settings: UserManagerSettings = {
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    post_logout_redirect_uri: config.redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    userStore: new WebStorageStateStore({ store: window.sessionStorage })
  };
  return new UserManager(settings);
}
