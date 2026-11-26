// REST calls of the Policies extension, through the console's API client (`@mdmesh/console`). Every endpoint here is
// one core's own pages already use; the extension adds no server code and no database tables.
import { apiClient, type Configuration, type DeviceView, type QueueCommandRequest } from '@mdmesh/console';
import type { GroupInput } from './groups';
import {
  allowedPackages,
  NAMESPACE,
  parsePolicy,
  POLICY_MAX_BYTES,
  policyBytes,
  type AppRef,
  type ConfigAppRow,
  type DeviceGroup,
  type LibraryApp,
  type PolicyData,
  type ScannedApp,
} from './model';

/** A configuration row of the list endpoint (no apps, files or extensions). */
export interface ConfigRow {
  id: number;
  name: string;
  description?: string;
  [key: string]: unknown;
}

/** A policy: a configuration's list row plus its PolicyData. */
export interface PolicyRow {
  id: number;
  name: string;
  description?: string;
  /** One of the three configurations MDMesh ships: shown, never saved. */
  builtIn: boolean;
  config: ConfigRow;
  data: PolicyData;
  /** Kiosk mode is on: phones open only the allowed apps. */
  kiosk: boolean;
  /** The apps the configuration allows on the kiosk screen; null when they could not be loaded. */
  allowed: AppRef[] | null;
}

const SEED_NAMES = new Set(['Managed Launcher', 'MIUI (Xiaomi Redmi)', 'Background (Agent) Mode']);
/** As core's configurationRules.isLocked: ids 1–3 with their seeded names. */
export const isBuiltIn = (c: { id?: number; name: string }) => c.id != null && c.id <= 3 && SEED_NAMES.has(c.name);

/**
 * Every configuration, with its policy data and allowed apps (two GETs each: the list rows leave `extensions` and the
 * apps out).
 */
export async function listPolicies(): Promise<PolicyRow[]> {
  const rows = await apiClient.get<ConfigRow[]>('/private/configurations/search');
  const [full, apps] = await Promise.all([
    Promise.all(rows.map((r) => apiClient.get<Configuration>(`/private/configurations/${r.id}`).catch(() => null))),
    Promise.all(rows.map((r) => configApps(r.id).catch(() => null))),
  ]);
  return rows.map((r, i) => {
    const list = apps[i];
    const names = new Map((list ?? []).map((a) => [a.pkg?.trim() ?? '', a.name]));
    return {
      id: r.id,
      name: r.name,
      description: r.description,
      builtIn: isBuiltIn(r),
      config: r,
      data: parsePolicy(full[i]?.extensions?.[NAMESPACE]),
      kiosk: r.kioskMode === true,
      allowed: list ? [...allowedPackages(list)].sort().map((pkg) => ({ pkg, label: names.get(pkg) || undefined })) : null,
    };
  });
}

export async function listDevices(): Promise<DeviceView[]> {
  // The same limit the Devices page uses (it loads up to 1000 and pages in the browser).
  const r = await apiClient.post<{ devices?: { items?: DeviceView[] } }>('/private/devices/search', {
    value: '',
    pageNum: 1,
    pageSize: 1000,
  });
  return r?.devices?.items ?? [];
}

/** A whole configuration, ready to save: every column, its files and settings, and its assigned apps. */
export interface LoadedConfig {
  config: Configuration;
}

interface AppRow {
  selected?: boolean;
  [key: string]: unknown;
}

/** A configuration's assigned apps: the selected rows of its app matrix, whole (every column goes back on a save). */
async function configApps(id: number): Promise<ConfigAppRow[]> {
  const rows = (await apiClient.get<AppRow[]>(`/private/configurations/applications/${id}`)) ?? [];
  return rows.filter((a) => a.selected).map(({ selected: _s, ...a }) => a as unknown as ConfigAppRow);
}

/**
 * Load a configuration the way the configuration editor does before a save. The server replaces every column and
 * list on a save, so the full GET (with files and settings) and the assigned apps must both go back.
 */
