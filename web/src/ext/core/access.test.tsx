// Actions follow the role: a role without edit_devices sees core's pages without the buttons that write to devices (the
// server would refuse them), and keeps every read. Rendered through the real routes, against a stubbed API.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AuthUser } from '../../api/auth';
import { AppRoutes } from '../../App';
import { ROLES, renderInConsole, stubApi } from '../../test/console';

const LOAD = { timeout: 3000 };
const NOW = Date.now();
const DEVICE = { id: 7, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: NOW };

/**
 * One device, out of sync (so the Overview offers Sync now), a running rollout, and two library apps, one linked
 * from another host without a checksum (so Apps shows its missing-checksum notice).
 */
function api(path: string, init?: RequestInit): unknown {
  const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
  const agent = `/agent/v1/devices/${DEVICE.number}`;
  switch (p) {
    case '/devices/search':
      return { devices: { items: [DEVICE], totalItemsCount: 1 }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
    case `/devices/number/${DEVICE.number}`:
      return DEVICE;
    case '/configurations/search':
    case '/configurations/list':
      return [{ id: 3, name: 'Warehouse' }];
    case '/configurations/3':
      return { id: 3, name: 'Warehouse', kioskMode: false };
    case '/agent/v1/configurations/syncSummary':
      return [{ configurationId: 3, total: 1, inSync: 0, outOfSync: 1, unsupported: 0, neverSeen: 0, deferred: 0 }];
    case `${agent}/configStatus`:
      return { configurationId: 3, currentRevision: 'b', appliedRevision: 'a', inSync: false, supported: true, lastCommand: { id: 5, status: 'done' } };
    case `${agent}/state`:
      return { battery: 80, locked: false, kioskActive: false, updatedAt: NOW, agentVersion: '0.3.7' };
    case `${agent}/events`:
    case `${agent}/commands`:
    case `${agent}/locations`:
      return init?.method === 'POST' ? { id: 1 } : [];
    case '/agent/v1/rollout/active':
      return {
        id: 7, targetVersion: '0.3.8', packageName: 'com.mdmesh.agent', apkVersionCode: 38, stage: 'canary',
        createdAt: NOW - 60_000, updatedAt: NOW,
        progress: { stage: 'canary', targetVersion: '0.3.8', canary: { total: 1, updated: 1, pending: 0, outstanding: 0, ineligible: 0 }, fleet: null },
      };
    case '/applications/search':
      return [
        { id: 1, name: 'Scanner', pkg: 'com.acme.scanner', version: '1.0', type: 'app', url: 'https://cdn.example.com/scanner.apk', hasApkHash: true },
        { id: 2, name: 'Legacy', pkg: 'com.acme.legacy', version: '0.9', type: 'app', url: 'https://cdn.example.com/legacy.apk', hasApkHash: false },
      ];
    case '/fdroid/search':
      return [{ packageName: 'org.example.maps', name: 'Maps', versionCode: 5, versionName: '1.0', apkUrl: 'https://f-droid.org/repo/maps.apk', sha256: 'a'.repeat(64) }];
    default:
      return null;
  }
}

/** The supervisor's status (not under /rest, so not in an envelope): a verified release is ready. */
const UPDATE_STATUS = {
  current: '0.3.7', latest: '0.3.8', updateAvailable: true, verified: true, channel: 'stable', checkedAt: NOW, error: null,
  apply: null, auto: false, apk: null, release: { notes: null, url: null, publishedAt: null },
};

function open(path: string, user: AuthUser, routes = api, status: object = UPDATE_STATUS) {
  const fetch = stubApi(routes);
  const rest = fetch.getMockImplementation();
  fetch.mockImplementation(async (input, init) =>
    String(input).startsWith('/update/status') ? new Response(JSON.stringify(status)) : rest!(input, init),
  );
  renderInConsole(<AppRoutes />, { path, user });
}

const main = () => within(screen.getByRole('main'));

describe.each([
  { role: 'Observer', user: ROLES.observer, shown: false },
  { role: 'User', user: ROLES.user, shown: true },
  { role: 'Admin', user: ROLES.admin, shown: true },
])('$role (edit_devices: $shown)', ({ user, shown }) => {
  const expectShown = (found: unknown[]) => expect(found.length > 0).toBe(shown);

  it('the device page: quick actions, remote actions and the rename pencil, its reads kept', async () => {
    open('/devices/WH-1', user);
    const panel = within(await screen.findByRole('tabpanel', undefined, LOAD));
    await panel.findByText('Recent commands', undefined, LOAD);
    expectShown(screen.queryAllByRole('toolbar', { name: 'Quick actions' }));
    expectShown(panel.queryAllByText('Remote actions'));
    expectShown(screen.queryAllByRole('button', { name: /Rename device/ }));
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(expect.arrayContaining(['Control', 'Telemetry', 'Location']));
  });

  it('the Devices list: Sync, the row menu, selection and Enroll, the list kept', async () => {
    open('/devices', user);
    await main().findByRole('link', { name: /Front desk/ }, LOAD);
    expectShown(main().queryAllByRole('button', { name: 'Sync WH-1 now' }));
    expectShown(main().queryAllByRole('button', { name: 'More actions for WH-1' }));
    expectShown(main().queryAllByRole('checkbox', { name: 'Select WH-1' }));
    expectShown(main().queryAllByRole('link', { name: 'Enroll device' }));
  });

  it('the Overview: Enroll, and Sync now on a device out of sync (Open device instead)', async () => {
    open('/', user);
    // The row's action once the probe has found the device out of sync.
    const sync = () => main().queryAllByRole('button', { name: 'Sync now: Front desk' });
    const openIt = () => main().queryAllByRole('link', { name: 'Open device: Front desk' });
    await waitFor(() => expect(sync().length + openIt().length).toBe(1), LOAD);
    expectShown(sync());
    expect(openIt().length > 0).toBe(!shown);
    expectShown(main().queryAllByRole('link', { name: 'Enroll device' }));
  });

  it('Settings: the rollout’s buttons and the Enroll link, the rollout itself kept', async () => {
    open('/settings', user);
    await main().findByText(/Updating the agent to/, undefined, LOAD);
    expectShown(main().queryAllByRole('button', { name: 'Cancel rollout' }));
    expectShown(main().queryAllByRole('button', { name: 'Promote to everyone else' }));
    expectShown(main().queryAllByRole('link', { name: 'Enroll a device' }));
  });

  it('Apps: Deploy, and the checksum and remove entries (edit_application_versions, edit_applications), the library kept', async () => {
    open('/apps', user);
    await main().findByText('com.acme.scanner', undefined, LOAD);
    expectShown(main().queryAllByRole('button', { name: 'Deploy Scanner' }));
    const headers = within(main().getByRole('table', { name: 'Library' })).getAllByRole('columnheader');
    expect(headers.map((h) => h.textContent)).toEqual(['App', 'Version', 'Source', 'Checksum', 'Used in', ...(shown ? ['Actions'] : [])]);
    expect(main().getByText('Legacy has no SHA-256 checksum.')).toBeTruthy();
    expectShown(main().queryAllByRole('button', { name: 'Add checksum' }));
    // Only a role that can add the checksum is told to.
    expectShown(main().queryAllByText(/Add the checksum before deploying it/));
    const more = main().queryAllByRole('button', { name: 'More for Scanner' });
    expectShown(more);
    if (more.length) {
      fireEvent.click(more[0]);
      const items = within(await screen.findByRole('menu')).getAllByRole('menuitem').map((m) => m.textContent);
      expect(items).toEqual(['SHA-256 checksum…', 'Remove from library…']);
    }
  });
});

// v0.3.8: the supervisor authorizes checking, updating, rolling back and the automatic-updates switch by asking the
// server whether the session may manage the deployment (GET /users/current's deploymentAdmin: a super-admin, or the
// org admin of a single-customer install; supervisor/server.js authorizeApply). The settings permission alone is not
// enough. Its status is open to every user.
const MULTI_CUSTOMER_ADMIN: AuthUser = { ...ROLES.admin, deploymentAdmin: false };

describe.each([
  { role: 'Observer', user: ROLES.observer, shown: false },
  { role: 'User', user: ROLES.user, shown: false },
  { role: 'Admin of a multi-customer install', user: MULTI_CUSTOMER_ADMIN, shown: false },
  { role: 'Admin', user: ROLES.admin, shown: true },
  { role: 'Super-admin', user: ROLES.superAdmin, shown: true },
])('Settings → Updates as $role (deploymentAdmin: $shown)', ({ user, shown }) => {
  it('offers Check now, the update and the automatic-updates switch, the status kept', async () => {
    open('/settings', user);
    const card = within(await screen.findByRole('region', { name: 'Updates' }, LOAD));
    await card.findByText(/is ready/, undefined, LOAD);
    expect(card.getByText(/Checked/)).toBeTruthy();
    expect(card.queryAllByRole('button', { name: 'Check now' }).length > 0).toBe(shown);
    expect(card.queryAllByRole('button', { name: /Update server and console/ }).length > 0).toBe(shown);
    expect(card.queryAllByRole('switch').length > 0).toBe(shown);
    expect(card.getByText('Install updates automatically')).toBeTruthy();
  });

  it('keeps the help that speaks of the update to the roles it offers the update to', async () => {
    // Automatic updates skip this release, as its last update rolled back: the note says Update installs it by hand.
    open('/settings', user, api, { ...UPDATE_STATUS, auto: true, autoSkipped: UPDATE_STATUS.latest });
    const card = within(await screen.findByRole('region', { name: 'Updates' }, LOAD));
    await card.findByText(/is ready/, undefined, LOAD);
    expect(card.queryAllByText(/Takes about 2 minutes/).length > 0).toBe(shown);
    expect(card.getByText(/won’t install/).textContent).toMatch(/a newer release installs on its own\.$/i);
    expect(card.queryAllByText(/installs it by hand/).length > 0).toBe(shown);
  });

  it('says who can update where it shows no Update button', async () => {
    open('/settings', user);
    const card = within(await screen.findByRole('region', { name: 'Updates' }, LOAD));
    await card.findByText(/is ready/, undefined, LOAD);
    expect(card.queryAllByText('Only a super-admin, or the admin of a single-customer install, can update.').length > 0)
      .toBe(!shown);
  });

  it('the sidebar’s update card says how long it takes only to those who may update', async () => {
    open('/settings', user);
    const nav = within(screen.getByRole('navigation', { name: 'Main' }));
    const card = await nav.findByRole('link', { name: /Server update 0\.3\.8/ }, LOAD);
    expect(card.textContent).toMatch(shown ? /About 2 minutes\./ : /An admin who runs this install can apply it\./);
  });
});

describe('Settings → Updates follows a deploymentAdmin refresh', () => {
  // A second customer appears (or the user's role changes): the next GET /users/current says no, and the buttons go
  // without a new sign-in. A server before 0.3.8 sends no deploymentAdmin, which counts as no.
  it.each([
    ['the server says no', { deploymentAdmin: false }],
    ['the server sends no deploymentAdmin', { deploymentAdmin: undefined }],
  ])('%s', async (_, change) => {
    let current: AuthUser = ROLES.admin;
    open('/settings', ROLES.admin, (path, init) => (path.endsWith('/users/current') ? current : api(path, init)));
    const card = within(await screen.findByRole('region', { name: 'Updates' }, LOAD));
    await card.findByRole('button', { name: /Update server and console/ }, LOAD);

    current = { ...ROLES.admin, ...change };
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    await waitFor(() => expect(card.queryByRole('button', { name: /Update server and console/ })).toBeNull());
    expect(card.queryByRole('button', { name: 'Check now' })).toBeNull();
    expect(card.queryByRole('switch')).toBeNull();
  });
});

const custom = (permissions: string[]): AuthUser => ({
  id: 40,
  login: 'custom',
  userRole: { id: 40, permissions: permissions.map((name) => ({ name })) },
});

// Pushing to devices queues app.install (AgentAdminResource, edit_devices); adding to a configuration is
// ApplicationResource POST /configurations (edit_applications), from a picker that ConfigurationResource GET /list
// fills, which checks no permission. Deploy offers the modes the role may use.
describe('Deploy offers only the modes the role may use', () => {
  it.each([
    ['edit_devices alone: push to devices', ['applications', 'edit_devices'], ['Push to devices']],
    ['edit_applications alone: add to a configuration', ['applications', 'edit_applications'], ['Add to a configuration']],
    ['both', ['applications', 'edit_devices', 'edit_applications'], ['Push to devices', 'Add to a configuration']],
  ])('%s', async (_, permissions, modes) => {
    open('/apps', custom(permissions));
    fireEvent.click(await main().findByRole('button', { name: 'Deploy Scanner' }, LOAD));
    const dialog = within(await screen.findByRole('dialog', { name: 'Deploy Scanner' }, LOAD));
    expect(dialog.getAllByRole('tab').map((t) => t.textContent)).toEqual(modes);
  });

  it('is not offered with neither', async () => {
    open('/apps', custom(['applications', 'configurations']));
    await main().findByText('com.acme.scanner', undefined, LOAD);
    expect(main().queryByRole('button', { name: 'Deploy Scanner' })).toBeNull();
  });

  // A role refresh (on tab focus) can take a mode away while the dialog is open.
  it.each([
    ['push to devices: Add to a configuration stays, its picker shown', ['applications', 'edit_applications'], ['Add to a configuration'], true],
    ['both: the dialog closes', ['applications'], [], false],
  ])('drops a mode a role refresh takes away, %s', async (_, after, modes, open_) => {
    let current = custom(['applications', 'edit_devices', 'edit_applications']);
    stubApi((path, init) => (path.endsWith('/users/current') ? current : api(path, init)));
    renderInConsole(<AppRoutes />, { path: '/apps', user: current });
    fireEvent.click(await main().findByRole('button', { name: 'Deploy Scanner' }, LOAD));
    const dialog = within(await screen.findByRole('dialog', { name: 'Deploy Scanner' }, LOAD));
    expect(dialog.getByRole('tab', { name: 'Push to devices' }).getAttribute('aria-selected')).toBe('true');

    current = custom(after);
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    if (!open_) {
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Deploy Scanner' })).toBeNull());
      return;
    }
    await waitFor(() => expect(dialog.getAllByRole('tab').map((t) => t.textContent)).toEqual(modes));
    expect(dialog.getByRole('tab', { name: 'Add to a configuration' }).getAttribute('aria-selected')).toBe('true');
    expect(dialog.queryByRole('list', { name: 'Devices' })).toBeNull();
    expect(dialog.getByRole('combobox')).toBeTruthy();
  });

  it('fills the configuration picker from GET /list, for a role without configurations', async () => {
    // /search refuses this role (configurations), so the server answers it with no list.
    open('/apps', custom(['applications', 'edit_applications']), (path, init) =>
      new URL(path, 'http://console').pathname.endsWith('/configurations/search') ? null : api(path, init),
    );
    fireEvent.click(await main().findByRole('button', { name: 'Deploy Scanner' }, LOAD));
    const dialog = within(await screen.findByRole('dialog', { name: 'Deploy Scanner' }, LOAD));
    expect(await dialog.findByRole('option', { name: 'Warehouse' }, LOAD)).toBeTruthy();
  });
});

// ConfigurationResource: creating (PUT with no id) needs add_config; copying (PUT /copy) and deleting (DELETE /{id})
// need copy_config; saving an existing one needs only configurations, which the page itself needs.
describe.each([
  { role: 'User', user: ROLES.user, add: false, copy: true },
  { role: 'Admin', user: ROLES.admin, add: true, copy: true },
  { role: 'configurations alone', user: custom(['configurations', 'applications']), add: false, copy: false },
])('Configurations as $role (add_config: $add, copy_config: $copy)', ({ user, add, copy }) => {
  const has = (found: unknown[], expected: boolean) => expect(found.length > 0).toBe(expected);

  it('the list: New configuration, and each card’s Duplicate and Delete', async () => {
    open('/configurations', user);
    await main().findByRole('link', { name: 'Warehouse' }, LOAD);
    has(main().queryAllByRole('button', { name: 'New configuration' }), add);
    has(main().queryAllByRole('button', { name: 'Duplicate' }), copy);
    fireEvent.click(main().getByRole('button', { name: 'More for Warehouse' }));
    const items = within(await screen.findByRole('menu')).getAllByRole('menuitem').map((m) => m.textContent);
    expect(items).toEqual(copy ? ['Edit', 'Duplicate…', 'Delete…'] : ['Edit']);
  });

  it('the editor: Duplicate and Delete', async () => {
    open('/configurations/3', user);
    await main().findByRole('heading', { name: 'Warehouse' }, LOAD);
    has(main().queryAllByRole('button', { name: 'Duplicate' }), copy);
    has(main().queryAllByRole('button', { name: 'Delete…' }), copy);
  });

  // A new configuration, blank or from a template (?from=), saves with PUT and no id, which needs add_config.
  // Duplicate (PUT /copy) has no route: it opens the copy the server made.
  it.each(['/configurations/new', '/configurations/new?from=3'])('%s: the editor, or no access', async (path) => {
    open(path, user);
    if (add) {
      expect(await main().findByRole('button', { name: 'Create configuration' }, LOAD)).toBeTruthy();
    } else {
      expect(await main().findByText('You don’t have access to this page', undefined, LOAD)).toBeTruthy();
      expect(main().queryByRole('button', { name: 'Create configuration' })).toBeNull();
    }
  });
});

// Adding to the library is ApplicationResource PUT /android (edit_applications); uploading is FilesResource
// POST /web-ui-files and /bundle, then /update to host it (edit_files).
describe.each([
  { role: 'User', user: ROLES.user, add: true, upload: true },
  { role: 'edit_applications without edit_files', user: custom(['applications', 'edit_applications']), add: true, upload: false },
  { role: 'edit_files and edit_devices, without edit_applications', user: custom(['applications', 'edit_files', 'edit_devices']), add: false, upload: true },
])('Apps sources as $role (edit_applications: $add, edit_files: $upload)', ({ user, add, upload }) => {
  const has = (found: unknown[], expected: boolean) => expect(found.length > 0).toBe(expected);

  it('F-Droid: Add to library', async () => {
    open('/apps?source=fdroid', user);
    await main().findByText('org.example.maps', undefined, LOAD);
    has(main().queryAllByRole('button', { name: 'Add Maps to library' }), add);
  });

  it('Custom APK: the upload and Add to library', async () => {
    open('/apps?source=custom', user);
    await main().findByRole('heading', { name: 'Add your own app' }, LOAD);
    has(main().queryAllByRole('button', { name: 'Choose a file' }), upload);
    has(main().queryAllByRole('button', { name: 'Add to library' }), add);
  });
});

// A source tab shows when the role can do something there that reaches the library or devices: Custom APK and
// F-Droid add to the library or deploy. An upload alone does neither, so edit_files alone gets no Custom APK tab.
// A link to a hidden one opens the Library.
describe.each([
  { role: 'Observer', user: ROLES.observer, tabs: ['Library'] },
  { role: 'edit_files alone', user: custom(['applications', 'edit_files']), tabs: ['Library'] },
  { role: 'edit_devices alone', user: custom(['applications', 'edit_devices']), tabs: ['Library', 'Custom APK', 'F-Droid'] },
  { role: 'edit_applications alone', user: custom(['applications', 'edit_applications']), tabs: ['Library', 'Custom APK', 'F-Droid'] },
])('Apps source tabs as $role', ({ user, tabs }) => {
  const tabBar = () => within(main().getByRole('navigation', { name: 'App source' }));

  it.each(['/apps', '/apps?source=custom', '/apps?source=fdroid'])('%s', async (path) => {
    open(path, user);
    await main().findByRole('navigation', { name: 'App source' }, LOAD);
    expect(tabBar().getAllByRole('link').map((t) => t.textContent?.replace(/\d+$/, ''))).toEqual(tabs);
    const source = new URLSearchParams(path.split('?')[1]).get('source');
    const label = source === 'custom' ? 'Custom APK' : source === 'fdroid' ? 'F-Droid' : 'Library';
    const current = tabs.includes(label) ? label : 'Library';
    expect(tabBar().getByRole('link', { current: 'page' }).textContent).toMatch(new RegExp(`^${current}`));
    if (current === 'Library') await main().findByText('com.acme.scanner', undefined, LOAD);
  });

  it('the empty library links only to the tabs shown', async () => {
    open('/apps', user, (path, init) => (new URL(path, 'http://console').pathname.endsWith('/applications/search') ? [] : api(path, init)));
    await main().findByText('No apps in the library yet', undefined, LOAD);
    expect(main().queryAllByRole('link', { name: 'Add your own app' }).length > 0).toBe(tabs.includes('Custom APK'));
    expect(main().queryAllByRole('link', { name: 'Browse F-Droid' }).length > 0).toBe(tabs.includes('F-Droid'));
    if (!tabs.includes('Custom APK')) expect(main().queryByText(/Upload an APK/)).toBeNull();
    if (!tabs.includes('F-Droid')) expect(main().queryByText(/from F-Droid/)).toBeNull();
    // Deploy goes with the F-Droid tab in these roles: the empty library speaks of deploying only to one that can.
    const empty = within(main().getByText('No apps in the library yet').parentElement!);
    expect(empty.queryAllByText(/pushed straight to devices/).length > 0).toBe(tabs.includes('F-Droid'));
  });
});

// RolloutResource refuses creating, promoting and cancelling a rollout to a user limited to some device groups
// (canReachWholeFleet), edit_devices or not.
it('Settings: a user limited to some device groups gets the rollout without its buttons', async () => {
  open('/settings', { ...ROLES.admin, allDevicesAvailable: false });
  await main().findByText(/Updating the agent to/, undefined, LOAD);
  expect(main().queryByRole('button', { name: 'Cancel rollout' })).toBeNull();
  expect(main().queryByRole('button', { name: 'Promote to everyone else' })).toBeNull();
  expect(main().getByRole('link', { name: 'Enroll a device' })).toBeTruthy();
});

describe('renaming follows edit_device_desc alone', () => {
  it.each([
    ['edit_device_desc without edit_devices', ['edit_device_desc'], true, false],
    ['edit_devices without edit_device_desc', ['edit_devices'], false, true],
  ])('%s', async (_, permissions, rename, actions) => {
    open('/devices/WH-1', custom(permissions));
    await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText('Recent commands', undefined, LOAD);
    expect(screen.queryAllByRole('button', { name: /Rename device/ }).length > 0).toBe(rename);
    expect(screen.queryAllByRole('toolbar', { name: 'Quick actions' }).length > 0).toBe(actions);
  });
});
