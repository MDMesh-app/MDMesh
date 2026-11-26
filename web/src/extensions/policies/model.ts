// Pure rules of the Policies extension: no React, no network, so they are unit-tested on their own (model.test.ts).
//
// A policy is a MDMesh configuration. What the configuration editor already has (device restrictions, location,
// connectivity) is edited through the configuration's own columns, so devices get it through config.apply as before.
// What is new lives in the configuration's `extensions.policies` namespace (PolicyData): its host phone and what to do
// with apps outside its allowed list. Device groups and brands live in the Policies server plugin (DeviceGroup).
//
// The allowed apps themselves are NOT stored here: they are the configuration's own apps marked Install and shown in
// kiosk, which is exactly the list the server sends phones as the kiosk allowlist. So what the console shows and what
// the phones enforce can't disagree.

/** The configuration-extensions namespace this extension owns (its id). */
export const NAMESPACE = 'policies';

/** What a configuration's `extensions.policies` holds. */
export interface PolicyData {
  v: 1;
  /** The host phone (device number) whose app scan the allowed apps are picked from. */
  host?: string;
  /**
   * The host's apps when someone last reviewed them (Mark reviewed on Allowed apps). A host app outside this list and
   * not allowed is new on the host: something to look at. Missing until the first review.
   */
  hostSeen?: string[];
  apps: AppControl;
}

/**
 * What happens to an app found on a phone that is outside the policy's allowed list (an "extra" app). It can't be
 * opened either way while kiosk is on; this decides whether it is also listed or removed.
 * - off: extra apps are not checked.
 * - report: they are listed on Status; nothing else happens.
 * - remove: they are listed with a Remove button (one click per app, or all of a phone's).
 * - auto: the console removes them as soon as it sees them, while the Policies page is open.
 */
export type AppMode = 'off' | 'report' | 'remove' | 'auto';

export interface AppRef {
  pkg: string;
  label?: string;
}

/** An extra app one phone may keep. */
export interface AppException {
  pkg: string;
  /** The device's number (its id in the console). */
  device: string;
  note?: string;
}

export interface AppControl {
  mode: AppMode;
  /** Send the phone an alert when one of its apps is removed. */
  notify: boolean;
  exceptions: AppException[];
}

export const EMPTY_POLICY: PolicyData = Object.freeze({
  v: 1,
  apps: { mode: 'report', notify: true, exceptions: [] },
}) as PolicyData;

/** The most host apps a review remembers (the namespace has a size limit; a phone has far fewer icon apps). */
const MAX_HOST_SEEN = 150;

/** The server's limit for one extension's data in a configuration (ConfigurationExtensions.NAMESPACE_MAX_BYTES). */
export const POLICY_MAX_BYTES = 16 * 1024;

/** The size of a policy's data as the server measures it: its JSON in UTF-8 bytes. */
export function policyBytes(data: PolicyData): number {
  return new TextEncoder().encode(JSON.stringify(data)).length;
}

const PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const MODES: readonly AppMode[] = ['off', 'report', 'remove', 'auto'];

/** Whether `pkg` is a well-formed Android package name. */
export const isPackageName = (pkg: string): boolean => PKG.test(pkg.trim());

function str(v: unknown, max = 200): string | undefined {
  return typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : undefined;
}

/**
 * The stored namespace as PolicyData. Defensive: the data may have been written by another client, by hand, or by an
 * older version of this extension, so anything malformed is dropped rather than trusted.
 */
export function parsePolicy(raw: unknown): PolicyData {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return clonePolicy(EMPTY_POLICY);
  const o = raw as Record<string, unknown>;
  const a = o.apps && typeof o.apps === 'object' ? (o.apps as Record<string, unknown>) : {};
  const mode = MODES.includes(a.mode as AppMode) ? (a.mode as AppMode) : 'report';
  const exceptions: AppException[] = [];
  if (Array.isArray(a.exceptions)) {
    for (const e of a.exceptions) {
      if (!e || typeof e !== 'object') continue;
      const r = e as Record<string, unknown>;
      const pkg = str(r.pkg, 255);
      const device = str(r.device, 100);
      if (!pkg || !isPackageName(pkg) || !device) continue;
      if (exceptions.some((x) => x.pkg === pkg && x.device === device)) continue;
      const note = str(r.note, 120);
      exceptions.push(note ? { pkg, device, note } : { pkg, device });
    }
  }
  const host = str(o.host, 100);
  const apps: AppControl = { mode, notify: a.notify !== false, exceptions };
  const out: PolicyData = host ? { v: 1, host, apps } : { v: 1, apps };
  if (Array.isArray(o.hostSeen)) {
    const seen = [...new Set(o.hostSeen.filter((x): x is string => typeof x === 'string' && isPackageName(x)).map((x) => x.trim()))];
    out.hostSeen = seen.slice(0, MAX_HOST_SEEN).sort();
  }
  return out;
}

