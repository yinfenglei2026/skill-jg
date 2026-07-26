export type ReleaseViewInput = {
  capabilityId: string;
  version: string;
  digest: string;
  state: string;
};

export type ReleaseViewModel = ReleaseViewInput & {
  title: string;
  statusLabel: string;
};

const labels: Record<string, string> = {
  DRAFT: 'Draft',
  VALIDATING: 'Validation running',
  REVIEW_REQUIRED: 'Review required',
  APPROVED: 'Approved',
  PUBLISHED: 'Published and deployable',
  DEPLOYING: 'Deployment pending',
  DEPLOYED: 'Deployed',
  DEGRADED: 'Degraded',
  FAILED: 'Failed',
  REJECTED: 'Rejected',
  REVOKED: 'Revoked'
};

export function toReleaseViewModel(release: ReleaseViewInput): ReleaseViewModel {
  return {
    ...release,
    title: `${release.capabilityId}@${release.version}`,
    statusLabel: labels[release.state] ?? 'Unknown state'
  };
}
