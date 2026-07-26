import { describe, expect, it } from 'vitest';
import { toReleaseViewModel } from '../src/release-view-model';

describe('release view model', () => {
  it('keeps the immutable digest visible and marks a published release deployable', () => {
    const digest = `sha256:0f${'2'.repeat(62)}`;
    expect(toReleaseViewModel({ capabilityId: 'support-agent', version: '1.0.0', digest, state: 'PUBLISHED' })).toEqual({
      capabilityId: 'support-agent',
      version: '1.0.0',
      digest,
      state: 'PUBLISHED',
      title: 'support-agent@1.0.0',
      statusLabel: 'Published and deployable'
    });
  });
});
