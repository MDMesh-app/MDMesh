// The console extension API: what an extension (web/src/extensions/<id>/index.ts) declares, and what core
// declares about itself through the same shapes (ext/core). Forks read these types through `@mdmesh/console`.
//
// An extension is typed data: an id, a title and a list of contributions. Each contribution names the slot it fills
// (a sidebar item, a routed page, a device tab, ...). Hosts render the contributions of their slot in `order`, each
// inside its own error boundary, and only when the signed-in user passes the contribution's `requires`.
import { lazy, type ComponentType, type LazyExoticComponent, type ReactElement } from 'react';
import type { Application } from '../api/applications';
import type { DeviceState, QueueCommandRequest } from '../api/commands';
import type { ConfigStatus } from '../api/configSync';
import type { Configuration } from '../api/configurations';
import type { DeviceView } from '../api/devices';
import type { TelemetrySnapshot } from '../api/telemetry';
import type { IconProps } from '../ui/icons';
import type { KeyValueItem } from '../ui/KeyValueList';
import type { MenuItem } from '../ui/Menu';
import type { ToastApi } from '../ui/toast';

/**
 * Major version of this API. A breaking change to a slot contract or to `@mdmesh/console` bumps it, after one
 * release of deprecation (CHANGELOG "Console extension API"). The registry refuses an extension built for another
 * version, so a stale fork fails loudly in CI instead of rendering wrongly.
 */
export const EXTENSION_API_VERSION = 1;

/** A component loaded on first render (its own chunk), made with {@link lazyComponent}. */
export type LazyComponent<P = object> = LazyExoticComponent<ComponentType<P>>;

/**
 * Who may see a contribution. Every field given must pass. This is UX only: the server enforces every call, and the
 * console hides what the server would refuse.
 */
export interface Requirement {
  /** Any one of these server permission names (case-insensitive, as the server compares). Super-admins pass. */
  permission?: string | readonly string[];
  /**
   * The id of a server plugin that must be on for the user's customer (`/rest/plugin/main/private/available`): in the
   * server's build, with its `plugins` row, and turned off neither for everyone nor for the customer.
   * Hidden while that list loads; when it cannot be read, this passes (the server still refuses what is not there).
   */
  serverPlugin?: string;
  /** Any other rule. A rule that throws counts as "no". */
  when?: (ctx: { user: CurrentUser }) => boolean;
}

/** The signed-in user as extensions see it: a stable subset of the server's user object. */
export interface CurrentUser {
  id: number;
  login: string;
  name?: string;
  email?: string;
  /** A super-admin passes every permission check. */
  superAdmin: boolean;
  /**
   * Whether the user reaches every device of the customer: false for one an admin limited to some device groups
   * (the server's SecurityContext.canReachAllDevices). Super-admins always do.
   */
  allDevices: boolean;
  /** The role's permission names, as the server last sent them (at sign-in, then on each refresh). */
  permissions: readonly string[];
}

interface Base {
  /** Lowercase kebab-case, unique within the slot across core and every extension. */
  id: string;
  requires?: Requirement;
}

/** Contributions rendered in a sequence. Core uses 100, 200, ... (`ORDER`), so a fork can slot in between. */
interface Ordered extends Base {
  order: number;
}

/** A sidebar item. It must point at a path a `route.page` serves, and it shows only when that page would. */
export interface NavItemContribution extends Ordered {
  slot: 'nav.item';
  label: string;
  icon: ComponentType<IconProps>;
  to: string;
  /**
   * Rendered after the label (Devices shows the fleet size); renders nothing on error. It shows on every page, so a
   * plain component is fine here (a lazyComponent works too).
   */
  badge?: ComponentType;
}

/**
 * A page inside the app shell. `path` is lowercase kebab-case segments and `:params`, e.g. `/network-map/:id`. It
 * may not reuse a core page's first segment or a path the edge sends elsewhere (ext/reservedPaths.ts).
 */
export interface RoutePageContribution extends Base {
  slot: 'route.page';
  path: string;
  component: LazyComponent;
}

/**
 * A command for `useDevice().sendCommand`: the name its toasts use ("Ring queued", "Ring failed"), and what to queue,
 * as a `request`, or `build`, which makes the request from `sendCommand`'s `values`.
 */
export type DeviceCommand =
  | { label: string; request: QueueCommandRequest }
  | { label: string; build(values: Record<string, string>): QueueCommandRequest };

/**
 * What the device page knows about the open device, for its tabs (`useDevice()`) and rail groups. The page loads
 * and polls it (every 5 seconds while the browser tab is shown); a tab only reads it. Fields may be added.
 *
 * It is a new object on every render (each poll, each 30-second tick), so an effect should depend on the fields it
 * reads, such as `device.number`, never on the whole value. `refresh` and `sendCommand` stay the same function for
 * as long as the page shows this device.
 */
