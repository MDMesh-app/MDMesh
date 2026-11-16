import { describe, expect, it } from 'vitest';
import { entrySize, parseReleaseEntries } from './releaseNotes';

describe('parseReleaseEntries', () => {
  it('reads a release body as one entry: the title line goes, the opening line is the headline, Security comes first', () => {
    const [e, ...rest] = parseReleaseEntries(
      ['v0.1.15', '', 'Faster moves.', '', 'New', '- A', '- B', '', 'Security', '- Sessions end on sign-out.'].join('\n'),
      '0.1.15',
      '2026-11-09T10:00:00Z',
    );
    expect(rest).toEqual([]);
    expect(e.version).toBe('0.1.15');
    expect(e.date).toBe('2026-11-09T10:00:00Z');
    expect(e.headline).toBe('Faster moves.');
    expect(e.groups.map((g) => g.title)).toEqual(['Security', 'New']);
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

  it('marks a release whose item names a CVE, and returns nothing for empty notes', () => {
    expect(parseReleaseEntries('Fixes\n- Patches CVE-2026-1234.', '1.0.0')[0].security).toBe(true);
    expect(parseReleaseEntries('  ', '1.0.0')).toEqual([]);
  });
});
