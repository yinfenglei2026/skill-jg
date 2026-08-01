import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('portal package scripts', () => {
  it('invokes Node entry points without platform-specific bin shims', () => {
    const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as {
      scripts: Record<string, string>;
    };

    expect(packageJson.scripts.test).toBe('node node_modules/vitest/vitest.mjs run');
    expect(packageJson.scripts.build).toBe(
      'node node_modules/typescript/bin/tsc --noEmit && node node_modules/vite/bin/vite.js build'
    );
  });
});