export interface DeviceContextValue {
  device: DeviceView;
  /** The agent's last telemetry report; null until it loads, or when the device has sent none. */
  telemetry: TelemetrySnapshot | null;
  /** Whether the first telemetry answer is in (null then means "none reported"). */
  telemetryLoaded: boolean;
  /** The agent's last state report (battery, lock, kiosk, agent version, `updatedAt`); null until there is one. */
  state: DeviceState | null;
  /** The device's configuration, once loaded; null when it has none, or until then. */
  configuration: Configuration | null;
  /** Whether the device runs its configuration's current revision; null until known. */
  configStatus: ConfigStatus | null;
  /** The time, updated every 30 seconds: for relative times that stay current. */
  now: number;
  /** Poll again now (after the one in flight, if any), instead of waiting for the next round. */
  refresh(): void;
  /**
   * Queue a command for this device the way the page sends its own: queue it, toast the outcome, nudge the device to
   * sync, refresh. It shows no confirmation or form: ask in the tab first when the command needs it. `values` feed
   * `build`. Resolves to whether it was queued (a failure has been toasted already). It leaves the page's own
   * actions as they are (not greyed out meanwhile, no mode marked pending): show your command's progress in your tab.
   */
  sendCommand(command: DeviceCommand, values?: Record<string, string>): Promise<boolean>;
}

/**
 * A tab on the device page, its body reading the device with `useDevice()`. The tab id is its `?tab=` value, so a
 * link can open it (`/devices/WH-12?tab=network`). Without `?tab=` the first tab opens; a `requires.serverPlugin`
 * tab ordered first keeps every tab closed until the plugin list is in, so the open tab never changes under the user.
 */
export interface DeviceTabContribution extends Ordered {
  slot: 'device.tab';
  label: string;
  icon?: ComponentType<IconProps>;
  /** Its own chunk, loaded when the tab is first opened. */
  component: LazyComponent;
  /** A short count after the label, as Activity shows its last 24 hours ("12"); undefined shows none. */
  count?(device: DeviceContextValue): string | undefined;
}

/** A titled group of facts in the device page's left rail, placed among the core groups by `order`. */
export interface DeviceRailGroupContribution extends Ordered {
  slot: 'device.railGroup';
  title: string;
  /** The rows; a group with none is left out. Keep it pure and cheap: it runs on every render of the page. */
  items(device: DeviceContextValue): KeyValueItem[];
}

/** What the configuration editor hands a `config.section` body. */
export interface ConfigSectionProps<T> {
  /**
   * The draft value: what `onChange` last gave, else the stored one, else `storage.defaults`. It comes frozen, all
   * the way down, in each case (the editor freezes the plain object given to `onChange`): changing it in place
   * (`value.hosts.push(h)`) throws.
   */
  value: T;
  /** The value as loaded (else `storage.defaults`), for "Changed" marks. Frozen, as `value`. */
  stored: T;
  /**
   * Replace the section's draft value with a new plain JSON object, never `value` changed in place
   * (`onChange({ ...value, hosts: [...value.hosts, h] })`). The editor's changed count, leave guard, Discard and
   * single save pick it up.
   */
  onChange(next: T): void;
  /** True on a built-in template, and for a role the server would not let save the configuration. */
  readOnly: boolean;
  /** The whole draft configuration, read only (e.g. to react to kiosk mode). */
  configuration: Readonly<Configuration>;
  /**
   * The problems to show: the section's `validate` result after a save attempt, or the server's refusal of its
   * namespace (its plugin's validator). Cleared when the value changes.
   */
  errors: readonly string[];
  /** A stable prefix for field ids. */
  idBase: string;
}

/**
 * A section of the configuration editor, placed among core's by `order` (`ORDER.configSection`). The editor renders
 * its card (`title`, `subtitle`, the anchor `x-<extension id>-<id>`) and its entry in the section nav; the body renders
 * the fields. Its value is saved with the configuration, in the same request, under `extensions[namespace]`: only when
 * it changed, on top of what is stored, so the namespaces of extensions this build lacks, or that the role cannot see,
 * stay as they are. A body that crashes shows a callout in its card; its namespace then keeps its stored value, and
 * the rest of the configuration still saves.
 */
