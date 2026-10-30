// The configuration editor as a host: core's sections and the extensions' through one registry, and an extension
// section in the editor's changed count, leave guard, validation, Discard, kiosk confirm and single save. Rendered
// through the real routes against a stubbed API, as a fork's extension would be.
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '../../api/auth';
import type { Configuration } from '../../api/configurations';
import { AppRoutes } from '../../App';
import { ConfigEditorPage } from '../../pages/ConfigEditorPage';
import { ROLES, registryOf, renderInConsole } from '../../test/console';
import demo from '../__fixtures__/demo';
import { CORE_EXTENSION } from '../core';
import { ORDER } from '../core/order';
import { createRegistry, type Registry } from '../registry';
import { configSection, defineExtension, lazyComponent, type ConfigSectionProps, type Contribution } from '../types';

const LOAD = { timeout: 3000 };

/** An ERROR envelope, as Response.ERROR(key, data) answers. */
class Refusal {
  constructor(
    readonly key: string,
    readonly data?: unknown,
  ) {}
}

interface Setup {
  config?: Partial<Configuration>;
  /** The answer to the save (PUT): the saved configuration by default. */
  onPut?: (body: Record<string, unknown>) => unknown;
  plugins?: string[];
  devices?: number;
  /** The answer to the role refresh (GET /private/users/current); none by default. */
  currentUser?: () => AuthUser;
}

/** Configuration 7 ("Warehouse"), no apps, an empty library, and `devices` devices on it. */
function serve({ config = {}, onPut, plugins = [], devices = 2, currentUser }: Setup = {}) {
  // GET /{id} always carries extensions ({} for none), as the server's NOT NULL column gives it.
  const stored: Configuration = { id: 7, name: 'Warehouse', kioskMode: false, extensions: {}, ...config };
  const puts: Record<string, unknown>[] = [];
  const answer = (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest', '');
    if (p === '/private/configurations' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      puts.push(body);
      return onPut ? onPut(body) : { ...stored, ...body };
    }
    if (p === `/private/configurations/${stored.id}`) return stored;
    if (p === `/private/configurations/applications/${stored.id}`) return [];
    if (p.startsWith('/private/applications/search')) return [];
    if (p === '/private/agent/v1/configurations/syncSummary') {
      return [{ configurationId: stored.id, total: devices, inSync: devices, outOfSync: 0, unsupported: 0, neverSeen: 0 }];
    }
    if (p === '/plugin/main/private/available') return plugins.map((identifier) => ({ identifier }));
    if (p === '/private/users/current') return currentUser?.() ?? null;
    return null;
  };
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const out = answer(String(input), init);
    const envelope =
      out instanceof Refusal
        ? { status: 'ERROR', message: out.key, data: out.data ?? null }
        : { status: 'OK', message: null, data: out ?? null };
    return new Response(JSON.stringify(envelope), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetch);
  return { puts, fetch };
}

function open(
  setup: Setup = {},
  { registry = registryOf(demo), user = ROLES.superAdmin, path }: { registry?: Registry; user?: AuthUser; path?: string } = {},
) {
  const api = serve(setup);
  renderInConsole(<AppRoutes />, { path: path ?? `/configurations/${setup.config?.id ?? 7}`, registry, user });
  return api;
}

const ext = (id: string, contributions: Contribution[], extra: { requires?: { serverPlugin: string } } = {}) =>
  defineExtension({ id, apiVersion: 1, title: id[0].toUpperCase() + id.slice(1), contributions, ...extra });

const demoLabel = () => screen.findByLabelText('Demo label', undefined, LOAD) as Promise<HTMLInputElement>;
const saveBar = () => screen.queryByRole('region', { name: 'Unsaved changes' });
const navLabels = () =>
  within(screen.getByRole('navigation', { name: 'Sections' }))
    .getAllByRole('link')
    .map((a) => a.textContent);
const navEntry = (label: string) =>
  within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('link', { name: new RegExp(`^${label}`) });