export async function loadConfig(id: number): Promise<LoadedConfig> {
  const [config, applications] = await Promise.all([apiClient.get<Configuration>(`/private/configurations/${id}`), configApps(id)]);
  if (!config || config.id !== id) throw new Error('That configuration no longer exists.');
  return { config: { ...config, applications: applications as unknown as Configuration['applications'] } };
}

/** The configuration's app rows as loaded. */
export const loadedApps = (loaded: LoadedConfig): ConfigAppRow[] => (loaded.config.applications ?? []) as unknown as ConfigAppRow[];

// ---- Library ----

/** Every Library application the user can see. */
export async function listLibrary(): Promise<LibraryApp[]> {
  return (await apiClient.get<LibraryApp[]>('/private/applications/search')) ?? [];
}

/**
 * A package-only Library entry for an app that is already on the phones (preinstalled, or installed by hand): no APK
 * and no download link, like the system apps MDMesh seeds. The server never sends an install for it; it only names
 * the package, so a configuration can allow it on the kiosk screen.
 */
export async function createPackageApp(pkg: string, name: string): Promise<LibraryApp> {
  const app = await apiClient.put<LibraryApp>('/private/applications/android', {
    name: name.slice(0, 100) || pkg,
    pkg,
    version: '0',
    versionCode: 0,
    system: true,
    type: 'app',
    runAfterInstall: false,
  });
  if (!app || typeof app.id !== 'number') throw new Error(`The Library did not return an entry for ${pkg}.`);
  return app;
}

/**
 * Save a configuration with some columns changed and, when given, new policy data. The other extensions'
 * namespaces go back as loaded; without new policy data, `extensions` is left out so the server keeps what is stored.
 */
export async function saveConfig(
  loaded: LoadedConfig,
  columns: Readonly<Record<string, unknown>>,
  policy?: PolicyData,
): Promise<void> {
  const { extensions, ...body } = loaded.config;
  const next: Record<string, unknown> = { ...body, ...columns };
  // The server keeps the stored Location services unless the save says what to do with it (an older client would
  // otherwise clear it): send the action for a changed value, as core's configuration editor does.
  if ('locationServices' in columns) {
    const v = columns.locationServices;
    next.locationServicesAction = v === true ? 'on' : v === false ? 'off' : 'notManaged';
  }
  if (policy) {
    const bytes = policyBytes(policy);
    if (bytes > POLICY_MAX_BYTES) {
      throw new Error(
        `This policy's data is too big to save (${Math.ceil(bytes / 1024)} KB, the limit is 16 KB). Remove some exceptions or phones moved into groups by hand, then try again.`,
      );
    }
    if (extensions == null && loaded.config.id != null) {
      throw new Error('The configuration’s extension data did not load, so it can’t be saved safely.');
    }
    next.extensions = { ...(extensions ?? {}), [NAMESPACE]: policy };
  }
  await apiClient.put('/private/configurations', next);
}

/** Load, change the policy data with `edit`, save. For the Groups tab, which changes no columns. */
export async function updatePolicyData(id: number, edit: (d: PolicyData) => PolicyData): Promise<void> {
  const loaded = await loadConfig(id);
  const current = parsePolicy(loaded.config.extensions?.[NAMESPACE]);
  await saveConfig(loaded, {}, edit(current));
}

/** Move devices to a configuration (core's bulk move; the server wakes them). */
export async function moveDevices(ids: number[], configurationId: number): Promise<void> {
  if (ids.length) await apiClient.put('/private/devices', { ids, configurationId });
}

// ---- per-device detail ----

// The agent endpoints below name a device by its NUMBER (P-1001), not its internal id: the server answers
// error.agent.device.unknown to an id. Only the bulk endpoint (queueBulk) takes internal ids.
const agentDevice = (number: string) => `/private/agent/v1/devices/${encodeURIComponent(number)}`;

export async function getHardware(number: string): Promise<Record<string, unknown> | null> {
  const t = await apiClient.get<{ hardware?: Record<string, unknown> } | null>(`${agentDevice(number)}/telemetry`);
  return t?.hardware ?? null;
}