export interface ConfigSectionContribution<T> extends Ordered {
  slot: 'config.section';
  title: string;
  subtitle?: string;
  /**
   * `namespace` is the extension id (the default) or `<id>.<name>` with `<name>` lowercase kebab-case
   * (`netaccess.vpn`), so an extension can only ever write its own. `defaults` is a JSON object, never written
   * until the user changes something; the registry keeps a frozen copy of it, as JSON has it. Values travel as a
   * browser's JSON: keep integers within ±2^53 (a 64-bit id goes in a string).
   */
  storage: { namespace?: string; defaults: T };
  component: LazyComponent<ConfigSectionProps<T>>;
  /**
   * Problems that block the save (empty: fine), as sentences for people. Run on Save when the value changed; one
   * that throws counts as none (the server still checks).
   */
  validate?(value: T, configuration: Readonly<Configuration>): string[];
  /**
   * How many changes the section counts, when its value differs from the stored one (default 1; at least 1, as the
   * value is saved either way). The values are compared as JSON, whatever their keys' order (the server's jsonb
   * keeps none).
   */
  changes?(stored: T, value: T): number;
}

/** What the Settings page hands a `settings.section` body. */
export interface SettingsSectionProps {
  /** The section's anchor (`/settings#<anchorId>`): the id of its card. */
  anchorId: string;
  /** The id of the section's heading, for the card's `aria-labelledby`. */
  headingId: string;
}

/**
 * A section of the Settings page, placed among core's sections by `order`, with an entry in its section nav that
 * reads `title`. The body renders its own card, as core's do:
 * `<Card id={anchorId} aria-labelledby={headingId}><CardHeader title="…" titleId={headingId} />…</Card>`.
 * Its anchor is `x-<extension id>-<id>`, so it can never take one core pages link to (`/settings#updates`).
 */
export interface SettingsSectionContribution extends Ordered {
  slot: 'settings.section';
  title: string;
  component: LazyComponent<SettingsSectionProps>;
}

/**
 * A card on the Overview, placed among core's by `order`. `main` and `aside` cards fill the rows under the fleet
 * summary: a `main` card starts a row, and the `aside` cards after it stack in that row's narrower column (so a
 * `main` placed between a core main card and its aside takes that aside into its own row). Their body renders a
 * `Card`. A `stat` is a tile in the fleet summary's stat grid, its body a `StatCell`. A body that
 * renders nothing takes no room.
 */
export interface OverviewCardContribution extends Ordered {
  slot: 'overview.card';
  placement: 'main' | 'aside' | 'stat';
  component: LazyComponent;
}

/** The row type of each list a contribution can extend. */
export interface ListRows {
  devices: DeviceView;
  apps: Application;
}

/** What a list hands its row and bulk actions. */
export interface RowActionHelpers {
  toast: ToastApi;
  /** Reload the list. */
  refresh(): void;
}

/** What the Devices list hands its row and bulk actions. */
export interface DeviceActionHelpers extends RowActionHelpers {
  /**
   * Queue a command on these devices and toast the outcome ("Ring queued", "Ring failed"): one device as the device
   * page sends it (queued, then nudged to sync), several as one bulk request. It refuses what the bulk endpoint
   * refuses, wipe and passcode reset, for any number of devices: those go from the device page only. `values` feed
   * `build`. It shows no confirmation: ask first when the command needs it. Resolves, once the nudge is sent, to
   * whether it was queued.
   */
  sendCommand(devices: readonly DeviceView[], command: DeviceCommand, values?: Record<string, string>): Promise<boolean>;
}

/** The helpers each list hands its actions. */
export interface ListHelpers {
  devices: DeviceActionHelpers;
  apps: RowActionHelpers;
}

/**
 * A column of the Devices or Apps table (not the Devices cards, which keep their layout), placed among core's by
 * `order` (`ORDER.devicesColumn`, `ORDER.appsColumn`). `header` is text or one element. A cell that throws shows `—`,
 * and one React cannot render shows a small error mark, in that cell only.
 */
export type ListColumnContribution = {
  [L in keyof ListRows]: Ordered & {
    slot: 'list.column';
    list: L;
    header: string | ReactElement;
    /** Keep it pure and cheap: it runs for every row on every render. */
    cell(row: ListRows[L]): ReactElement | string | number | null;
    /** Makes the column sortable: rows sort by this value (numbers, then text; null last). */
    sortValue?(row: ListRows[L]): string | number | null;
    mono?: boolean;
    align?: 'left' | 'right';
  };
}[keyof ListRows];

/**
 * An entry in a row's "More actions" menu, placed among core's by `order` (`ORDER.devicesRowAction`,
 * `ORDER.appsRowAction`); on Devices, from `ORDER.devicesRowAction.move` on it joins the group below the separator.
 * Return null to leave it out for that row. A label that is not plain text needs `textValue`.
 */
