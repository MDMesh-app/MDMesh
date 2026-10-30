// Kiosk entry shared by the device page and the bulk dialogs: the `kiosk.enter` payloads (a custom kiosk picked in the
// console, and the kiosk a configuration defines), the configuration's launcher theme, and the app-icon cache.
import type { CommandHistoryItem } from '../api/commands';
import type { ConfigApp, Configuration } from '../api/configurations';
import { parseOutcomes } from '../api/configSync';
import { CONFIG_FIELDS } from './configFields';

/**
 * The configuration's own help for the exit PIN, so every kiosk dialog says the same: with agent 0.3.6+, an empty PIN
 * means no exit on the device.
 */
export const EXIT_PIN_HELP = CONFIG_FIELDS.find((f) => f.key === 'password')?.help;
/** Placeholder of an exit-PIN input that may stay empty. */
export const EXIT_PIN_PLACEHOLDER = 'Empty: no exit on the device (agent 0.3.6+)';
/** What a kiosk without an exit PIN means on the device, by agent version. */
export const NO_PIN_MEANS = 'agents before 0.3.6 let anyone leave the kiosk; 0.3.6 and later have no exit on the device';

export type KioskChoice = {
  mode: 'launcher' | 'single';
  /** Pinned / allowed package names. */
  packages: string[];
  exitMode: 'gesture' | 'visible' | 'remote';
  password?: string;
};

export type KioskTheme = {
  backgroundColor?: string;
  textColor?: string;
  iconSize?: string;
};

/** The `kiosk.enter` payload (KioskApplyPayload): also the `kiosk` block of a `config.apply` document. */
export interface KioskPayload {
  mode: 'launcher' | 'single';
  allowedPackages: string[];
  pinPackage?: string;
  features: {
    home?: boolean;
    recents?: boolean;
    notifications?: boolean;
    systemInfo?: boolean;
    keyguard?: boolean;
    lockButtons?: boolean;
    lockVolume?: boolean;
  };
  exitMode: KioskChoice['exitMode'];
  password?: string;
  theme?: KioskTheme;
}

/** Build the custom (one-time) `kiosk.enter` payload (device-independent). Mirrors the single-device apply() exactly. */
export function buildKioskPayload(c: KioskChoice, theme?: KioskTheme): KioskPayload {
  const common = {
    exitMode: c.exitMode,
    password: c.password || undefined,
    features: { home: true, notifications: true },
    // A direct kiosk action changes the allowed apps, not the configuration's appearance.
    // Include the assigned configuration theme so it does not revert to launcher defaults.
    ...(theme ? { theme } : {}),
  };
  return c.mode === 'single'
    ? { mode: 'single', pinPackage: c.packages[0], allowedPackages: c.packages, ...common }
    : { mode: 'launcher', allowedPackages: c.packages, ...common };
}

/* ---------------------------------------------------------------------------------------------------------------
   The configured kiosk: the kiosk a configuration defines, as `kiosk.enter`
   --------------------------------------------------------------------------------------------------------------- */

const ACTION_INSTALL = 1;

/** What a configuration's kiosk is, for the "Return to configured kiosk" actions. */
export interface ConfiguredKiosk {
  /** The `kiosk.enter` payload: the same document the configuration's `config.apply` carries as `kiosk`. */
  payload: KioskPayload;
  /** The kiosk's apps, main app first, with the names the configuration shows. */
  apps: { pkg: string; name: string }[];
  /** The configuration sets a main app, but it isn't an app set to Install and shown in kiosk, so the kiosk leaves it out. */
  mainAppLeftOut: boolean;
}

/** The configuration's apps that its kiosk shows: set to Install, shown in kiosk, with a package name. */
const kioskApp = (a: ConfigApp) => typeof a.pkg === 'string' && a.pkg.trim() !== '' && a.action === ACTION_INSTALL && a.showIcon !== false;

const bool = (v: unknown) => (typeof v === 'boolean' ? v : undefined);

