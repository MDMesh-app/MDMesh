// Release notes from the supervisor (`release.notes`) are the GitHub release body: free text in light markdown.
// MDMesh writes them as a title line, paragraphs, short section lines ("Upgrading", "New", "Fixes") and "- " bullets
// whose long lines wrap with a two-space indent. This turns that into blocks the page renders as plain React text
// (never as HTML), so nothing in the notes can inject markup.

export type NoteBlock =
  | { kind: 'heading'; text: string }
  | { kind: 'para'; text: string }
  | { kind: 'list'; items: string[] };

const BULLET = /^\s{0,3}(?:[-*+]|\d+[.)])\s+/;
const MD_HEADING = /^\s{0,3}#{1,6}\s+/;

/** Inline markdown down to its text: links keep their label, emphasis and code marks go. */
function inline(s: string): string {
  return s
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/(\*\*|__)(.+?)\1/g, '$2')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

export function parseReleaseNotes(raw: string | null | undefined, version?: string | null): NoteBlock[] {
  if (!raw) return [];
  const lines = raw.replace(/\r\n?/g, '\n').split('\n');
  const blocks: NoteBlock[] = [];
  let para: string[] | null = null;
  let list: string[] | null = null;
  const flushPara = () => {
    if (para && para.length) blocks.push({ kind: 'para', text: inline(para.join(' ')) });
    para = null;
  };
  const flushList = () => {
    if (list && list.length) blocks.push({ kind: 'list', items: list.map(inline).filter(Boolean) });
    list = null;
  };

  for (const line of lines) {
    if (!line.trim()) {
      flushPara();
      // A blank line between bullets keeps the list; anything else after it starts a new block.
      continue;
    }
    if (MD_HEADING.test(line)) {
      flushPara();
      flushList();
      blocks.push({ kind: 'heading', text: inline(line.replace(MD_HEADING, '')) });
      continue;
    }
    if (BULLET.test(line)) {
      flushPara();
      if (!list) list = [];
      list.push(line.replace(BULLET, ''));
      continue;
    }
    if (list && /^\s+/.test(line)) {
      // Wrapped continuation of the last bullet.
      list[list.length - 1] += ` ${line.trim()}`;
      continue;
    }
    flushList();
    if (!para) para = [];
    para.push(line.trim());
  }
  flushPara();
  flushList();

  // A one-line, unpunctuated paragraph right before a list is a section title ("Upgrading", "Fixes").
  const out = blocks.map((b, i): NoteBlock => {
    const next = blocks[i + 1];
    if (b.kind === 'para' && next?.kind === 'list' && b.text.length <= 48 && !/[.!?:,;]$/.test(b.text)) {
      return { kind: 'heading', text: b.text };
    }
    return b;
  });

  // The body usually opens with the version as its title ("v0.3.3"): the card already says it.
  const first = out[0];
  if (first && first.kind !== 'list' && version) {
    const t = first.text.replace(/^mdmesh\s+/i, '').replace(/^v/i, '');
    if (t === version.replace(/^v/i, '')) out.shift();
  }
  return out.filter((b) => (b.kind === 'list' ? b.items.length > 0 : b.text.length > 0));
}

/** Rough size used to decide whether the notes start collapsed. */
export function notesWeight(blocks: ReadonlyArray<NoteBlock>): number {
  return blocks.reduce((n, b) => n + (b.kind === 'list' ? b.items.length : 1), 0);
}