/** Save from the save bar, once it lets you (the assigned apps load first). */
async function save() {
  const button = () => within(saveBar()!).getByRole<HTMLButtonElement>('button', { name: /^(Save and apply|Create configuration)$/ });
  await waitFor(() => expect(button().disabled).toBe(false));
  fireEvent.click(button());
}
const toastText = () => screen.getAllByRole('status').map((t) => t.textContent).join(' | ');
/**
 * Every toast shown from now on, kept after its 5 s timer removes it, so a test that counts them doesn't depend on
 * how fast it runs. Stop it with `stop()`.
 */
function recordToasts() {
  const shown: string[] = [];
  const observer = new MutationObserver((records) => {
    for (const r of records) {
      for (const node of r.addedNodes) {
        if (!(node instanceof HTMLElement)) continue;
        const toasts = node.matches('[role="status"]') ? [node] : [...node.querySelectorAll('[role="status"]')];
        for (const t of toasts) shown.push(t.textContent ?? '');
      }
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
  return { shown, stop: () => observer.disconnect() };
}

async function typeDescription(text: string) {
  const area = (await screen.findByText('Description', undefined, LOAD)).closest('.v2-field')?.querySelector('textarea');
  if (!area) throw new Error('no description field');
  fireEvent.change(area, { target: { value: text } });
}

describe('configuration sections: the host', () => {
  it('places an extension section among core’s by order, in the page and the section nav', async () => {
    open();
    await demoLabel();
    expect(navLabels().map((l) => l?.replace(/\d.*$/, '').trim())).toEqual([
      'Identity', 'Apps', 'Kiosk', 'Network', 'Demo', 'Security', 'Enrollment', 'Headwind only',
    ]);
    const card = document.getElementById('x-demo-demo');
    expect(card?.tagName).toBe('SECTION');
    expect(within(card!).getByRole('heading', { name: 'Demo' })).toBeTruthy();
    expect(within(card!).getByText('Saved under extensions.demo.')).toBeTruthy();
    // Sections follow one another in the page in the nav's order.
    const ids = [...document.querySelectorAll('.v2-seclayout__content > section[id]')].map((s) => s.id);
    expect(ids).toEqual(['identity', 'apps', 'kiosk', 'network', 'x-demo-demo', 'security', 'enrollment', 'headwind']);
  });

  it('with no extension, shows core’s sections only, as before', async () => {
    open({}, { registry: registryOf() });
    await screen.findByText('Description', undefined, LOAD);
    expect(navLabels().map((l) => l?.replace(/\d.*$/, '').trim())).toEqual([
      'Identity', 'Apps', 'Kiosk', 'Network', 'Security', 'Enrollment', 'Headwind only',
    ]);
  });

  it('is built from the registry: core without its Kiosk section has none', async () => {
    const { registry } = createRegistry(
      { ...CORE_EXTENSION, contributions: CORE_EXTENSION.contributions.filter((c) => !(c.slot === 'config.section' && c.id === 'kiosk')) },
      [],
    );
    open({}, { registry });
    await screen.findByText('Description', undefined, LOAD);
    expect(document.getElementById('kiosk')).toBeNull();
    expect(navLabels().join()).not.toContain('Kiosk');
  });

  it('hands the section its stored value, else its defaults, and never writes the defaults', async () => {
    const { puts } = open();
    const label = await demoLabel();
    expect(label.value).toBe('');
    expect(saveBar()).toBeNull();
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
    expect(puts[0].description).toBe('Back room');
  });
});

describe('configuration sections: changes', () => {
  const config = { extensions: { demo: { on: false, label: 'Front' }, netaccess: { port: 443 } } };

  it('counts an edit in the save bar, the nav and the leave guard, and Discard puts the stored value back', async () => {
    open({ config });
    const label = await demoLabel();
    expect(label.value).toBe('Front');
    fireEvent.change(label, { target: { value: 'Back' } });
    expect(within(saveBar()!).getByText('1 unsaved change')).toBeTruthy();
    expect(navEntry('Demo').textContent).toContain('1 changed');

    // Leaving asks first.
    fireEvent.click(screen.getAllByRole('link', { name: /^Devices/ })[0]);
    const dialog = await screen.findByRole('alertdialog', { name: 'Leave without saving?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep editing' }));
    expect(screen.getByLabelText('Demo label')).toBeTruthy();

    fireEvent.click(within(saveBar()!).getByRole('button', { name: 'Discard' }));
    expect(((await demoLabel()) as HTMLInputElement).value).toBe('Front');
    expect(saveBar()).toBeNull();
  });

  it('compares values as JSON, not by their keys’ order (jsonb keeps none)', async () => {
    function Reorder({ value, onChange }: ConfigSectionProps<{ a: number; b: number }>) {
      return <button onClick={() => onChange({ b: value.b, a: value.a })}>Rewrite</button>;
    }
    const reorder = ext('reorder', [
      configSection<{ a: number; b: number }>({
        id: 'reorder',
        order: 1000,
        title: 'Reorder',
        storage: { defaults: { a: 0, b: 0 } },
        component: lazyComponent(async () => ({ Reorder }), 'Reorder'),
      }),
    ]);
    open({ config: { extensions: { reorder: { a: 1, b: 2 } } } }, { registry: registryOf(reorder) });
    fireEvent.click(await screen.findByRole('button', { name: 'Rewrite' }, LOAD));
    expect(saveBar()).toBeNull();
  });

  it('saves core’s fields and the changed namespace in one PUT, on top of the stored extensions', async () => {
    const { puts } = open({ config });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await typeDescription('Back room');
    expect(within(saveBar()!).getByText('2 unsaved changes')).toBeTruthy();
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].description).toBe('Back room');
    // netaccess has no section in this build: it goes back as stored, as does every namespace nobody changed.
    expect(puts[0].extensions).toEqual({ demo: { on: false, label: 'Back' }, netaccess: { port: 443 } });
    await waitFor(() => expect(toastText()).toContain('Configuration saved'));
  });

  it('leaves extensions out of a save that changes none of them, as before', async () => {
    const { puts } = open({ config });
    await demoLabel();
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
  });

  it('keeps the kiosk confirm as it was: kiosk changes ask first, a section’s change does not', async () => {
    const kiosk = { kioskMode: true, password: '4931', extensions: { demo: { on: false, label: 'Front' } } };
    const { puts } = open({ config: kiosk });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(screen.queryByRole('dialog', { name: /Change kiosk/ })).toBeNull();
  });

  it('asks before a kiosk change, with a section’s change in the same save', async () => {
    const kiosk = { kioskMode: true, password: '4931', extensions: { demo: { on: false, label: 'Front' } } };
    const { puts } = open({ config: kiosk });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    fireEvent.click(screen.getByRole('switch', { name: 'Kiosk mode' }));
    await save();
    const confirm = await screen.findByRole('dialog', { name: 'Change kiosk on 2 devices?' });
    expect(puts).toHaveLength(0);
    fireEvent.click(within(confirm).getByRole('button', { name: 'Save and apply' }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].kioskMode).toBe(false);
    expect(puts[0].extensions).toEqual({ demo: { on: false, label: 'Back' } });
  });
});

