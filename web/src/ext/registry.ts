// The registry: every contribution of core and of the extensions, validated, sorted per slot and frozen. It is
// built once at startup (ext/load.ts); hosts read their slot from it and filter by the signed-in user.
//
// Validation is pure and reports problems instead of throwing: load.ts throws on them under test (so CI fails) and
// otherwise logs them and drops what is wrong, so one broken extension never takes the console down.
import { isValidElement } from 'react';
import { matchRoutes } from 'react-router';
import { frozenJsonCopy } from './freeze';
import { CONSOLE_SEGMENTS, EDGE_PREFIXES } from './reservedPaths';
import {
  CORE_STORAGE,
  EXTENSION_API_VERSION,
  type Contribution,
  type ContributionOf,
  type ExtensionDefinition,
  type Requirement,
  type SlotId,
} from './types';

/** The id core's own contributions are registered under. No extension may use it. */
export const CORE_ID = 'core';

export interface ExtensionInfo {
  readonly id: string;
  readonly title: string;
  readonly core: boolean;
}

export interface SlotEntry<C extends Contribution = Contribution> {
  /** `<extension id>/<contribution id>`: the React key and the log tag (`[ext:netaccess/network]`). */
  readonly key: string;
  readonly extension: ExtensionInfo;
  readonly contribution: C;
  /** Every requirement that must pass: the extension's, the contribution's and, for a nav item, its page's. */
  readonly requires: readonly Requirement[];
}

export interface Registry {
  readonly extensions: readonly ExtensionInfo[];
  /** The slot's entries in display order (all of them: hosts filter by the user). */
  entries<S extends SlotId>(slot: S): readonly SlotEntry<ContributionOf<S>>[];
  /** Whether any contribution depends on the active server plugins, so the console only fetches them when needed. */
  readonly usesServerPlugins: boolean;
}

export interface RegistryResult {
  registry: Registry;
  /** One line per dropped extension or contribution, e.g. `[ext:netaccess] route.page "map": path "/rest/map" ...`. */
  problems: string[];
}

/**
 * What a field holds: text, a component, a function, true or false, one of a few values, a React element, or text or
 * an element (`node`). Elements and nodes are checked because core markup renders them (a table header, a button's
 * icon), where a stray object would break the page rather than the contribution.
 */
type FieldKind = 'text' | 'component' | 'function' | 'boolean' | 'element' | 'node' | readonly string[];

/**
 * The fields each slot cannot do without, checked at runtime because a manifest that is not type-checked (plain
 * JavaScript, or an `as any`) must not crash the console while it loads. Keyed by SlotId, so the compiler flags a
 * slot added to the Contribution union but not here.
 */
const REQUIRED_FIELDS: Record<SlotId, Record<string, FieldKind>> = {
  'nav.item': { label: 'text', icon: 'component', to: 'text' },
  'route.page': { path: 'text', component: 'component' },
  'device.tab': { label: 'text', component: 'component' },
  'device.railGroup': { title: 'text', items: 'function' },
  'config.section': { title: 'text', component: 'component' },
  'settings.section': { title: 'text', component: 'component' },
  'overview.card': { placement: ['main', 'aside', 'stat'], component: 'component' },
  'list.column': { list: ['devices', 'apps'], header: 'node', cell: 'function' },
  'list.rowAction': { list: ['devices', 'apps'], item: 'function' },
  'list.bulkAction': { list: ['devices'], label: 'text', run: 'function' },
};

/** Fields a slot may leave out, checked when given: a text `badge` would render as an unknown DOM element. */
const OPTIONAL_FIELDS: Partial<Record<SlotId, Record<string, FieldKind>>> = {
  'nav.item': { badge: 'component' },
  'device.tab': { icon: 'component', count: 'function' },
  'config.section': { subtitle: 'text', validate: 'function', changes: 'function' },
  'list.column': { sortValue: 'function', mono: 'boolean', align: ['left', 'right'] },
  'list.bulkAction': { icon: 'element', danger: 'boolean' },
};

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

