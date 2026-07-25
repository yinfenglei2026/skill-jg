import assert from 'node:assert/strict';
import test from 'node:test';
import { toReleaseViewModel } from '../src/release-view-model.mjs';

test('keeps the immutable digest visible and marks a published release deployable', () => {
  const digest = `sha256:0f${'2'.repeat(62)}`;
  const view = toReleaseViewModel({
    capabilityId: 'support-agent',
    version: '1.0.0',
    digest,
    state: 'PUBLISHED'
  });

  assert.equal(view.title, 'support-agent@1.0.0');
  assert.equal(view.digest, digest);
  assert.equal(view.statusLabel, 'Published and deployable');
});