export function clonePolicy(p: PolicyData): PolicyData {
  return JSON.parse(JSON.stringify(p)) as PolicyData;
}

/** Whether two policies say the same thing (compared as JSON of the parsed form). */
export function samePolicy(a: PolicyData, b: PolicyData): boolean {
  return JSON.stringify(parsePolicy(a)) === JSON.stringify(parsePolicy(b));
}

// ---------------------------------------------------------------------------------------------------------------
// Brands

export const UNKNOWN_BRAND = 'unknown';

/** How the console names a brand key. Unlisted brands are capitalised. */
const BRAND_NAMES: Record<string, string> = {
  oppo: 'Oppo', realme: 'Realme', oneplus: 'OnePlus', vivo: 'Vivo', iqoo: 'iQOO', samsung: 'Samsung',
  xiaomi: 'Xiaomi', redmi: 'Redmi', poco: 'POCO', motorola: 'Motorola', google: 'Google', nokia: 'Nokia',
  huawei: 'Huawei', honor: 'Honor', lenovo: 'Lenovo', infinix: 'Infinix', tecno: 'Tecno', itel: 'itel',
  lava: 'Lava', nothing: 'Nothing', sony: 'Sony', lg: 'LG', asus: 'ASUS', zebra: 'Zebra',
  [UNKNOWN_BRAND]: 'Unknown brand',
};

/** Other spellings phones report (Build.BRAND / MANUFACTURER), to one key. */
const BRAND_ALIASES: Record<string, string> = {
  moto: 'motorola', 'motorola mobility': 'motorola', 'hmd global': 'nokia', hmd: 'nokia', 'one plus': 'oneplus',
  mi: 'xiaomi', 'samsung electronics': 'samsung',
};

