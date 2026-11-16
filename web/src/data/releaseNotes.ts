// Release notes from the supervisor (`release.notes`) are the GitHub release body: a CHANGELOG.md release section,
// word for word (RELEASING.md), sometimes headed by the version. It may also hold several releases, each under a
// "## v0.3.7 — 2026-10-22" heading as in CHANGELOG.md. This splits it into one entry per release, newest first, and
// reads each with the What's new parser (whatsnew/changelog.ts): plain data the page renders as React text, never as
// HTML, so nothing in the notes can inject markup.

import { parseNotes, type Block, type ListItem, type NotesSection } from '../whatsnew/changelog';

/** A release's notes, grouped for the Updates card. */
export interface ReleaseEntry {
  version: string;
  /** "2026-10-22" from the heading, or the release's `publishedAt` for the one the supervisor offers. */
  date: string | null;
  /** The release's opening paragraph, its one-line summary; null when it opens straight into a section. */
  headline: string | null;
  /** Further opening and closing paragraphs. */
  intro: Block[];
  /** The sections, in reading order: Security first, then New, Heads-up, Upgrading, Fixes, others, Under the hood. */
  groups: NotesSection[];
  /** Any line of it is about security (a Security section, or an item that says "security" or names a CVE). */
  security: boolean;
}

const RELEASE_HEADING = /^\s{0,3}#{1,6}\s*(?:MDMesh\s+)?v?(\d+\.\d+\.\d+)\b(?:\s*[—–-]\s*(\d{4}-\d{2}-\d{2}))?.*$/i;
const TITLE_LINE = /^\s*(?:#{1,6}\s*)?(?:MDMesh\s+)?v?(\d+\.\d+\.\d+)\s*$/i;
const SECURITY_TEXT = /\bsecurity\b|\bCVE-\d{4}-\d+/i;

const itemText = (item: ListItem): string =>
  item.parts.map((p) => (p.kind === 'text' ? p.text : p.items.map(itemText).join(' '))).join(' ');

/** Whether a list item is about security (its text says so, or names a CVE). */
export const isSecurityItem = (item: ListItem): boolean => SECURITY_TEXT.test(itemText(item));

/** A Security section's title ("Security", "Security (agent 0.5.0)"). */
export const isSecurityGroup = (g: NotesSection): boolean => /^Security\b/.test(g.title);

function entry(version: string, date: string | null, body: string): ReleaseEntry {
  const notes = parseNotes(body);
  const intro = [...notes.intro, ...notes.outro];
  const first = intro[0];
  const headline = first && first.kind === 'para' ? first.text : null;
  const groups = notes.sections
    .map((s, i) => ({ s, i }))
    .sort((a, b) => Number(isSecurityGroup(b.s)) - Number(isSecurityGroup(a.s)) || a.i - b.i)
    .map(({ s }) => s);
  const security = groups.some(
    (g) =>
      isSecurityGroup(g) ||
      g.blocks.some((b) => (b.kind === 'list' ? b.items.some(isSecurityItem) : SECURITY_TEXT.test(b.text))),
  );
  return { version, date, headline, intro: headline ? intro.slice(1) : intro, groups, security };
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
  const out: ReleaseEntry[] = [];
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
    if (!v || !body.some((l) => l.trim())) continue;
    out.push(entry(v, p.date ?? (v === offered ? published : null), body.join('\n')));
  }
  return out.sort((a, b) => compareVersions(b.version, a.version));
}

function compareVersions(a: string, b: string): number {
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
