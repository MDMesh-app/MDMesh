import { describe, expect, it } from 'vitest';
import demo from './__fixtures__/demo';
import { registry, selectExtensions } from './load';

const at = (folder: string) => `../extensions/${folder}/index.ts`;

describe('selectExtensions', () => {
  it('takes each folder’s default export, in folder order', () => {
    const other = { ...demo, id: 'alpha' };
    const { definitions, problems } = selectExtensions({ [at('demo')]: { default: demo }, [at('alpha')]: { default: other } }, undefined);
    expect(problems).toEqual([]);
    expect(definitions.map((d) => d.id)).toEqual(['alpha', 'demo']);
  });

  it('leaves out the extensions VITE_EXTENSIONS_DISABLED names, even broken ones', () => {
    const modules = { [at('demo')]: { default: demo }, [at('broken')]: {} };
    expect(selectExtensions(modules, ' broken, demo ')).toEqual({ definitions: [], problems: [] });
  });

  it('accepts an index.tsx manifest', () => {
    expect(selectExtensions({ '../extensions/demo/index.tsx': { default: demo } }, undefined).definitions).toEqual([demo]);
  });

  it('reports a folder without a default export', () => {
    expect(selectExtensions({ [at('broken')]: {} }, undefined).problems).toEqual([
      '[ext:broken] ../extensions/broken/index.ts has no default export: export default defineExtension({ ... })',
    ]);
  });

  it('reports a folder whose manifest throws while it is read, and keeps the others', () => {
    const throwing = {
      get default(): unknown {
        throw new Error('no manifest today');
      },
    };
    expect(selectExtensions({ [at('broken')]: throwing, [at('demo')]: { default: demo } }, undefined)).toEqual({
      definitions: [demo],
      problems: ['[ext:broken] could not be read: no manifest today'],
    });
  });

  it('reports an extension whose id is not its folder name', () => {
    expect(selectExtensions({ [at('netaccess')]: { default: demo } }, undefined).problems).toEqual([
      '[ext:netaccess] the extension id "demo" must be its folder name',
    ]);
  });
});

describe('the production registry', () => {
  // Importing ./load already validated every manifest in web/src/extensions: under test it throws on any problem.
  it('loads core and every extension folder without problems', () => {
    expect(registry.extensions[0]).toEqual({ id: 'core', title: 'MDMesh', core: true });
  });

  it('keeps the ids of core’s nav items and pages (forks and links depend on them)', () => {
    expect(registry.entries('nav.item').filter((e) => e.extension.core).map((e) => e.contribution.id)).toEqual([
      'overview', 'devices', 'configurations', 'apps', 'enroll', 'settings',
    ]);
    expect(registry.entries('route.page').filter((e) => e.extension.core).map((e) => e.contribution.path)).toEqual(
      expect.arrayContaining(['/', '/devices', '/devices/:id', '/configurations', '/configurations/:id', '/apps', '/enroll', '/settings']),
    );
  });
});
