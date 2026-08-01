import { describe, expect, it } from 'vitest';
import { actionsFor, normalizeRoles } from '../src/release-actions';

describe('release action policy', () => {
  it('normalizes Keycloak and flat role claims', () => {
    expect(normalizeRoles({
      realm_access: { roles: ['reviewer', 'read-only'] },
      roles: ['REVIEWER', 'operator']
    })).toEqual(['REVIEWER', 'READ_ONLY', 'OPERATOR']);
  });

  it('offers the reviewer actions supported by each review state', () => {
    expect(actionsFor(['REVIEWER'], 'DRAFT')).toEqual([
      { id: 'validate', label: 'Validate', kind: 'transition' }
    ]);
    expect(actionsFor(['REVIEWER'], 'VALIDATING')).toEqual([
      { id: 'review-required', label: 'Require review', kind: 'transition' },
      { id: 'reject', label: 'Reject', kind: 'transition' }
    ]);
  });

  it('offers approval only for an approver reviewing an immutable candidate', () => {
    expect(actionsFor(['APPROVER'], 'REVIEW_REQUIRED')).toEqual([
      { id: 'approve', label: 'Approve', kind: 'transition' }
    ]);
    expect(actionsFor(['APPROVER'], 'DRAFT')).toEqual([]);
  });

  it('offers publication and deployment only to operators in eligible states', () => {
    expect(actionsFor(['OPERATOR'], 'APPROVED')).toEqual([
      { id: 'publish', label: 'Publish', kind: 'transition' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ]);
    expect(actionsFor(['OPERATOR'], 'PUBLISHED')).toEqual([
      { id: 'deploy', label: 'Deploy', kind: 'deployment' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ]);
  });

  it('offers retry and revocation for recoverable deployment states', () => {
    expect(actionsFor(['OPERATOR'], 'FAILED')).toEqual([
      { id: 'deploy', label: 'Retry deployment', kind: 'deployment' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ]);
    expect(actionsFor(['OPERATOR'], 'DEGRADED')).toEqual([
      { id: 'deploy', label: 'Retry deployment', kind: 'deployment' },
      { id: 'revoke', label: 'Revoke', kind: 'transition' }
    ]);
  });

  it('offers no mutations to read-only or unknown roles', () => {
    expect(actionsFor(['READ_ONLY'], 'PUBLISHED')).toEqual([]);
    expect(actionsFor(['OWNER'], 'DRAFT')).toEqual([]);
    expect(actionsFor([], 'REVIEW_REQUIRED')).toEqual([]);
  });
});
