import { createElement, forwardRef, memo } from 'react';
import { describe, expect, it } from 'vitest';
import { legacyRedirect } from '../routes';
import { IconPulse } from '../ui/icons';
import { CORE_EXTENSION } from './core';
import { createRegistry, sectionAnchor, sectionNamespace, type SlotEntry } from './registry';
import { CONSOLE_SEGMENTS, EDGE_PREFIXES } from './reservedPaths';
import { CORE_STORAGE, lazyComponent, type Contribution, type ExtensionDefinition, type Requirement } from './types';

const Body = lazyComponent(async () => ({ Body: () => null }), 'Body');

function ext(id: string, contributions: Contribution[], extra: Partial<ExtensionDefinition> = {}): ExtensionDefinition {
  return { id, apiVersion: 1, title: id, contributions, ...extra };
}
const page = (id: string, path: string, extra: Partial<Contribution> = {}) =>
  ({ slot: 'route.page', id, path, component: Body, ...extra }) as Contribution;
const nav = (id: string, to: string, order = 1000) =>
  ({ slot: 'nav.item', id, order, label: id, icon: IconPulse, to }) as Contribution;
const card = (id: string, order: number) =>
  ({ slot: 'overview.card', id, order, placement: 'main', component: Body }) as Contribution;
const settings = (id: string) =>
  ({ slot: 'settings.section', id, order: 1000, title: id, component: Body }) as Contribution;
const section = (id: string, namespace?: string) =>
  ({ slot: 'config.section', id, order: 1000, title: id, storage: { namespace, defaults: {} }, component: Body }) as Contribution;

/** The problems of registering `extensions` after core. */
function problemsOf(...extensions: ExtensionDefinition[]): string[] {
  return createRegistry(CORE_EXTENSION, extensions).problems;
}
const keys = (entries: readonly SlotEntry[]) => entries.map((e) => e.key);

describe('createRegistry: a valid extension', () => {
  it('registers core and the extension with no problems', () => {
    const { registry, problems } = createRegistry(CORE_EXTENSION, [ext('demo', [page('demo', '/demo'), nav('demo', '/demo')])]);
    expect(problems).toEqual([]);
    expect(registry.extensions.map((e) => e.id)).toEqual(['core', 'demo']);
    expect(keys(registry.entries('route.page'))).toContain('demo/demo');
    expect(keys(registry.entries('nav.item'))).toContain('demo/demo');
  });

  it('freezes what it returns', () => {
    const { registry } = createRegistry(CORE_EXTENSION, []);
    const entries = registry.entries('nav.item');
    expect(Object.isFrozen(registry)).toBe(true);
    expect(Object.isFrozen(entries)).toBe(true);
    expect(Object.isFrozen(entries[0])).toBe(true);
    expect(Object.isFrozen(entries[0].contribution)).toBe(true);
  });

  it('returns an empty list for a slot nobody fills', () => {
    expect(createRegistry(CORE_EXTENSION, []).registry.entries('list.column')).toEqual([]);
  });
});