/** Components are functions or classes, or React's own objects (lazy, memo, forwardRef), which carry `$$typeof`. */
function hasKind(value: unknown, kind: FieldKind): boolean {
  if (typeof kind !== 'string') return typeof value === 'string' && kind.includes(value);
  if (kind === 'text') return typeof value === 'string' && value.trim() !== '';
  if (kind === 'function') return typeof value === 'function';
  if (kind === 'boolean') return typeof value === 'boolean';
  if (kind === 'element') return isValidElement(value);
  if (kind === 'node') return (typeof value === 'string' && value.trim() !== '') || isValidElement(value);
  return typeof value === 'function' || (isObject(value) && '$$typeof' in value);
}

function describeKind(kind: FieldKind): string {
  if (typeof kind !== 'string') return `one of ${kind.map((v) => `"${v}"`).join(', ')}`;
  if (kind === 'element') return 'a React element';
  if (kind === 'node') return 'text or a React element';
  return kind === 'boolean' ? 'true or false' : `a ${kind}`;
}

/** Ids and namespace names: lowercase kebab-case, as they end up in URLs, anchors and classes. */
const ID = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const ID_MAX = 32;
/** Extension page paths: static first segment, then static segments or `:params`. */
const ROUTE_PATH = /^\/[a-z0-9]+(?:-[a-z0-9]+)*(?:\/(?:[a-z0-9]+(?:-[a-z0-9]+)*|:[a-zA-Z][a-zA-Z0-9]*))*$/;

/**
 * The DOM id of a settings or configuration section, also its `#anchor`. Extension anchors carry an `x-<extension>-`
 * prefix, so they can never take an anchor core pages link to (`/settings#updates`).
 */
export function sectionAnchor(extension: ExtensionInfo, contributionId: string): string {
  return extension.core ? contributionId : `x-${extension.id}-${contributionId}`;
}

export function createRegistry(
  core: ExtensionDefinition,
  extensions: readonly ExtensionDefinition[],
): RegistryResult {
  const problems: string[] = [];
  const accepted: SlotEntry[] = [];
  const infos: ExtensionInfo[] = [];

  [core, ...extensions].forEach((raw, index) => {
    // One extension at a time, all or nothing: a manifest that throws while it is read (a getter, a proxy) is
    // reported and left out with everything it contributed, and the next one still loads.
    try {
      const own = readExtension(raw, index === 0, infos, accepted, problems);
      if (own) {
        infos.push(own.extension);
        accepted.push(...own.entries);
      }
    } catch (error) {
      problems.push(`[ext:${describeId(raw)}] could not be read: ${error instanceof Error ? error.message : String(error)}`);
    }
  });

  const entries = linkNavItems(accepted, problems);
  return { registry: freezeRegistry(infos, entries), problems };
}

/** The extension and its accepted entries, or null (with the problem noted) when the whole extension is rejected. */
function readExtension(
  raw: ExtensionDefinition,
  isCore: boolean,
  seen: readonly ExtensionInfo[],
  accepted: readonly SlotEntry[],
  problems: string[],
): { extension: ExtensionInfo; entries: SlotEntry[] } | null {
  // Shallow copies of the manifest and of each contribution, so each top-level field is read once: what is validated
  // is what the registry keeps. It freezes these copies, never the manifest's own objects.
  const definition = isObject(raw) ? { ...raw } : raw;
  const problem = extensionProblem(definition, isCore, seen);
  if (problem) {
    problems.push(`[ext:${describeId(definition)}] ${problem}`);
    return null;
  }
  const extension: ExtensionInfo = Object.freeze({ id: definition.id, title: definition.title, core: isCore });
  const entries: SlotEntry[] = [];
  const found: string[] = [];
  [...definition.contributions].forEach((item: unknown, position) => {
    if (!isObject(item)) {
      found.push(`[ext:${extension.id}] contribution ${position + 1} is not an object`);
      return;
    }
    const entry = makeEntry(extension, definition.requires, { ...item } as unknown as Contribution);
    const why = contributionProblem(entry, [...accepted, ...entries]) ?? freezeStorage(entry);
    if (why) found.push(`${tag(entry)}: ${why}`);
    else entries.push(entry);
  });
  problems.push(...found);
  return { extension, entries };
}

