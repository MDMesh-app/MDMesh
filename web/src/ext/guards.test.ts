// The import rules that keep forks out of core files and core out of fork folders, checked over the source tree:
// - only ext/load.ts imports web/src/extensions/ (so a fork's folder is picked up without a core edit)
// - an extension imports only `@mdmesh/console`, `react` and files in its own folder (so core can change freely); its
//   tests may also import vitest, @testing-library and `@mdmesh/console/testing`, and a file that isn't a test may not
//   import a test file (which would pull those into the production build), nor glob one ('!**/*.test.*' excludes them)
// - an extension's dynamic imports and globs are plain strings the check can read: no template literal, no glob `base`
// - only tests import the test fixtures
// Each rule is a pure function of a file's path and code, so the cases at the end prove it catches what it should.
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = resolve(import.meta.dirname, '..');
const WEB = dirname(SRC);
const EXTENSIONS = join(SRC, 'extensions');
const FIXTURES = join(SRC, 'ext', '__fixtures__');
const LOADER = join(SRC, 'ext', 'load.ts');
const EXTENSION_PACKAGES = new Set(['@mdmesh/console', 'react']);
/** What an extension's own tests may import besides. */
const EXTENSION_TEST_PACKAGES = new Set(['vitest', '@testing-library/react', '@testing-library/dom', '@mdmesh/console/testing']);

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const path = join(dir, d.name);
    if (d.isDirectory()) return sourceFiles(path);
    return /\.[cm]?[jt]sx?$/.test(d.name) ? [path] : [];
  });
}

interface Import {
  spec: string;
  /** An import.meta.glob pattern rather than one module. */
  glob: boolean;
  // A glob whose list also has '!**/*.test.*', which leaves out every test file below it.
  excludesTests?: boolean;
  /**
   * A dynamic import or glob this check can't read: a template literal (Vite turns `./pages/${n}.tsx` into a glob), a
   * list with anything but plain strings, a glob `base` (its patterns start there), a type argument with parentheses.
   * It could load anything, so it reaches every folder.
   */
  opaque?: boolean;
}

/** The text between the parentheses of the call whose `(` is at `open`, minding strings and nesting. */
function callArgs(code: string, open: number): string {
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    const c = code[i];
    if (c === "'" || c === '"' || c === '`') {
      for (i++; i < code.length && code[i] !== c; i++) if (code[i] === '\\') i++;
    } else if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c) && --depth === 0) return code.slice(open + 1, i);
  }
  return code.slice(open + 1);
}

const STRING = String.raw`'[^'\\\n]*'|"[^"\\\n]*"`;
/** One plain string, then maybe options. */
const ONE = new RegExp(String.raw`^\s*(${STRING})\s*(?:,([\s\S]*))?$`);
/** A list of plain strings, then maybe options. */
const LIST = new RegExp(String.raw`^\s*(\[\s*(?:(?:${STRING})\s*(?:,\s*)?)*\])\s*(?:,([\s\S]*))?$`);
// A glob's options, read only when written plainly: Vite evaluates them as code, so a key spelled any other way
// ('base', ['base'], \u0062ase, a comment) could still be `base`, which moves where the patterns start.
const SCALAR = String.raw`true|false|-?\d+(?:\.\d+)?|${STRING}`;
const QUERY = String.raw`${STRING}|\{\s*(?:(?:[A-Za-z_$][\w$]*|${STRING})\s*:\s*(?:${SCALAR})\s*(?:,\s*)?)*\}`;
const OPTION = String.raw`(?:eager|import|exhaustive|caseSensitive|as)\s*:\s*(?:${SCALAR})|query\s*:\s*(?:${QUERY})`;
const GLOB_OPTIONS = new RegExp(String.raw`^\s*(?:\{\s*(?:(?:${OPTION})\s*(?:,\s*)?)*\}\s*,?\s*)?$`);