describe('configuration sections: problems', () => {
  it('runs validate on Save: the problem shows in the toast and the section, blocks the save and the kiosk confirm', async () => {
    const { puts } = open({ config: { kioskMode: true, password: '4931' } });
    await demoLabel();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Demo on' }));
    fireEvent.click(screen.getByRole('switch', { name: 'Kiosk mode' }));
    await save();
    await waitFor(() => expect(toastText()).toContain('Fix 1 problem before saving'));
    expect(toastText()).toContain('Demo: Give the demo a label before turning it on.');
    const card = document.getElementById('x-demo-demo')!;
    expect(within(card).getByRole('alert').textContent).toBe('Give the demo a label before turning it on.');
    expect(navEntry('Demo').textContent).toContain('1 problem');
    expect(screen.queryByRole('dialog', { name: /Change kiosk/ })).toBeNull();
    expect(puts).toHaveLength(0);
    // The cursor is put in the section's first field.
    expect(document.activeElement).toBe(screen.getByLabelText('Demo label'));

    // Fixing it clears the problem, and the save goes on (through the kiosk confirm).
    fireEvent.change(screen.getByLabelText('Demo label'), { target: { value: 'Front' } });
    expect(within(card).queryByRole('alert')).toBeNull();
    await save();
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Change kiosk/ })).getByRole('button', { name: 'Save and apply' }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].extensions).toEqual({ demo: { label: 'Front', on: true } });
  });

  it('shows a server validator’s refusal as sentences in the toast, and in the section it names', async () => {
    const refusal = new Refusal('error.extension.demo.taken', [
      { namespace: 'demo', key: 'error.extension.demo.taken', message: 'The label Back is taken.' },
      { namespace: null, key: 'error.extensions.size', message: 'The extensions’ data is larger than 64 KB in all.' },
    ]);
    const { puts } = open({ config: { extensions: { demo: { on: false, label: 'Front' } } }, onPut: () => refusal });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await save();
    await waitFor(() => expect(toastText()).toContain('The label Back is taken.'));
    expect(toastText()).toContain('The extensions’ data is larger than 64 KB in all.');
    expect(toastText()).not.toContain('error.extension');
    expect(within(document.getElementById('x-demo-demo')!).getByRole('alert').textContent).toBe('The label Back is taken.');
    expect(navEntry('Demo').textContent).toContain('1 problem');
    // Still on the editor, the change still there.
    expect(puts).toHaveLength(1);
    expect(screen.getByLabelText<HTMLInputElement>('Demo label').value).toBe('Back');
    expect(within(saveBar()!).getByRole('button', { name: 'Save and apply' })).toBeTruthy();
  });

  it('isolates a section that crashes: its callout, the rest still saves, its namespace as stored', async () => {
    function Fragile({ value, onChange }: ConfigSectionProps<{ boom?: boolean }>) {
      if (value.boom) throw new Error('fragile');
      return <button onClick={() => onChange({ ...value, boom: true })}>Break</button>;
    }
    const fragile = ext('fragile', [
      configSection<{ boom?: boolean }>({
        id: 'fragile',
        order: ORDER.configSection.network + 10,
        title: 'Fragile',
        storage: { defaults: {} },
        component: lazyComponent(async () => ({ Fragile }), 'Fragile'),
      }),
    ]);
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { puts } = open({ config: { extensions: { fragile: { kept: true } } } }, { registry: registryOf(demo, fragile) });
    fireEvent.click(await screen.findByRole('button', { name: 'Break' }, LOAD));
    const card = document.getElementById('x-fragile-fragile')!;
    expect(within(card).getByRole('alert').textContent).toContain(
      'The “Fragile” extension ran into a problem. Changes made here aren’t saved.',
    );
    // Its change is not trusted, so it counts for nothing; the other sections work and save.
    expect(saveBar()).toBeNull();
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].extensions).toEqual({ demo: { on: false, label: 'Back' }, fragile: { kept: true } });
  });

  it('hands out defaults that cannot be changed in place, so an edit never leaks into the next configuration', async () => {
    // The common slip: push into `value`, then onChange with a new top-level object.
    function Hosts({ value, onChange }: ConfigSectionProps<{ hosts: string[] }>) {
      return (
        <>
          <span>Hosts: {value.hosts.join(',') || 'none'}</span>
          <button
            onClick={() => {
              value.hosts.push('h');
              onChange({ ...value });
            }}
          >
            Add host
          </button>
        </>
      );
    }
    const hosts = ext('hosts', [
      configSection<{ hosts: string[] }>({
        id: 'hosts',
        order: 1000,
        title: 'Hosts',
        storage: { defaults: { hosts: [] } },
        component: lazyComponent(async () => ({ Hosts }), 'Hosts'),
      }),
    ]);
    const registry = registryOf(hosts);
    // React reports a handler's throw on window; the browser would log it.
    const thrown: unknown[] = [];
    const onError = (e: ErrorEvent) => {
      thrown.push(e.error);
      e.preventDefault();
    };
    window.addEventListener('error', onError);
    try {
      // Configuration A: the namespace is not stored, so the section gets the defaults.
      open({}, { registry });
      fireEvent.click(await screen.findByRole('button', { name: 'Add host' }, LOAD));
      expect(thrown.some((e) => e instanceof TypeError)).toBe(true);
      expect(screen.getByText('Hosts: none')).toBeTruthy();
      expect(saveBar()).toBeNull();
      cleanup();

      // Configuration B, in the same session, has none either.
      const { puts } = open({ config: { id: 8, name: 'Office' } }, { registry });
      expect(await screen.findByText('Hosts: none', undefined, LOAD)).toBeTruthy();
      await typeDescription('Front room');
      await save();
      await waitFor(() => expect(puts).toHaveLength(1));
      expect(puts[0]).not.toHaveProperty('extensions');
    } finally {
      window.removeEventListener('error', onError);
    }
  });

  it('hands out the stored value frozen too: changing it while rendering crashes into the section’s callout', async () => {
    function Tagger({ value }: ConfigSectionProps<{ tags: string[] }>) {
      value.tags.push('seen');
      return <span>Tagged</span>;
    }
    const tagger = ext('tagger', [
      configSection<{ tags: string[] }>({
        id: 'tagger',
        order: 1000,
        title: 'Tagger',
        storage: { defaults: { tags: [] } },
        component: lazyComponent(async () => ({ Tagger }), 'Tagger'),
      }),
    ]);
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { puts } = open({ config: { extensions: { tagger: { tags: ['a'] } } } }, { registry: registryOf(tagger) });
    const card = await waitFor(() => {
      const c = document.getElementById('x-tagger-tagger')!;
      expect(within(c).getByRole('alert').textContent).toContain('The “Tagger” extension ran into a problem.');
      return c;
    }, LOAD);
    expect(within(card).queryByText('Tagged')).toBeNull();
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
  });

  it('keeps `value` frozen after an edit: changing the object given to onChange in place throws', async () => {
    function Hosts({ value, onChange }: ConfigSectionProps<{ hosts: string[] }>) {
      return (
        <>
          <span>Hosts: {value.hosts.join(',') || 'none'}</span>
          <button onClick={() => onChange({ ...value, hosts: [...value.hosts, 'h1'] })}>Add h1</button>
          <button onClick={() => value.hosts.push('h2')}>Push h2</button>
        </>
      );
    }
    const hosts = ext('hosts', [
      configSection<{ hosts: string[] }>({
        id: 'hosts',
        order: 1000,
        title: 'Hosts',
        storage: { defaults: { hosts: [] } },
        component: lazyComponent(async () => ({ Hosts }), 'Hosts'),
      }),
    ]);
    const thrown: unknown[] = [];
    const onError = (e: ErrorEvent) => {
      thrown.push(e.error);
      e.preventDefault();
    };
    window.addEventListener('error', onError);
    try {
      const { puts } = open({}, { registry: registryOf(hosts) });
      fireEvent.click(await screen.findByRole('button', { name: 'Add h1' }, LOAD));
      expect(screen.getByText('Hosts: h1')).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Push h2' }));
      expect(thrown.some((e) => e instanceof TypeError)).toBe(true);
      await save();
      await waitFor(() => expect(puts).toHaveLength(1));
      expect(puts[0].extensions).toEqual({ hosts: { hosts: ['h1'] } });
    } finally {
      window.removeEventListener('error', onError);
    }
  });

  it('keeps a stored value it cannot read, and says so', async () => {
    const { puts } = open({ config: { extensions: { demo: ['not', 'an', 'object'] } } });
    await screen.findByText('Description', undefined, LOAD);
    const card = document.getElementById('x-demo-demo')!;
    expect(within(card).getByRole('alert').textContent).toContain('can’t be read here, so saving keeps them as they are');
    expect(screen.queryByLabelText('Demo label')).toBeNull();
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
  });
});