describe('createRegistry: rejected extensions', () => {
  it.each([
    ['an uppercase id', ext('Demo', [])],
    ['an id with an underscore', ext('my_ext', [])],
    ['an id ending in a hyphen', ext('demo-', [])],
    ['a one-letter-too-long id', ext(`a${'b'.repeat(32)}`, [])],
  ])('rejects %s', (_, definition) => {
    expect(problemsOf(definition)).toEqual([expect.stringMatching(/must be lowercase kebab-case/)]);
  });

  it('rejects the reserved id "core"', () => {
    expect(problemsOf(ext('core', []))).toEqual([expect.stringMatching(/reserved for MDMesh/)]);
  });

  it('rejects a second extension with the same id', () => {
    expect(problemsOf(ext('demo', []), ext('demo', []))).toEqual([expect.stringMatching(/already uses this id/)]);
  });

  it('rejects another API version', () => {
    const stale = { ...ext('demo', []), apiVersion: 2 } as unknown as ExtensionDefinition;
    expect(problemsOf(stale)).toEqual(['[ext:demo] written for extension API 2, this console runs 1']);
  });

  it('rejects a missing title', () => {
    expect(problemsOf(ext('demo', [], { title: ' ' }))).toEqual(['[ext:demo] needs a title']);
  });

  it('rejects something that is not a definition', () => {
    expect(problemsOf({ id: 'demo' } as unknown as ExtensionDefinition)).toEqual([expect.stringMatching(/not an extension definition/)]);
  });

  it.each([
    ['null', null],
    ['a string', 'settings'],
    ['a list', ['settings']],
  ])('rejects requires that is %s, dropping all of it (instead of crashing or gating nothing)', (_, requires) => {
    const { registry, problems } = createRegistry(CORE_EXTENSION, [
      ext('demo', [page('demo', '/demo')], { requires: requires as unknown as Requirement }),
    ]);
    expect(problems).toEqual(['[ext:demo] requires: must be an object such as { permission: "settings" }']);
    expect(keys(registry.entries('route.page'))).not.toContain('demo/demo');
  });

  it('reports and skips a manifest that throws while it is read, and keeps the other extensions', () => {
    const throwing = {
      ...page('boom', '/boom'),
      get path(): string {
        throw new Error('no path today');
      },
    };
    const { registry, problems } = createRegistry(CORE_EXTENSION, [
      ext('broken', [page('first', '/broken'), throwing as Contribution]),
      ext('demo', [page('demo', '/demo')]),
    ]);
    expect(problems).toEqual(['[ext:broken] could not be read: no path today']);
    expect(registry.extensions.map((e) => e.id)).toEqual(['core', 'demo']);
    // Nothing of the broken extension stays, not even what was read before the throw.
    expect(keys(registry.entries('route.page'))).not.toContain('broken/first');
    expect(keys(registry.entries('route.page'))).toContain('demo/demo');
  });

  it('reports a manifest whose id cannot be read', () => {
    const definition = {
      ...ext('demo', []),
      get id(): string {
        throw new Error('no id');
      },
    };
    expect(problemsOf(definition)).toEqual(['[ext:?] could not be read: no id']);
  });

  it('keeps the values it validated: each field of a manifest is read once', () => {
    let reads = 0;
    const definition = {
      ...ext('demo', [page('demo', '/demo')]),
      get requires(): Requirement {
        return ++reads === 1 ? { permission: 'settings' } : (null as unknown as Requirement);
      },
    };
    const { registry, problems } = createRegistry(CORE_EXTENSION, [definition]);
    expect(problems).toEqual([]);
    expect(registry.entries('route.page').find((e) => e.key === 'demo/demo')?.requires).toEqual([{ permission: 'settings' }]);
  });

  it('freezes its own copies, never the manifest’s objects', () => {
    const contribution = page('demo', '/demo');
    createRegistry(CORE_EXTENSION, [ext('demo', [contribution])]);
    expect(Object.isFrozen(contribution)).toBe(false);
  });

  it('rejects an empty permission list on the extension, dropping all of it', () => {
    const { registry, problems } = createRegistry(CORE_EXTENSION, [
      ext('demo', [page('demo', '/demo')], { requires: { permission: [] } }),
    ]);
    expect(problems).toEqual([expect.stringMatching(/requires: permission must be/)]);
    expect(keys(registry.entries('route.page'))).not.toContain('demo/demo');
  });
});