/**
 * The kiosk a configuration defines, or null when its kiosk mode is off. A port of the server's
 * DesiredConfigBuilder.kiosk (common/src/main/java/com/hmdm/util/DesiredConfigBuilder.java), so the payload equals the
 * `kiosk` block of the configuration's `config.apply` field for field (the agent then treats both as the same kiosk):
 * - the main app is the app whose usedVersionId equals mainAppId (an applicationVersions.id, not an applications.id);
 * - allowed packages: the main app first, then every other kiosk app by package name, de-duplicated, in Java's
 *   TreeSet order (UTF-16 code units, which is also what Array.prototype.sort does);
 * - single (pinned) when the main app is the only app, otherwise the launcher grid (pinPackage still names the main app);
 * - features from the kiosk* columns (unset ones left out), exit by visible button when kioskExit is on, the exit PIN
 *   as stored, and the launcher theme.
 * `apps` must be the configuration's assigned apps (getConfigurationApps), not the library.
 */
export function configuredKiosk(cfg: Configuration, apps: ConfigApp[]): ConfiguredKiosk | null {
  if (cfg.kioskMode !== true) return null;
  const eligible = apps.filter(kioskApp);
  const mainAppId = typeof cfg.mainAppId === 'number' ? cfg.mainAppId : undefined;
  let main: ConfigApp | undefined;
  // The server keeps the LAST matching row; the package is what matters (rows of one package dedupe below).
  for (const a of eligible) if (mainAppId != null && a.usedVersionId === mainAppId) main = a;
  const mainPkg = main?.pkg?.trim();
  const names = new Map<string, string>();
  for (const a of eligible) {
    const pkg = a.pkg!.trim();
    if (!names.has(pkg)) names.set(pkg, a.name || pkg);
  }
  const others = [...new Set(eligible.map((a) => a.pkg!.trim()))].filter((p) => p !== mainPkg).sort();
  const allowed = mainPkg ? [mainPkg, ...others] : others;
  const features: KioskPayload['features'] = {};
  const feature = (key: keyof KioskPayload['features'], column: string) => {
    const v = bool(cfg[column]);
    if (v !== undefined) features[key] = v;
  };
  feature('home', 'kioskHome');
  feature('recents', 'kioskRecents');
  feature('notifications', 'kioskNotifications');
  feature('systemInfo', 'kioskSystemInfo');
  feature('keyguard', 'kioskKeyguard');
  feature('lockButtons', 'kioskLockButtons');
  feature('lockVolume', 'kioskLockVolume');
  const payload: KioskPayload = {
    mode: mainPkg && allowed.length === 1 ? 'single' : 'launcher',
    allowedPackages: allowed,
    ...(mainPkg ? { pinPackage: mainPkg } : {}),
    features,
    exitMode: cfg.kioskExit === true ? 'visible' : 'gesture',
    ...(typeof cfg.password === 'string' ? { password: cfg.password } : {}),
    theme: kioskTheme(cfg) ?? {},
  };
  return {
    payload,
    apps: allowed.map((pkg) => ({ pkg, name: names.get(pkg) ?? pkg })),
    mainAppLeftOut: mainAppId != null && !mainPkg,
  };
}

/** "Pinned to Signage Player" / "Launcher grid with 3 apps": what the configured kiosk shows. */
export function configuredKioskSummary(k: ConfiguredKiosk): string {
  if (k.payload.mode === 'single') return `Pinned to ${k.apps[0]?.name ?? k.payload.pinPackage}`;
  const n = k.apps.length;
  return `Launcher grid with ${n} app${n === 1 ? '' : 's'}`;
}

const OPEN = new Set(['pending', 'delivered', 'accepted']);

/**
 * The newest command that changes the device's kiosk: `kiosk.enter`, `kiosk.exit`, or a `config.apply` that is still
 * on its way or whose result has a kiosk outcome. History items are compared by id (newest is highest).
 */
export function lastKioskCommand(history: CommandHistoryItem[]): CommandHistoryItem | undefined {
  let best: CommandHistoryItem | undefined;
  for (const c of history) {
    const counts =
      c.type === 'kiosk.enter' ||
      c.type === 'kiosk.exit' ||
      (c.type === 'config.apply' && (OPEN.has(c.status) || parseOutcomes(c.detail)?.outcomes?.kiosk !== undefined));
    if (counts && (!best || Number(c.id) > Number(best.id))) best = c;
  }
  return best;
}

export type ConfiguredKioskState =
  /** Nothing says the device is in its configured kiosk: the action is offered. */
  | 'ready'
  /** A command that puts the device into its configured kiosk is waiting for the device. */
  | 'sending'
  /** The device runs its configured kiosk now. */
  | 'active';

/**
 * Whether the device is already in the kiosk its configuration defines. The history carries no payloads, so a
 * `kiosk.enter` counts as the configured kiosk only when this console sent it as one (`configuredIds`); otherwise the
 * action stays on offer (sending the same kiosk again is harmless: the agent keeps a kiosk it already runs).
 */
