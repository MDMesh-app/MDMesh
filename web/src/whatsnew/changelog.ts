// CHANGELOG.md as the What's new dialog reads it. A release's section is cut out by the rules of
// release/changelog-section.sh, which writes the release's tag annotation and GitHub release notes, so the dialog says
// exactly what the release notes say. The section's text is then parsed into blocks of plain data that the dialog
// renders as React elements, never as HTML: only headings ("New", "Fixes"), paragraphs, "- " bullets (nested by
// indentation), **bold**, `code` and links (http(s), or a path in the console).
//
// No DOM here: vite.changelog.ts runs this at build time too (the bundled notes, whatsnew/virtual.d.ts).

/** A release version as the release script takes it: X.Y.Z. */
export const RELEASE_VERSION = /^\d+\.\d+\.\d+$/;

/**
 * changelog-section.sh's `norm`: CRs and trailing spaces and tabs off, runs of blank lines squeezed to one, leading
 * and trailing blank lines dropped.
 */
export function normalize(text: string): string {
  const out: string[] = [];
  let blank = false;
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '').replace(/[ \t]+$/, '');
    if (line === '') {
      blank = true;
      continue;
    }
    if (out.length && blank) out.push('');
    out.push(line);
    blank = false;
  }
  return out.join('\n');
}

export interface ChangelogSection {
  /** "0.3.7", or null for Unreleased. */
  version: string | null;
  /** The heading's date ("## v0.3.7 — 2026-10-22"), if it has one. */
  date: string | null;
  /** The section's text, normalized. */
  body: string;
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The lines of the section under the one heading `heading` matches, and that heading; null unless exactly one does. */
function cut(changelog: string, heading: RegExp): { head: string; body: string } | null {
  const lines = changelog.split('\n').map((l) => l.replace(/\r$/, ''));
  const at = lines.flatMap((l, i) => (heading.test(l) ? [i] : []));
  if (at.length !== 1) return null;
  const start = at[0] + 1;
  let end = lines.findIndex((l, i) => i >= start && l.startsWith('## '));
  if (end === -1) end = lines.length;
  return { head: lines[at[0]], body: normalize(lines.slice(start, end).join('\n')) };
}

const dateOf = (head: string) => /\b(\d{4}-\d{2}-\d{2})\b/.exec(head)?.[1] ?? null;

/**
 * The section `## vX.Y.Z …` of CHANGELOG.md, as release/changelog-section.sh cuts it: from the line after its heading
 * (`## v0.3.7` followed by a space or the line's end) to the next `## ` line. Null wherever the script refuses to tag:
 * no such heading, more than one, an empty section, or one with an HTML comment (a leftover `<!-- pending -->`).
 */
export function releaseSection(changelog: string, version: string): ChangelogSection | null {
  const v = version.replace(/^v/, '');
  if (!RELEASE_VERSION.test(v)) return null;
  const found = cut(changelog, new RegExp(`^## v${escape(v)}(\\s|$)`));
  if (!found || !found.body || found.body.includes('<!--')) return null;
  return { version: v, date: dateOf(found.head), body: found.body };
}

/** The Unreleased section, its HTML comments (the pending markers of PRs not merged yet) left out; null when empty. */
export function unreleasedSection(changelog: string): ChangelogSection | null {
  const found = cut(changelog, /^## Unreleased(\s|$)/);
  if (!found) return null;
  const body = normalize(found.body.replace(/<!--[\s\S]*?-->/g, ''));
  return body ? { version: null, date: null, body } : null;
}

/** Every version with a section releaseSection accepts, in the file's order (newest first). */
export function releaseVersions(changelog: string): string[] {
  const out: string[] = [];
  for (const line of changelog.split('\n')) {
    const m = /^## v(\d+\.\d+\.\d+)(\s|$)/.exec(line.replace(/\r$/, ''));
    if (m && !out.includes(m[1]) && releaseSection(changelog, m[1])) out.push(m[1]);
  }
  return out;
}

// ---- the section's text as blocks ----

export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'code'; text: string }
  | { kind: 'strong'; children: Inline[] }
  /** `internal`: a path in the console (`/settings#updates`), opened in place; else an http(s) address. */
  | { kind: 'link'; href: string; internal: boolean; children: Inline[] };

export type ItemPart = { kind: 'text'; text: string } | { kind: 'list'; items: ListItem[] };
export interface ListItem {
  /** Its text, then any nested list and any text after it, in order. Text is inline markdown (parseInline). */
  parts: ItemPart[];
}
export type Block = { kind: 'para'; text: string } | { kind: 'list'; items: ListItem[] };

export type SectionKind = 'new' | 'headsUp' | 'upgrading' | 'fixes' | 'other' | 'underTheHood';
export interface NotesSection {
  kind: SectionKind;
  /** As written: "New", "New (agent 0.3.4)", "Heads-up". */
  title: string;
  blocks: Block[];
}
export interface Notes {
  /** What comes before the first section heading (a release's opening line). */
  intro: Block[];
  /** In the dialog's order: New, Heads-up, Upgrading, Fixes, any other, Under the hood. */
  sections: NotesSection[];
  /** Paragraphs after the sections ("Thanks @… for the reports"). */
  outro: Block[];
}

/** The section headings CHANGELOG.md uses, each alone on its line, with an optional "(…)" or ": text" after it. */
const LABEL = /^(Upgrading|Heads-up|New|Fixes|Security|Under the hood|CI \/ release)( \([^)]*\))?(?::\s*(.*))?$/;
const KIND: Readonly<Record<string, SectionKind>> = {
  New: 'new',
  'Heads-up': 'headsUp',
  Upgrading: 'upgrading',
  Fixes: 'fixes',
  'Under the hood': 'underTheHood',
};
const RANK: Readonly<Record<SectionKind, number>> = { new: 0, headsUp: 1, upgrading: 2, fixes: 3, other: 4, underTheHood: 5 };
const BULLET = /^[-*] +/;