describe('createRegistry: rejected contributions', () => {
  it('drops only the bad contribution and keeps the rest of the extension', () => {
    const { registry, problems } = createRegistry(CORE_EXTENSION, [ext('demo', [page('ok', '/demo'), page('bad', '/rest/x')])]);
    expect(problems).toHaveLength(1);
    expect(keys(registry.entries('route.page'))).toContain('demo/ok');
    expect(keys(registry.entries('route.page'))).not.toContain('demo/bad');
  });

  it('rejects an unknown slot', () => {
    const odd = { slot: 'sidebar.widget', id: 'w', order: 1 } as unknown as Contribution;
    expect(problemsOf(ext('demo', [odd]))).toEqual(['[ext:demo] sidebar.widget "w": unknown slot']);
  });

  it('rejects a contribution that is not an object, without crashing', () => {
    const odd = [null, 'nav', page('ok', '/demo')] as unknown as Contribution[];
    const { registry, problems } = createRegistry(CORE_EXTENSION, [ext('demo', odd)]);
    expect(problems).toEqual(['[ext:demo] contribution 1 is not an object', '[ext:demo] contribution 2 is not an object']);
    expect(keys(registry.entries('route.page'))).toContain('demo/ok');
  });

  it.each([
    ['a nav item without a target', { ...nav('a', '/demo'), to: undefined }, 'needs to (a text)'],
    ['a nav item without an icon', { ...nav('a', '/demo'), icon: undefined }, 'needs icon (a component)'],
    ['a page without a component', { ...page('a', '/demo'), component: undefined }, 'needs component (a component)'],
    ['a column without a cell', { slot: 'list.column', id: 'a', order: 1, list: 'devices', header: 'A' }, 'needs cell (a function)'],
  ])('rejects %s', (_, contribution, message) => {
    expect(problemsOf(ext('demo', [contribution as Contribution]))).toEqual([expect.stringContaining(message)]);
  });

  const column = { slot: 'list.column', id: 'a', order: 1, list: 'devices', header: 'A', cell: () => 'a' };
  const rowAction = { slot: 'list.rowAction', id: 'a', order: 1, list: 'devices', item: () => null };
  const bulk = { slot: 'list.bulkAction', id: 'a', order: 1, list: 'devices', label: 'A', run: () => {} };
  const tab = { slot: 'device.tab', id: 'a', order: 1, label: 'A', component: Body };

  it.each([
    ['a page whose component is a plain object', { ...page('a', '/demo'), component: {} }, 'needs component (a component)'],
    ['a nav badge that is a string (it would render as a <hi> element)', { ...nav('a', '/demo'), badge: 'hi' }, 'badge must be a component'],
    ['a device tab icon that is not a component', { ...tab, icon: '<svg/>' }, 'icon must be a component'],
    ['a section subtitle that is not text', { ...section('a'), subtitle: 5 }, 'subtitle must be a text'],
    ['a section validate that is not a function', { ...section('a'), validate: [] }, 'validate must be a function'],
    ['a section changes that is not a function', { ...section('a'), changes: 1 }, 'changes must be a function'],
    ['a section whose storage is not an object', { ...section('a'), storage: 'net' }, 'needs storage: { defaults }'],
    ['a column sortValue that is not a function', { ...column, sortValue: 'name' }, 'sortValue must be a function'],
    ['a column mono that is not a boolean', { ...column, mono: 'yes' }, 'mono must be true or false'],
    ['a column align outside its values', { ...column, align: 'center' }, 'align must be one of "left", "right"'],
    ['a bulk action danger that is not a boolean', { ...bulk, danger: 1 }, 'danger must be true or false'],
    ['a column on a list it cannot extend', { ...column, list: 'configurations' }, 'needs list (one of "devices", "apps")'],
    ['a column without a list', { ...column, list: undefined }, 'needs list (one of "devices", "apps")'],
    ['a row action on an unknown list', { ...rowAction, list: 'users' }, 'needs list (one of "devices", "apps")'],
    ['a row action on the Configurations list, which has no host', { ...rowAction, list: 'configurations' }, 'needs list (one of "devices", "apps")'],
    // The header and the bulk icon render in core markup: an object there would break the whole table or bar.
    ['a column without a header', { ...column, header: undefined }, 'needs header (text or a React element)'],
    ['a column whose header is an object', { ...column, header: {} }, 'needs header (text or a React element)'],
    ['a column whose header is blank', { ...column, header: ' ' }, 'needs header (text or a React element)'],
    ['a bulk action icon that is not an element', { ...bulk, icon: IconPulse }, 'icon must be a React element'],
    ['a bulk action on a list without a bulk bar', { ...bulk, list: 'apps' }, 'needs list (one of "devices")'],
    ['an overview card in an unknown place', { ...card('a', 1), placement: 'top' }, 'needs placement (one of "main", "aside", "stat")'],
  ])('rejects %s', (_, contribution, message) => {
    expect(problemsOf(ext('demo', [contribution as Contribution]))).toEqual([expect.stringContaining(message)]);
  });

  it('accepts optional fields of the right kind, and memo, forwardRef and lazy components', () => {
    const contributions = [
      page('demo', '/demo', { component: memo(() => null) as unknown as typeof Body }),
      { ...nav('demo', '/demo'), badge: forwardRef(() => null) },
      { ...tab, icon: IconPulse },
      { ...section('demo'), subtitle: 'Sub', validate: () => [], changes: () => 1 },
      { ...column, sortValue: () => 1, mono: true, align: 'right' },
      { ...column, id: 'b', header: createElement('abbr', { title: 'Battery' }, 'Bat') },
      { ...rowAction, list: 'apps' },
      { ...bulk, danger: false, icon: createElement(IconPulse) },
      card('demo', 1),
    ] as Contribution[];
    expect(problemsOf(ext('demo', contributions))).toEqual([]);
  });

  it('rejects a bad contribution id', () => {
    expect(problemsOf(ext('demo', [card('Big Card', 1)]))).toEqual([expect.stringMatching(/"Big Card": id .* kebab-case/)]);
  });

  it('rejects an id another extension already has in the slot (and one core has)', () => {
    expect(problemsOf(ext('one', [card('stats', 1)]), ext('two', [card('stats', 2)]))).toEqual([
      '[ext:two] overview.card "stats": the id is already taken by one/stats',
    ]);
    expect(problemsOf(ext('demo', [nav('devices', '/devices')]))).toEqual([
      '[ext:demo] nav.item "devices": the id is already taken by core/devices',
    ]);
  });

  it('allows the same id in different slots', () => {
    expect(problemsOf(ext('demo', [page('demo', '/demo'), nav('demo', '/demo'), card('demo', 1)]))).toEqual([]);
  });

  it('rejects an order that is not a finite number', () => {
    expect(problemsOf(ext('demo', [card('a', Number.NaN)]))).toEqual([expect.stringMatching(/order must be a finite number/)]);
  });

  it.each([
    [{ permission: '' }, /permission must be/],
    [{ permission: ['edit_devices', ' '] }, /permission must be/],
    [{ serverPlugin: '' }, /serverPlugin must be a plugin id/],
    [{ when: true }, /when must be a function/],
    [null, /requires: must be an object/],
    ['settings', /requires: must be an object/],
    [['settings'], /requires: must be an object/],
  ])('rejects the requirement %j', (requires, message) => {
    expect(problemsOf(ext('demo', [{ ...card('a', 1), requires } as Contribution]))).toEqual([expect.stringMatching(message)]);
  });
});

