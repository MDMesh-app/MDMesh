import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  normalize,
  parseInline,
  parseNotes,
  releaseSection,
  releaseVersions,
  safeHref,
  unreleasedSection,
  type Block,
  type ListItem,
} from './changelog';

const ROOT = resolve(import.meta.dirname, '../../..');
const CHANGELOG = readFileSync(resolve(ROOT, 'CHANGELOG.md'), 'utf8');

const sample = [
  '# Changelog',
  '',
  '## Unreleased',
  '',
  '<!-- pending #120 -->',
  'New',
  '- Something coming.',
  '',
  '## v0.3.10 — 2026-11-04',
  '',
  'Fixes',
  '- Ten.   ',
  '',
  '',
  '- Still ten.',
  '',
  '## v0.3.1 — 2026-09-25',
  '',
  'Fixes',
  '- One.',
  '',
  '### Not a section heading',
  '- Still one.',
  '## v0.3.0',
  '',
  '## Older releases',
  '',
  'See GitHub.',
].join('\n');

describe('the section of a release, as release/changelog-section.sh cuts it', () => {
  it('gives every released section of CHANGELOG.md exactly as the script prints it', () => {
    const versions = releaseVersions(CHANGELOG);
    expect(versions.length).toBeGreaterThan(5);
    for (const v of versions) {
      const script = execFileSync('bash', [resolve(ROOT, 'release/changelog-section.sh'), v], { encoding: 'utf8' });
      expect(`v${v}\n\n${releaseSection(CHANGELOG, v)!.body}\n`).toBe(script);
    }
  });

  it('matches the version exactly: v0.3.1 is not v0.3.10', () => {
    expect(releaseSection(sample, '0.3.1')?.body).toBe('Fixes\n- One.\n\n### Not a section heading\n- Still one.');
    expect(releaseSection(sample, 'v0.3.10')).toEqual({ version: '0.3.10', date: '2026-11-04', body: 'Fixes\n- Ten.\n\n- Still ten.' });
  });

  it('gives nothing where the script refuses: no such section, an empty one, two of them, an HTML comment', () => {
    expect(releaseSection(sample, '0.2.9')).toBeNull();
    expect(releaseSection(sample, '0.3.0')).toBeNull();
    expect(releaseSection(`${sample}\n## v0.3.1\n- Again.`, '0.3.1')).toBeNull();
    expect(releaseSection('## v1.0.0\n- a\n<!-- pending -->\n', '1.0.0')).toBeNull();
    expect(releaseSection(sample, 'latest')).toBeNull();
    expect(releaseVersions(sample)).toEqual(['0.3.10', '0.3.1']);
  });

  it('reads CRLF files as the script does', () => {
    expect(releaseSection('## v1.0.0 — 2026-01-02\r\n\r\nNew\r\n- a  \r\n', '1.0.0')?.body).toBe('New\n- a');
  });

  it('gives Unreleased without its pending markers, and nothing when it is empty', () => {
    expect(unreleasedSection(sample)).toEqual({ version: null, date: null, body: 'New\n- Something coming.' });
    expect(unreleasedSection('## Unreleased\n\n<!-- pending -->\n\n## v1.0.0\n- a')).toBeNull();
    expect(unreleasedSection('## v1.0.0\n- a')).toBeNull();
  });

  it('normalizes as the script’s norm does', () => {
    expect(normalize('\n\n a \t\r\n\n\n\nb\n\n')).toBe(' a\n\nb');
  });
});

const texts = (item: ListItem) => item.parts.map((p) => (p.kind === 'text' ? p.text : `[${p.items.length} items]`));
const list = (b: Block | undefined) => {
  if (b?.kind !== 'list') throw new Error(`not a list: ${JSON.stringify(b)}`);
  return b.items;
};