function describeId(definition: unknown): string {
  try {
    const id = (definition as Partial<ExtensionDefinition> | null)?.id;
    return typeof id === 'string' && id ? id : '?';
  } catch {
    return '?';
  }
}

function tag(entry: SlotEntry): string {
  return `[ext:${entry.extension.id}] ${String(entry.contribution.slot)} "${String(entry.contribution.id)}"`;
}

function idProblem(what: string, id: unknown): string | null {
  if (typeof id !== 'string' || !ID.test(id) || id.length > ID_MAX) {
    return `${what} "${String(id)}" must be lowercase kebab-case, at most ${ID_MAX} characters`;
  }
  return null;
}

function extensionProblem(definition: ExtensionDefinition, isCore: boolean, seen: readonly ExtensionInfo[]): string | null {
  if (!definition || typeof definition !== 'object' || !Array.isArray(definition.contributions)) {
    return 'is not an extension definition: export default defineExtension({ ... })';
  }
  const badId = idProblem('id', definition.id);
  if (badId) return badId;
  if (definition.id === CORE_ID && !isCore) return `the id "${CORE_ID}" is reserved for MDMesh`;
  if (seen.some((e) => e.id === definition.id)) return 'another extension already uses this id';
  if (definition.apiVersion !== EXTENSION_API_VERSION) {
    return `written for extension API ${String(definition.apiVersion)}, this console runs ${EXTENSION_API_VERSION}`;
  }
  if (typeof definition.title !== 'string' || !definition.title.trim()) return 'needs a title';
  const badRequires = requirementProblem(definition.requires);
  return badRequires ? `requires: ${badRequires}` : null;
}

function makeEntry(extension: ExtensionInfo, extensionRequires: Requirement | undefined, contribution: Contribution): SlotEntry {
  const requires = [extensionRequires, contribution.requires].filter((r): r is Requirement => !!r);
  return { key: `${extension.id}/${contribution.id}`, extension, contribution, requires };
}

function requirementProblem(requirement: Requirement | undefined): string | null {
  if (requirement === undefined) return null;
  // Destructuring null would throw, and a string or a list would gate nothing.
  if (!isObject(requirement) || Array.isArray(requirement)) return 'must be an object such as { permission: "settings" }';
  const { permission, serverPlugin, when } = requirement;
  const names = typeof permission === 'string' ? [permission] : permission;
  if (names !== undefined && (!Array.isArray(names) || !names.length || names.some((n) => typeof n !== 'string' || !n.trim()))) {
    return 'permission must be a permission name or a non-empty list of them';
  }
  if (serverPlugin !== undefined && (typeof serverPlugin !== 'string' || !serverPlugin.trim())) {
    return 'serverPlugin must be a plugin id';
  }
  if (when !== undefined && typeof when !== 'function') return 'when must be a function';
  return null;
}