export interface DeviceConfigStatus {
  configurationId?: number | null;
  inSync: boolean;
  supported: boolean;
  appliedRevision?: string | null;
  lastCommand?: { status?: string; detail?: string | null } | null;
}

export function getConfigStatus(number: string): Promise<DeviceConfigStatus | null> {
  return apiClient.get<DeviceConfigStatus | null>(`${agentDevice(number)}/configStatus`);
}

interface HistoryItem {
  id: number | string;
  type: string;
  status: string;
  detail?: string | null;
  completedAt?: number;
  subject?: string | null;
}

export interface DeviceApps {
  /** null when the device was never scanned. */
  apps: ScannedApp[] | null;
  scannedAt?: number;
  /** A scan queued and not answered yet. */
  scanPending: boolean;
  /** Packages with an uninstall queued or on its way. */
  removing: Set<string>;
}

const OPEN = new Set(['pending', 'delivered', 'accepted']);

/** The device's latest app scan and its open uninstalls, from its command history (newest first). */
export async function getDeviceApps(number: string): Promise<DeviceApps> {
  const hist = (await apiClient.get<HistoryItem[]>(`${agentDevice(number)}/commands?since=0`)) ?? [];
  const removing = new Set(
    hist.filter((c) => c.type === 'app.uninstall' && OPEN.has(c.status) && c.subject).map((c) => c.subject as string),
  );
  const scanPending = hist.some((c) => c.type === 'apps.scan' && OPEN.has(c.status));
  const scan = hist.find((c) => c.type === 'apps.scan' && c.status === 'done' && !!c.detail);
  let apps: ScannedApp[] | null = null;
  if (scan?.detail) {
    try {
      const raw = (JSON.parse(scan.detail) as { apps?: unknown })?.apps;
      apps = Array.isArray(raw)
        ? raw.filter((a): a is ScannedApp => !!a && typeof a === 'object' && typeof (a as ScannedApp).pkg === 'string')
        : null;
    } catch {
      apps = null;
    }
  }
  return { apps, scannedAt: scan?.completedAt, scanPending, removing };
}

/** The phone's latest saved scan (no command is sent), or null when it was never scanned. */
export async function latestScan(number: string): Promise<{ apps: ScannedApp[]; scannedAt?: number } | null> {
  const r = await getDeviceApps(number);
  return r.apps ? { apps: r.apps, scannedAt: r.scannedAt } : null;
}

const sleep = (ms: number) => new Promise((res) => setTimeout(res, ms));

/** Queue a command and wait for its result detail (as the kiosk picker does). The phone must be online. */
async function runForResult(number: string, req: QueueCommandRequest, timeoutMs: number, signal?: AbortSignal): Promise<string> {
  const queued = (await queue(number, req)) as { id?: number | string } | null;
  if (queued?.id == null) throw new Error('The command was not queued.');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(1500);
    if (signal?.aborted) throw new Error('Cancelled.');
    const hist = (await apiClient.get<HistoryItem[]>(`${agentDevice(number)}/commands?since=0`).catch(() => null)) ?? [];
    const cmd = hist.find((c) => String(c.id) === String(queued.id));
    if (!cmd) continue;
    if (cmd.status === 'done') return cmd.detail ?? '';
    if (cmd.status === 'failed' || cmd.status === 'unsupported' || cmd.status === 'expired') {
      throw new Error(cmd.detail || `The phone answered ${cmd.status}.`);
    }
  }
  throw new Error('The phone did not answer in time. Is it online? The scan stays queued and runs when it connects.');
}

/** Scan a phone now and wait for its apps (about a minute at most). Icon apps only: apps.scan lists what a user can open. */
export async function scanNow(number: string, signal?: AbortSignal): Promise<ScannedApp[]> {
  await syncNow(number).catch(() => undefined);
  const detail = await runForResult(number, SCAN, 75000, signal);
  const raw = (JSON.parse(detail || '{}') as { apps?: unknown } | null)?.apps;
  return Array.isArray(raw)
    ? raw.filter((a): a is ScannedApp => !!a && typeof a === 'object' && typeof (a as ScannedApp).pkg === 'string')
    : [];
}

