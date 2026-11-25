// The console's licence files and policy check (vite.licences.ts): what a build ships under /licenses/, and which
// bundled packages fail it.
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';
import { THIRD_PARTY, licences, readAllowed, violations } from './vite.licences.ts';

const repo = (path: string) => join(dirname(fileURLToPath(import.meta.url)), '..', path);
const FILES = { licence: repo('LICENSE'), notice: repo('NOTICE'), allowed: repo('docs/licenses/allowed.txt') };
const ALLOWED = ['Apache-2.0', 'MIT', 'BSD-2-Clause'];

/** Runs the plugin's generateBundle over a bundle holding `packages` as Vite's THIRD-PARTY.json. */
function generate(packages: unknown[] | null, enforce: boolean, files = FILES, assets: string[] = []) {
  const plugin = licences(files, enforce);
  const bundle: Record<string, unknown> =
    packages === null ? {} : { [THIRD_PARTY]: { type: 'asset', source: JSON.stringify(packages), originalFileNames: [] } };
  if (assets.length > 0) bundle['assets/a.woff2'] = { type: 'asset', source: '', originalFileNames: assets };
  const ctx = {
    emitFile: vi.fn(),
    warn: vi.fn(),
    error: (message: string): never => {
      throw new Error(message);
    },
  };
  const hook = plugin.generateBundle as unknown as { handler: (this: typeof ctx, o: unknown, b: unknown) => void };
  hook.handler.call(ctx, {}, bundle);
  return { ...ctx, bundle };
}

describe('violations', () => {
  it('passes a package whose licence, or one of its choices, is allowed', () => {
    expect(
      violations(
        [
          { name: 'react', version: '19.3.1', identifier: 'MIT' },
          { name: 'spark-md5', version: '3.0.2', identifier: '(WTFPL OR MIT)' },
        ],
        ALLOWED,
      ),
    ).toEqual([]);
  });

  it('names a package under another licence, under one that needs two, or with none', () => {
    expect(
      violations(
        [
          { name: 'gpl-lib', version: '1.0.0', identifier: 'GPL-3.0-only' },
          { name: 'both', version: '1.0.0', identifier: 'MIT AND GPL-2.0-only' },
          { name: 'bare', version: '1.0.0' },
          { name: 'odd', version: '1.0.0', identifier: 'SEE LICENSE IN LICENSE.md' },
        ],
        ALLOWED,
      ),
    ).toEqual([
      'gpl-lib@1.0.0: GPL-3.0-only',
      'both@1.0.0: MIT AND GPL-2.0-only',
      'bare@1.0.0: no licence in its package.json',
      'odd@1.0.0: SEE LICENSE IN LICENSE.md',
    ]);
  });
});

describe('readAllowed', () => {
  it('reads the SPDX ids of docs/licenses/allowed.txt, without its comments', () => {
    const allowed = readAllowed(FILES.allowed);
    expect(allowed).toContain('Apache-2.0');
    expect(allowed).toContain('MIT');
    expect(allowed.some((id) => id.includes('#') || id.includes(' ') || /GPL/.test(id))).toBe(false);
  });
});

describe('licences', () => {
  it('ships LICENSE and NOTICE next to the third-party list', () => {
    const ctx = generate([{ name: 'react', version: '19.3.1', identifier: 'MIT' }], true);
    const emitted = ctx.emitFile.mock.calls.map(([f]) => f as { fileName: string; source: string });
    expect(emitted.map((f) => f.fileName)).toEqual(['licenses/LICENSE.txt', 'licenses/NOTICE.txt']);
    expect(emitted[0].source).toContain('Apache License');
    expect(emitted[1].source).toContain('Headwind MDM');
    expect(ctx.warn).not.toHaveBeenCalled();
  });

  it('adds the packages whose files CSS loads to the third-party list, once each, with their licence text', () => {
    const font = (name: string) => `node_modules/@fontsource/${name}/files/${name}-latin-400-normal.woff2`;
    const { bundle } = generate([{ name: 'leaflet', version: '1.9.4', identifier: 'MIT' }], true, FILES, [
      font('roboto'),
      font('roboto'),
      font('ibm-plex-mono'),
      'node_modules/leaflet/dist/images/layers.png',
      'src/assets/logo.png',
    ]);
    const list = JSON.parse((bundle[THIRD_PARTY] as { source: string }).source) as Record<string, string>[];
    expect(list.map((p) => `${p.name} ${p.identifier}`)).toEqual([
      'leaflet MIT',
      '@fontsource/roboto OFL-1.1',
      '@fontsource/ibm-plex-mono OFL-1.1',
    ]);
    expect(list[1].text).toContain('SIL OPEN FONT LICENSE Version 1.1');
  });

  it('fails the build in CI when a bundled package is under a licence the list does not allow', () => {
    expect(() => generate([{ name: 'gpl-lib', version: '1.0.0', identifier: 'GPL-3.0-only' }], true)).toThrow(
      /gpl-lib@1\.0\.0: GPL-3\.0-only/,
    );
  });

  it('only warns outside CI', () => {
    const ctx = generate([{ name: 'gpl-lib', version: '1.0.0', identifier: 'GPL-3.0-only' }], false);
    expect(ctx.warn).toHaveBeenCalledWith(expect.stringContaining('gpl-lib@1.0.0: GPL-3.0-only'));
  });

  it('fails without the third-party list, or without NOTICE', () => {
    expect(() => generate(null, false)).toThrow(/vite.config.ts must set build.license/);
    expect(() => generate([], false, { ...FILES, notice: repo('no-such-dir/NOTICE') })).toThrow(/NOTICE not found/);
  });
});