describe('createRegistry: route paths', () => {
  it.each(['/Demo', '/demo/', 'demo', '/demo_page', '/:id', '/demo/*', '/demo?tab=1'])('rejects the malformed path %s', (path) => {
    expect(problemsOf(ext('demo', [page('demo', path)]))).toEqual([expect.stringMatching(/must be lowercase kebab-case segments/)]);
  });

  it('accepts params after a fixed first segment', () => {
    expect(problemsOf(ext('demo', [page('demo', '/network-map/:deviceId/history')]))).toEqual([]);
  });

  it.each(EDGE_PREFIXES.map((p) => `${p}x`))('rejects %s, which the edge sends elsewhere', (path) => {
    expect(problemsOf(ext('demo', [page('demo', path)]))).toEqual([expect.stringMatching(/the edge sends to another backend/)]);
  });

  it.each(CONSOLE_SEGMENTS.filter((s) => /^\/[a-z0-9-]+$/.test(s)).map((s) => `${s}/x`))('rejects %s, which the console reserves', (path) => {
    expect(problemsOf(ext('demo', [page('demo', path)]))).toEqual([expect.stringMatching(/which the console reserves/)]);
  });

  it.each(['/devices/network', '/settings/extra', '/apps/:id/more'])('rejects %s, under a core page', (path) => {
    expect(problemsOf(ext('demo', [page('demo', path)]))).toEqual([expect.stringMatching(/under the core page/)]);
  });

  it('rejects a path the same extension already serves, whatever its params are called', () => {
    expect(problemsOf(ext('one', [page('a', '/map/:id'), page('b', '/map/:deviceId')]))).toEqual([
      '[ext:one] route.page "b": path "/map/:deviceId" is already served by one/a',
    ]);
  });

  it('gives each first segment to one extension, so none can shadow another’s pages', () => {
    expect(problemsOf(ext('one', [page('a', '/reports/:id')]), ext('two', [page('b', '/reports/new')]))).toEqual([
      '[ext:two] route.page "b": path "/reports/new" is under /reports, which the extension one uses',
    ]);
  });

  it('keeps the reserved legacy paths in step with the redirects in routes.ts', () => {
    for (const legacy of ['/v2', '/dashboard', '/configs', '/overview']) {
      expect(CONSOLE_SEGMENTS).toContain(legacy);
      expect(legacyRedirect(legacy)).not.toBeNull();
    }
  });
});