const ICON_KEY = 'x-policies-icon:';

/** App icons cached in this browser, by package (a convenience: an empty cache only means fetching them again). */
export function cachedIcons(pkgs: readonly string[]): Record<string, string> {
  const out: Record<string, string> = {};
  try {
    for (const p of pkgs) {
      const v = localStorage.getItem(ICON_KEY + p);
      if (v) out[p] = v;
    }
  } catch {
    /* storage blocked: no cache */
  }
  return out;
}

/** Fetch icons from a phone (apps.icons), 24 at a time, caching each in this browser. */
export async function fetchIcons(
  number: string,
  pkgs: readonly string[],
  onBatch: (icons: Record<string, string>) => void,
  signal?: AbortSignal,
): Promise<void> {
  for (let i = 0; i < pkgs.length; i += 24) {
    if (signal?.aborted) return;
    const detail = await runForResult(number, { type: 'apps.icons', payload: JSON.stringify({ packages: pkgs.slice(i, i + 24) }) }, 75000, signal);
    const parsed = JSON.parse(detail || '{}') as { icons?: { pkg?: string; pngBase64?: string }[] };
    const got: Record<string, string> = {};
    for (const ic of parsed.icons ?? []) {
      if (!ic.pkg || !ic.pngBase64) continue;
      got[ic.pkg] = `data:image/png;base64,${ic.pngBase64}`;
      try {
        localStorage.setItem(ICON_KEY + ic.pkg, got[ic.pkg]);
      } catch {
        /* storage full or blocked: keep it for this page only */
      }
    }
    onBatch(got);
  }
}

// ---- the whole fleet in one request (the Policies server plugin) ----

/** One phone of the plugin's fleet answer. A null status means: ask core's per-phone endpoint. */
export interface FleetPhone {
  id: number;
  number: string;
  hardware?: Record<string, string> | null;
  status?: DeviceConfigStatus | null;
  scan?: { apps: ScannedApp[]; scannedAt?: number } | null;
  scanPending: boolean;
  removing: string[];
  /** The agent version the phone last reported. */
  agentVersion?: string | null;
  /** Its agent reports app.launch: it can open an app on request. */
  canLaunch?: boolean;
}

export interface FleetAnswer {
  /** Core's status logic was reachable; when false every status is null. */
  statusAvailable: boolean;
  serverTime: number;
  devices: FleetPhone[];
}

/**
 * Every phone's brand, policy status, latest scan and open scans and uninstalls in ONE request, from the Policies
 * server plugin (plugins/fork/policies). null when the plugin isn't installed or is off, or the request fails: the
 * caller then asks per phone, as before.
 */
export async function getFleet(): Promise<FleetAnswer | null> {
  try {
    const r = await apiClient.get<FleetAnswer | null>('/plugins/policies/private/fleet');
    return r && Array.isArray(r.devices) ? r : null;
  } catch {
    return null;
  }
}

/** A plugin phone as the per-phone calls would have given it. */
export function fleetApps(p: FleetPhone): DeviceApps {
  return {
    apps: p.scan ? p.scan.apps.filter((a) => !!a && typeof a.pkg === 'string') : null,
    scannedAt: p.scan?.scannedAt,
    scanPending: p.scanPending === true,
    removing: new Set(Array.isArray(p.removing) ? p.removing : []),
  };
}

// ---- bulk jobs (the Policies server plugin) ----

export type JobAction = 'reapply' | 'scan' | 'command' | 'install' | 'remove';
export type JobPhase = 'sending' | 'running' | 'waiting' | 'done' | 'cancelled';

/** A bulk job and its progress, as the server counts it. */
export interface Job {
  id: number;
  action: JobAction;
  label?: string | null;
  createdAt: number;
  cancelled: boolean;
  phones: number;
  /** Phones by wake state: queued, woken, offline, cancelled. */
  wake: Record<string, number>;
  /** Commands by status: pending, delivered, accepted, done, failed, unsupported, expired. */
  commands: Record<string, number>;
  phase: JobPhase;
}