interface OpenItem {
  item: ListItem;
  /** The bullet's column. */
  at: number;
  /** A blank line came after its last text. */
  gap: boolean;
}

/** A release section's text as blocks, its sections in the dialog's order. */
export function parseNotes(body: string): Notes {
  const intro: Block[] = [];
  const outro: Block[] = [];
  const sections: NotesSection[] = [];
  let target: Block[] = intro;
  /** The current section has content, so a paragraph after a blank line may be the release's closing lines. */
  let sectionFilled = false;
  /**
   * Where the closing lines would start: in the last section, from a paragraph after a blank line. Another section
   * heading after it keeps them in their section; at the end of the text they move to the outro.
   */
  let tail: { blocks: Block[]; from: number } | null = null;
  let para: string[] | null = null;
  let list: ListItem[] | null = null;
  let stack: OpenItem[] = [];
  let blank = false;

  const flushPara = () => {
    if (para) target.push({ kind: 'para', text: para.join(' ') });
    if (para && target !== intro && target !== outro) sectionFilled = true;
    para = null;
  };
  const closeList = () => {
    if (list) target.push({ kind: 'list', items: list });
    if (list && target !== intro && target !== outro) sectionFilled = true;
    list = null;
    stack = [];
  };

  for (const raw of body.split('\n')) {
    if (!raw.trim()) {
      flushPara();
      blank = true;
      for (const o of stack) o.gap = true;
      continue;
    }
    const indent = raw.length - raw.trimStart().length;
    const text = raw.trim();
    const bullet = BULLET.test(text);

    if (list && bullet) {
      // A bullet: a sibling of the open item at its column, else a child of the item it is indented under.
      while (stack.length && stack[stack.length - 1].at >= indent) stack.pop();
      const item: ListItem = { parts: [{ kind: 'text', text: text.replace(BULLET, '') }] };
      const parent = stack[stack.length - 1];
      if (!parent) list.push(item);
      else {
        const last = parent.item.parts[parent.item.parts.length - 1];
        if (last.kind === 'list') last.items.push(item);
        else parent.item.parts.push({ kind: 'list', items: [item] });
      }
      stack.push({ item, at: indent, gap: false });
      blank = false;
      continue;
    }
    if (list && indent > 0 && stack.length) {
      // An indented line: more text of the deepest item it is indented under.
      while (stack.length > 1 && stack[stack.length - 1].at + 2 > indent) stack.pop();
      const open = stack[stack.length - 1];
      const last = open.item.parts[open.item.parts.length - 1];
      if (last.kind === 'text' && !open.gap) last.text += ` ${text}`;
      else open.item.parts.push({ kind: 'text', text });
      open.gap = false;
      blank = false;
      continue;
    }
    closeList();

    const label = indent === 0 ? LABEL.exec(text) : null;
    if (label) {
      flushPara();
      const section: NotesSection = { kind: KIND[label[1]] ?? 'other', title: label[1] + (label[2] ?? ''), blocks: [] };
      sections.push(section);
      target = section.blocks;
      sectionFilled = false;
      tail = null;
      if (label[3]) para = [label[3].charAt(0).toUpperCase() + label[3].slice(1)];
      blank = false;
      continue;
    }
    if (bullet) {
      flushPara();
      list = [];
      const item: ListItem = { parts: [{ kind: 'text', text: text.replace(BULLET, '') }] };
      list.push(item);
      stack = [{ item, at: indent, gap: false }];
      blank = false;
      continue;
    }
    if (para && !blank) {
      para.push(text);
      continue;
    }
    flushPara();
    if (sectionFilled && blank && !tail && target !== intro) tail = { blocks: target, from: target.length };
    para = [text];
    blank = false;
  }
  flushPara();
  closeList();
  if (tail) outro.push(...tail.blocks.splice(tail.from));

  const ordered = sections
    .filter((s) => s.blocks.length)
    .map((s, i) => ({ s, i }))
    .sort((a, b) => RANK[a.s.kind] - RANK[b.s.kind] || a.i - b.i)
    .map(({ s }) => s);
  return { intro, sections: ordered, outro };
}