describe('createRegistry: nav items', () => {
  it('rejects an item no page serves', () => {
    expect(problemsOf(ext('demo', [nav('demo', '/nowhere')]))).toEqual(['[ext:demo] nav.item "demo": no route.page serves "/nowhere"']);
  });

  it('rejects a target with a query or a hash', () => {
    expect(problemsOf(ext('demo', [page('demo', '/demo'), nav('demo', '/demo?tab=1')]))).toEqual([
      expect.stringMatching(/to "\/demo\?tab=1" must be a path/),
    ]);
  });

  it('links an item to the page react-router would pick, not the first declared', () => {
    const { registry } = createRegistry(CORE_EXTENSION, [
      ext('reports', [
        page('report', '/reports/:id'),
        page('new-report', '/reports/new', { requires: { permission: 'edit_reports' } }),
        nav('new-report', '/reports/new'),
      ]),
    ]);
    const item = registry.entries('nav.item').find((e) => e.key === 'reports/new-report');
    expect(item?.requires).toEqual([{ permission: 'edit_reports' }]);
  });

  it('accepts an item that points at a parameterised page', () => {
    expect(problemsOf(ext('demo', [page('map', '/map/:id'), nav('map', '/map/home')]))).toEqual([]);
  });

  it('takes on the requirements of the page it points at', () => {
    const { registry } = createRegistry(CORE_EXTENSION, [
      ext('demo', [page('demo', '/demo', { requires: { permission: 'settings' } }), nav('demo', '/demo')], {
        requires: { serverPlugin: 'demo' },
      }),
    ]);
    const item = registry.entries('nav.item').find((e) => e.key === 'demo/demo');
    // The item's own (extension-wide) requirement, then the page's two (extension-wide + its own).
    expect(item?.requires).toEqual([{ serverPlugin: 'demo' }, { serverPlugin: 'demo' }, { permission: 'settings' }]);
    const coreItem = registry.entries('nav.item').find((e) => e.key === 'core/configurations');
    expect(coreItem?.requires).toEqual([{ permission: 'configurations' }]);
  });
});

describe('createRegistry: section anchors and storage', () => {
  it('prefixes extension anchors so they never take a core one', () => {
    const core = { id: 'core', title: 'MDMesh', core: true };
    const demo = { id: 'demo', title: 'Demo', core: false };
    expect(sectionAnchor(core, 'updates')).toBe('updates');
    expect(sectionAnchor(demo, 'updates')).toBe('x-demo-updates');
  });

  it('rejects two settings sections that end up with the same anchor', () => {
    expect(problemsOf(ext('a-b', [settings('c')]), ext('a', [settings('b-c')]))).toEqual([
      '[ext:a] settings.section "b-c": anchor "#x-a-b-c" is already taken by a-b/c',
    ]);
  });

  it('rejects two configuration sections that end up with the same anchor', () => {
    expect(problemsOf(ext('a-b', [section('c')]), ext('a', [section('b-c')]))).toEqual([
      expect.stringMatching(/anchor "#x-a-b-c" is already taken/),
    ]);
  });

  it('rejects two configuration sections writing the same namespace', () => {
    expect(problemsOf(ext('net', [section('a'), section('b')]))).toEqual([
      '[ext:net] config.section "b": storage namespace "net" is already used by net/a',
    ]);
  });

  it('keeps each extension to its own namespaces: its id, or its id, a dot and a name', () => {
    expect(problemsOf(ext('net', [section('a'), section('b', 'net.vpn'), section('c', 'net.split-tunnel')]))).toEqual([]);
    expect(problemsOf(ext('net', [section('a')]), ext('other', [section('b', 'net')]))).toEqual([
      '[ext:other] config.section "b": storage namespace "net" must be "other" or "other.<name>", <name> lowercase kebab-case',
    ]);
  });

  it('never lets an extension take a namespace that is another extension’s id, even one left out of the build', () => {
    // `net-access` is disabled (VITE_EXTENSIONS_DISABLED) or not in this fork: its namespace is still not net's.
    expect(problemsOf(ext('net', [section('a', 'net-access')]))).toEqual([
      expect.stringContaining('storage namespace "net-access" must be "net" or "net.<name>"'),
    ]);
    expect(problemsOf(ext('net', [section('a', 'net-access')]), ext('net-access', [section('b')]))).toEqual([
      expect.stringContaining('storage namespace "net-access" must be "net"'),
    ]);
  });

  it('binds core’s sections to the configuration’s own fields: no namespace, and the rule is for extensions only', () => {
    const { registry, problems } = createRegistry(CORE_EXTENSION, [ext('net', [section('a'), section('b', 'net.vpn')])]);
    expect(problems).toEqual([]);
    const namespaces = registry.entries('config.section').map((e) => [e.key, sectionNamespace(e)]);
    expect(namespaces.filter(([key]) => String(key).startsWith('core/')).map(([, ns]) => ns)).toEqual(Array(7).fill(null));
    expect(namespaces.filter(([key]) => !String(key).startsWith('core/'))).toEqual([['net/a', 'net'], ['net/b', 'net.vpn']]);
  });

  it('gives an extension that copies core’s storage shape a namespace, and holds it to a JSON object', () => {
    expect(problemsOf(ext('net', [{ ...section('a'), storage: { ...CORE_STORAGE } } as Contribution]))).toEqual([
      '[ext:net] config.section "a": storage.defaults must be a JSON object',
    ]);
    expect(problemsOf(ext('net', [{ ...section('a'), storage: { defaults: ['a'] } } as Contribution]))).toEqual([
      '[ext:net] config.section "a": storage.defaults must be a JSON object',
    ]);
  });

  it('keeps a frozen JSON copy of the defaults, so a body cannot change them in place, and leaves the manifest’s own', () => {
    const defaults = { hosts: ['a'], vpn: { on: false } };
    const { registry, problems } = createRegistry(CORE_EXTENSION, [
      ext('net', [{ ...section('a'), storage: { defaults } } as Contribution]),
    ]);
    expect(problems).toEqual([]);
    const kept = registry.entries('config.section').find((e) => e.key === 'net/a')!.contribution.storage;
    expect(kept.defaults).toEqual(defaults);
    expect(Object.isFrozen(kept)).toBe(true);
    expect(Object.isFrozen(kept.defaults.hosts) && Object.isFrozen(kept.defaults.vpn)).toBe(true);
    expect(() => kept.defaults.hosts.push('b')).toThrow(TypeError);
    expect(Object.isFrozen(defaults.hosts)).toBe(false);
  });

  it('refuses defaults that are not JSON (a BigInt, a cycle)', () => {
    const cycle: Record<string, unknown> = {};
    cycle.self = cycle;
    for (const defaults of [{ n: BigInt(1) }, cycle]) {
      expect(problemsOf(ext('net', [{ ...section('a'), storage: { defaults } } as Contribution]))).toEqual([
        '[ext:net] config.section "a": storage.defaults must be a JSON object',
      ]);
    }
  });

  it.each(['Net', 'net.', 'net.Vpn', 'net.vpn.wifi', 'net.vpn_wifi', 'net..vpn', `net.${'a'.repeat(33)}`, 'other.vpn'])(
    'rejects the malformed namespace %s',
    (namespace) => {
      expect(problemsOf(ext('net', [section('a', namespace)]))).toEqual([expect.stringMatching(/storage namespace .* must be "net"/)]);
    },
  );
});