export interface JobRequest {
  action: JobAction;
  label: string;
  deviceIds?: number[];
  command?: { type: string; payload?: string };
  removals?: { deviceId: number; packages: string[]; alertTitle?: string; alertBody?: string }[];
}

const JOBS = '/plugins/policies/private/jobs';

/** Start a job: one request; the server queues every command at once and wakes the phones at a steady rate. */
export function createJob(req: JobRequest): Promise<Job> {
  return apiClient.post<Job>(JOBS, req);
}

/** The customer's jobs of the last 24 hours, newest first. */
export async function listJobs(): Promise<Job[]> {
  return (await apiClient.get<Job[]>(JOBS)) ?? [];
}

export function cancelJob(id: number): Promise<Job> {
  return apiClient.post<Job>(`${JOBS}/${id}/cancel`, {});
}

// ---- automation (the Policies server plugin) ----

export interface Automation {
  /** Nightly sweep and new-phone scans. */
  automaticScans: boolean;
  /** The window's hours (0–23) in timeZone; equal hours mean all day. */
  windowStart: number;
  windowEnd: number;
  timeZone: string;
  /** Scans finished after this are checked for auto-remove; null until it first runs. */
  autoRemoveSince?: number | null;
  lastSweep?: { night: string; total: number; reached: number; finished: boolean; jobId?: number | null } | null;
}

const AUTOMATION = '/plugins/policies/private/automation';

export function getAutomation(): Promise<Automation> {
  return apiClient.get<Automation>(AUTOMATION);
}

export function saveAutomation(a: Pick<Automation, 'automaticScans' | 'windowStart' | 'windowEnd' | 'timeZone'>): Promise<Automation> {
  return apiClient.put<Automation>(AUTOMATION, a);
}

// ---- commands ----

export function queue(number: string, req: QueueCommandRequest): Promise<unknown> {
  return apiClient.post(`${agentDevice(number)}/commands`, req);
}

/** One command to many devices (core's bulk endpoint, which takes internal device ids). */
export function queueBulk(deviceIds: number[], req: QueueCommandRequest): Promise<{ queued: number; skipped: number[] }> {
  return apiClient.post('/private/agent/v1/bulk/commands', { deviceIds, command: req });
}

/** Wake a device so it checks in now. */
export function syncNow(number: string): Promise<unknown> {
  return apiClient.post(`${agentDevice(number)}/sync`, {});
}

export const SCAN: QueueCommandRequest = { type: 'apps.scan' };

/** Silent uninstall as Device Owner. The agent doesn't advertise a capability for it, so none is required. */
export const uninstall = (pkg: string): QueueCommandRequest => ({
  type: 'app.uninstall',
  payload: JSON.stringify({ packageName: pkg }),
});

export const alert = (title: string, body: string): QueueCommandRequest => ({
  type: 'device.alert',
  requiresCapability: 'device.alert',
  payload: JSON.stringify({ title: title || undefined, body }),
});

// The device page's remote actions, as core sends them (api/commands.ts ACTION_TEMPLATES).
export const COMMANDS = {
  lock: { type: 'device.lock', requiresCapability: 'device.lock' },
  ring: { type: 'device.ring', requiresCapability: 'device.ring', payload: JSON.stringify({ durationMs: 30000 }) },
  ringStop: { type: 'device.ringStop', requiresCapability: 'device.ringStop' },
  reboot: { type: 'device.reboot', requiresCapability: 'device.reboot' },
  kioskExit: { type: 'kiosk.exit' },
  alwaysOn: { type: 'device.powerMode', requiresCapability: 'device.powerMode', payload: JSON.stringify({ mode: 'alwaysOn' }) },
  batterySaver: { type: 'device.powerMode', requiresCapability: 'device.powerMode', payload: JSON.stringify({ mode: 'adaptive' }) },
  locationAccurate: { type: 'device.locationMode', requiresCapability: 'device.locationMode', payload: JSON.stringify({ mode: 'active' }) },
  locationSaver: { type: 'device.locationMode', requiresCapability: 'device.locationMode', payload: JSON.stringify({ mode: 'passive' }) },
} satisfies Record<string, QueueCommandRequest>;

