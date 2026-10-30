// The build check for v0.3 console files a fork's merge of 0.4.0 left in the tree (check-v03-files.ts).
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { describeV03Files, findV03Files, V03_FILES } from './check-v03-files.ts';

const WEB = dirname(fileURLToPath(import.meta.url));
const SCRIPT = join(WEB, 'check-v03-files.ts');

let scratch: string | null = null;
afterEach(() => {
  if (scratch) rmSync(scratch, { recursive: true, force: true });
  scratch = null;
});

/** A scratch web/ with the check and the given files under src/. */
function scratchWeb(files: string[]): string {
  scratch = mkdtempSync(join(tmpdir(), 'mdmesh-v03-files-'));
  copyFileSync(SCRIPT, join(scratch, 'check-v03-files.ts'));
  for (const f of files) {
    mkdirSync(dirname(join(scratch, 'src', f)), { recursive: true });
    writeFileSync(join(scratch, 'src', f), '/* a fork kept this */\n');
  }
  return scratch;
}

const run = (web: string) => spawnSync(process.execPath, [join(web, 'check-v03-files.ts')], { encoding: 'utf8' });
/** The check's own output (an older Node 24 adds a type-stripping warning before it). */
const said = (stderr: string) => stderr.slice(Math.max(0, stderr.indexOf('These v0.3'))).trim();

describe('v0.3 console files check', () => {
  it('finds none in this tree', () => {
    // On failure this shows the build's message: each file left, and where its content belongs now.
    expect(describeV03Files(findV03Files(WEB))).toBeNull();
  });

  it('points each v0.3 file at files that exist in this tree', () => {
    for (const [file, where] of Object.entries(V03_FILES)) {
      const paths = where.match(/[\w./-]+\.(?:tsx?|css)\b|[\w-]+\/[\w/-]*\//g) ?? [];
      if (file !== 'styles.css') expect(paths.length, file).toBeGreaterThan(0);
      for (const p of paths) expect(existsSync(join(WEB, 'src', p)), `${file} → ${p}`).toBe(true);
    }
  });

  it('passes the build with none left', () => {
    const r = run(scratchWeb(['pages/OverviewPage.tsx', 'ui/LocationMap.tsx']));
    expect(r.stderr).not.toContain('v0.3');
    expect(r.status).toBe(0);
  });

  it('fails the build with one line per file left, saying where it belongs', () => {
    const r = run(scratchWeb(['styles.css', 'components/TelemetryCard.tsx', 'ui/status.ts']));
    expect(r.status).toBe(1);
    const lines = said(r.stderr).split('\n');
    expect(lines.slice(0, 2).join(' ')).toContain('(see docs/UPGRADING.md, v0.4.0 for forks)');
    expect(lines.slice(2)).toEqual([
      `- web/src/styles.css: now ${V03_FILES['styles.css']}`,
      '- web/src/components/TelemetryCard.tsx: now pages/DeviceDetailTabs.tsx (Telemetry tab); add a tab with device.tab',
      '- web/src/ui/status.ts: now data/presence.ts (isOnline) and ui/Status.tsx (status pills)',
    ]);
  });

  it('says nothing when there is nothing to say', () => {
    expect(describeV03Files([])).toBeNull();
  });
});