/** Where a type argument that starts at `at` (a '<') ends, minding nesting and arrows (`() => T`). */
function skipTypeArgs(code: string, at: number): number {
  let depth = 0;
  for (let i = at; i < code.length; i++) {
    if (code[i] === '<') depth++;
    else if (code[i] === '>' && code[i - 1] !== '=' && --depth === 0) return i + 1;
  }
  return code.length;
}

/** Every module a file refers to: static and dynamic imports, re-exports, side-effect imports, glob patterns. */
function imports(code: string): Import[] {
  const found: Import[] = [];
  for (const m of code.matchAll(/\bfrom\s*['"]([^'"]+)['"]|\bimport\s+['"]([^'"]+)['"]/g)) {
    found.push({ spec: m[1] ?? m[2], glob: false });
  }
  // import('module') and import.meta.glob('pattern', options) or import.meta.glob(['a', 'b', '!c'], options), read
  // as whole calls. In a list a negated pattern adds nothing, but '!**/*.test.*' keeps the others from matching tests
  // ('!./**/*.test.*' doesn't: Vite resolves it from the importing file's folder, and '../*.tsx' reaches above it).
  // Not obj.import(…) or obj?.import(…), a property of that name; but a spread, ...import(…), is one.
  for (const m of code.matchAll(/(?<![\w$])(?<!(?:^|[^.])\.)import(?:\s*\(|\.meta\.glob\b)/g)) {
    const glob = m[0].endsWith('glob');
    let open = m.index + m[0].length - (glob ? 0 : 1);
    if (glob) {
      while (/\s/.test(code[open] ?? '')) open++;
      if (code[open] === '<') open = skipTypeArgs(code, open);
      while (/\s/.test(code[open] ?? '')) open++;
    }
    const call = code[open] === '(' ? callArgs(code, open) : null;
    const read = call === null ? null : (ONE.exec(call) ?? (glob ? LIST.exec(call) : null));
    if (!read || (glob && !GLOB_OPTIONS.test(read[2] ?? ''))) {
      const shown = call === null ? m[0] : `${code.slice(m.index, open)}(${call.trim()})`;
      found.push({ spec: shown.replace(/\s+/g, ' '), glob, opaque: true });
      continue;
    }
    const specs = [...read[1].matchAll(/'([^']*)'|"([^"]*)"/g)].map((s) => s[1] ?? s[2]);
    const excludesTests = glob && specs.includes('!**/*.test.*');
    for (const spec of specs) if (!spec.startsWith('!')) found.push({ spec, glob, excludesTests });
  }
  return found;
}

const isInside = (path: string, dir: string) => path === dir || path.startsWith(dir + sep);
const isTest = (file: string) => /\.test\.tsx?$/.test(file);
/** Whether an import names a test file (`./x.test`, `./x.test.tsx`, `./x.test.tsx?raw`). */
const isTestModule = (spec: string) => /\.test(\.[cm]?[jt]sx?)?$/.test(spec.replace(/\?.*$/, ''));
// Whether an import can load a test file: it names one, or it is a glob whose file name has a wildcard ('./*.tsx'
// matches 'x.test.tsx') and no fixed extension other than a script's ('./icons/*.svg' can't), without '!**/*.test.*'
// beside it.
function mayLoadTest(i: Import): boolean {
  if (isTestModule(i.spec)) return true;
  if (!i.glob || i.excludesTests) return false;
  const name = i.spec.replace(/\?.*$/, '').split('/').pop() ?? '';
  if (!/[*?{[]/.test(name)) return false;
  const ext = /\.([A-Za-z0-9]+)$/.exec(name)?.[1];
  return !ext || /^[cm]?[jt]sx?$/.test(ext);
}
const show = (file: string) => relative(SRC, file);

/**
 * The path an import starts from: its module, or for a glob the folder before its first wildcard. Relative to the
 * file, or root-absolute (`/src/...`, which Vite resolves from web/). Null for a package.
 */
function base(file: string, { spec, glob }: Import): string | null {
  let fixed = spec;
  if (glob) {
    const wildcard = spec.search(/[*?{[]/);
    const prefix = wildcard === -1 ? spec : spec.slice(0, wildcard);
    fixed = prefix.replace(/[^/]*$/, '') || './'; // the folder: drop a partial file name ("./page-*.tsx")
  }
  if (fixed.startsWith('.')) return resolve(dirname(file), fixed);
  if (fixed.startsWith('/')) return join(WEB, fixed);
  return null;
}

/** Whether an import can load something in `dir`: its module is in it, or its glob can match files below it. */
function reaches(file: string, i: Import, dir: string): boolean {
  if (i.opaque) return true;
  const path = base(file, i);
  return path !== null && (isInside(path, dir) || (i.glob && isInside(dir, path)));
}

/** The folder of the extension `file` belongs to (extensions/<id>/ or ext/__fixtures__/<id>/), if any. */
function extensionFolder(file: string): string | null {
  for (const root of [EXTENSIONS, FIXTURES]) {
    if (isInside(file, root)) return join(root, relative(root, file).split(sep)[0]);
  }
  return null;
}

const offenders = (file: string, bad: Import[]) => bad.map((i) => `${show(file)} -> ${i.spec}`);

/** Rule 1: a core file (not the loader, not an extension) reaching into extensions/. */
function coreImportingExtensions(file: string, code: string): string[] {
  if (file === LOADER || extensionFolder(file)) return [];
  return offenders(file, imports(code).filter((i) => reaches(file, i, EXTENSIONS)));
}

/**
 * Rule 2: an extension importing anything but `@mdmesh/console`, `react` and its own files (its tests: also vitest,
 * @testing-library and `@mdmesh/console/testing`). Of its own files, only its tests may import a test file.
 */
function extensionImportingCore(file: string, code: string): string[] {
  const folder = extensionFolder(file);
  if (!folder) return [];
  const packageAllowed = (spec: string) => EXTENSION_PACKAGES.has(spec) || (isTest(file) && EXTENSION_TEST_PACKAGES.has(spec));
  const allowed = (i: Import) => {
    if (i.opaque) return false;
    const path = base(file, i);
    if (path === null) return !i.glob && packageAllowed(i.spec);
    return isInside(path, folder) && (isTest(file) || !mayLoadTest(i));
  };
  return offenders(file, imports(code).filter((i) => !allowed(i)));
}

/** Rule 3: a non-test file outside the fixtures reaching into them. */
function nonTestImportingFixtures(file: string, code: string): string[] {
  if (isTest(file) || isInside(file, FIXTURES)) return [];
  return offenders(file, imports(code).filter((i) => reaches(file, i, FIXTURES)));
}

// This file is left out: its cases below are bad imports on purpose, written as strings.
const files = sourceFiles(SRC)
  .filter((file) => file !== import.meta.filename)
  .map((file) => ({ file, code: readFileSync(file, 'utf8') }));
const overTree = (rule: (file: string, code: string) => string[]) => files.flatMap(({ file, code }) => rule(file, code));

describe('import guards', () => {
  it('scans the fixture extension', () => {
    expect(files.filter(({ file }) => isInside(file, FIXTURES)).length).toBeGreaterThanOrEqual(2);
  });

  it('only ext/load.ts imports web/src/extensions', () => {
    expect(overTree(coreImportingExtensions)).toEqual([]);
  });

  it('extensions import only @mdmesh/console, react and their own files', () => {
    expect(overTree(extensionImportingCore)).toEqual([]);
  });

  it('only tests import the test fixtures', () => {
    expect(overTree(nonTestImportingFixtures)).toEqual([]);
  });
});

describe('import guards catch what they should (the rules above are not vacuous)', () => {
  const at = (path: string) => join(SRC, path);

  it.each([
    ['a relative import', `import { x } from '../extensions/netaccess';`],
    ['an import over several lines', `import {\n  NetworkPage,\n  other,\n} from '../extensions/netaccess/NetworkPage';`],
    ['a dynamic import', `const m = await import('../extensions/netaccess/index.ts');`],
    ['a re-export', `export * from '../extensions/netaccess';`],
    ['a root-absolute import', `import x from '/src/extensions/netaccess';`],
    ['a broad glob', `const all = import.meta.glob('../**/*.tsx');`],
    ['a glob into extensions', `const all = import.meta.glob<{ default: unknown }>('../extensions/*/index.ts', { eager: true });`],
    ['a glob list', `const all = import.meta.glob(['./*.ts', '/src/extensions/**/*.ts']);`],
    ['a template literal', 'const m = await import(`../extensions/${id}/index.ts`);'],
    ['a glob from a base', `const all = import.meta.glob('./*/index.ts', { base: '../extensions' });`],
    ['a glob from a quoted base', `const all = import.meta.glob('./*/index.ts', { 'base': '../extensions' });`],
    ['a spread glob', `const all = { ...import.meta.glob('../extensions/*/index.ts', { eager: true }) };`],
  ])('a core file reaching extensions/ through %s', (_, code) => {
    expect(coreImportingExtensions(at('pages/DevicesPage.tsx'), code)).toHaveLength(1);
  });

  it('lets core import its own files, and globs that cannot reach extensions/', () => {
    const code = [
      `import { x } from './ui/Button';`,
      `import y from '/src/api/client';`,
      `const p = import.meta.glob('./pages/*.tsx');`,
      `const n = import.meta.glob(['./ui/*.ts', '!./ui/*.test.ts']);`,
    ].join('\n');
    expect(coreImportingExtensions(at('App.tsx'), code)).toEqual([]);
  });

  it.each([
    [`import { apiClient } from '../../api/client';`],
    [`import { createRoot } from 'react-dom/client';`],
    [`import 'react-dom';`],
    [`import { createRegistry } from '@mdmesh/console/../ext/registry';`],
    [`import { apiClient } from '/src/api/client';`],
    [`import { x } from '../other/index';`],
    [`const siblings = import.meta.glob('../*/index.ts');`],
    ['const m = await import(`../../api/client`);'],
    [`const icons = import.meta.glob('./*.svg', { base: '../../api', query: '?raw' });`],
    [`const api = import.meta.glob(['./*.ts', '!**/*.test.*'], { 'base': '../../api', eager: true });`],
    [`const api = import.meta.glob(['./*.ts', '!**/*.test.*'], { "base": '../../api', eager: true });`],
    [`const api = import.meta.glob(['./*.ts', '!**/*.test.*'], { ['base']: '../../api' });`],
    [String.raw`const api = import.meta.glob(['./*.ts', '!**/*.test.*'], { \u0062ase: '../../api' });`],
    [`const api = import.meta.glob(['./*.ts', '!**/*.test.*'], { base /* */ : '../../api' });`],
    [`const api = import.meta.glob(['./*.ts', '!**/*.test.*'], { ...opts });`],
    [`const api = { ...import.meta.glob('../../api/*.ts', { eager: true }) };`],
    [`const api = { ...import.meta.glob(['./*.ts', '!**/*.test.*'], { base: '../../api', eager: true }) };`],
    [`const api = [...import('../../api/client')];`],
  ])('an extension importing outside its folder: %s', (code) => {
    expect(extensionImportingCore(at('extensions/netaccess/index.ts'), code)).toHaveLength(1);
  });

  it.each([
    [`import { renderConsole } from '@mdmesh/console/testing';`],
    [`import { describe } from 'vitest';`],
    [`import { screen } from '@testing-library/react';`],
    [`import { fixture } from './fixtures.test.tsx';`],
    [`import { fixture } from './fixtures.test';`],
    [`const lazy = () => import('./sub/helpers.test.ts');`],
    [`import source from './netaccess.test.tsx?raw';`],
    [`const pages = import.meta.glob('./*.tsx', { eager: true });`],
    [`const lazy = import.meta.glob('./**/*');`],
    [`const pages = import.meta.glob(['./*.tsx', '!./Draft.tsx']);`],
    [`const pages = import.meta.glob(['./**/*.tsx', '!./pages/a.test.tsx']);`],
    [`const pages = import.meta.glob(['./pages/*.tsx', '!./pages/*.test.tsx']);`],
    [`const all = import.meta.glob('./**');`],
    // Vite resolves '!./…' from this file's folder, so it doesn't leave out the tests '../*.tsx' reaches from sub/.
    [`const pages = import.meta.glob<{ default: unknown }>(['./pages/*.tsx', '!./**/*.test.*'], { eager: true });`],
    [`const pages = import.meta.glob(['./[A-Z]*.tsx']);`],
    ['const pages = import.meta.glob(`./*.tsx`);'],
    ['const page = (n: string) => import(`./pages/${n}.tsx`);'],
    [`const pages = import.meta.glob<() => void>('./*.tsx');`],
    [`const g = import.meta.glob;`],
    [`const pages = import.meta.glob<Record<string, () => Promise<unknown>>>('./*.tsx');`],
  ])('an extension’s non-test file importing what only its tests may: %s', (code) => {
    expect(extensionImportingCore(at('extensions/netaccess/NetworkPage.tsx'), code)).toHaveLength(1);
  });

  it('lets an extension’s tests import vitest, @testing-library and @mdmesh/console/testing, and still nothing of core', () => {
    const code = [
      `import { describe, expect, it } from 'vitest';`,
      `import { screen } from '@testing-library/react';`,
      `import { renderConsole, stubApi } from '@mdmesh/console/testing';`,
      `import netaccess from './index';`,
      `import { fixture } from './fixtures.test';`,
    ].join('\n');
    expect(extensionImportingCore(at('extensions/netaccess/netaccess.test.tsx'), code)).toEqual([]);
    expect(extensionImportingCore(at('extensions/netaccess/netaccess.test.tsx'), `import { x } from '../../test/console';`)).toHaveLength(1);
  });

  it('lets an extension import @mdmesh/console, react and its own files', () => {
    const code = [
      `import { Card } from '@mdmesh/console';`,
      `import { useState } from 'react';`,
      `import { Page } from './Page';`,
      `import './netaccess.css';`,
      `const Lazy = () => import('./sub/Lazy');`,
      `const own = import.meta.glob(['./widgets/*.tsx', '!**/*.test.*']);`,
      `const pages = import.meta.glob<{ default: unknown }>(\n  ['./pages/*.tsx', "!**/*.test.*",],\n  { eager: true },\n);`,
      `const shots = import.meta.glob(['./shots/[a-z]*.png']);`,
      `const raw = import.meta.glob<Record<string, string>>('./icons/*.svg', { query: { raw: true, v: 2 }, import: 'default', eager: false, });`,
      `const svg = import.meta.glob<() => Promise<string>>('./icons/*.svg', { query: '?url', exhaustive: false, caseSensitive: true });`,
      `const x = settings.import('./anything'); // obj.import(…) is a method, not an import`,
      `const y = settings?.import('../../api/client');`,
      `const icons = import.meta.glob('./icons/*.svg', { query: '?url', eager: true });`,
      `const one = import.meta.glob('./widgets/Clock.tsx');`,
    ].join('\n');
    expect(extensionImportingCore(at('extensions/netaccess/index.ts'), code)).toEqual([]);
  });

  it.each([
    [`import demo from '../ext/__fixtures__/demo';`],
    [`import demo from '/src/ext/__fixtures__/demo';`],
    [`const all = import.meta.glob('../ext/**/*.ts');`],
  ])('a non-test file importing the fixtures: %s', (code) => {
    expect(nonTestImportingFixtures(at('pages/DevicesPage.tsx'), code)).toHaveLength(1);
  });

  it('lets tests import the fixtures', () => {
    expect(nonTestImportingFixtures(at('App.test.tsx'), `import demo from './ext/__fixtures__/demo';`)).toEqual([]);
  });
});