describe('configuration sections: new configurations', () => {
  it('creates one from a template with the template’s namespaces, the edit on top', async () => {
    const template = { extensions: { demo: { on: false, label: 'Front' }, netaccess: { port: 443 } } };
    const { puts } = open({ config: template }, { path: '/configurations/new?from=7' });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('id');
    expect(puts[0].name).toBe('Warehouse copy');
    // netaccess has no section in this build: the copy takes it as the template has it.
    expect(puts[0].extensions).toEqual({ demo: { on: false, label: 'Back' }, netaccess: { port: 443 } });
  });

  it('creates a blank one with the edited namespace only', async () => {
    const { puts } = open({}, { path: '/configurations/new' });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Office' } });
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].name).toBe('Office');
    expect(puts[0].extensions).toEqual({ demo: { label: 'Back', on: false } });
  });

  it('says a template’s unreadable value stops the new configuration, whose namespaces the server all checks', async () => {
    open({ config: { extensions: { demo: ['not', 'an', 'object'] } } }, { path: '/configurations/new?from=7' });
    await screen.findByText('Description', undefined, LOAD);
    expect(within(document.getElementById('x-demo-demo')!).getByRole('alert').textContent).toBe(
      'The template’s “Demo” settings can’t be read here, and the server won’t create a configuration with them. ' +
        'Duplicate the template instead.',
    );
  });

  it('tells a role without copy_config that another role can duplicate the template, not to do it itself', async () => {
    const noCopy = {
      ...ROLES.admin,
      userRole: { ...ROLES.admin.userRole!, permissions: ROLES.admin.userRole!.permissions!.filter((p) => p.name !== 'copy_config') },
    };
    open({ config: { extensions: { demo: ['not', 'an', 'object'] } } }, { path: '/configurations/new?from=7', user: noCopy });
    await screen.findByText('Description', undefined, LOAD);
    expect(within(document.getElementById('x-demo-demo')!).getByRole('alert').textContent).toBe(
      'The template’s “Demo” settings can’t be read here, and the server won’t create a configuration with them. ' +
        'Someone whose role may duplicate configurations can duplicate the template instead.',
    );
  });
});

