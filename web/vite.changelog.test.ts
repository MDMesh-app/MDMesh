// The What's new notes plugin: a production build without CHANGELOG.md fails, a dev server or test run only warns.
// node:url's URL: under jsdom the global one isn't the URL fileURLToPath takes.
import { URL as NodeURL, fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';
import { changelogNotes } from './vite.changelog.ts';

const INDEX = '\0virtual:mdmesh-changelog';

/** Load the plugin's index module as a build (`production`) or the dev server would, reading `file`. */
function load(file: string, production: boolean) {
  const plugin = changelogNotes(undefined, file);
  (plugin.configResolved as unknown as (c: { isProduction: boolean }) => void)({ isProduction: production });
  const ctx = {
    warn: vi.fn(),
    error: (message: string): never => {
      throw new Error(message);
    },
    addWatchFile: vi.fn(),
  };
  // The plugin defines both hooks as plain functions (not the { handler } form).
  const out = (plugin.load as unknown as (this: typeof ctx, id: string) => string | null).call(ctx, INDEX);
  return { out, ctx };
}

describe('changelogNotes', () => {
  // Nothing is written: a path that isn't there, and the repository's own CHANGELOG.md.
  const missing = fileURLToPath(new NodeURL('./no-such-dir/CHANGELOG.md', import.meta.url));
  const real = fileURLToPath(new NodeURL('../CHANGELOG.md', import.meta.url));

  it('fails a production build when CHANGELOG.md is missing', () => {
    expect(() => load(missing, true)).toThrow(/CHANGELOG\.md not found: a production build needs it/);
  });

  it('only warns in a dev server or test run', () => {
    const { out, ctx } = load(missing, false);
    expect(ctx.warn).toHaveBeenCalledWith(expect.stringContaining('not found'));
    expect(out).toContain('export const versions = [];');
  });

  it('builds the versions from a CHANGELOG.md that is there', () => {
    const { out, ctx } = load(real, true);
    expect(ctx.warn).not.toHaveBeenCalled();
    expect(out).toMatch(/export const versions = \[.*"0\.3\.7"/);
  });
});
