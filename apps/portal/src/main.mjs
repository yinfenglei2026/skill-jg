import { toReleaseViewModel } from './release-view-model.mjs';

const apiBase = window.GOVERNANCE_API_BASE ?? 'http://localhost:8080/api/v1';
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

async function loadAuditEvents() {
  const target = document.querySelector('[data-audit]');
  try {
    const response = await fetch(`${apiBase}/audit-events`);
    if (!response.ok) {
      throw new Error(`Audit API returned ${response.status}`);
    }
    const events = await response.json();
    target.replaceChildren(...events.map((event) => {
      const item = document.createElement('li');
      item.textContent = `${event.action} ${event.subject} ${event.digest ?? ''}`.trim();
      return item;
    }));
  } catch (error) {
    target.textContent = `Audit events unavailable: ${error.message}`;
  }
}

loadAuditEvents();