export type ListRowActionContribution = {
  [L in keyof ListRows]: Ordered & {
    slot: 'list.rowAction';
    list: L;
    item(row: ListRows[L], helpers: ListHelpers[L]): MenuItem | null;
  };
}[keyof ListRows];

/**
 * A button in the Devices bulk-selection bar, placed among core's by `order` (`ORDER.devicesBulkAction`). `run`
 * gets the selected devices the list shows; the button is disabled until a promise it returns settles.
 */
export interface ListBulkActionContribution extends Ordered {
  slot: 'list.bulkAction';
  list: 'devices';
  label: string;
  icon?: ReactElement;
  danger?: boolean;
  run(rows: readonly DeviceView[], helpers: DeviceActionHelpers): void | Promise<void>;
}

export type Contribution =
  | NavItemContribution
  | RoutePageContribution
  | DeviceTabContribution
  | DeviceRailGroupContribution
  // `any`: each section has its own value type, which a union member cannot name. `configSection<T>()` checks it.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  | ConfigSectionContribution<any>
  | SettingsSectionContribution
  | OverviewCardContribution
  | ListColumnContribution
  | ListRowActionContribution
  | ListBulkActionContribution;

export type SlotId = Contribution['slot'];

/** The contribution type of one slot. */
export type ContributionOf<S extends SlotId> = Extract<Contribution, { slot: S }>;

export interface ExtensionDefinition {
  /** Lowercase kebab-case, the same as the extension's folder name. `core` is MDMesh's own. */
  id: string;
  /** The API version the extension was written against: always `EXTENSION_API_VERSION`. */
  apiVersion: typeof EXTENSION_API_VERSION;
  /** Human name, used in error callouts ("The “Network access” extension ran into a problem"). */
  title: string;
  /** Applies to every contribution, on top of each one's own `requires`. */
  requires?: Requirement;
  contributions: readonly Contribution[];
}

/** Declare an extension: the default export of `extensions/<id>/index.ts`. Typing only. */
export function defineExtension(definition: ExtensionDefinition): ExtensionDefinition {
  return definition;
}

/** Declare a configuration section with its value type checked against its component, validator and defaults. */
export function configSection<T>(section: Omit<ConfigSectionContribution<T>, 'slot'>): ConfigSectionContribution<T> {
  return { ...section, slot: 'config.section' };
}

/**
 * A component from a named export, loaded on first render: `lazyComponent(() => import('./NetworkTab'), 'NetworkTab')`.
 * Each extension's UI is then its own chunk, so extensions do not grow the console's first load.
 */
export function lazyComponent<P, K extends string>(
  load: () => Promise<Record<K, ComponentType<P>>>,
  name: K,
): LazyComponent<P> {
  return lazy(async () => {
    let module: Record<K, ComponentType<P>>;
    try {
      module = await load();
    } catch (error) {
      throw lazyLoadError(error instanceof Error ? error.message : String(error), error);
    }
    const component = module[name];
    // A renamed export would otherwise surface as React's "element type is invalid", far from the cause.
    if (!component) throw lazyLoadError(`lazyComponent: the module has no export named "${name}"`);
    return { default: component };
  });
}

/**
 * What a DeviceCommand queues. `build` counts only when it is a function: plain JavaScript can carry `build:
 * undefined` beside a request. Internal: core's two senders (the device page, the Devices list) share it.
 */
export function commandRequest(command: DeviceCommand, values: Record<string, string>): QueueCommandRequest {
  return 'build' in command && typeof command.build === 'function'
    ? command.build(values)
    : (command as { request: QueueCommandRequest }).request;
}

/**
 * Core's own plain component where a slot takes a LazyComponent: core's bodies come with the page that builds them,
 * so there is no chunk to load and no Suspense flash. Hosts render both alike. Internal: not in `@mdmesh/console`,
 * so extensions are still held to lazyComponent.
 */
export function coreComponent<P>(component: ComponentType<P>): LazyComponent<P> {
  return component as unknown as LazyComponent<P>;
}

/**
 * Core's configuration sections edit the configuration's own fields, which the editor binds itself, not `extensions`:
 * their `storage` is this object, which only core can name, and the namespace rule does not apply to them. Internal:
 * not in `@mdmesh/console`.
 */
export const CORE_STORAGE: { readonly defaults: null } = Object.freeze({ defaults: null });

/**
 * React.lazy keeps a failed load and throws it again on every render, so "Try again" cannot help: error boundaries
 * offer a reload for this name (ui/AppErrorBoundary isChunkLoadError).
 */
function lazyLoadError(message: string, cause?: unknown): Error {
  return Object.assign(new Error(message), { name: 'LazyLoadError', cause });
}