/** Open an app's screen (agent protocol 1.13). Only phones whose agent reports the capability get it. */
export const openApp = (pkg: string): QueueCommandRequest => ({
  type: 'app.launch',
  requiresCapability: 'app.launch',
  payload: JSON.stringify({ packageName: pkg }),
});

export const lockscreenMessage = (message: string): QueueCommandRequest => ({
  type: 'device.lockscreenMessage',
  requiresCapability: 'device.lockscreenMessage',
  payload: JSON.stringify({ message }),
});

/** Queue app.install for the apps the phone's configuration installs (core's "Install config apps"). */
export function syncApps(number: string): Promise<{ queued: number }> {
  return apiClient.post(`${agentDevice(number)}/syncApps`, {});
}

// ---- agent update (core's staged rollout) ----

export interface AgentApk {
  version: string;
  versionCode: number;
  sha256: string;
  available: boolean;
}

/** The agent APK the updater mirrors from the latest verified release, or null when there is none (or no updater). */
export async function getAgentApk(): Promise<AgentApk | null> {
  try {
    const r = await fetch('/update/status', { headers: { Accept: 'application/json' }, credentials: 'include' });
    if (!r.ok) return null;
    const s = (await r.json()) as { apk?: AgentApk | null };
    return s?.apk?.available ? s.apk : null;
  } catch {
    return null;
  }
}

export interface ActiveRollout {
  id: number;
  targetVersion: string;
  stage: string;
}

export function getActiveRollout(): Promise<ActiveRollout | null> {
  return apiClient.get<ActiveRollout | null>('/private/agent/v1/rollout/active');
}

/** Start a staged agent update with these phones as its first stage (Settings → Agent rollout promotes it). */
export function startAgentUpdate(apk: AgentApk, numbers: string[]): Promise<ActiveRollout> {
  return apiClient.post('/private/agent/v1/rollout', {
    targetVersion: apk.version,
    packageName: 'com.mdmesh.agent',
    apkVersionCode: apk.versionCode,
    apkSha256: apk.sha256,
    canaryDeviceNumbers: numbers,
  });
}

/**
 * Copy a configuration (core's Duplicate; needs copy_config), keeping its description as core's copy does, with its
 * allowed apps, rules and host phone. Returns the copy's id (found by its name, which the server keeps unique).
 */
export async function copyConfig(id: number, name: string, description?: string): Promise<number> {
  await apiClient.put('/private/configurations/copy', { id, name, description });
  const rows = await apiClient.get<ConfigRow[]>('/private/configurations/search');
  const copy = rows.find((r) => r.name === name && r.id !== id);
  if (!copy) throw new Error('The copy was made, but it could not be found. Reload the page.');
  return copy.id;
}

// ---- device groups and brands (Policies server plugin) ----

const GROUPS = '/plugins/policies/private/groups';

/** Groups, brands made by hand, and brands set on phones by hand, as the plugin stores them. */
export interface GroupsState {
  groups: DeviceGroup[];
  brands: { brand: string; label: string }[];
  /** Phone (device id) → the brand set on it by hand. */
  deviceBrands: Map<number, string>;
}

interface GroupsAnswer {
  groups?: { id: number; name: string; configurationId: number | null; brands?: string[]; devices?: number[] }[];
  brands?: { brand: string; label: string }[];
  deviceBrands?: { deviceId: number; brand: string }[];
}

function toState(a: GroupsAnswer | null): GroupsState {
  return {
    groups: (a?.groups ?? []).map((g) => ({
      id: g.id,
      name: g.name,
      policyId: g.configurationId ?? null,
      brands: Array.isArray(g.brands) ? g.brands : [],
      devices: Array.isArray(g.devices) ? g.devices : [],
    })),
    brands: Array.isArray(a?.brands) ? a.brands : [],
    deviceBrands: new Map((a?.deviceBrands ?? []).map((d) => [d.deviceId, d.brand])),
  };
}