/** A brand as one lowercase key (`OPPO` → `oppo`, `moto` → `motorola`). */
export function normalizeBrand(raw: string | null | undefined): string {
  const s = (raw ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
  if (!s) return UNKNOWN_BRAND;
  return BRAND_ALIASES[s] ?? s;
}

/** Names of brands made by hand (Groups → New brand), as their maker wrote them. */
const CUSTOM_NAMES = new Map<string, string>();

/** Use these names for brands made by hand (the plugin's list), in place of the ones before. */
export function setCustomBrandNames(list: readonly { brand: string; label: string }[]): void {
  CUSTOM_NAMES.clear();
  for (const b of list) CUSTOM_NAMES.set(b.brand, b.label);
}

export function brandLabel(key: string): string {
  return CUSTOM_NAMES.get(key) ?? BRAND_NAMES[key] ?? key.replace(/(^|[\s-])([a-z])/g, (_m, p: string, c: string) => p + c.toUpperCase());
}

/** Brands a description may start with ("oppo A5x 5g Sunny"), for devices that never sent telemetry. */
const KNOWN_BRANDS = Object.keys(BRAND_NAMES).filter((b) => b !== UNKNOWN_BRAND);

/**
 * A device's brand: the agent's telemetry first (Build.BRAND, then MANUFACTURER), else the first word of its
 * description when that is a known brand (guessed: true), else unknown.
 */
export function deviceBrand(
  hardware: Record<string, unknown> | null | undefined,
  description: string | null | undefined,
): { brand: string; guessed: boolean } {
  const fromTelemetry = [hardware?.brand, hardware?.manufacturer].find((v): v is string => typeof v === 'string' && !!v.trim());
  if (fromTelemetry) return { brand: normalizeBrand(fromTelemetry), guessed: false };
  const first = normalizeBrand((description ?? '').split(/[\s\-_/(]+/)[0]);
  if (KNOWN_BRANDS.includes(first)) return { brand: first, guessed: true };
  return { brand: UNKNOWN_BRAND, guessed: false };
}

/** Groups to start from, by device family. Only those with a brand present are offered. */
export const SUGGESTED_GROUPS: readonly { name: string; brands: string[] }[] = [
  { name: 'Oppo family', brands: ['oppo', 'realme', 'oneplus'] },
  { name: 'Vivo family', brands: ['vivo', 'iqoo'] },
  { name: 'Xiaomi family', brands: ['xiaomi', 'redmi', 'poco'] },
  { name: 'Samsung', brands: ['samsung'] },
  { name: 'Motorola', brands: ['motorola'] },
];

/**
 * A device group (stored by the Policies server plugin). It covers whole brands, so every phone of them joins it, new
 * ones too, and phones picked by hand, whatever their brand. It points at one policy, or none yet: groups come first,
 * policies are given to them. A brand and a phone are in one group at most.
 */
export interface DeviceGroup {
  id: number;
  name: string;
  /** The policy its phones should be on; null until one is given. */
  policyId: number | null;
  /** Brand keys as normalizeBrand returns them (`oppo`, `realme`). */
  brands: string[];
  /** Phones picked into it by hand, by device id. */
  devices: number[];
}

/** Older name, kept for the files that say it. */
export type PlacedGroup = DeviceGroup;

/** The group a brand belongs to; the first one wins if data were ever stored twice. */
export function groupForBrand(groups: readonly DeviceGroup[], brand: string): DeviceGroup | undefined {
  return groups.find((g) => g.brands.includes(brand));
}

/** A phone's group: the one it was picked into, else its brand's. `manual` says which. */
export function groupForDevice(
  groups: readonly DeviceGroup[],
  deviceId: number,
  brand: string,
): { group?: DeviceGroup; manual: boolean } {
  const byHand = groups.find((g) => g.devices.includes(deviceId));
  if (byHand) return { group: byHand, manual: true };
  return { group: groupForBrand(groups, brand), manual: false };
}

// ---------------------------------------------------------------------------------------------------------------
// Device control (configuration columns)

/** How a configuration column maps to the policy screen. */
export type ControlKind =
  /** Boolean? where true = allowed (bluetooth, camera, usbStorage, wifi). */
  | 'allow'
  /** Boolean? stored as "disableX": true = blocked. */
  | 'disable'
  /** Boolean? where true = on (wifiRadio, locationServices). */
  | 'onoff';

export interface ControlDef {
  key: string;
  label: string;
  kind: ControlKind;
  section: 'device' | 'network' | 'system';
  help: string;
}

export const CONTROLS: readonly ControlDef[] = [
  { key: 'camera', label: 'Camera', kind: 'allow', section: 'device', help: 'Block stops every app from using the camera. Photos already taken stay.' },
  { key: 'disableScreenshots', label: 'Screenshots and screen recording', kind: 'disable', section: 'device', help: 'Block stops screenshots and screen recording, so company data can’t be captured.' },
  { key: 'usbStorage', label: 'USB file transfer', kind: 'allow', section: 'device', help: 'Block stops copying files over USB and mounting external storage.' },
  { key: 'bluetooth', label: 'Bluetooth', kind: 'allow', section: 'device', help: 'Block turns Bluetooth off and stops the user turning it on.' },
  { key: 'wifi', label: 'Wi-Fi settings', kind: 'allow', section: 'network', help: 'Block locks the Wi-Fi settings, so the user can’t change networks. Wi-Fi stays on.' },
  { key: 'wifiRadio', label: 'Wi-Fi radio', kind: 'onoff', section: 'network', help: 'On turns Wi-Fi on. Off turns it off (skipped while Wi-Fi is the only network, so the phone stays reachable).' },
  { key: 'locationServices', label: 'Location services', kind: 'onoff', section: 'network', help: 'Android 11 and later. On or Off, locked so the user can’t change it.' },
  { key: 'disableFactoryReset', label: 'Factory reset', kind: 'disable', section: 'system', help: 'Block stops a factory reset from Settings, which would remove the phone from management.' },
  { key: 'disableSafeBoot', label: 'Safe Mode', kind: 'disable', section: 'system', help: 'Block stops rebooting into Safe Mode, which would bypass these rules.' },
];

/** The three states a control shows: not managed, allowed (or on), blocked (or off). */
export type ControlState = 'unmanaged' | 'allow' | 'block';

export function controlState(def: ControlDef, stored: unknown): ControlState {
  if (stored !== true && stored !== false) return 'unmanaged';
  const allowed = def.kind === 'disable' ? !stored : stored;
  return allowed ? 'allow' : 'block';
}

/** The column value for a state. */
export function controlValue(def: ControlDef, state: ControlState): boolean | null {
  if (state === 'unmanaged') return null;
  const allowed = state === 'allow';
  return def.kind === 'disable' ? !allowed : allowed;
}

export const LOCATION_MODES = [
  { value: 'DONOTTRACK', label: 'Off' },
  { value: 'WIFI', label: 'Last known' },
  { value: 'GPS', label: 'Fresh GPS fix' },
] as const;

export const CONNECTIVITY_MODES = [
  { value: '', label: 'Each phone decides' },
  { value: 'adaptive', label: 'Battery-saver' },
  { value: 'alwaysOn', label: 'Always-on' },
] as const;

/** One-click security levels: the device-control state each one sets. */
export type Preset = 'strict' | 'balanced' | 'open';

export const PRESETS: Record<Preset, { label: string; help: string; states: Record<string, ControlState> }> = {
  strict: {
    label: 'Strict',
    help: 'Blocks everything that can leak data or remove the phone from management. Wi-Fi radio and location stay as they are.',
    states: {
      camera: 'block', disableScreenshots: 'block', usbStorage: 'block', bluetooth: 'block', wifi: 'block',
      wifiRadio: 'unmanaged', locationServices: 'allow', disableFactoryReset: 'block', disableSafeBoot: 'block',
    },
  },
  balanced: {
    label: 'Balanced',
    help: 'Keeps the phone useful (camera, Bluetooth, Wi-Fi) and blocks what removes it from management or copies data out.',
    states: {
      camera: 'allow', disableScreenshots: 'allow', usbStorage: 'block', bluetooth: 'allow', wifi: 'allow',
      wifiRadio: 'unmanaged', locationServices: 'allow', disableFactoryReset: 'block', disableSafeBoot: 'block',
    },
  },
  open: {
    label: 'Open',
    help: 'Allows everything. Use it for test phones.',
    states: {
      camera: 'allow', disableScreenshots: 'allow', usbStorage: 'allow', bluetooth: 'allow', wifi: 'allow',
      wifiRadio: 'unmanaged', locationServices: 'unmanaged', disableFactoryReset: 'allow', disableSafeBoot: 'allow',
    },
  },
};

/** Which preset the configuration matches exactly, or null (custom). */
export function matchingPreset(config: Readonly<Record<string, unknown>>): Preset | null {
  for (const key of Object.keys(PRESETS) as Preset[]) {
    const states = PRESETS[key].states;
    if (CONTROLS.every((d) => controlState(d, config[d.key]) === states[d.key])) return key;
  }
  return null;
}

/** How many of the controls block something: the "protection" line on a policy card. */
export function protectionSummary(config: Readonly<Record<string, unknown>>): { blocked: number; managed: number; total: number } {
  let blocked = 0;
  let managed = 0;
  for (const d of CONTROLS) {
    const s = controlState(d, config[d.key]);
    if (s !== 'unmanaged') managed++;
    if (s === 'block' && d.kind !== 'onoff') blocked++;
  }
  return { blocked, managed, total: CONTROLS.length };
}

// ---------------------------------------------------------------------------------------------------------------
// Application control

export interface ScannedApp {
  pkg: string;
  label?: string;
  system?: boolean;
}

/** The agent itself: never an extra app, whatever the list says. */
export const PROTECTED_PKGS: ReadonlySet<string> = new Set(['com.mdmesh.agent', 'com.hmdm.launcher']);


/** An app installed on a phone that is outside its policy's allowed list. */
export interface Violation {
  pkg: string;
  label: string;
  system: boolean;
  /** An uninstall for it is already queued or on its way. */
  removing: boolean;
}

/**
 * The extra apps of a phone: the apps of its latest scan that are not allowed, minus the phone's exceptions. Apps the
 * phone marks as system are left out: kiosk already stops them opening, and Android doesn't let an MDM remove them.
 * Only the phone's own flag counts, so a maker's preloaded app the phone reports as a normal app (a Redmi's Clock or
 * Notes) is an extra app like any other.
 */
export function findExtras(
  apps: readonly ScannedApp[],
  allowed: ReadonlySet<string>,
  control: AppControl,
  deviceNumber: string,
  removingPkgs: ReadonlySet<string> = new Set(),
): Violation[] {
  if (control.mode === 'off') return [];
  const excepted = new Set(control.exceptions.filter((e) => e.device === deviceNumber).map((e) => e.pkg));
  const out: Violation[] = [];
  for (const a of apps) {
    if (a.system === true || allowed.has(a.pkg) || excepted.has(a.pkg) || PROTECTED_PKGS.has(a.pkg)) continue;
    out.push({ pkg: a.pkg, label: a.label || a.pkg, system: false, removing: removingPkgs.has(a.pkg) });
  }
  return out.sort((x, y) => x.label.localeCompare(y.label));
}

/** The extra apps the console may remove now: not a system app and not already being removed. */
export const removable = (v: readonly Violation[]): Violation[] => v.filter((x) => !x.system && !x.removing);

// ---- the allowed list (the configuration's apps) ----

/** A configuration's app row (ConfigApp), as far as the allowed list needs it. Other fields go back untouched. */
export interface ConfigAppRow {
  /** The Library application id. */
  id: number;
  name?: string;
  pkg?: string;
  version?: string;
  /** 0 = hide, 1 = install, 2 = remove. */
  action?: number;
  showIcon?: boolean;
  remove?: boolean;
  usedVersionId?: number;
  [key: string]: unknown;
}

/** A Library application, as far as the allowed list needs it. */
export interface LibraryApp {
  id: number;
  name: string;
  pkg: string;
  version?: string;
  latestVersion?: number;
  url?: string;
  type?: string;
  system?: boolean;
}

/** Whether a configuration row puts its app on the kiosk screen (the server's kiosk rule). */
export const isAllowedRow = (a: ConfigAppRow): boolean => (a.action ?? 1) === 1 && a.showIcon !== false && !!a.pkg?.trim();

/** The packages a configuration allows on the kiosk screen. */
export function allowedPackages(rows: readonly ConfigAppRow[]): Set<string> {
  return new Set(rows.filter(isAllowedRow).map((a) => a.pkg!.trim()));
}

export interface AllowPlan {
  /** The configuration's new app rows. */
  rows: ConfigAppRow[];
  /** The new main app (cleared when the main app is no longer allowed). */
  mainAppId: number | null;
  /** Packages with no Library entry yet: they get a package-only one, then a row. */
  missing: string[];
  /** Library apps with a download link newly set to Install: the server installs them on phones that lack them. */
  installs: string[];
}

/**
 * The configuration rows that allow exactly `want` on the kiosk screen, changing as little as possible:
 * - a wanted app already in the configuration is set to Install and shown in kiosk;
 * - an allowed app no longer wanted is only hidden from kiosk (its Install stays, so nothing is uninstalled);
 * - a wanted app not in the configuration is added from the Library (a non-web entry of its package), or listed in
 *   `missing` when the Library has none.
 * Rows without a package, and rows of apps that are neither wanted nor allowed, are left exactly as they are.
 */
export function planAllowList(
  rows: readonly ConfigAppRow[],
  want: ReadonlySet<string>,
  library: readonly LibraryApp[],
  mainAppId: number | null | undefined,
): AllowPlan {
  let main = mainAppId ?? null;
  const have = new Set<string>();
  const installs: string[] = [];
  const next = rows.map((r) => {
    const pkg = r.pkg?.trim();
    if (!pkg) return r;
    if (want.has(pkg)) {
      have.add(pkg);
      if (isAllowedRow(r)) return r;
      return { ...r, action: 1, showIcon: true, remove: false };
    }
    if (!isAllowedRow(r)) return r;
    if (main != null && r.usedVersionId === main) main = null;
    return { ...r, showIcon: false };
  });
  const missing: string[] = [];
  for (const pkg of [...want].sort()) {
    if (have.has(pkg)) continue;
    const lib = library.find((l) => l.pkg === pkg && (l.type ?? 'app') !== 'web');
    if (!lib) {
      missing.push(pkg);
      continue;
    }
    if (lib.url && !lib.system) installs.push(lib.name || pkg);
    next.push(libraryRow(lib));
  }
  return { rows: next, mainAppId: main, missing, installs };
}

/** A configuration row that installs a Library app and shows it in kiosk (as the configuration editor adds one). */
export function libraryRow(lib: LibraryApp): ConfigAppRow {
  return {
    id: lib.id,
    name: lib.name,
    pkg: lib.pkg,
    version: lib.version,
    usedVersionId: lib.latestVersion,
    action: 1,
    showIcon: true,
    remove: false,
  };
}

/**
 * The host's apps that are new since the last review: on the host, not allowed, not seen at the last review. None
 * before the first review (there is nothing to compare with yet).
 */
export function newOnHost(hostApps: readonly ScannedApp[], allowed: ReadonlySet<string>, seen: readonly string[] | undefined): ScannedApp[] {
  if (!seen) return [];
  const known = new Set(seen);
  return hostApps.filter((a) => !allowed.has(a.pkg) && !known.has(a.pkg));
}

/** Packages that look like a phone (dialer) app: a kiosk without one can't make calls. */
export const looksLikeDialer = (pkg: string): boolean => /dialer|\.phone$|incallui|telecom/i.test(pkg);

// ---------------------------------------------------------------------------------------------------------------
// Compliance

export type SyncState = 'inSync' | 'applying' | 'failed' | 'agentTooOld' | 'notReported' | 'outOfSync' | 'unknown';

export interface StatusInput {
  configurationId?: number | null;
  inSync?: boolean;
  supported?: boolean;
  appliedRevision?: string | null;
  lastCommand?: { status?: string; detail?: string | null } | null;
}

/** The device's configuration sync, as one state. */
export function syncState(s: StatusInput | null | undefined): SyncState {
  if (!s || s.configurationId == null) return 'unknown';
  if (s.supported === false) return 'agentTooOld';
  if (s.inSync) return 'inSync';
  const st = s.lastCommand?.status;
  if (st === 'pending' || st === 'delivered' || st === 'accepted') return 'applying';
  if (st === 'failed') return 'failed';
  if (!s.appliedRevision) return 'notReported';
  return 'outOfSync';
}

/** The settings a device reported it can't apply (outcome `unsupported`), from config.apply's result. */
export function unsupportedSettings(detail: string | null | undefined): string[] {
  if (!detail) return [];
  try {
    const parsed = JSON.parse(detail) as { outcomes?: Record<string, unknown> };
    const outcomes = parsed?.outcomes && typeof parsed.outcomes === 'object' ? parsed.outcomes : {};
    return Object.entries(outcomes)
      .filter(([, o]) => o === 'unsupported')
      .map(([k]) => k.replace(/^policies\./, ''))
      .sort();
  } catch {
    return [];
  }
}

export type Verdict = 'compliant' | 'attention' | 'unknown';

export interface ComplianceInput {
  /** The policy its group asks for: undefined when it is in no group, null when its group has no policy yet. */
  expectedPolicyId?: number | null;
  configurationId?: number;
  sync: SyncState;
  unsupported: number;
  /** null while its apps are not known (never scanned, or not loaded yet). */
  violations: number | null;
  appMode: AppMode;
}

export interface Compliance {
  verdict: Verdict;
  reasons: string[];
}

/** One device's verdict and why, in the order someone would fix them. */
export function compliance(c: ComplianceInput): Compliance {
  const reasons: string[] = [];
  if (c.expectedPolicyId === undefined) reasons.push('In no group');
  else if (c.expectedPolicyId === null) reasons.push('Its group has no policy yet');
  else if (c.configurationId !== c.expectedPolicyId) reasons.push('On another policy than its group');
  if (c.sync === 'failed') reasons.push('Policy failed to apply');
  else if (c.sync === 'agentTooOld') reasons.push('Agent too old for policies');
  else if (c.sync === 'outOfSync') reasons.push('Policy not applied yet');
  if (c.unsupported > 0) reasons.push(c.unsupported === 1 ? '1 setting not supported' : `${c.unsupported} settings not supported`);
  if (c.violations) reasons.push(c.violations === 1 ? '1 app outside the allowed list' : `${c.violations} apps outside the allowed list`);
  if (reasons.length) return { verdict: 'attention', reasons };
  if (c.sync === 'unknown' || c.sync === 'notReported' || c.sync === 'applying') {
    return { verdict: 'unknown', reasons: [c.sync === 'applying' ? 'Applying the policy' : 'Not reported yet'] };
  }
  if (c.appMode !== 'off' && c.violations === null) return { verdict: 'unknown', reasons: ['Apps not scanned yet'] };
  return { verdict: 'compliant', reasons: [] };
}

/** Run `fn` over `items`, at most `limit` at a time, in order of start. */
export async function eachLimited<T>(items: readonly T[], limit: number, fn: (item: T) => Promise<void>, stop?: () => boolean): Promise<void> {
  let next = 0;
  const worker = async () => {
    while (next < items.length && !stop?.()) {
      const item = items[next++];
      await fn(item).catch(() => undefined);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
}