describe('configuration sections: edge cases', () => {
  const kept = { extensions: { demo: { on: false, label: 'Front' } } };

  it('never blocks a save on stored data validate would refuse, when the save leaves it as it is', async () => {
    const { puts } = open({ config: { extensions: { demo: { on: true, label: '' } } } });
    await demoLabel();
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
    expect(toastText()).not.toContain('problem');
  });

  it('counts a crashed section’s change again once “Try again” renders it', async () => {
    let explode = true;
    function Flaky({ value, onChange }: ConfigSectionProps<{ boom?: boolean }>) {
      if (value.boom && explode) throw new Error('flaky');
      return <button onClick={() => onChange({ ...value, boom: true })}>{value.boom ? 'Boomed' : 'Break'}</button>;
    }
    const flaky = ext('flaky', [
      configSection<{ boom?: boolean }>({
        id: 'flaky',
        order: 1000,
        title: 'Flaky',
        storage: { defaults: {} },
        component: lazyComponent(async () => ({ Flaky }), 'Flaky'),
      }),
    ]);
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { puts } = open({}, { registry: registryOf(flaky) });
    fireEvent.click(await screen.findByRole('button', { name: 'Break' }, LOAD));
    const card = document.getElementById('x-flaky-flaky')!;
    expect(within(card).getByRole('alert')).toBeTruthy();
    expect(saveBar()).toBeNull();

    explode = false;
    fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
    expect(await within(card).findByRole('button', { name: 'Boomed' })).toBeTruthy();
    expect(within(saveBar()!).getByText('1 unsaved change')).toBeTruthy();
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].extensions).toEqual({ flaky: { boom: true } });
  });

  it('counts a changed section once even when its changes() says 0, so Save and the leave prompt still see it', async () => {
    function Bump({ value, onChange }: ConfigSectionProps<{ n: number }>) {
      return <button onClick={() => onChange({ n: value.n + 1 })}>Bump</button>;
    }
    const bump = ext('bump', [
      configSection<{ n: number }>({
        id: 'bump',
        order: 1000,
        title: 'Bump',
        storage: { defaults: { n: 0 } },
        changes: () => 0,
        component: lazyComponent(async () => ({ Bump }), 'Bump'),
      }),
    ]);
    open({}, { registry: registryOf(bump) });
    fireEvent.click(await screen.findByRole('button', { name: 'Bump' }, LOAD));
    expect(within(saveBar()!).getByText('1 unsaved change')).toBeTruthy();
    expect(navEntry('Bump').textContent).toContain('1 changed');
  });

  it('never saves an onChange value that is not a JSON object', async () => {
    function Lister({ onChange }: ConfigSectionProps<object>) {
      return <button onClick={() => onChange(['a'])}>List</button>;
    }
    const lister = ext('lister', [
      configSection<object>({
        id: 'lister',
        order: 1000,
        title: 'Lister',
        storage: { defaults: {} },
        component: lazyComponent(async () => ({ Lister }), 'Lister'),
      }),
    ]);
    const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { puts } = open({}, { registry: registryOf(lister) });
    fireEvent.click(await screen.findByRole('button', { name: 'List' }, LOAD));
    expect(saveBar()).toBeNull();
    expect(logged.mock.calls.some(([m]) => String(m).includes('not a plain JSON object'))).toBe(true);
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
  });

  it('says so, and sends nothing, when the configuration came without its extensions', async () => {
    const { puts } = open({ config: { extensions: undefined } });
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await save();
    await waitFor(() => expect(toastText()).toContain('extensions data did not load'));
    expect(puts).toHaveLength(0);
  });

  it('drops, and says so once, the edit of a section a role refresh hides', async () => {
    function Vpn({ value, onChange }: ConfigSectionProps<{ on: boolean }>) {
      return (
        <>
          <span>VPN is {value.on ? 'on' : 'off'}</span>
          <button onClick={() => onChange({ on: !value.on })}>Turn on</button>
        </>
      );
    }
    const vpn = ext('vpn', [
      configSection<{ on: boolean }>({
        id: 'vpn',
        order: 1000,
        title: 'VPN',
        requires: { permission: 'plugin_vpn_edit' },
        storage: { defaults: { on: false } },
        component: lazyComponent(async () => ({ Vpn }), 'Vpn'),
      }),
    ]);
    const withVpn = { ...ROLES.admin, userRole: { ...ROLES.admin.userRole!, permissions: [...ROLES.admin.userRole!.permissions!, { name: 'plugin_vpn_edit' }] } };
    let current: AuthUser = withVpn;
    const { puts } = open({ ...kept, currentUser: () => current }, { registry: registryOf(demo, vpn), user: withVpn });
    fireEvent.click(await screen.findByRole('button', { name: 'Turn on' }, LOAD));
    expect(within(saveBar()!).getByText('1 unsaved change')).toBeTruthy();

    const toasts = recordToasts();
    const dropped = () => toasts.shown.filter((t) => t.includes('Changes dropped'));
    try {
      current = ROLES.admin;
      act(() => void document.dispatchEvent(new Event('visibilitychange')));
      await waitFor(() => expect(dropped().join()).toContain('“VPN” is no longer available to you, so its changes won’t be saved.'));
      expect(screen.queryByRole('button', { name: 'Turn on' })).toBeNull();
      expect(saveBar()).toBeNull();

      // The role gets the permission back: the section shows its stored value, so the edit really left the draft.
      current = withVpn;
      act(() => void document.dispatchEvent(new Event('visibilitychange')));
      expect(await screen.findByText('VPN is off', undefined, LOAD)).toBeTruthy();
      expect(saveBar()).toBeNull();
      expect(dropped()).toHaveLength(1);
    } finally {
      toasts.stop();
    }

    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].extensions).toEqual({ demo: { on: false, label: 'Back' } });
  });

  it('drops a crashed section’s edit when a role refresh hides it, as its callout said: shown again, it shows the stored value', async () => {
    let explode = true;
    function Flaky({ value, onChange }: ConfigSectionProps<{ boom?: boolean }>) {
      if (value.boom && explode) throw new Error('flaky');
      return <button onClick={() => onChange({ ...value, boom: true })}>{value.boom ? 'Boomed' : 'Break'}</button>;
    }
    const flaky = ext('flaky', [
      configSection<{ boom?: boolean }>({
        id: 'flaky',
        order: 1000,
        title: 'Flaky',
        requires: { permission: 'plugin_flaky_edit' },
        storage: { defaults: {} },
        component: lazyComponent(async () => ({ Flaky }), 'Flaky'),
      }),
    ]);
    const withFlaky = { ...ROLES.admin, userRole: { ...ROLES.admin.userRole!, permissions: [...ROLES.admin.userRole!.permissions!, { name: 'plugin_flaky_edit' }] } };
    let current: AuthUser = withFlaky;
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { puts } = open({ currentUser: () => current }, { registry: registryOf(flaky), user: withFlaky });
    fireEvent.click(await screen.findByRole('button', { name: 'Break' }, LOAD));
    const callout = within(document.getElementById('x-flaky-flaky')!).getByRole('alert');
    expect(callout.textContent).toContain('Changes made here aren’t saved.');
    expect(saveBar()).toBeNull();

    current = ROLES.admin;
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    await waitFor(() => expect(document.getElementById('x-flaky-flaky')).toBeNull());

    // Shown again, even by a body that no longer crashes on the edit, it renders afresh from the stored value: the
    // edit left the draft, so there is nothing to save.
    explode = false;
    current = withFlaky;
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    expect(await screen.findByRole('button', { name: 'Break' }, LOAD)).toBeTruthy();
    expect(saveBar()).toBeNull();
    await typeDescription('Back room');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('extensions');
  });
});

