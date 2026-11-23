// Release notes from the supervisor (`release.notes`) are the GitHub release body: a CHANGELOG.md release section,
// word for word (RELEASING.md), sometimes headed by the version. It may also hold several releases, each under a
// "## v0.3.7 — 2026-10-22" heading as in CHANGELOG.md. This splits it into one entry per release, newest first, and
// reads each with the What's new parser (whatsnew/changelog.ts): plain data the page renders as React text, never as
// HTML, so nothing in the notes can inject markup.

import { parseNotes, type Block, type NotesSection } from '../whatsnew/changelog';

/** A release's notes, grouped for the Updates card. */
export interface ReleaseEntry {
  /** null: notes that name no version, with no version offered either. */
  version: string | null;
  /** "2026-10-22" from the heading, or the release's `publishedAt` for the one the supervisor offers. */
  date: string | null;
  /** The release's opening paragraph, its one-line summary; null when it opens straight into a section. */
  headline: string | null;
  /** Further opening paragraphs, then the closing ones ("Thanks @… for the reports"). */
  intro: Block[];
  /** The sections in the What's new order: New, Heads-up, Upgrading, Fixes, others (Security), Under the hood. */
  groups: NotesSection[];
  /**
   * It has security fixes: its notes have a Security section ("Security", "### Security", "Security (agent 0.5.0)"),
   * which every release with a security fix carries. Nothing else counts: the word alone appears in feature notes
   * ("Wi-Fi security").
   */
  security: boolean;
}

/** A release's own heading, as CHANGELOG.md writes it: the version, then a date or nothing ("### 0.5.0 changes" isn't). */
const RELEASE_HEADING = /^\s{0,3}#{1,6}\s*(?:MDMesh\s+)?v?(\d+\.\d+\.\d+)(?:\s*[—–-]\s*(\d{4}-\d{2}-\d{2}))?\s*$/i;
const TITLE_LINE = /^\s*(?:#{1,6}\s*)?(?:MDMesh\s+)?v?(\d+\.\d+\.\d+)\s*$/i;
/**
 * A markdown heading's marks ("### Fixes", "### Notes"): the What's new parser takes a section's bare label, and any
 * other heading reads as its text, not "### Notes". Release headings are split off before this.
 */
const MD_HEADING = /^\s{0,3}#{1,6}\s+/;

/** A Security section's title ("Security", "Security (agent 0.5.0)"). */
export const isSecurityGroup = (g: NotesSection): boolean => /^Security\b/.test(g.title);

function entry(version: string | null, date: string | null, body: string): ReleaseEntry {
  const notes = parseNotes(body.split('\n').map((l) => l.replace(MD_HEADING, '')).join('\n'));
  // The summary is the opening line only, never a closing one (a release may open straight into a section).
  const first = notes.intro[0];
  const headline = first && first.kind === 'para' ? first.text : null;
  const intro = [...(headline ? notes.intro.slice(1) : notes.intro), ...notes.outro];
  const groups = notes.sections;
  return { version, date, headline, intro, groups, security: groups.some(isSecurityGroup) };
}

interface Part {
  version: string | null;
  date: string | null;
  lines: string[];
}

/**
 * The notes as entries, newest first. `version` and `publishedAt` describe the release on offer: notes without
 * release headings are that release's.
 */
export function parseReleaseEntries(
  raw: string | null | undefined,
  version: string | null | undefined,
  publishedAt?: string | null,
): ReleaseEntry[] {
  if (!raw || !raw.trim()) return [];
  const parts: Part[] = [];
  let cur: Part = { version: null, date: null, lines: [] };
  for (const line of raw.replace(/\r\n?/g, '\n').split('\n')) {
    const h = RELEASE_HEADING.exec(line);
    if (h) {
      if (cur.version || cur.lines.some((l) => l.trim())) parts.push(cur);
      cur = { version: h[1], date: h[2] ?? null, lines: [] };
      continue;
    }
    cur.lines.push(line);
  }
  parts.push(cur);

  const offered = version ? version.replace(/^v/i, '') : null;
  const published = publishedAt && Number.isFinite(Date.parse(publishedAt)) ? publishedAt : null;
  // Each release's text, in order; the same release twice (an unheaded preamble, then its heading) is one entry.
  const bodies: Array<{ version: string | null; date: string | null; lines: string[] }> = [];
  for (const p of parts) {
    let body = p.lines;
    let v = p.version;
    // A title line ("v0.3.3") opening the text names its release; the entry's header says it, so the line goes.
    const firstLine = body.findIndex((l) => l.trim());
    const title = firstLine >= 0 ? TITLE_LINE.exec(body[firstLine]) : null;
    if (title) {
      v = v ?? title[1];
      body = body.slice(firstLine + 1);
    }
    v = v ?? offered;
    if (!body.some((l) => l.trim())) continue;
    const same = bodies.find((b) => b.version === v);
    if (same) {
      same.lines.push('', ...body);
      same.date = same.date ?? p.date;
    } else {
      bodies.push({ version: v, date: p.date, lines: body });
    }
  }
  return bodies
    .map((b) => entry(b.version, b.date ?? (b.version === offered ? published : null), b.lines.join('\n')))
    .sort((a, b) => (a.version && b.version ? compareVersions(b.version, a.version) : 0));
}

/** Orders two X.Y.Z versions: negative when a is older, 0 when equal, positive when newer. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return (pa[i] ?? 0) - (pb[i] ?? 0);
  return 0;
}

/** How many items and paragraphs an entry holds. */
export function entrySize(e: ReleaseEntry): number {
  return (
    e.intro.length +
    e.groups.reduce((n, g) => n + g.blocks.reduce((m, b) => m + (b.kind === 'list' ? b.items.length : 1), 0), 0)
  );
}
