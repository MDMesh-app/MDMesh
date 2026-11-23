// The literals ratchet: English written straight into the UI, counted per file with the TypeScript API, may only go
// down. New text goes through react-intl (`defineMessages`, `<FormattedMessage>`, `intl.formatMessage`, with an
// explicit id; see web/README.md), so it can be translated; the text that was there before 0.6.0 is grandfathered
// in literals-baseline.json and lowered as areas move to messages.
//
// What counts as a literal (each string once):
// - JSX text with a letter in it (`<p>No devices</p>`), except inside <code> and <kbd> and the brand name;
// - a string or template in a UI attribute or property (`title`, `label`, `placeholder`, `aria-label`, `help`,
//   anything ending in Label, Text, Title…), wherever it sits in the expression (`label={a ? 'On' : 'Off'}`);
// - anywhere else, a capitalised sentence: `'Couldn’t save the device.'`, `` `Wipe ${n} devices?` ``.
// Not counted: tests and test helpers, the dev-only component gallery, the contrast test data, fork extensions
// (src/extensions/), module specifiers, types, and a message descriptor's own id/defaultMessage/description.
//
// A file above its baseline fails. A file below it fails too, until the baseline is lowered with
// `npm run i18n:baseline` (which only ever lowers counts). Code moved to another file moves its count by hand.
import { readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { createVirtualFileSystem } from 'typescript/unstable/fs';
import { API } from 'typescript/unstable/sync';
import { SyntaxKind, type Node, type SourceFile } from 'typescript/unstable/ast';
import { afterAll, describe, expect, it } from 'vitest';

const WEB = resolve(import.meta.dirname, '../..');
const BASELINE = join(import.meta.dirname, 'literals-baseline.json');
/** Set by npm when the run is `npm run i18n:baseline`. */
const LOWERING = process.env.npm_lifecycle_event === 'i18n:baseline';

const SKIPPED_FILES = [
  /\.test\.tsx?$/,
  /\.d\.ts$/,
  /^src\/test\//,
  /^src\/sdk\/testing\.tsx$/,
  /^src\/ext\/__fixtures__\//,
  /^src\/extensions\//,
  /^src\/pages\/Gallery(Page|More)\.tsx$/,
  /^src\/a11y\/contrastPairs\.ts$/,
];
const UI_NAME = /^(title|subtitle|label|placeholder|alt|description|help|hint|more|tooltip|aria-(label|description|valuetext|roledescription|placeholder))$|(Label|Text|Title|Hint|Help)$/;
const DESCRIPTOR_KEYS = new Set(['id', 'defaultMessage', 'description']);
const BRANDS = new Set(['MDMesh']);
const LETTER = /\p{L}/u;
/** Starts with a capitalised word and goes on with another word (not SVG path data such as 'M12 5v14'). */
const SENTENCE = /^\s*\p{Lu}[\p{L}’'.-]*[,:]?\s+[\p{L}"“'(]/u;

const nameOf = (node: Node): string => {
  const name = (node as unknown as { name?: Node }).name;
  return name ? name.getText().replace(/^['"]|['"]$/g, '') : '';
};

/** The JSX tag of the element whose attributes hold `attr`. */
const tagOf = (attr: Node) => (attr.parent.parent as unknown as { tagName: Node }).tagName.getText();

/** A react-intl descriptor's own fields: an object with a defaultMessage, or FormattedMessage's props. */
function isDescriptorField(node: Node): boolean {
  if (!DESCRIPTOR_KEYS.has(nameOf(node))) return false;
  if (node.kind === SyntaxKind.JsxAttribute) return tagOf(node) === 'FormattedMessage';
  return (node.parent as unknown as { properties: Node[] }).properties.some((p) => nameOf(p) === 'defaultMessage');
}

/** The UI attribute or property a string feeds, through ?:, ??, ||, && and parentheses; undefined otherwise. */
function uiSlot(node: Node): Node | undefined {
  let at = node;
  for (;;) {
    const up = at.parent;
    if (up.kind === SyntaxKind.JsxAttribute || up.kind === SyntaxKind.PropertyAssignment) return UI_NAME.test(nameOf(up)) ? up : undefined;
    if (
      up.kind !== SyntaxKind.ParenthesizedExpression &&
      up.kind !== SyntaxKind.ConditionalExpression &&
      up.kind !== SyntaxKind.BinaryExpression &&
      up.kind !== SyntaxKind.JsxExpression
    ) {
      return undefined;
    }
    at = up;
  }
}

function textOf(node: Node): string {
  const t = node as unknown as { text?: string; head?: { text: string }; templateSpans?: { literal: { text: string } }[] };
  if (node.kind === SyntaxKind.TemplateExpression) {
    return [t.head?.text ?? '', ...(t.templateSpans ?? []).map((s) => s.literal.text)].join(' x ');
  }
  return t.text ?? '';
}

/** The UI literals in one file, as their text. */
export function literalsIn(file: SourceFile): string[] {
  const found: string[] = [];
  const visit = (node: Node): void => {
    switch (node.kind) {
      case SyntaxKind.ImportDeclaration:
      case SyntaxKind.ExportDeclaration:
      case SyntaxKind.LiteralType:
      case SyntaxKind.ImportType:
        return;
      case SyntaxKind.JsxAttribute:
      case SyntaxKind.PropertyAssignment:
        if (isDescriptorField(node)) return;
        break;
      case SyntaxKind.JsxElement: {
        const tag = (node as unknown as { openingElement: { tagName: Node } }).openingElement.tagName.getText();
        if (tag === 'code' || tag === 'kbd') return;
        break;
      }
      case SyntaxKind.JsxText: {
        const text = textOf(node).trim();
        if (LETTER.test(text) && !BRANDS.has(text)) found.push(text);
        return;
      }
      case SyntaxKind.StringLiteral:
      case SyntaxKind.NoSubstitutionTemplateLiteral:
      case SyntaxKind.TemplateExpression: {
        const text = textOf(node);
        if (BRANDS.has(text.trim())) return;
        if ((uiSlot(node) && LETTER.test(text)) || SENTENCE.test(text)) {
          found.push(text);
          return;
        }
        break;
      }
    }
    node.forEachChild(visit);
  };
  visit(file);
  return found;
}

/** Per file (a web/-relative path), its count of UI literals, for every file the app's tsconfig includes. */
function countTree(): Record<string, number> {
  const api = new API({ cwd: WEB });
  try {
    const project = api.updateSnapshot({ openProjects: [join(WEB, 'tsconfig.app.json')] }).getProjects()[0];
    const counts: Record<string, number> = {};
    for (const path of project.rootFiles) {
      const rel = relative(WEB, path).split('\\').join('/');
      if (SKIPPED_FILES.some((re) => re.test(rel))) continue;
      const n = literalsIn(project.program.getSourceFile(path) as SourceFile).length;
      if (n) counts[rel] = n;
    }
    return counts;
  } finally {
    api.close();
  }
}

/** A snippet's literals, parsed as web/src/x.tsx in a project of its own. */
function literalsOf(code: string): string[] {
  const api = new API({
    cwd: '/v',
    fs: createVirtualFileSystem({
      '/v/tsconfig.json': JSON.stringify({ compilerOptions: { jsx: 'react-jsx', noLib: true }, files: ['x.tsx'] }),
      '/v/x.tsx': code,
    }),
  });
  try {
    const project = api.updateSnapshot({ openProjects: ['/v/tsconfig.json'] }).getProjects()[0];
    return literalsIn(project.program.getSourceFile('/v/x.tsx') as SourceFile);
  } finally {
    api.close();
  }
}

describe('the literals ratchet', () => {
  const baseline: Record<string, number> = JSON.parse(readFileSync(BASELINE, 'utf8'));
  const counts = countTree();
  const files = [...new Set([...Object.keys(baseline), ...Object.keys(counts)])].sort();

  afterAll(() => {
    if (!LOWERING) return;
    const lowered = Object.fromEntries(
      files.map((f) => [f, Math.min(baseline[f] ?? 0, counts[f] ?? 0)] as const).filter(([, n]) => n > 0),
    );
    writeFileSync(BASELINE, `${JSON.stringify(lowered, null, 2)}\n`);
  });

  it('adds no untranslated text to any file', () => {
    const over = files.filter((f) => (counts[f] ?? 0) > (baseline[f] ?? 0)).map((f) => `${f}: ${counts[f]} (baseline ${baseline[f] ?? 0})`);
    expect(over, 'new UI text must be a react-intl message (web/README.md, "Translatable text")').toEqual([]);
  });

  it.skipIf(LOWERING)('has a baseline no higher than the code', () => {
    const under = files.filter((f) => (counts[f] ?? 0) < (baseline[f] ?? 0)).map((f) => `${f}: ${counts[f] ?? 0} (baseline ${baseline[f]})`);
    expect(under, 'fewer literals than the baseline: run `npm run i18n:baseline` in web/ and commit it').toEqual([]);
  });
});

describe('what counts as a literal', () => {
  it('counts JSX text, UI attributes and properties, and sentences', () => {
    expect(
      literalsOf(`
        const help = { label: 'Allow', value: 'allow' };
        const note = 'Couldn’t save the device.';
        const ask = (n: number) => \`Wipe \${n} devices?\`;
        export const A = (p: { on: boolean }) => (
          <section aria-label="Devices" className="v2-card">
            <h1 title={p.on ? 'On' : 'Off'}>No devices yet</h1>
          </section>
        );
      `),
    ).toEqual(['Allow', 'Couldn’t save the device.', 'Wipe  x  devices?', 'Devices', 'On', 'Off', 'No devices yet']);
  });

  it('skips messages, code, module paths, types, identifiers and the brand', () => {
    expect(
      literalsOf(`
        import { defineMessages, FormattedMessage } from 'react-intl';
        import './Page.css';
        type Group = 'Kiosk appearance';
        const m = defineMessages({ t: { id: 'devices.list.title', defaultMessage: 'All devices', description: 'Page title' } });
        const key = 'hmdm.admin.user';
        export const A = () => (
          <p className="v2-mono" data-state="open">
            <FormattedMessage id="devices.list.empty" defaultMessage="No devices yet" description="Empty list" />
            <code>npm run i18n:extract</code> MDMesh {' '}
            <svg><path d="M12 5v14M5 12h14" /></svg>
          </p>
        );
      `),
    ).toEqual([]);
  });
});
