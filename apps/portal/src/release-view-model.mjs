const labels = {
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

export function toReleaseViewModel(release) {
  return {
    title: `${release.capabilityId}@${release.version}`,
    digest: release.digest,
    state: release.state,
    statusLabel: labels[release.state] ?? 'Unknown state'
  };
}
