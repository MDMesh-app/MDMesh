// Product rules for configurations that the console's list and editor share. Pure functions; no React.
import type { Application } from '../api/applications';
import type { ConfigApp, Configuration } from '../api/configurations';
import type { ConfigSyncSummary } from '../api/configSync';

// The seeded device-template defaults are locked: view-only, and used as bases
// for new configs (start from scratch or from one of these). Both the fixed seed
// id (the seed SQL inserts ids 1-3) and the seed name must match: a user config
// that happens to reuse a seed name stays editable, and so does a seed row an admin
// renamed through the API. This never locks more than the name-only check did.
// A future seed rename must update both sets in the same release.
const SEED_IDS: ReadonlySet<number> = new Set([1, 2, 3]);
const SEED_NAMES: ReadonlySet<string> = new Set([
  'Managed Launcher',
  'MIUI (Xiaomi Redmi)',
  'Background (Agent) Mode',
]);
export const isLocked = (c: Configuration) => c.id != null && SEED_IDS.has(c.id) && SEED_NAMES.has(c.name);

/** The server flags a kiosk exit PIN that is a well-known default (absent on older servers). */
export const hasDefaultPin = (c: Configuration) => c.defaultKioskPin === true;
/**
 * Kiosk on with no exit PIN: agents before 0.3.6 let anyone leave without a prompt; 0.3.6 and later have no exit on
 * the device.
 */
export const hasNoPin = (c: Configuration) => c.kioskMode === true && !c.password;

/**
 * The API uses an explicit action for Location services so a console which predates the field cannot silently
 * change a saved policy to "Not managed" when it round-trips a configuration. The action is intentionally separate
 * from the persisted Boolean: only a user selection sends on, off, or notManaged; an omitted action means keep.
 */
export function locationServicesAction(value: unknown): 'on' | 'off' | 'notManaged' {
  return value === true ? 'on' : value === false ? 'off' : 'notManaged';
}

// The configurations INSERT sets every column explicitly, so NOT-NULL columns
// can't fall back to their DB defaults — a blank config must supply them or the
// insert fails (e.g. "null value in column pushoptions"). customerId is set
// server-side (insertRecord); applications must be a (possibly empty) array.
export const NEW_CONFIG_DEFAULTS: Partial<Configuration> = {
  type: 0,
  pushOptions: 'mqttWorker',
  appPermissions: 'GRANTALL',
  requestUpdates: 'DONOTTRACK',
  downloadUpdates: 'UNLIMITED',
  desktopHeader: 'NO_HEADER',
  iconSize: 'SMALL',
  defaultFilePath: '/',
  systemUpdateType: 0,
  useDefaultDesignSettings: true,
};

/**
 * A fresh editable draft, optionally seeded from a base config. The list endpoint carries no `applications`, so a
 * caller that wants the base's apps passes them in `baseApps` (otherwise the copy starts with whatever `base` has).
 */
export function cloneForNew(base: Configuration | null, baseApps?: ConfigApp[]): Configuration {
  if (!base) return { ...NEW_CONFIG_DEFAULTS, name: '', applications: [] } as Configuration;
  const c: Configuration = { ...base, name: `${base.name} copy` };
  delete c.id;
  delete c.qrCodeKey;
  // The server treats an omitted action as an older client's "keep the stored value" request. A new
  // configuration has no stored value, so a copied policy needs an explicit action to retain its meaning.
  c.locationServicesAction = locationServicesAction(c.locationServices);
  c.applications = (baseApps ?? base.applications ?? []).map((a) => ({ ...a }));
  return c;
}

/**
 * Devices that will actually re-apply kiosk: agents too old for config.apply ("unsupported") won't.
 * null = no sync summary for this configuration, or only wiped devices visible (this admin may not see them all) —
 * the caller fails closed (dialog without a number).
 */
export function affectedDeviceCount(row: ConfigSyncSummary | undefined): number | null {
  return row && row.total > 0 ? Math.max(0, row.total - row.unsupported) : null;
}

// configurations.mainAppId is an applicationVersions.id (NOT an applications.id): the server
// maps it back to the assigned app via configurationApplications.applicationVersionId.

/** The version id the main-app picker writes for an app: its assigned version, else its latest. */
export function versionIdForApp(app: Application, assigned: ConfigApp[]): number | undefined {
  return assigned.find((x) => x.id === app.id)?.usedVersionId ?? app.latestVersion;
}

/** Display name for a mainAppId (a version id); a dash when it can't be resolved. */
export function appNameForVersionId(apps: Application[], versionId?: number, assigned: ConfigApp[] = []): string {
  if (versionId == null) return '—';
  const a = assigned.find((x) => x.usedVersionId === versionId);
  if (a) return a.name ?? a.pkg ?? '—';
  return apps.find((x) => x.latestVersion === versionId)?.name ?? '—';
}