/** Why `entry` cannot join the slot, given the entries accepted so far (core's come first). */
function contributionProblem(entry: SlotEntry, accepted: readonly SlotEntry[]): string | null {
  const c = entry.contribution;
  if (!Object.prototype.hasOwnProperty.call(REQUIRED_FIELDS, c.slot)) return 'unknown slot';
  const badId = idProblem('id', c.id);
  if (badId) return badId;
  const fields = c as unknown as Record<string, unknown>;
  const missing = Object.entries(REQUIRED_FIELDS[c.slot]).find(([field, kind]) => !hasKind(fields[field], kind));
  if (missing) return `needs ${missing[0]} (${describeKind(missing[1])})`;
  const wrong = Object.entries(OPTIONAL_FIELDS[c.slot] ?? {}).find(
    ([field, kind]) => fields[field] !== undefined && !hasKind(fields[field], kind),
  );
  if (wrong) return `${wrong[0]} must be ${describeKind(wrong[1])}`;
  if (c.slot === 'nav.item' && !/^\/[^?#]*$/.test(c.to)) return `to "${c.to}" must be a path such as "/network", without ? or #`;
  if ('order' in c && !Number.isFinite(c.order)) return 'order must be a finite number';
  if (c.slot !== 'route.page' && !('order' in c)) return 'needs an order';
  const badRequires = requirementProblem(c.requires);
  if (badRequires) return `requires: ${badRequires}`;

  const sameSlot = accepted.filter((e) => e.contribution.slot === c.slot);
  const twin = sameSlot.find((e) => e.contribution.id === c.id);
  if (twin) return `the id is already taken by ${twin.key}`;

  switch (c.slot) {
    case 'route.page':
      return routeProblem(entry as SlotEntry<ContributionOf<'route.page'>>, sameSlot as SlotEntry<ContributionOf<'route.page'>>[]);
    case 'settings.section':
      return anchorProblem(entry, sameSlot);
    case 'config.section':
      return anchorProblem(entry, sameSlot) ?? namespaceProblem(entry as SlotEntry<ContributionOf<'config.section'>>, sameSlot);
    default:
      return null;
  }
}

/** `/devices/:id` and `/devices/:number` are the same route. */
function routeShape(path: string): string {
  return path.toLowerCase().replace(/:[^/]+/g, ':');
}

function firstSegment(path: string): string {
  return `/${path.split('/')[1] ?? ''}`.toLowerCase();
}

function routeProblem(
  entry: SlotEntry<ContributionOf<'route.page'>>,
  routes: readonly SlotEntry<ContributionOf<'route.page'>>[],
): string | null {
  const { path } = entry.contribution;
  const clash = routes.find((r) => routeShape(r.contribution.path) === routeShape(path));
  if (clash) return `path "${path}" is already served by ${clash.key}`;
  if (entry.extension.core) return null;

  if (!ROUTE_PATH.test(path)) {
    return `path "${path}" must be lowercase kebab-case segments or :params, starting with a fixed segment`;
  }
  const edge = EDGE_PREFIXES.find((p) => path.startsWith(p));
  if (edge) return `path "${path}" starts with ${edge}, which the edge sends to another backend`;
  const segment = firstSegment(path);
  if (CONSOLE_SEGMENTS.includes(segment)) return `path "${path}" is under ${segment}, which the console reserves`;
  // A first segment belongs to one owner, so no extension can shadow another's `/reports/:id` with `/reports/new`.
  const owner = routes.find((r) => r.extension.id !== entry.extension.id && firstSegment(r.contribution.path) === segment);
  if (owner?.extension.core) return `path "${path}" is under the core page ${owner.contribution.path}`;
  if (owner) return `path "${path}" is under ${segment}, which the extension ${owner.extension.id} uses`;
  return null;
}

function anchorProblem(entry: SlotEntry, sections: readonly SlotEntry[]): string | null {
  const anchor = sectionAnchor(entry.extension, entry.contribution.id);
  if (entry.extension.core && anchor.startsWith('x-')) return 'core anchors may not start with "x-" (extensions own it)';
  const clash = sections.find((s) => sectionAnchor(s.extension, s.contribution.id) === anchor);
  return clash ? `anchor "#${anchor}" is already taken by ${clash.key}` : null;
}

/**
 * The `extensions` key a configuration section stores its value under, or null for a core section, which edits the
 * configuration's own fields (CORE_STORAGE).
 */
export function sectionNamespace(entry: SlotEntry<ContributionOf<'config.section'>>): string | null {
  const { storage } = entry.contribution;
  if (entry.extension.core && storage === CORE_STORAGE) return null;
  return storage?.namespace ?? entry.extension.id;
}

/**
 * An extension's namespaces are its id (the default) and `<id>.<name>` (`netaccess.vpn`), `<name>` lowercase
 * kebab-case. An id has no dot, so no extension can claim another's namespace, whichever extensions a build has.
 * Core's sections bind its own fields instead (CORE_STORAGE), so the rule is for extension storage only.
 */
function namespaceProblem(entry: SlotEntry<ContributionOf<'config.section'>>, sections: readonly SlotEntry[]): string | null {
  const { storage } = entry.contribution;
  if (!isObject(storage) || !('defaults' in storage)) return 'needs storage: { defaults }';
  const namespace = sectionNamespace(entry);
  if (namespace === null) return null;
  // What `extensions` holds per namespace, and what the server accepts.
  if (!isObject(storage.defaults) || Array.isArray(storage.defaults)) return 'storage.defaults must be a JSON object';
  const own = entry.extension.id;
  const name = typeof namespace === 'string' && namespace.startsWith(`${own}.`) ? namespace.slice(own.length + 1) : null;
  if (namespace !== own && (name === null || idProblem('name', name))) {
    return `storage namespace "${String(namespace)}" must be "${own}" or "${own}.<name>", <name> lowercase kebab-case`;
  }
  const clash = (sections as SlotEntry<ContributionOf<'config.section'>>[]).find((s) => sectionNamespace(s) === namespace);
  return clash ? `storage namespace "${namespace}" is already used by ${clash.key}` : null;
}

/**
 * An extension section's `storage`, replaced by a frozen copy whose defaults are a deep-frozen JSON copy: the editor
 * hands the defaults to every configuration that has no stored value, so a body that changed them in place would
 * carry its edit into the next configuration. Null when that worked, else the problem (defaults that aren't JSON).
 */
function freezeStorage(entry: SlotEntry): string | null {
  if (entry.contribution.slot !== 'config.section') return null;
  const section = entry.contribution as ContributionOf<'config.section'>;
  if (sectionNamespace(entry as SlotEntry<ContributionOf<'config.section'>>) === null) return null;
  let defaults: unknown;
  try {
    defaults = frozenJsonCopy(section.storage.defaults);
  } catch {
    return 'storage.defaults must be a JSON object';
  }
  section.storage = Object.freeze({ ...section.storage, defaults });
  return null;
}

/**
 * A nav item must lead to a page, and shows only when that page would: it takes on the page's requirements, so
 * core and extensions state a page's permission once, on the route.
 */
function linkNavItems(entries: readonly SlotEntry[], problems: string[]): SlotEntry[] {
  const routes = entries.filter((e): e is SlotEntry<ContributionOf<'route.page'>> => e.contribution.slot === 'route.page');
  const linked: SlotEntry[] = [];
  for (const entry of entries) {
    if (entry.contribution.slot !== 'nav.item') {
      linked.push(entry);
      continue;
    }
    const to = entry.contribution.to;
    // The page react-router would render for `to` (best-ranked, not first declared).
    const matches = matchRoutes(routes.map((r) => ({ id: r.key, path: r.contribution.path })), to) ?? [];
    const page = routes.find((r) => r.key === matches[matches.length - 1]?.route.id);
    if (!page) {
      problems.push(`${tag(entry)}: no route.page serves "${to}"`);
      continue;
    }
    linked.push({ ...entry, requires: [...entry.requires, ...page.requires] });
  }
  return linked;
}

function order(entry: SlotEntry): number {
  return 'order' in entry.contribution ? entry.contribution.order : 0;
}

/**
 * Display order: `order`, then core before extensions (an extension at `ORDER.navItem.devices` comes after Devices),
 * then the key, so equal orders still sort the same on every build.
 */
function compareEntries(a: SlotEntry, b: SlotEntry): number {
  return (
    order(a) - order(b) ||
    Number(b.extension.core) - Number(a.extension.core) ||
    (a.key < b.key ? -1 : a.key > b.key ? 1 : 0)
  );
}

function freezeRegistry(extensions: readonly ExtensionInfo[], entries: readonly SlotEntry[]): Registry {
  const bySlot = new Map<SlotId, readonly SlotEntry[]>();
  for (const slot of Object.keys(REQUIRED_FIELDS) as SlotId[]) {
    const ofSlot = entries
      .filter((e) => e.contribution.slot === slot)
      .sort(compareEntries)
      .map((e) => Object.freeze({ ...e, contribution: Object.freeze(e.contribution), requires: Object.freeze(e.requires) }));
    bySlot.set(slot, Object.freeze(ofSlot));
  }
  const usesServerPlugins = entries.some((e) => e.requires.some((r) => r.serverPlugin !== undefined));
  return Object.freeze({
    extensions: Object.freeze([...extensions]),
    entries: <S extends SlotId>(slot: S) => (bySlot.get(slot) ?? []) as readonly SlotEntry<ContributionOf<S>>[],
    usesServerPlugins,
  });
}