describe('configuration sections: who sees and edits them', () => {
  it('hides a section whose server plugin is off for the customer, and keeps its namespace as stored', async () => {
    function Vpn() {
      return <span>VPN body</span>;
    }
    const netaccess = ext(
      'netaccess',
      [
        configSection({
          id: 'vpn',
          order: 1000,
          title: 'VPN',
          storage: { defaults: {} },
          component: lazyComponent(async () => ({ Vpn }), 'Vpn'),
        }),
      ],
      { requires: { serverPlugin: 'netaccess' } },
    );
    const config = { extensions: { demo: { on: false, label: 'Front' }, netaccess: { port: 443 } } };
    const { puts, fetch } = open({ config, plugins: [] }, { registry: registryOf(demo, netaccess) });
    // Gated sections are hidden while the list loads too: decide only once it is in.
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).endsWith('/plugin/main/private/available'))).toBe(true));
    await act(async () => {});
    fireEvent.change(await demoLabel(), { target: { value: 'Back' } });
    expect(screen.queryByText('VPN body')).toBeNull();
    expect(navLabels().join()).not.toContain('VPN');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].extensions).toEqual({ demo: { on: false, label: 'Back' }, netaccess: { port: 443 } });
  });

  it('shows it when /plugin/main/private/available lists the plugin', async () => {
    function Vpn() {
      return <span>VPN body</span>;
    }
    const netaccess = ext(
      'netaccess',
      [configSection({ id: 'vpn', order: 1000, title: 'VPN', storage: { defaults: {} }, component: lazyComponent(async () => ({ Vpn }), 'Vpn') })],
      { requires: { serverPlugin: 'netaccess' } },
    );
    open({ plugins: ['netaccess'] }, { registry: registryOf(netaccess) });
    expect(await screen.findByText('VPN body', undefined, LOAD)).toBeTruthy();
  });

  it('makes the section read only on a built-in template', async () => {
    open({ config: { id: 1, name: 'Managed Launcher', extensions: { demo: { on: true, label: 'Kiosk' } } } });
    const label = await demoLabel();
    expect(label.disabled).toBe(true);
    expect(saveBar()).toBeNull();
  });

  it('makes the section read only for a role the server would not let save', async () => {
    // The route already shows "no access" without `configurations`; the section mirrors the save's own check too.
    serve({ config: { extensions: { demo: { on: true, label: 'Kiosk' } } } });
    renderInConsole(
      <Routes>
        <Route path="/configurations/:id" element={<ConfigEditorPage />} />
      </Routes>,
      { path: '/configurations/7', registry: registryOf(demo), user: ROLES.observer },
    );
    expect((await demoLabel()).disabled).toBe(true);
  });
});