describe('createRegistry: order', () => {
  it('sorts each slot by order, then by key, whatever the declaration order', () => {
    const { registry } = createRegistry(CORE_EXTENSION, [
      ext('zeta', [card('b', 200), card('z', 100)]),
      ext('alpha', [card('a', 200), card('c', 50)]),
    ]);
    // Core's cards sit at 100-400 (overview.card is one sequence; the host splits stats from cards).
    expect(keys(registry.entries('overview.card')).filter((k) => !k.startsWith('core/'))).toEqual([
      'alpha/c', 'zeta/z', 'alpha/a', 'zeta/b',
    ]);
    expect(keys(registry.entries('overview.card')).slice(0, 4)).toEqual(['alpha/c', 'core/needs-attention', 'core/sync', 'zeta/z']);
  });

  it('puts core’s item first on an equal order, whatever the extension’s key', () => {
    const { registry } = createRegistry(CORE_EXTENSION, [ext('aaa', [page('aaa', '/aaa'), nav('aaa', '/aaa', 200)])]);
    expect(keys(registry.entries('nav.item')).slice(0, 3)).toEqual(['core/overview', 'core/devices', 'aaa/aaa']);
  });

  it('puts an extension nav item between core items by ORDER', () => {
    const { registry } = createRegistry(CORE_EXTENSION, [ext('demo', [page('demo', '/demo'), nav('demo', '/demo', 250)])]);
    expect(keys(registry.entries('nav.item'))).toEqual([
      'core/overview', 'core/devices', 'demo/demo', 'core/configurations', 'core/apps', 'core/enroll', 'core/settings',
    ]);
  });

  it('notes whether anything depends on the active server plugins', () => {
    expect(createRegistry(CORE_EXTENSION, []).registry.usesServerPlugins).toBe(false);
    const gated = ext('demo', [card('a', 1)], { requires: { serverPlugin: 'demo' } });
    expect(createRegistry(CORE_EXTENSION, [gated]).registry.usesServerPlugins).toBe(true);
  });
});