/** The groups state, or null without the Policies server plugin (not installed, off, or failing). */
export async function getGroups(): Promise<GroupsState | null> {
  try {
    const r = await apiClient.get<GroupsAnswer | null>(GROUPS);
    return r && Array.isArray(r.groups) ? toState(r) : null;
  } catch {
    return null;
  }
}

// Every change answers with the whole new state, so the page needs no second request.
export async function saveGroup(input: GroupInput, id?: number): Promise<GroupsState> {
  return toState(id == null ? await apiClient.post<GroupsAnswer>(GROUPS, input) : await apiClient.put<GroupsAnswer>(`${GROUPS}/${id}`, input));
}

export async function deleteGroup(id: number): Promise<GroupsState> {
  return toState(await apiClient.del<GroupsAnswer>(`${GROUPS}/${id}`));
}

/** Picks phones into a group, or with null takes them out of the group they were picked into (back to their brand's). */
export async function setGroupMembers(deviceIds: readonly number[], groupId: number | null): Promise<GroupsState> {
  return toState(await apiClient.put<GroupsAnswer>(`${GROUPS}/members`, { deviceIds, groupId }));
}

/** Sets a brand on phones by hand, or with null lets them show the brand they report again. */
export async function setDeviceBrands(deviceIds: readonly number[], brand: string | null): Promise<GroupsState> {
  return toState(await apiClient.put<GroupsAnswer>(`${GROUPS}/device-brands`, { deviceIds, brand }));
}

export async function createBrand(label: string): Promise<GroupsState> {
  return toState(await apiClient.post<GroupsAnswer>(`${GROUPS}/brands`, { label }));
}

export async function deleteBrand(brand: string): Promise<GroupsState> {
  return toState(await apiClient.del<GroupsAnswer>(`${GROUPS}/brands/${encodeURIComponent(brand)}`));
}

/** Gives these groups to a policy, or with null takes their policy away. */
export async function setGroupsPolicy(configurationId: number | null, groupIds: readonly number[]): Promise<GroupsState> {
  return toState(await apiClient.put<GroupsAnswer>(`${GROUPS}/policy`, { configurationId, groupIds }));
}

// ---- app schedules (the Policies server plugin) ----

/** Who a schedule opens the app on. */
export type ScheduleTarget = 'policy' | 'group' | 'phones';

/** Open an app on a policy's phones at set times. */
export interface Schedule {
  id: number;
  configurationId: number;
  pkg: string;
  appName?: string | null;
  target: ScheduleTarget;
  /** For target group; null once that group is deleted. */
  groupId: number | null;
  /** For target phones: device ids. */
  devices: number[];
  /** Monday = 1 ... Sunday = 64. */
  days: number;
  times: string[];
  timeZone: string;
  /** A phone offline at the time still gets it within this many minutes. */
  lateMinutes: number;
  enabled: boolean;
  lastSlot?: string | null;
  nextRun?: number | null;
}

export type ScheduleInput = Omit<Schedule, 'id' | 'configurationId' | 'lastSlot' | 'nextRun'>;

const SCHEDULES = '/plugins/policies/private/schedules';

export async function listSchedules(configurationId: number): Promise<Schedule[]> {
  return (await apiClient.get<Schedule[]>(`${SCHEDULES}/${configurationId}`)) ?? [];
}

export function saveSchedule(configurationId: number, input: ScheduleInput, id?: number): Promise<Schedule> {
  return id == null
    ? apiClient.post<Schedule>(`${SCHEDULES}/${configurationId}`, input)
    : apiClient.put<Schedule>(`${SCHEDULES}/${configurationId}/${id}`, input);
}

export async function deleteSchedule(configurationId: number, id: number): Promise<void> {
  await apiClient.del(`${SCHEDULES}/${configurationId}/${id}`);
}
