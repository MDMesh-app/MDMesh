import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { releaseSection, releaseVersions } from '../whatsnew/changelog';
import { entrySize, parseReleaseEntries } from './releaseNotes';

const CHANGELOG = readFileSync(resolve(import.meta.dirname, '../../../CHANGELOG.md'), 'utf8');
/** A release's GitHub notes as the supervisor hands them over: the title line, then its CHANGELOG section. */
const releaseBody = (v: string) => `v${v}\n\n${releaseSection(CHANGELOG, v)!.body}\n`;

describe('parseReleaseEntries', () => {
  it('reads a release body as one entry: the title line goes, the opening line is the headline, a Security section marks it', () => {
    const [e, ...rest] = parseReleaseEntries(
      ['v0.1.15', '', 'Faster moves.', '', 'New', '- A', '- B', '', 'Security', '- Sessions end on sign-out.'].join('\n'),
      '0.1.15',
      '2026-11-09T10:00:00Z',
    );
    expect(rest).toEqual([]);
    expect(e.version).toBe('0.1.15');
    expect(e.date).toBe('2026-11-09T10:00:00Z');
    expect(e.headline).toBe('Faster moves.');
    expect(e.groups.map((g) => g.title)).toEqual(['New', 'Security']);
    expect(e.security).toBe(true);
    expect(entrySize(e)).toBe(3);
  });

  it('splits several releases under their headings, newest first, each with its own date', () => {
    const entries = parseReleaseEntries(
      ['## v0.3.6 — 2026-10-01', 'Fixes', '- Old fix.', '', '## v0.3.7 — 2026-10-22', 'New', '- New thing.'].join('\n'),
      '0.3.7',
    );
    expect(entries.map((e) => [e.version, e.date])).toEqual([
      ['0.3.7', '2026-10-22'],
      ['0.3.6', '2026-10-01'],
    ]);
    expect(entries.every((e) => !e.security)).toBe(true);
  });

  it('marks a release only for its Security section, written either way; nothing else counts', () => {
    expect(parseReleaseEntries('### Security\n- Sessions end on sign-out.', '1.0.0')[0].security).toBe(true);
    expect(parseReleaseEntries('Security (agent 0.5.0)\n- A fix.', '1.0.0')[0].security).toBe(true);
    expect(parseReleaseEntries('Fixes\n- Patches CVE-2026-1234 in the security check.', '1.0.0')[0].security).toBe(false);
    expect(parseReleaseEntries('  ', '1.0.0')).toEqual([]);
  });

  it('does not mark v0.3.2, whose notes have no Security section (and which fixed no security issue)', () => {
    const entries = parseReleaseEntries(releaseBody('0.3.2'), '0.3.2');
    expect(entries.map((e) => e.version)).toEqual(['0.3.2']);
    expect(entries[0].security).toBe(false);
    expect(entries[0].groups.length).toBeGreaterThan(2);
  });

  it('takes the summary from the opening line only, never a closing thank-you', () => {
    const [e] = parseReleaseEntries('Fixes\n- A fix.\n\nThanks @someone for the report.', '1.0.0');
    expect(e.headline).toBeNull();
    expect(e.intro).toEqual([{ kind: 'para', text: 'Thanks @someone for the report.' }]);
  });

  it('keeps notes that name no version, and one entry for a release whose heading follows its preamble', () => {
    expect(parseReleaseEntries('Fixes\n- A fix.', null).map((e) => e.version)).toEqual([null]);
    const entries = parseReleaseEntries('Intro line.\n\n## v1.0.0\nFixes\n- A fix.', '1.0.0');
    expect(entries.map((e) => e.version)).toEqual(['1.0.0']);
    expect(entries[0].headline).toBe('Intro line.');
  });

  it('starts a new entry only at a release heading, not a sub-heading that opens with a version', () => {
    const entries = parseReleaseEntries('New\n- A.\n\n### 0.5.0 database changes\n- B.', '0.5.0');
    expect(entries).toHaveLength(1);
    // …and it reads as its text, without the marks.
    expect(JSON.stringify(entries[0])).toContain('0.5.0 database changes');
    expect(JSON.stringify(entries[0])).not.toContain('###');
  });
});

describe('Security releases (the signed manifest’s securityReleases)', () => {
  it('are the versions whose notes this console labels "Security fixes", as release/security-releases.py lists them', () => {
    const labelled = releaseVersions(CHANGELOG)
      .filter((v) => parseReleaseEntries(releaseBody(v), v)[0]?.security)
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    const script = resolve(import.meta.dirname, '../../../release/security-releases.py');
    const derived = JSON.parse(execFileSync('python3', [script, resolve(import.meta.dirname, '../../../CHANGELOG.md')], { encoding: 'utf8' }));
    expect(labelled.length).toBeGreaterThan(0);
    expect(derived).toEqual(labelled);
  });
  it('are exactly these: a release that gains or loses its Security section changes the signed record on purpose', () => {
    const script = resolve(import.meta.dirname, '../../../release/security-releases.py');
    const derived = JSON.parse(execFileSync('python3', [script, resolve(import.meta.dirname, '../../../CHANGELOG.md')], { encoding: 'utf8' }));
    // Update this list together with CHANGELOG.md.
    expect(derived).toEqual(['0.3.1', '0.3.3', '0.3.4', '0.3.5', '0.3.6', '0.3.7', '0.3.8', '0.3.9', '0.4.0', '0.5.0', '0.5.1', '0.6.0']);
  });
  it('can’t diverge over fenced code: the script skips it, the console’s parser doesn’t read it, CHANGELOG.md has none', () => {
    expect(CHANGELOG).not.toMatch(/^\s{0,3}(```|~~~)/m);
  });
});