export function configuredKioskState({
  history,
  kioskActive,
  inSync,
  configuredIds,
}: {
  history: CommandHistoryItem[];
  kioskActive: boolean | undefined;
  /** The device runs the configuration's current revision (configStatus.inSync). */
  inSync: boolean | undefined;
  configuredIds: ReadonlySet<string>;
}): ConfiguredKioskState {
  const last = lastKioskCommand(history);
  if (!last) return 'ready';
  const fromConfig = last.type === 'config.apply';
  const configured = fromConfig || (last.type === 'kiosk.enter' && configuredIds.has(String(last.id)));
  if (!configured) return 'ready';
  if (OPEN.has(last.status)) return 'sending';
  const entered = fromConfig ? parseOutcomes(last.detail)?.outcomes?.kiosk === 'applied' : last.status === 'done';
  return entered && kioskActive === true && (!fromConfig || inSync === true) ? 'active' : 'ready';
}

// The `kiosk.enter` command ids this browser sent as a configured kiosk, per device (the history can't tell them from a
// custom kiosk). A convenience only: without it the action simply stays on offer.
const configuredKey = (device: string) => `mdm.kiosk.configured.${device}`;

export function configuredKioskIds(device: string): Set<string> {
  try {
    const v = JSON.parse(localStorage.getItem(configuredKey(device)) ?? '[]') as unknown;
    return new Set(Array.isArray(v) ? v.map(String) : []);
  } catch {
    return new Set();
  }
}

export function rememberConfiguredKiosk(device: string, id: number | string) {
  try {
    const ids = [...configuredKioskIds(device), String(id)].slice(-20);
    localStorage.setItem(configuredKey(device), JSON.stringify(ids));
  } catch {
    /* storage unavailable: the action just stays on offer */
  }
}

/** `version` is at least `min` (dotted numbers, e.g. "0.3.4"); null when it can't be read. */
export function versionAtLeast(version: string | null | undefined, min: string): boolean | null {
  const parse = (v: string) => v.replace(/^v/i, '').split(/[-+ ]/)[0].split('.').map((x) => Number(x));
  if (!version) return null;
  const a = parse(version);
  const b = parse(min);
  if (a.some((x) => !Number.isInteger(x) || x < 0)) return null;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0);
    if (d !== 0) return d > 0;
  }
  return true;
}

/** The agent release whose kiosk honours features.lockVolume (protocol 1.4); older agents ignore it. */
export const LOCK_VOLUME_AGENT = '0.3.4';

/** The assigned configuration's launcher theme (colours, icon size), or undefined when it sets none. */
export function kioskTheme(configuration: Configuration | undefined): KioskTheme | undefined {
  if (!configuration) return undefined;
  const value = (key: 'backgroundColor' | 'textColor' | 'iconSize') =>
    typeof configuration[key] === 'string' ? (configuration[key] as string) : undefined;
  const theme = {
    backgroundColor: value('backgroundColor'),
    textColor: value('textColor'),
    iconSize: value('iconSize'),
  };
  return Object.values(theme).some((v) => v !== undefined) ? theme : undefined;
}

// App icons fetched from a device (apps.icons), cached in localStorage per package and version code.
const iconKey = (pkg: string, v?: number) => `mdm.icon.${pkg}@${v ?? 0}`;

export function cachedIcon(pkg: string, v?: number): string | undefined {
  try {
    return localStorage.getItem(iconKey(pkg, v)) ?? undefined;
  } catch {
    return undefined;
  }
}

/** Remove every localStorage key with the given prefix, except `keep`. */
function evictIcons(prefix: string, keep?: string) {
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const k = localStorage.key(i);
    if (k && k.startsWith(prefix) && k !== keep) localStorage.removeItem(k);
  }
}

export function cacheIcon(pkg: string, v: number | undefined, dataUrl: string) {
  const key = iconKey(pkg, v);
  try {
    // Drop stale versions of this package's icon so the cache doesn't grow without bound.
    evictIcons(`mdm.icon.${pkg}@`, key);
    localStorage.setItem(key, dataUrl);
  } catch {
    // Quota: evict every cached icon once and retry before giving up silently.
    try {
      evictIcons('mdm.icon.');
      localStorage.setItem(key, dataUrl);
    } catch {
      /* still over quota */
    }
  }
}
