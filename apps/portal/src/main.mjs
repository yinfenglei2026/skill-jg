import { toReleaseViewModel } from './release-view-model.mjs';
const release = toReleaseViewModel({
  capabilityId: 'support-agent',
  version: '1.0.0',
  digest: `sha256:0f${'2'.repeat(62)}`,
  state: 'PUBLISHED'
});

const releaseElement = document.querySelector('[data-release]');
releaseElement.innerHTML = `
  <h2>${release.title}</h2>
  <p><strong>State:</strong> ${release.statusLabel}</p>
  <code>${release.digest}</code>
`;

document.querySelector('[data-audit]').textContent = 'Audit events are available after portal sign-in.';
