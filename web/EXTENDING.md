# Extending MDMesh (for forks)

A fork adds to MDMesh in two folders that core never changes, so a merge from upstream stays clean:

- `web/src/extensions/<id>/`: a **console extension**. It adds sidebar items and pages, device tabs and rail groups,
  configuration sections, Settings sections, Overview cards, and columns and actions on the Devices and Apps lists.
- `plugins/fork/<id>/`: a **server plugin**. It adds REST resources, tables, checks on configuration data, and policy
  keys in the configuration a device applies.

Use the same `<id>` for both: lowercase kebab-case (`netaccess`), at most 32 characters.

An extension is TypeScript built into the console with the rest of it. Nothing is loaded at runtime, and it runs with
the same trust as core code. Core's own pages, tabs and sections are registered the same way (`web/src/ext/core/`),
so an extension's items sit among core's and look like them.

Contents: [Console extension](#console-extension) · [Slots](#slots) · [Who sees what: requires](#who-sees-what-requires)
· [Placing items: ORDER](#placing-items-order) · [When something breaks](#when-something-breaks) ·
[What an extension may import](#what-an-extension-may-import) · [Server plugin](#server-plugin) ·
[Testing](#testing) · [Stability](#stability) · [Troubleshooting](#troubleshooting)

## Console extension

```
web/src/extensions/
  netaccess/
    index.ts             export default defineExtension({ id: 'netaccess', ... })   (the id is the folder name)
    NetworkPage.tsx      components, each loaded with lazyComponent(() => import('./NetworkPage'), 'NetworkPage')
    netaccess.css        classes prefixed x-netaccess-
    netaccess.test.tsx   its tests (Testing, below)
```

The console finds `extensions/*/index.ts` (or `index.tsx`) at build time (`web/src/ext/load.ts`). You edit no core
file. `index.ts` is the manifest: plain data, read when the console starts. Every component in it is a
`lazyComponent`, its own chunk, loaded the first time it renders.

```ts
// web/src/extensions/netaccess/index.ts
import { createElement } from 'react';
import { configSection, defineExtension, IconDownload, IconNetwork, lazyComponent, ORDER } from '@mdmesh/console';
import type { Vpn } from './VpnSection';

export default defineExtension({
  id: 'netaccess',             // the folder name
  apiVersion: 1,               // EXTENSION_API_VERSION
  title: 'Network access',     // named in error callouts
  requires: { serverPlugin: 'netaccess' },   // optional; applies to every contribution
  contributions: [
    // one object per item, in the slots below
  ],
});
```

Each contribution has a `slot`, an `id` and, except `route.page`, an `order`. The `id` is lowercase kebab-case and
unique within its slot across core and every extension (core's rail groups include `network`, for example: the
registry reports a clash). A contribution can have its own `requires`. The examples below are one extension: they
compile and pass the console's checks together.

## Slots

### nav.item and route.page

```ts
{
  slot: 'route.page',
  id: 'network',
  path: '/network',
  requires: { permission: 'plugin_netaccess_view' },
  component: lazyComponent(() => import('./NetworkPage'), 'NetworkPage'),
},
{
  slot: 'nav.item',
  id: 'network',
  order: ORDER.navItem.devices + 50,
  label: 'Network',
  icon: IconNetwork,
  to: '/network',
},
```

```tsx
// NetworkPage.tsx: reads its server plugin's REST (Server plugin, below) through the console's API client
import { useEffect, useState } from 'react';
import { apiClient, Card, CardHeader, EmptyState } from '@mdmesh/console';

interface Blocked {
  id: number;
  host: string;
}

export function NetworkPage() {
  const [blocked, setBlocked] = useState<Blocked[] | null>(null);
  useEffect(() => {
    apiClient.get<Blocked[]>('/plugins/netaccess/private/blocked').then(setBlocked, () => setBlocked([]));
  }, []);
  return (
    <Card>
      <CardHeader title="Blocked hosts" />
      {blocked?.length ? (
        <ul>{blocked.map((b) => <li key={b.id}>{b.host}</li>)}</ul>
      ) : (
        <EmptyState title="Nothing blocked">No device has reached a blocked host.</EmptyState>
      )}
    </Card>
  );
}
```

- `apiClient` prefixes `/rest` and unwraps the server's envelope; a refusal throws an `ApiError`.
- The page renders inside the app shell. A role that fails its `requires` sees "You don't have access to this page"
  there, not a redirect, so a shared link explains itself.
- `path` is lowercase kebab-case segments or `:params` after a fixed first segment (`/network-map/:id`). It may not use
  a core page's first segment (`/devices/...`), one the console keeps (`/login`, `/set-password`, `/_gallery`, `/v2`,
  `/dashboard`, `/configs`, `/overview`, `/assets`, `/licenses`), or start with a path the edge sends elsewhere
  (`/rest`, `/files`, `/agent`, `/update`, `/recovery`, `/healthz`, `/api`). These match as prefixes, so
  `/restaurants` and `/updates` are taken too. A first segment belongs to one extension.
- A nav item must point at a path a `route.page` serves, and it shows only when that page would: it takes the page's
  `requires`. `badge` (optional) is a component rendered after the label, as Devices shows the fleet size.

### device.tab

```ts
{
  slot: 'device.tab',
  id: 'network',
  order: ORDER.deviceTab.telemetry + 50,   // between Telemetry and Apps
  label: 'Network',
  icon: IconNetwork,
  component: lazyComponent(() => import('./NetworkTab'), 'NetworkTab'),
},
```

```tsx
// NetworkTab.tsx
import { Button, Card, CardHeader, KeyValueList, useDevice } from '@mdmesh/console';

export function NetworkTab() {
  const { device, telemetry, sendCommand } = useDevice();
  const dynamic = telemetry?.dynamic ?? {};
  return (
    <Card>
      <CardHeader title="Connection" subtitle={device.number} />
      <KeyValueList
        items={[
          { key: 'ssid', label: 'Wi-Fi network', value: String(dynamic.wifiSsid ?? '—') },
          { key: 'ip', label: 'Local IP', value: String(dynamic.ipAddress ?? '—'), mono: true },
        ]}
      />
      <Button onClick={() => void sendCommand({ label: 'Ping', request: { type: 'netaccess.ping' } })}>Ping gateway</Button>
    </Card>
  );
}
```

- The tab takes no props. `useDevice()` gives `device`, `telemetry`, `telemetryLoaded`, `state`, `configuration`,
  `configStatus`, `now` (updated every 30 seconds), `refresh()` and `sendCommand(command, values?)`. The page loads
  and polls them (every 5 seconds while the browser tab is shown). The value is a new object on every render, so an
  effect depends on the fields it reads (`device.number`), never on the whole value.
- `sendCommand` queues the command, toasts the outcome, nudges the device to sync and refreshes. It asks nothing
  first: confirm in your tab when the command needs it. It resolves to whether the command was queued. A command is
  `{ label, request: { type, requiresCapability?, payload? } }` (`payload` is a JSON string), or
  `{ label, build(values) }` to make the request from `values`. Give the tab `requires: { permission: 'edit_devices' }`
  when it sends commands, as the server checks that.
- The tab's id is its `?tab=` value: `/devices/WH-12?tab=network` opens it. `count(device)` (optional) returns a short
  text shown after the label, as Activity shows "12".

### device.railGroup

```ts
{
  slot: 'device.railGroup',
  id: 'access',
  order: ORDER.deviceRailGroup.network + 50,
  title: 'Network access',
  items: ({ telemetry }) => [{ key: 'ssid', label: 'Wi-Fi', value: String(telemetry?.dynamic?.wifiSsid ?? '—') }],
},
```

A group of rows in the device page's left rail. `items` gets what `useDevice()` gives. It runs on every render of the
page, so keep it pure and cheap. A group with no rows is left out.

### config.section

```ts
configSection<Vpn>({
  id: 'vpn',
  order: ORDER.configSection.network + 50,   // after Network, before Security
  title: 'VPN',
  subtitle: 'Saved with the configuration, under extensions["netaccess.vpn"].',
  storage: { namespace: 'netaccess.vpn', defaults: { on: false, host: '' } },
  validate: (v) => (v.on && !v.host.trim() ? ['Give the VPN a host before turning it on.'] : []),
  requires: { permission: 'configurations' },
  component: lazyComponent(() => import('./VpnSection'), 'VpnSection'),
}),
```

```tsx
// VpnSection.tsx
import { ChangedChip, Checkbox, FieldRow, Input, type ConfigSectionProps } from '@mdmesh/console';

export interface Vpn {
  on: boolean;
  host: string;
}

export function VpnSection({ value, stored, onChange, readOnly, errors, idBase }: ConfigSectionProps<Vpn>) {
  return (
    <>
      <FieldRow id={`${idBase}-on`} label="VPN">
        {() => (
          <Checkbox label="Route traffic through the VPN" disabled={readOnly} checked={value.on}
            onChange={(e) => onChange({ ...value, on: e.target.checked })} />
        )}
      </FieldRow>
      <FieldRow
        id={`${idBase}-host`}
        label="Host"
        adornment={value.host !== stored.host ? <ChangedChip /> : undefined}
        below={errors.length ? <span role="alert">{errors.join(' ')}</span> : undefined}
      >
        {({ labelId }) => (
          <Input aria-labelledby={labelId} disabled={readOnly} value={value.host}
            onChange={(e) => onChange({ ...value, host: e.target.value })} />
        )}
      </FieldRow>
    </>
  );
}
```

The editor draws the card (title, subtitle, anchor `#x-netaccess-vpn`) and the entry in its section nav. The body
renders the fields. Use `configSection<T>(...)` rather than a plain object, so `T` is checked against the defaults,
`validate` and the component.

**Storage.** The value is saved in the configuration's `extensions` object, under the namespace: your id (the default)
or `<id>.<name>` (`netaccess.vpn`) for a second section. No extension can write another's namespace.

- `defaults` is a JSON object. The section shows it while nothing is stored, and it is never saved until the user
  changes something.
- The body gets `value` (the draft), `stored` (as loaded, for "Changed" marks), `onChange`, `readOnly`, `errors`,
  `configuration` (the whole draft, read only) and `idBase` (a prefix for field ids).
- Call `onChange` with a new plain JSON object. `value` and `stored` are frozen, so changing them in place
  (`value.hosts.push(h)`) throws: write `onChange({ ...value, hosts: [...value.hosts, h] })`. The editor freezes the
  object you pass to `onChange` too, so `value` stays frozen after an edit, and so does your own reference to that
  object. A value that isn't a plain JSON object (an array, a cycle) is logged and never saved.
- Keep values a browser's JSON holds exactly: integers within ±2^53 (put a 64-bit id in a string). The console sends
  every namespace it loaded back as a browser parsed it.
- The server takes at most 16 KB per namespace and 64 KB for all of `extensions`.

**Changes.** An edit joins the editor's save bar count, the section's nav entry ("1 changed"), the leave prompt,
Discard and the single save. Values compare as JSON, whatever their keys' order. `changes(stored, value)` (optional)
says how many changes to count; a changed section counts at least 1.

**Save.** One PUT carries the configuration's own fields and `extensions`:

- Only when a section changed. The save then sends the namespaces as loaded with the changed ones on top, so the
  namespaces of extensions this build lacks, or that the role can't see, go back as they are.
- A save that changes no section leaves `extensions` out, and the server keeps what is stored.
- A new configuration from a template (`/configurations/new?from=7`) copies the template's namespaces, with the edits
  on top. A blank new one has only the namespaces the user edited.
- Two people saving the same configuration: the last save wins, for `extensions` as for the configuration's fields.

**Validation.** On Save, `validate(value, configuration)` runs for each changed section, before the kiosk confirm. A
non-empty list blocks the save: the toast says "Fix 1 problem before saving", the section shows the sentences in
`errors`, its nav entry says "1 problem", and the cursor goes to its first field. Changing the value clears them.
Unchanged data is not checked, so stored data your rules would now refuse never blocks a save of something else.
When the server refuses the save for your namespace (your plugin's validator, below), its sentences show in the
toast and in `errors` too.

**Read only.** `readOnly` is true on a built-in template, and for a role the server would not let save the
configuration. Its changes are not counted or saved.

**Edge cases.**

- A body that crashes shows a callout in its card ("Changes made here aren't saved"). Its namespace keeps the stored
  value, and the rest of the configuration still saves. "Try again" renders it again, and its change counts again.
- A stored value that isn't a JSON object is not shown. On an update it goes back as stored. A new configuration
  from that template is refused by the server (which checks every namespace of a new configuration). Duplicate on the
  template copies `extensions` as they are, and needs `copy_config`. The callout suggests Duplicate only to a role
  with `copy_config`. Any other role is told that someone whose role may duplicate configurations can do it.
- When a role or plugin refresh hides a section with unsaved changes, its edit is dropped and a toast says so. A
  crashed section's edit is dropped too when it is hidden, without a toast (its callout already said so). Shown again,
  it renders the stored value.

### settings.section

```ts
{
  slot: 'settings.section',
  id: 'network',
  order: ORDER.settingsSection.server + 50,
  title: 'Network access',
  requires: { permission: 'settings' },
  component: lazyComponent(() => import('./NetworkSettings'), 'NetworkSettings'),
},
```

```tsx
// NetworkSettings.tsx
import { Card, CardHeader, type SettingsSectionProps } from '@mdmesh/console';

export function NetworkSettings({ anchorId, headingId }: SettingsSectionProps) {
  return (
    <Card id={anchorId} aria-labelledby={headingId}>
      <CardHeader title="Network access" titleId={headingId} subtitle="Hosts every device may reach." />
    </Card>
  );
}
```

The body renders its own card with the `anchorId` and `headingId` it gets, as core's sections do. The Settings nav
entry reads `title`. The anchor is `x-<id>-<section id>` (`/settings#x-netaccess-network`), so it never takes one
core links to (`/settings#updates`).

### overview.card

```ts
{
  slot: 'overview.card',
  id: 'blocked',
  order: ORDER.overviewCard.recentActivity + 50,
  placement: 'aside',
  component: lazyComponent(() => import('./BlockedCard'), 'BlockedCard'),
},
{
  slot: 'overview.card',
  id: 'blocked-stat',
  order: ORDER.overviewStat.rollout + 50,
  placement: 'stat',
  component: lazyComponent(() => import('./BlockedCard'), 'BlockedStat'),
},
```

- `main` and `aside` cards fill the rows under the fleet summary, ordered by `ORDER.overviewCard`. A `main` card
  starts a row, and the `aside` cards after it stack in that row's narrow column. The body renders a `Card`.
- `stat` is a tile in the fleet summary, ordered by `ORDER.overviewStat`. The body renders a `StatCell`
  (`<StatCell label="Blocked today" value="12" />`).
- A body that renders nothing takes no room.

### list.column

```ts
{
  slot: 'list.column',
  id: 'public-ip',
  list: 'devices',
  order: ORDER.devicesColumn.android + 50,
  header: 'Public IP',
  cell: (device) => device.publicIp ?? '—',
  sortValue: (device) => device.publicIp ?? null,
  mono: true,
},
```

A column of the Devices table (`list: 'devices'`, rows are `DeviceView`) or the Apps library (`list: 'apps'`, rows are
`Application`). The Devices cards view keeps its layout. `header` is text or one element. `cell` runs for every row
on every render: keep it pure and cheap. `sortValue` makes the column sortable (numbers, then text; null last).
`mono` and `align: 'right'` are optional. Place it with `ORDER.devicesColumn` or `ORDER.appsColumn`; core's Actions
column stays last.

### list.rowAction

```ts
{
  slot: 'list.rowAction',
  id: 'ping',
  list: 'devices',
  order: ORDER.devicesRowAction.ring + 50,
  requires: { permission: 'edit_devices' },
  item: (device, { sendCommand }) => ({
    key: 'ping',
    label: 'Ping gateway',
    onSelect: () => void sendCommand([device], { label: 'Ping', request: { type: 'netaccess.ping' } }),
  }),
},
```

An entry in a row's "More actions" menu, on Devices or Apps. `item(row, helpers)` returns a menu item
(`{ key, label, onSelect, icon?, danger?, disabled?, textValue? }`; `textValue` when `label` isn't plain text), or
null to leave it out for that row. Helpers are `toast` and `refresh()`, and on Devices `sendCommand(devices, command,
values?)`. That sends one device as the device page does and several as one bulk request. It refuses wipe and
passcode reset for any number of devices: those are sent from the device page only. On Devices, from
`ORDER.devicesRowAction.move` on an entry joins the group below the separator. Place Apps entries with
`ORDER.appsRowAction`.

### list.bulkAction

```ts
{
  slot: 'list.bulkAction',
  id: 'export',
  list: 'devices',
  order: ORDER.devicesBulkAction.move + 50,
  label: 'Export',
  icon: createElement(IconDownload),
  run: (devices, { toast }) => toast.push('ok', 'Exported', `${devices.length} devices`),
},
```

A button in the Devices bulk-selection bar. `run(rows, helpers)` gets the selected devices the list shows, and the
Devices helpers above. The button is disabled until a promise it returns settles. `danger: true` gives it alert ink.

## Who sees what: requires

```ts
requires: { permission: ['settings', 'plugin_netaccess_admin'], serverPlugin: 'netaccess', when: ({ user }) => user.allDevices }
```

Every field given must pass. `requires` on the extension applies to each contribution, on top of its own.

- `permission`: any one of these server permission names, compared ignoring case, as the server does. A super-admin
  passes. Core pages and actions use the same check: Configurations needs `configurations`, Apps `applications`, Enroll
  and device commands `edit_devices`. Give an action the permission its server call checks, so a role that can't use
  it doesn't see it.
- `serverPlugin`: the server plugin of that id must be on for the user's customer, as
  `/rest/plugin/main/private/available` lists it. Items are hidden while that list loads. When it can't be read, the
  check passes (the server still refuses what isn't there). This is the one place the console and the server agree on
  a plugin, so the plugin's id, its `plugins` row, its validator and its contributor all use the same `<id>` (Server
  plugin, below).
- `when`: any other rule. One that throws counts as no. `user` is `{ id, login, name, email, superAdmin, allDevices,
  permissions }`; `allDevices` is false for a user an admin limited to some device groups.

This is for what people see. The server checks every call itself. The role is refreshed when the console starts and
when the browser tab is shown again.

## Placing items: ORDER

`ORDER` holds the order of core's items in each slot, with gaps of 100: `ORDER.navItem`, `deviceTab`,
`deviceRailGroup`, `configSection`, `settingsSection`, `overviewCard`, `overviewStat`, `devicesColumn`, `appsColumn`,
`devicesRowAction`, `devicesBulkAction` and `appsRowAction`. Place an item relative to core's
(`ORDER.deviceTab.telemetry + 50`), so it keeps its place when core reorders. On an equal order core's item comes
first, then extensions by `<extension id>/<contribution id>`.

## When something breaks

- **A manifest problem** (a duplicate id, a reserved path, a missing field, a nav item without a page, another
  `apiVersion`) leaves out that contribution, or the whole extension, and logs a line in the browser console:
  `[ext:netaccess] route.page "map": path "/rest/map" starts with /rest, which the edge sends to another backend`.
  `npm test` fails on the same line, so CI catches it first.
- **A manifest that throws while it loads** (at import) stops the console from starting. The manifests load eagerly,
  so the routes and the sidebar are known on the first render. `npm test` imports every manifest, so CI catches this
  too.
- **A component that crashes** shows a callout in its place ("The “Network access” extension ran into a problem. Try
  again"), and the rest of the page works. A page shows the page-level error card, a nav badge shows nothing, and a
  table cell or header shows a small "Error" mark. A chunk that fails to load offers "Reload" instead.
- **A function that throws** is logged once and gives a neutral value instead: a `cell` shows `—`, a `sortValue`
  sorts as empty, `items` gives no rows, `count` no count, `item` no menu entry, `validate` no problems (the server
  still checks), `changes` 1, and `when` "no".

## What an extension may import

Only `@mdmesh/console`, `react` and files in its own folder. Its tests may also import `vitest`,
`@testing-library/react`, `@testing-library/dom` and `@mdmesh/console/testing`. `npm test` checks this over the source
tree (`web/src/ext/guards.test.ts`), along with: only `web/src/ext/load.ts` imports `web/src/extensions/`, and only
tests import the test fixtures.

Only `*.test.ts` and `*.test.tsx` files count as tests. A file that isn't one may not import a test file, so the
production build never pulls in a test or what it imports. A glob over your own folder in such a file would match its
tests too (`./*.tsx` matches `netaccess.test.tsx`), so it must leave them all out with `'!**/*.test.*'`:
`import.meta.glob(['./pages/*.tsx', '!**/*.test.*'], { eager: true })` (exactly that pattern: `'!./**/*.test.*'` starts
from the file's own folder). A glob for other files (`./icons/*.svg`) needs no such pattern. A dynamic import or a glob
takes plain strings the check can read: no template literal (`` import(`./pages/${name}.tsx`) `` is a glob in
disguise), and a glob's options only `eager`, `import`, `query`, `exhaustive`, `caseSensitive` and `as`, written
plainly (no `base`). Keep a helper that needs `vitest`, `@testing-library` or
`@mdmesh/console/testing` in the test file that uses it: a file that isn't a test may not import them.

`@mdmesh/console` (`web/src/sdk/index.ts`) exports:

- the extension API: `defineExtension`, `configSection`, `lazyComponent`, `ORDER`, `EXTENSION_API_VERSION`, and the
  types of every slot and its props;
- hooks: `useDevice`, `useCurrentUser`, `usePermission`, `useToast`;
- routing, the console's own react-router: `Link`, `useLocation`, `useNavigate`, `useParams`, `useSearchParams`, and
  `paths`;
- the API client: `apiClient` (calls under `/rest`, unwraps the server's envelope), `ApiError`, `API_BASE`, and the
  types `DeviceView`, `Configuration`, `Application`, `TelemetrySnapshot`, `DeviceState`, `QueueCommandRequest`,
  `ConfigStatus`;
- the UI kit: buttons, `Card`, `CardHeader`, `Callout`, `Notice`, fields and inputs, `Segmented`, `TriState`,
  `Toggle`, tabs, `Table`, `KeyValueList`, `SectionNav`, `Badge`, status pills, bars, `EmptyState`, `Modal`, `Menu`,
  `PageHeader`, `Pagination`, `RelativeTime`, `CopyField`, `StatCell`, and the icons.

`web/src/sdk/surface.txt` lists every name. Nothing else in `web/src` is public, and any of it may change in any
release. Style with the design tokens (the `.v2` custom properties in `web/src/tokens.css`, as `var(--ink)` or
`var(--surface)`), and prefix your classes `x-<id>-`: `v2-` classes are core's. The `v2` in `.v2` and `v2-` is part of
the names, not a version: they are stable, and no later console renames them for a "v3". `npm run dev` serves the
component gallery at `/_gallery`.

## Server plugin

### Add one

1. Put it in `plugins/fork/<id>/`, with a `pom.xml` whose parent is `fork`:

   ```xml
   <artifactId>netaccess</artifactId>
   <packaging>jar</packaging>
   <version>0.1.0</version>
   <parent>
       <groupId>com.hmdm.plugin</groupId>
       <artifactId>fork</artifactId>
       <version>0.1.0</version>
       <relativePath>../pom.xml</relativePath>
   </parent>
   <dependencies>
       <dependency>
           <groupId>com.hmdm.plugin</groupId>
           <artifactId>platform</artifactId>
           <version>0.1.0</version>
       </dependency>
   </dependencies>
   ```

2. List it as a `<module>` in `plugins/fork/pom.xml` and as a `<dependency>` in `plugins/fork/bundle/pom.xml`. Core
   never changes those two lists. The server depends on the bundle, so `mvn -pl server -am package` builds the plugin
   into `launcher.war`: `unzip -l server/target/launcher.war | grep netaccess` lists
   `WEB-INF/lib/netaccess-0.1.0.jar`. Don't use `init-plugin.sh`: it edits core's poms.

3. Implement `com.hmdm.plugin.PluginConfiguration`. Put the plugin's classes in a package under `com.mdmesh`, by
   convention `com.mdmesh.<package>`, `<package>` being the id without dashes. The server finds a `PluginConfiguration`,
   and serves JAX-RS resources, only below `com.mdmesh` and `com.hmdm`; `com.hmdm` is core's. Jersey registers the
   `@Provider`s there too, and a provider under `com.mdmesh` (a `ContainerRequestFilter`, `ContainerResponseFilter` or
   `ExceptionMapper`) applies to every resource, core's included: check the request's path inside it before acting on
   it. `getRootPackage()` returns that package (`"com.mdmesh.netaccess"`), as each core plugin returns its own
   (`"com.hmdm.plugins.messaging"`); the server doesn't read it today. The server installs the Guice modules
   `getPluginModules` returns. `plugins/deviceinfo` is a complete example of the REST, Liquibase and persistence wiring.

4. Keep to the build's language level. The server runs on JDK 17, but the build compiles with `-source 1.8 -target
   1.8` (`java.version` in the root `pom.xml`): Java 8 syntax, Java 8 bytecode. So `var`, records, text blocks and
   switch expressions don't compile in a plugin, as they don't in core. The library is JDK 17's, because the build
   doesn't pass `--release 8`: core calls some Java 9+ APIs (`Set.of`), and a plugin can too. Don't raise the
   plugin's compile target (`-target`, `maven.compiler.target` or `maven.compiler.release`) in its pom: Jersey
   2.25.1's scanner can't read a class built for Java 17 under `com.hmdm` or `com.mdmesh`. It throws, and the
   server's REST fails to start.

**One id for everything:** `getPluginId()`, the `plugins` row's `identifier`, the validator's `extensionId()`, the
contributor's `pluginId()`, the console extension's `id` and its `requires: { serverPlugin }` are all `<id>`. Don't
reuse a core plugin's id (`audit`, `deviceinfo`, `devicelog`, `messaging`, `push`, `xtra`), or `fixture`, which core's
tests use: the scan keeps whichever it finds first and skips the other.

| What | Convention |
|---|---|
| REST | `/rest/plugins/<id>/private/...` and `/rest/plugins/<id>/public/...`, filtered in your `ServletModule` as messaging's are (`MessagingRestModule`): `JWTFilter`, `AuthFilter`, `PluginAccessFilter` and `PrivateIPFilter` on `/private/*`, `PublicIPFilter` on `/public/*`. `PluginAccessFilter` answers 404 on `/private/*` for a customer the plugin is off for. It doesn't cover `/public/*` (On or off, per customer, below). deviceinfo's filters are the same, but its paths have an extra segment (`/rest/plugins/deviceinfo/deviceinfo/private/*`). Return the `ServletModule` from `getPluginModules` itself, not installed from another module: core's `PrivateRouteFilterTest` reads its mappings and fails the build on a route below `/private/` that doesn't go through `AuthFilter` and then `PluginAccessFilter`. |
| Tables | `plugin_<id>_*`, with the id's dashes as underscores (`plugin_net_access_rules` for `net-access`) |
| Liquibase | `liquibase/<id>.changelog.xml`, run by an `AbstractLiquibaseModule`; changeSet ids `plugin-<id>-*`, as every core plugin's are (`plugin-deviceinfo-22.10.2019-13:34`). Give each changeSet `context="common"`, as core's do. It isn't required: the server runs Liquibase with the contexts `common,private` (`usage.scenario` is `private`), and a changeSet without a context runs in every context. Don't use `shared`: a changeSet with only that context never runs on an MDMesh install, as every one sets `usage.scenario=private`. |
| Permissions | `plugin_<id>_*` rows, with dashes as underscores, which your changelog adds together with their grants to roles (Permissions, below) |
| Plugin row | one `plugins` row with `identifier = '<id>'`, which your changelog inserts (`INSERT INTO plugins (identifier, name) VALUES ('netaccess', 'Network access')`). It is required: without it the plugin is off for every customer. |

**Permissions.** A `permissions` row alone grants nothing: a role has a permission only through a
`userRolePermissions` row. Add both in one changeSet, as `messaging.changelog.xml` does:

```xml
<changeSet id="plugin-netaccess-23.10.2026-permissions" author="you" context="common">
    <comment>Permission,new: plugin_netaccess_view</comment>
    <sql>
        INSERT INTO permissions (name, description) VALUES ('plugin_netaccess_view', 'Can see the network rules');
        INSERT INTO userRolePermissions (roleId, permissionId)
            SELECT r.id, p.id FROM userRoles r, permissions p
            WHERE p.name = 'plugin_netaccess_view' AND r.id IN (1, 2, 100);
    </sql>
    <rollback>
        DELETE FROM userRolePermissions
            WHERE permissionId IN (SELECT id FROM permissions WHERE name = 'plugin_netaccess_view');
        DELETE FROM permissions WHERE name = 'plugin_netaccess_view';
    </rollback>
</changeSet>
```

The seeded roles are 1 (Super-Admin), 2 (Admin), 3 (User) and 100 (Observer). Grant 1 and 2, 3 if operators should
have it, and 100 only for a read-only permission. The `admin` account a new install seeds is role 2, Admin, not
Super-Admin: without the grant to 2 it doesn't get the permission, and your items stay hidden from it. (Super-Admin
passes every permission check, with or without the row.) Roles added later don't get it.

**On or off, per customer.** A plugin's private REST (`/private/*`), validator and contributor run only for the
customers it is on for: it has its `plugins` row, the row isn't `disabled` (off for everyone; read at start, so restart
after changing it), and the customer hasn't turned it off (`pluginsDisabled`). A new customer gets a copy of customer
1's list, shortly after it is created.

A customer's list is set by POST `/rest/plugin/main/private/disabled`, for the signed-in user's customer, by a role
with `plugins_customer_access_management` (Super-Admin and Admin have it; others get `error.permission.denied`). The
body is a JSON array of `plugins.id` row ids, not identifiers: `[7]`. GET `/rest/plugin/main/private/active` lists the
rows with their `id`. It replaces the customer's whole list, so send the ids that are already off with the one you
add; `[]` turns every plugin back on. It takes effect at once, without a restart.

Your public REST (`/public/*`, which devices call without a signed-in user) stays live for every customer: no filter
checks it. A public resource checks for itself. Inject `com.hmdm.plugin.service.PluginStatusCache` and call
`isPluginDisabled("<id>", customerId)` with the customer of the device the request is for, and refuse the request
with `Response.PLUGIN_DISABLED()` (`error.resource.disabled`) when it answers true. Find that customer as
`DeviceInfoResource` does: look the device up by the number in the request with `UnsecureDAO.getDeviceByNumber(number)`
(in `common`), refuse the request with `Response.DEVICE_NOT_FOUND_ERROR()` (`error.notfound.device`) when there is no
such device, and pass the device's `getCustomerId()`. Both refusals are the usual `ERROR` envelope with HTTP status
200, not the 404 `PluginAccessFilter` answers on `/private/*`:

```java
@GET
@Path("/public/ping/{number}")
@Produces(MediaType.APPLICATION_JSON)
public Response ping(@PathParam("number") String number) {
    Device device = unsecureDAO.getDeviceByNumber(number);
    if (device == null) return Response.DEVICE_NOT_FOUND_ERROR();
    if (pluginStatusCache.isPluginDisabled("netaccess", device.getCustomerId())) return Response.PLUGIN_DISABLED();
    return Response.OK("pong");
}
```

The one-argument `isPluginDisabled("<id>")`, which `PluginAccessFilter` uses, reads the customer from the
`SecurityContext`. A `/public/*` request has none, and without a customer it checks only that the plugin is installed
and not off for everyone, so it answers false (on) whichever customer turned it off. So call it only after
`SecurityContext.init(device.getCustomerId())`, releasing it in a `finally`, as `DeviceInfoResource` does; the
two-argument form needs neither. A public route also has to be declared ([Public routes](#public-routes), below).

The console's `requires: { serverPlugin }` reads the same answer from `/rest/plugin/main/private/available`, so your
tabs and sections show where the plugin works. One difference: that list reads the row's `disabled` live, while the
server reads it at start, so the two disagree until the restart.

Bind the extension points from one of your Guice modules. Core binds none, so without plugins nothing changes:

```java
Multibinder.newSetBinder(binder(), ConfigurationExtensionValidator.class).addBinding().to(NetaccessValidator.class);
Multibinder.newSetBinder(binder(), DesiredConfigContributor.class).addBinding().to(NetaccessPolicies.class);
```

On JDK 17, Guice needs `--add-opens java.base/java.lang=ALL-UNNAMED` for `.to(X.class)`. Tomcat runs with it, but a
plain JUnit run doesn't. In a test that builds an injector from your module, bind `.toInstance(new X())` instead, or
add the flag to surefire's `argLine`.

### Public routes

A route that isn't below a `/private/` segment answers without a console session, to anyone who can reach the server. So
each one is a decision someone made on purpose, and the build checks it was made. Core's `PublicRouteInventoryTest`
reads the Java sources under every module's `src/main/java` (it doesn't follow symlinks): the routes of each `@Path`
resource, `@WebServlet` and `@ServerEndpoint` class, and each `publicResources` list written as `Arrays.asList(...)`,
`Collections.singletonList(...)` or `List.of(...)`. It also reads the servlet mappings in
`server/src/main/webapp/WEB-INF/web.xml`, as core's routes, which only core's list can keep: a plugin's servlets go in
its `ServletModule`. A `ServletModule`'s servlets are read from the bindings the module makes, so each pattern it serves
counts however it is written (`serve(BASE + "*")` too), as a route of the module's source. It also checks that every
resource Jersey serves and every module that serves a servlet was among those sources, so one from a symlinked, Kotlin
or dependency-only plugin fails the build instead of going unchecked. Modules are checked by class: every concrete
`ServletModule` class under `com.hmdm` and `com.mdmesh` on the server's classpath, nested, anonymous and indirect
subclasses included, must be the class of a module the server installs on its own, so return each of yours from
`getPluginModules()` itself rather than installing it from inside another module. `mvn -pl server -am package` fails on
a public route nobody has kept. (A route below `/private/` is `PrivateRouteFilterTest`'s: it must go through
`AuthFilter`.) Core keeps its own in that test. A plugin in `plugins/fork/` declares its in
`plugins/fork/public-routes.txt`, one route per line, or a prefix that covers the routes below it:

```
/rest/plugins/netaccess/public
```

Core ships that file with only its header comment and never changes it, so your lines stay yours. It counts only
for sources under `plugins/fork/`: it can't make a core route public. Each line names one plugin's routes:
`/rest/plugins/<id>` or below it, `<id>` an id no core route uses. The build refuses a broader line (`/*`, `/rest`,
`/rest/plugins`), so a fork route outside `/rest/plugins/<id>/`, under a legacy prefix such as `/rest/public/sync`
included, can't be declared. Until a route is declared the build fails with
`public routes that are neither legacy (...) nor kept: ... [/rest/plugins/netaccess/public, ...]`.

### Configuration data: extensions

A configuration's `extensions` is a JSON object of namespaces: `<id>` and `<id>.<name>`, each a JSON object, stored in
`configurations.extensions` (jsonb). The console's sections store their values there, and your plugin reads them with
`configuration.getExtensions()`.

- One configuration's GET (`/rest/private/configurations/{id}`) returns it, and a copy (Duplicate) copies it. The
  lists (`/search`, `/search/{value}`) leave it out.
- A save (PUT `/rest/private/configurations`) without `extensions`, or with null, keeps what is stored, so an older
  client can't wipe it. A save with it replaces all of it: send back every namespace you loaded.
- jsonb doesn't keep key order: compare values, not their text. Numbers compare by value (`1.0` stored, `1` sent is
  not a change). This works both ways: `1` stored and `1.0` sent by an API client is not a change either, so the
  validator isn't asked and `1.0` is written. A validator that wants integers only (`isIntegralNumber()`) must read
  the stored data defensively too.
- It travels in configuration reads to every user who can open the configuration. Keep secrets (VPN keys) out of it
  unless you also keep them out of those reads.

A `ConfigurationExtensionValidator` (`com.hmdm.extension`) checks a save:

```java
public class NetaccessValidator implements ConfigurationExtensionValidator {
    @Override public String extensionId() { return "netaccess"; }

    @Override public List<Problem> validate(String namespace, JsonNode value) {
        if (!"netaccess.vpn".equals(namespace) || !value.path("on").asBoolean() || !value.path("host").asText().trim().isEmpty()) return null;
        return Collections.singletonList(new Problem("host", "Give the VPN a host before turning it on."));
    }
}
```

- It is asked about each of its namespaces the save changes: every one for a new configuration, and on an update
  those whose value differs from what is stored.
- So data it would now refuse never blocks a save that leaves it alone. The exception is a new configuration made
  from a template that holds such data. Duplicate (`/configurations/copy`, which needs `copy_config`) copies a
  configuration without checks.
- Core's own checks come first, on the same namespaces: a namespace's name (`error.extensions.namespace`), a JSON
  object (`error.extensions.object`), no U+0000 (`error.extensions.nul`), at most 16 KB
  (`error.extensions.namespace.size`), and at most 64 KB for all namespaces, changed or not (`error.extensions.size`).
- A `Problem(code, message)` refuses the save. The response is an `ERROR` whose message is
  `error.extension.<namespace>.<code>` (`error.extension.netaccess.vpn.host`) and whose data lists every problem with
  its namespace, key and message. The console shows the messages. A `code` that isn't lowercase kebab-case of at most
  32 characters becomes `invalid`, and a problem without a message gets
  `The "<namespace>" settings were refused (<code>).`
- It gets a copy of the data, so it can't change what is stored. Data stored before the plugin was installed, while it
  was off for the customer, or by a copy, was never checked: read it defensively.
- `extensionId()` is read once, at start. One that isn't lowercase kebab-case of at most 32 characters fails the
  server's start.
- A validator that throws refuses the save with `error.internal.server`, and the server logs it with your id. The
  response is the usual `ERROR` envelope with HTTP status 200, not a 500. This holds for anything it throws, an `Error`
  (Kotlin's `TODO()`, an `AssertionError`, a `StackOverflowError`) included. Only the JVM's other failures
  (`OutOfMemoryError`) pass through.

### Policy keys in config.apply

A `DesiredConfigContributor` adds keys to the document a configuration's devices apply:

```java
public class NetaccessPolicies implements DesiredConfigContributor {
    @Override public String pluginId() { return "netaccess"; }

    @Override public Map<String, Boolean> policies(Configuration configuration, List<Application> applications) {
        JsonNode on = configuration.getExtensions() == null ? null : configuration.getExtensions().path("netaccess.vpn").get("on");
        return on == null || !on.isBoolean() ? null : Collections.singletonMap("vpn", on.asBoolean());
    }
}
```

- The devices get the key `x.netaccess.vpn`, flat in `policies`: `"policies": {"x.netaccess.vpn": true}`, read as
  `policies["x.netaccess.vpn"]`, not as nested objects. The server puts `x.<pluginId>.` in front of each key your map
  returns (`vpn`), which must be letters and digits starting with a lowercase letter, at most 64 characters.
- Values are booleans only: `policies` is a map of booleans on the wire, and the agent would refuse the whole document
  for one value of another type. Settings that aren't booleans will come as a new section of the document.
- A null value, or no key, leaves the document and its revision as they are without your plugin, so installing it
  re-applies only the configurations it has something to say about.
- MDMesh's agent reports a key it has no toggle for as `unsupported` and applies the rest of the document. The device
  page lists it as "netaccess: vpn", Not supported. To enforce it, add the toggle to your fork's agent
  (`ConfigApplier`'s toggle policies).
- It runs on every check-in of a capable device, and each time the console reads a device's or the configurations'
  sync status. Keep it pure and fast, and treat the configuration and its apps as read-only. `pluginId()` is read
  once, at start; one that isn't lowercase kebab-case of at most 32 characters fails the server's start.
- **Fail closed:** a throw (an `Error` included, and a failure while your map is read), a bad key or a value that isn't
  a Boolean stops config.apply for that configuration's devices until it is fixed, rather than sending a document
  without it. The server logs it, and the console shows those devices out of sync.
- Turning the plugin off for a customer removes its keys from the documents of that customer's configurations: their
  revisions change, the devices apply them again, and the agent releases what the keys managed. The `extensions` data
  stays as stored.
- The document is stored with each config.apply command and sent to the device, so the rule on secrets applies here
  too.

## Testing

`npm test` (in `web/`) runs every console test with vitest and jsdom, yours included. Without any test of yours it
already imports and validates every manifest (`web/src/ext/load.test.ts`) and checks the import rules. Put render
tests next to your code, as `*.test.tsx`, and render the whole console with `@mdmesh/console/testing`:

```tsx
// web/src/extensions/netaccess/netaccess.test.tsx
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderConsole, stubApi } from '@mdmesh/console/testing';
import netaccess from './index';

describe('netaccess', () => {
  it('shows its page to a role with plugin_netaccess_view, when the server plugin is on', async () => {
    stubApi((path) => (path.endsWith('/plugin/main/private/available') ? [{ identifier: 'netaccess' }] : null));
    renderConsole({ extensions: [netaccess], path: '/network' });
    expect(await screen.findByText('Nothing blocked')).toBeTruthy();
  });
});
```

- `renderConsole({ extensions, path, user })` renders the console's routes, shell and pages at `path`, signed in as
  `user` (a super-admin by default), with core and `extensions` registered. A problem with an extension throws.
- `stubApi(answer)` answers every API call with an OK envelope: `answer(path, init)`'s value, or null.
- `ROLES` holds the seeded roles as users (`superAdmin`, `admin`, `user`, `observer`), to test `requires`.
- `registryOf(...extensions)` builds the registry alone, for a test of the manifest's validation.

`npx vitest run src/extensions/netaccess` runs only yours. `@mdmesh/console/testing` is for tests only, and it has no
stability promise yet: it may change in any release.

On the server, a plugin's own JUnit tests run with `mvn -pl server -am package`, like core's.

## Stability

- `EXTENSION_API_VERSION` is 1. The console refuses an extension written for another `apiVersion`, so a stale fork
  fails in CI rather than rendering wrongly.
- Removing or changing an export of `@mdmesh/console` or a slot contract is a breaking change. It is announced in the
  CHANGELOG ("Console extension API"), and the old one is kept, marked `@deprecated`, for one release (about three
  weeks) first; then the change bumps `EXTENSION_API_VERSION`. Adding is not breaking.
- `DeviceView`, `Configuration` and `Application` are the console's own types, as the server sends them: fields may
  be added, never removed or changed without that deprecation.
- `web/src/sdk/surface.txt` (the names and the design tokens) and `web/src/sdk/surface.d.ts.txt` (the types and props
  behind them) are snapshots. Any change to the surface fails `npm test` until they are updated on purpose
  (`npm test -- -u`), so it shows in review.
- The design tokens keep their names, and the `.v2` class on `<body>` that scopes them stays: a token is renamed or
  removed only with the same deprecation.
- `ORDER`'s keys and values are in that snapshot, and tests pin core's nav ids, page paths and section anchors
  (`/settings#updates`).
- The server side has the same promise: `ConfigurationExtensionValidator`, `DesiredConfigContributor`, the
  `configurations.extensions` storage and its limits, and the plugin conventions in the Server plugin section above. A
  breaking change to any of them gets the same CHANGELOG notice and one release of deprecation. New methods arrive as
  default methods, so they are not breaking.

## Troubleshooting

- **`npm run build` stops with "These v0.3 console files were removed or replaced in 0.4.0".** A merge of 0.4.0 kept
  files the v0.3 console had (`check-v03-files.ts` lists them). Each line says where the file's content belongs now;
  move your changes there, then delete the file
  ([docs/UPGRADING.md, "After the merge" under v0.4.0 for forks](../docs/UPGRADING.md#v040-for-forks)).
- **The server logs `The validator "netaccess" runs for no customer: no plugin with this id is installed and on (a
  PluginConfiguration, and a plugins row that isn't disabled)`** (or `The contributor ...`). No plugin of that id is
  installed and on: the id differs from the plugin's `getPluginId()` (one id for everything), the `plugins` row is
  missing, or the row is `disabled`. The INFO line logged just before these warnings, `Server plugins' extension
  points: validators [...], contributors [...]`, lists what the server found. A plugin missing from it is missing
  from `plugins/fork/bundle/pom.xml`, or its `PluginConfiguration` is outside `com.mdmesh` and `com.hmdm`.
- **The plugin's private REST answers 404.** It has no `plugins` row, the row is `disabled`, or the customer turned it
  off. `PluginAccessFilter` answers 404 on `/private/*` for all of them. (If its own check fails, it logs the error
  and lets the request through.) The console's `requires: { serverPlugin }` hides the extension's items for the same
  reasons (`/rest/plugin/main/private/available` doesn't list it). When its `/public/*` routes answer 404 too, the
  filter isn't the cause (it doesn't cover them): Jersey doesn't serve the resource. Jersey serves resources only below
  `com.mdmesh` and `com.hmdm`, so check that the resource class is in your plugin's package and has its `@Path`.
- **The console shows none of the extension.** Look in the browser console for `[ext:<id>]` lines, and run `npm test`.
  The folder name must be the extension id.
- **The console doesn't start after adding an extension.** A manifest probably throws while it loads. To find which,
  move extension folders out of `web/src/extensions/` and rebuild. `VITE_EXTENSIONS_DISABLED=netaccess,other npm run
  build` leaves those extensions out of the registry, to bisect a build that starts but misbehaves. Their manifests are
  still bundled and run, so it can't get past one that throws.
- **A save is refused with `error.extensions.*`.** Core's own checks refused the data (Configuration data, above). The
  toast shows the sentence.
