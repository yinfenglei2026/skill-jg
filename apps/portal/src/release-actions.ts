import type { ReleaseTransition } from './governance-api';

export type ReleaseAction =
  | { id: ReleaseTransition; label: string; kind: 'transition' }
  | { id: 'deploy'; label: string; kind: 'deployment' };

type RoleProfile = {
  realm_access?: unknown;
  roles?: unknown;
  [claim: string]: unknown;
};

const actionMatrix: Record<string, Record<string, ReleaseAction[]>> = {
  REVIEWER: {
    DRAFT: [{ id: 'validate', label: 'Validate', kind: 'transition' }],
    VALIDATING: [
      { id: 'review-required', label: 'Require review', kind: 'transition' },
      { id: 'reject', label: 'Reject', kind: 'transition' }
    ],
    REVIEW_REQUIRED: [{ id: 'reject', label: 'Reject', kind: 'transition' }]
  },
  APPROVER: {
    REVIEW_REQUIRED: [{ id: 'approve', label: 'Approve', kind: 'transition' }]
  },
  OPERATOR: {
    APPROVED: [
      { id: 'publish', label: 'Publish', kind: 'transition' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ],
    PUBLISHED: [
      { id: 'deploy', label: 'Deploy', kind: 'deployment' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ],
    DEPLOYING: [{ id: 'revoke', label: 'Revoke', kind: 'transition' }],
    DEPLOYED: [{ id: 'revoke', label: 'Revoke', kind: 'transition' }],
    FAILED: [
      { id: 'deploy', label: 'Retry deployment', kind: 'deployment' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ],
    DEGRADED: [
      { id: 'deploy', label: 'Retry deployment', kind: 'deployment' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ]
  }
};

function claimRoles(value: unknown): string[] {
  if (typeof value === 'string') return [value];
  if (!Array.isArray(value)) return [];
  return value.filter((role): role is string => typeof role === 'string');
}

export function normalizeRoles(profile: RoleProfile): string[] {
  const realmAccess = profile.realm_access && typeof profile.realm_access === 'object'
    ? profile.realm_access as { roles?: unknown }
    : undefined;
  const roles = [...claimRoles(realmAccess?.roles), ...claimRoles(profile.roles)];
  return [...new Set(roles.map((role) => role.toUpperCase().replaceAll('-', '_')))];
}

export function actionsFor(roles: string[], state: string): ReleaseAction[] {
  const actions = roles.flatMap((role) => actionMatrix[role]?.[state] ?? []);
  return actions.filter((action, index) => actions.findIndex((candidate) => candidate.id === action.id) === index);
}