describe('a section’s text as blocks', () => {
  it('puts the sections in the dialog’s order: New, Heads-up, Upgrading, Fixes, any other, Under the hood', () => {
    const notes = parseNotes(
      ['Upgrading', '- Update.', '', 'Heads-up', '- Careful.', '', 'Under the hood', '- Tests.', '', 'Security', '- Safe.', '', 'Fixes', '- Fixed.', '', 'New (agent 0.3.4)', '- Shiny.'].join('\n'),
    );
    expect(notes.sections.map((s) => [s.kind, s.title])).toEqual([
      ['new', 'New (agent 0.3.4)'],
      ['headsUp', 'Heads-up'],
      ['upgrading', 'Upgrading'],
      ['fixes', 'Fixes'],
      ['other', 'Security'],
      ['underTheHood', 'Under the hood'],
    ]);
    expect(texts(list(notes.sections[0].blocks[0])[0])).toEqual(['Shiny.']);
  });

  it('joins a bullet’s wrapped lines, and nests an indented bullet under its item, with the text after it', () => {
    const notes = parseNotes(
      [
        'Heads-up',
        '- It also hides the buttons the server',
        '  would refuse:',
        '  - `edit_devices`: device actions and kiosk, Sync',
        '    now.',
        '  - `settings`: Check now.',
        '  Every role still sees what it saw before.',
        '- Second.',
      ].join('\n'),
    );
    const items = list(notes.sections[0].blocks[0]);
    expect(items).toHaveLength(2);
    expect(texts(items[0])).toEqual(['It also hides the buttons the server would refuse:', '[2 items]', 'Every role still sees what it saw before.']);
    const nested = items[0].parts[1];
    expect(nested.kind === 'list' && nested.items.map(texts)).toEqual([['`edit_devices`: device actions and kiosk, Sync now.'], ['`settings`: Check now.']]);
    expect(texts(items[1])).toEqual(['Second.']);
  });

  it('keeps an opening line before the sections, a "Label: text" section, and closing thanks after them', () => {
    const notes = parseNotes(
      [
        'A follow-up security release. Please',
        'update every server.',
        '',
        'Fixes',
        '- Fixed.',
        '',
        'Under the hood: agent protocol 1.3 (additive), Node 24',
        'across the build.',
        '',
        'Thanks @someone for the report.',
      ].join('\n'),
    );
    expect(notes.intro).toEqual([{ kind: 'para', text: 'A follow-up security release. Please update every server.' }]);
    expect(notes.sections.map((s) => s.title)).toEqual(['Fixes', 'Under the hood']);
    expect(notes.sections[1].blocks).toEqual([{ kind: 'para', text: 'Agent protocol 1.3 (additive), Node 24 across the build.' }]);
    expect(notes.outro).toEqual([{ kind: 'para', text: 'Thanks @someone for the report.' }]);
  });

  it('keeps a paragraph inside its section when another section follows it', () => {
    const notes = parseNotes(['Upgrading', '- Do X.', '', 'Back up first, then:', '- step two', '', 'Fixes', '- f', '', 'Thanks @a.'].join('\n'));
    expect(notes.sections[0].title).toBe('Upgrading');
    expect(notes.sections[0].blocks).toHaveLength(3);
    expect(notes.sections[0].blocks[1]).toEqual({ kind: 'para', text: 'Back up first, then:' });
    expect(notes.sections[1].blocks).toHaveLength(1);
    expect(notes.outro).toEqual([{ kind: 'para', text: 'Thanks @a.' }]);
  });

  it('reads a word that only starts like a heading as text', () => {
    const notes = parseNotes('New installs get a random PIN.\n\nFixes\n- Fixed.');
    expect(notes.intro).toEqual([{ kind: 'para', text: 'New installs get a random PIN.' }]);
  });

  it('parses every released section of CHANGELOG.md and Unreleased into at least one block', () => {
    for (const v of releaseVersions(CHANGELOG)) {
      const n = parseNotes(releaseSection(CHANGELOG, v)!.body);
      expect(n.intro.length + n.sections.length + n.outro.length, v).toBeGreaterThan(0);
    }
  });
});

describe('inline marks', () => {
  it('reads `code`, **bold** and links, and leaves unmatched marks as written', () => {
    expect(parseInline('Set `LEGACY_DEVICE_API=1` **now**, see [the notes](https://github.com/MDMesh-app/MDMesh/releases).')).toEqual([
      { kind: 'text', text: 'Set ' },
      { kind: 'code', text: 'LEGACY_DEVICE_API=1' },
      { kind: 'text', text: ' ' },
      { kind: 'strong', children: [{ kind: 'text', text: 'now' }] },
      { kind: 'text', text: ', see ' },
      { kind: 'link', href: 'https://github.com/MDMesh-app/MDMesh/releases', internal: false, children: [{ kind: 'text', text: 'the notes' }] },
      { kind: 'text', text: '.' },
    ]);
    expect(parseInline('a ` b ** c [d](')).toEqual([{ kind: 'text', text: 'a ` b ** c [d](' }]);
    expect(parseInline('**`x` y**')).toEqual([{ kind: 'strong', children: [{ kind: 'code', text: 'x' }, { kind: 'text', text: ' y' }] }]);
  });

  it('keeps only http(s) and console paths as links; any other address shows its label as text', () => {
    expect(parseInline('[Updates](/settings#updates)')).toEqual([
      { kind: 'link', href: '/settings#updates', internal: true, children: [{ kind: 'text', text: 'Updates' }] },
    ]);
    for (const bad of ['javascript:alert`1`', 'JAVASCRIPT:void0', 'data:text/html,x', '//evil.example/x', 'RELEASING.md', 'mailto:a@b.c', 'vbscript:x']) {
      expect(parseInline(`[click](${bad})`), bad).toEqual([{ kind: 'text', text: 'click' }]);
    }
    // An address the link syntax doesn't take (parentheses in it) stays text as written, never a link.
    expect(parseInline('[click](javascript:alert(1))').some((n) => n.kind === 'link')).toBe(false);
    expect(safeHref('https://example.com/a b')).toBeNull();
    expect(safeHref('/\\evil.example')).toBeNull();
    expect(safeHref('http://example.com')).toEqual({ href: 'http://example.com/', internal: false });
  });

  it('never turns text into markup: angle brackets stay text', () => {
    expect(parseInline('<img src=x onerror=alert(1)>')).toEqual([{ kind: 'text', text: '<img src=x onerror=alert(1)>' }]);
  });
});
