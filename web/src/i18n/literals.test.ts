// @vitest-environment node
// The literals ratchet: English written straight into the UI, counted per file with the TypeScript API, may only go
// down. New text goes through react-intl (`defineMessages`, `<FormattedMessage>`, `intl.formatMessage`, with an
// explicit id; see web/README.md), so it can be translated; the text that was there before 0.6.0 is grandfathered
// in literals-baseline.json and lowered as areas move to messages. It is a count per file: turning one string into a
// message pays for one new raw string in the same file, which review sees in the diff.
//
// What counts as a literal (each string once):
// - JSX text with a letter in it (`<p>No devices</p>`), except inside <code> and <kbd> and the brand name;
// - a string or template shown as a JSX child (`<b>{on ? 'Enabled' : 'Disabled'}</b>`, `<b>{'Save'}</b>`), or given
//   to a UI attribute or property (`title`, `label`, `header`, `children`, `placeholder`, `aria-label`, `help`,
//   anything ending in Label, Text, Title…), through ( ), ?: arms, ??, || and &&;
// - anywhere else, a capitalised sentence: `'Couldn’t save the device.'`, `` `Wipe ${n} devices?` ``.
// Not counted: tests and test helpers, the dev-only component gallery, the contrast test data, fork extensions
// (src/extensions/), module specifiers, types, a message descriptor's own id/defaultMessage/description, and text for
// developers: console.* arguments, `new …Error(…)` messages, and the operands of ===, !==, ==, != and `case`.
//
// A file above its baseline fails, listing its literals with their lines. A file below it fails too, until the
// baseline is lowered with `npm run i18n:baseline`, which only ever lowers counts. Code moved to another file moves its
// count by hand.
//
// The API runs TypeScript's native compiler (tsgo, the binary `tsc` is) over IPC. `typescript` is pinned exactly
// (package.json) and updated on its own (.github/dependabot.yml), because `typescript/unstable/*` may change in any
// release. Each closed session prints "context canceled" on stderr: that is tsgo shutting down, not an error.
import { readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { SyntaxKind, type Node, type SourceFile } from 'typescript/unstable/ast';
import {
  isBinaryExpression,
  isCallExpression,
  isCaseClause,
  isConditionalExpression,
  isIdentifier,
  isJsxAttribute,
  isJsxAttributes,
  isJsxElement,
  isJsxExpression,
  isJsxFragment,
  isJsxOpeningElement,
  isJsxSelfClosingElement,
  isNewExpression,
  isNoSubstitutionTemplateLiteral,
  isObjectLiteralExpression,
  isParenthesizedExpression,
  isPropertyAccessExpression,
  isPropertyAssignment,
  isStringLiteral,
  isTemplateExpression,
} from 'typescript/unstable/ast/is';
import { createVirtualFileSystem } from 'typescript/unstable/fs';
import { API } from 'typescript/unstable/sync';
import { describe, expect, it } from 'vitest';

const WEB = resolve(import.meta.dirname, '../..');
const BASELINE = join(import.meta.dirname, 'literals-baseline.json');
/** Set by npm to the script's name. */
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
const UI_NAME =
  /^(title|subtitle|label|header|children|placeholder|alt|description|help|hint|more|tooltip|aria-(label|description|valuetext|roledescription|placeholder))$|(Label|Text|Title|Hint|Help)$/;
const DESCRIPTOR_KEYS = new Set(['id', 'defaultMessage', 'description']);
const EQUALITY = new Set<SyntaxKind>([
  SyntaxKind.EqualsEqualsEqualsToken,
  SyntaxKind.ExclamationEqualsEqualsToken,
  SyntaxKind.EqualsEqualsToken,
  SyntaxKind.ExclamationEqualsToken,
]);
const FALLBACK = new Set<SyntaxKind>([SyntaxKind.QuestionQuestionToken, SyntaxKind.BarBarToken, SyntaxKind.AmpersandAmpersandToken]);
const BRANDS = new Set(['MDMesh']);
const LETTER = /\p{L}/u;
/** Starts with a capitalised word and goes on with another word (not SVG path data such as 'M12 5v14'). */
const SENTENCE = /^\s*\p{Lu}[\p{L}’'.-]*[,:]?\s+[\p{L}"“'(]/u;

export interface Literal {
  text: string;
  line: number;
}

/** A property's or attribute's name, when it is written plainly. */
function nameOf(node: Node): string {
  if (!isPropertyAssignment(node) && !isJsxAttribute(node)) return '';
  const { name } = node;
  return isIdentifier(name) || isStringLiteral(name) || isNoSubstitutionTemplateLiteral(name) ? name.text : '';
}

/** The JSX tag whose attributes hold `attr`. */
function tagOf(attr: Node): string {
  const attrs = attr.parent;
  if (!isJsxAttributes(attrs)) return '';
  const element = attrs.parent;
  return isJsxOpeningElement(element) || isJsxSelfClosingElement(element) ? element.tagName.getText() : '';
}

/** A react-intl descriptor's own fields: an object with a defaultMessage, or FormattedMessage's props. */
function isDescriptorField(node: Node): boolean {
  if (!DESCRIPTOR_KEYS.has(nameOf(node))) return false;
  if (isJsxAttribute(node)) return tagOf(node) === 'FormattedMessage';
  const object = node.parent;
  return isObjectLiteralExpression(object) && object.properties.some((p) => nameOf(p) === 'defaultMessage');
}

/** Where a string's value goes: climbs ( ), { }, ?: arms and ??, ||, && from the literal to the node that uses it. */
function destination(node: Node): Node {
  let at = node;
  for (;;) {
    const up = at.parent;
    const through =
      isParenthesizedExpression(up) ||
      isJsxExpression(up) ||
      (isConditionalExpression(up) && at !== up.condition) ||
      (isBinaryExpression(up) && FALLBACK.has(up.operatorToken.kind));
    if (!through) return up;
    at = up;
  }
}

/** Shown to people: a JSX child, or a UI attribute or property. */
function isShown(to: Node): boolean {
  if (isJsxElement(to) || isJsxFragment(to)) return true;
  return (isJsxAttribute(to) || isPropertyAssignment(to)) && UI_NAME.test(nameOf(to));
}

/** For developers, never shown as such: a console.* argument, an Error's message, a comparison's operand. */
function isForDevelopers(to: Node): boolean {
  if (isCallExpression(to)) {
    const callee = to.expression;
    return isPropertyAccessExpression(callee) && isIdentifier(callee.expression) && callee.expression.text === 'console';
  }
  if (isNewExpression(to)) return isIdentifier(to.expression) && /Error$/.test(to.expression.text);
  return (isBinaryExpression(to) && EQUALITY.has(to.operatorToken.kind)) || isCaseClause(to);
}

function textOf(node: Node): string {
  if (isTemplateExpression(node)) return [node.head.text, ...node.templateSpans.map((s) => s.literal.text)].join(' x ');
  if (isStringLiteral(node) || isNoSubstitutionTemplateLiteral(node)) return node.text;
  return node.getText().trim();
}

/** The UI literals in one file: their text and line. */
export function literalsIn(file: SourceFile): Literal[] {
  const found: Literal[] = [];
  const add = (node: Node, text: string) =>
    found.push({ text, line: file.getLineAndCharacterOfPosition(node.getStart(file)).line + 1 });
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
      case SyntaxKind.JsxElement:
        if (isJsxElement(node) && ['code', 'kbd'].includes(node.openingElement.tagName.getText())) return;
        break;
      case SyntaxKind.JsxText: {
        const text = textOf(node);
        if (LETTER.test(text) && !BRANDS.has(text)) add(node, text);
        return;
      }
      case SyntaxKind.StringLiteral:
      case SyntaxKind.NoSubstitutionTemplateLiteral:
      case SyntaxKind.TemplateExpression: {
        const text = textOf(node);
        const to = destination(node);
        if (BRANDS.has(text.trim()) || isForDevelopers(to)) break;
        const isKey = isPropertyAssignment(node.parent) && node.parent.name === node;
        if ((!isKey && isShown(to) && LETTER.test(text)) || SENTENCE.test(text)) {
          add(node, text);
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

/** Per file (a web/-relative path), its UI literals, for every file the app's tsconfig includes. */
function scanTree(): Record<string, Literal[]> {
  const api = new API({ cwd: WEB });
  try {
    const project = api.updateSnapshot({ openProjects: [join(WEB, 'tsconfig.app.json')] }).getProjects()[0];
    const found: Record<string, Literal[]> = {};
    for (const path of project.rootFiles) {
      const rel = relative(WEB, path).split('\\').join('/');
      if (SKIPPED_FILES.some((re) => re.test(rel))) continue;
      const file = project.program.getSourceFile(path);
      if (!file) throw new Error(`TypeScript returned no source for ${rel}`);
      const literals = literalsIn(file);
      if (literals.length) found[rel] = literals;
    }
    return found;
  } finally {
    api.close();
  }
}

/** The literals of each snippet, each parsed as its own .tsx file. */
function literalsOf(...snippets: string[]): Literal[][] {
  const files = Object.fromEntries(snippets.map((code, i) => [`/v/x${i}.tsx`, code]));
  const api = new API({
    cwd: '/v',
    fs: createVirtualFileSystem({
      ...files,
      '/v/tsconfig.json': JSON.stringify({ compilerOptions: { jsx: 'react-jsx', noLib: true }, files: Object.keys(files) }),
    }),
  });
  try {
    const project = api.updateSnapshot({ openProjects: ['/v/tsconfig.json'] }).getProjects()[0];
    return Object.keys(files).map((path) => literalsIn(project.program.getSourceFile(path) as SourceFile));
  } finally {
    api.close();
  }
}

describe('the literals ratchet', () => {
  const baseline: Record<string, number> = JSON.parse(readFileSync(BASELINE, 'utf8'));
  const found = scanTree();
  const count = (f: string) => found[f]?.length ?? 0;
  const files = [...new Set([...Object.keys(baseline), ...Object.keys(found)])].sort();

  it('adds no untranslated text to any file', () => {
    const over = files
      .filter((f) => count(f) > (baseline[f] ?? 0))
      .map((f) => [`${f}: ${count(f)} (baseline ${baseline[f] ?? 0}):`, ...found[f].map((l) => `  ${l.line}: ${l.text}`)].join('\n'));
    expect(over, 'new UI text must be a react-intl message (web/README.md, "Translatable text")').toEqual([]);
  });

  it.skipIf(LOWERING)('has a baseline no higher than the code', () => {
    const under = files.filter((f) => count(f) < (baseline[f] ?? 0)).map((f) => `${f}: ${count(f)} (baseline ${baseline[f]})`);
    expect(under, 'fewer literals than the baseline: run `npm run i18n:baseline` in web/ and commit it').toEqual([]);
  });

  it.runIf(LOWERING)('lowers the baseline', () => {
    const lowered = Object.fromEntries(files.map((f) => [f, Math.min(baseline[f] ?? 0, count(f))] as const).filter(([, n]) => n > 0));
    expect(found, 'the scan found no literals at all, so it is broken: the baseline stays').not.toEqual({});
    writeFileSync(BASELINE, `${JSON.stringify(lowered, null, 2)}\n`);
  });
});

describe('what counts as a literal', () => {
  const texts = (...snippets: string[]) => literalsOf(...snippets).map((ls) => ls.map((l) => l.text));

  it('counts JSX text and children, UI attributes and properties, and sentences', () => {
    expect(
      texts(`
        const help = { label: 'Allow', value: 'allow' };
        const columns = [{ key: 'type', header: 'Type' }];
        const note = 'Couldn’t save the device.';
        const ask = (n: number) => \`Wipe \${n} devices?\`;
        export const A = (p: { on: boolean }) => (
          <section aria-label="Devices" className="v2-card">
            <h1 title={p.on ? 'On' : 'Off'}>No devices yet</h1>
            <b>{p.on ? 'Enabled' : 'Disabled'}</b>
            <>{'Save'}</>
            <Button children="Delete" />
          </section>
        );
      `),
    ).toEqual([
      ['Allow', 'Type', 'Couldn’t save the device.', 'Wipe  x  devices?', 'Devices', 'On', 'Off', 'No devices yet', 'Enabled', 'Disabled', 'Save', 'Delete'],
    ]);
  });

  it('skips messages, code, module paths, types, identifiers, the brand and text for developers', () => {
    expect(
      texts(`
        import { defineMessages, FormattedMessage } from 'react-intl';
        import './Page.css';
        type Group = 'Kiosk appearance';
        const m = defineMessages({ t: { id: 'devices.list.title', defaultMessage: 'All devices', description: 'Page title' } });
        const a = { 'aria-label': intl.formatMessage(m.t) };
        const key = 'hmdm.admin.user';
        console.error(\`Couldn't load the \${key} messages\`);
        const owner = (mode: string) => mode === 'Device owner';
        function fail(kind: string) {
          switch (kind) {
            case 'Work profile': return null;
          }
          throw new Error('Unexpected response from the server');
        }
        export const A = (p: { on: boolean }) => (
          <p className={p.on ? 'is-on' : 'is-off'} data-state="open">
            <FormattedMessage id="devices.list.empty" defaultMessage="No devices yet" description="Empty list" />
            <code>npm run i18n:extract</code> MDMesh {' '}
            <svg><path d="M12 5v14M5 12h14" /></svg>
          </p>
        );
      `),
    ).toEqual([[]]);
  });

  it('gives each literal its line', () => {
    expect(literalsOf('const a = 1;\nexport const A = () => <p>Hello</p>;\n')).toEqual([[{ text: 'Hello', line: 2 }]]);
  });
});