/**
 * A link's address as the dialog may open it: an http(s) address, or a path in the console ("/settings#updates", not
 * "//host"). Anything else (javascript:, data:, a repository file such as RELEASING.md) is null, and its label shows
 * as plain text.
 */
export function safeHref(href: string): { href: string; internal: boolean } | null {
  if (/[\s\\]/.test(href)) return null;
  if (/^\/(?!\/)/.test(href)) return { href, internal: true };
  if (!/^https?:\/\//i.test(href)) return null;
  try {
    const u = new URL(href);
    return u.protocol === 'http:' || u.protocol === 'https:' ? { href: u.href, internal: false } : null;
  } catch {
    return null;
  }
}

const LINK = /\[([^\]]+)\]\(([^()\s]+)\)/y;

/** Inline markdown as plain data: `code`, **bold**, [label](address); an unmatched mark stays as it is written. */
export function parseInline(text: string): Inline[] {
  const out: Inline[] = [];
  let buf = '';
  const flush = () => {
    if (buf) out.push({ kind: 'text', text: buf });
    buf = '';
  };
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '`') {
      const end = text.indexOf('`', i + 1);
      if (end > i + 1) {
        flush();
        out.push({ kind: 'code', text: text.slice(i + 1, end) });
        i = end + 1;
        continue;
      }
    }
    if (ch === '*' && text.startsWith('**', i)) {
      const end = text.indexOf('**', i + 2);
      if (end > i + 2) {
        flush();
        out.push({ kind: 'strong', children: parseInline(text.slice(i + 2, end)) });
        i = end + 2;
        continue;
      }
    }
    if (ch === '[') {
      LINK.lastIndex = i;
      const m = LINK.exec(text);
      if (m) {
        flush();
        const label = parseInline(m[1]);
        const safe = safeHref(m[2]);
        if (safe) out.push({ kind: 'link', ...safe, children: label });
        else out.push(...label);
        i = LINK.lastIndex;
        continue;
      }
    }
    buf += ch;
    i++;
  }
  flush();
  return out;
}
