import { queueCommand, listCommandHistory, forceSync, type QueueCommandRequest, type CommandHistoryItem } from './commands';

// Device app inventory for the kiosk picker. The agent answers `apps.scan` / `apps.icons`
// commands and returns the data as JSON in the command-result `detail`; we read it back from
// the command-history endpoint we already poll — so there is no dedicated server API.

export interface AppInfo {
  pkg: string;
  label: string;
  system?: boolean;
  launchable?: boolean;
  versionName?: string;
  versionCode?: number;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Queue a command and poll command history until it completes, returning its result `detail`. */
async function runForResult(
  deviceId: number | string,
  req: QueueCommandRequest,
  timeoutMs = 75000,
  signal?: AbortSignal,
): Promise<CommandHistoryItem> {
  const queued = await queueCommand(deviceId, req, signal);
  const id = queued?.id;
  if (id == null) throw new Error('Command was not queued');
  const deadline = Date.now() + timeoutMs;
  let nextSyncAt = 0;
  while (Date.now() < deadline) {
    if (signal?.aborted) throw new Error('Cancelled');
    await sleep(1500);
    if (signal?.aborted) throw new Error('Cancelled');
    const hist = await listCommandHistory(deviceId, 0, signal).catch(() => []);
    const cmd = hist.find((c) => String(c.id) === String(id));
    if (!cmd) continue;
    if (cmd.status === 'done') return cmd;
    if (cmd.status === 'failed' || cmd.status === 'unsupported' || cmd.status === 'expired') {
      throw new Error(cmd.detail || `Device returned ${cmd.status}`);
    }
    // Executing a command does not post its result: that waits for another check-in.
    // Nudge delivered scans/icons so the console need not wait for the periodic heartbeat.
    if (cmd.status === 'delivered' && Date.now() >= nextSyncAt) {
      nextSyncAt = Date.now() + 15000;
      await forceSync(deviceId, signal).catch(() => undefined);
    }
  }
  throw new Error('Timed out waiting for the device (is it online?)');
}

/** Scan the device for installed packages (metadata only — no icons). */
export async function scanApps(deviceId: number | string, signal?: AbortSignal): Promise<AppInfo[]> {
  const { detail } = await runForResult(deviceId, { type: 'apps.scan' }, 75000, signal);
  const parsed = JSON.parse(detail || '{}') as { apps?: AppInfo[] };
  return parsed.apps ?? [];
}

export interface ScanSnapshot {
  includeAll?: boolean;
  apps: AppInfo[];
  scannedAt?: number;
  /** Server time when the scan was queued; changes completed later may not be covered. */
  freshnessAt?: number;
}

/**
 * The device's most recent saved scan, read from command history (every apps.scan result is
 * durably stored per device in the command's detail). Returns null if the device was never scanned,
 * so the picker can show the saved list instantly instead of re-scanning every time.
 */
export async function getLatestScan(deviceId: number | string, includeAll = false): Promise<ScanSnapshot | null> {
  const hist = await listCommandHistory(deviceId).catch(() => []);
  return scanFromHistory(hist, includeAll);
}

/** Read a saved scan from a history response already fetched by the inventory monitor. */
export function scanFromHistory(hist: CommandHistoryItem[], includeAll = false): ScanSnapshot | null {
  for (const scan of hist) {
    if (scan.type !== 'apps.scan' || scan.status !== 'done' || !scan.detail) continue;
    try {
      const parsed = JSON.parse(scan.detail) as ScanSnapshot;
      if (includeAll && !parsed.includeAll) continue;
      if (!Array.isArray(parsed.apps)) continue;
      const apps = includeAll ? parsed.apps : parsed.apps.filter((a) => a.launchable);
      return { apps, includeAll: !!parsed.includeAll, scannedAt: scan.completedAt, freshnessAt: scan.createdAt ?? scan.completedAt };
    } catch { /* skip malformed results */ }
  }
  return null;
}

function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

/** Overall cap across all icon batches — on top of the per-command timeout. */
const ICONS_DEADLINE_MS = 3 * 60 * 1000;

/** Fetch base64-PNG icons for the given packages, in batches. Returns a pkg→dataURL map. */
export async function fetchIcons(
  deviceId: number | string,
  packages: string[],
  onBatch?: (icons: Record<string, string>) => void,
  signal?: AbortSignal,
): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  const deadline = Date.now() + ICONS_DEADLINE_MS;
  for (const batch of chunk(packages, 24)) {
    const left = deadline - Date.now();
    if (left <= 0) throw new Error('Icon fetch took too long — try again');
    const { detail } = await runForResult(deviceId, {
      type: 'apps.icons',
      payload: JSON.stringify({ packages: batch }),
    }, Math.min(75000, left), signal);
    const parsed = JSON.parse(detail || '{}') as { icons?: Array<{ pkg: string; pngBase64: string }> };
    const got: Record<string, string> = {};
    for (const ic of parsed.icons ?? []) got[ic.pkg] = `data:image/png;base64,${ic.pngBase64}`;
    Object.assign(out, got);
    onBatch?.(got);
  }
  return out;
}

/** Explicit full inventory. Older agents must not silently appear to report all packages. */
export async function scanInstalledApps(deviceId: number | string, signal?: AbortSignal): Promise<ScanSnapshot> {
  const result = await runForResult(deviceId, {
    type: 'apps.scan', payload: JSON.stringify({ includeAll: true }),
  }, 75000, signal);
  const parsed = JSON.parse(result.detail || '{}') as ScanSnapshot;
  if (!parsed.includeAll || !Array.isArray(parsed.apps)) {
    throw new Error('Update the device agent to support the full installed-app inventory.');
  }
  return { ...parsed, scannedAt: result.completedAt, freshnessAt: result.createdAt ?? result.completedAt };
}

/** Only successful package changes newer than the inventory require another device scan. */
export function latestAppChange(history: CommandHistoryItem[], scannedAt: number): CommandHistoryItem | undefined {
  return history
    .filter((c) => (c.type === 'app.install' || c.type === 'app.uninstall') &&
      c.status === 'done' && (c.completedAt ?? 0) > scannedAt)
    .sort((a, b) => (b.completedAt ?? 0) - (a.completedAt ?? 0))[0];
}

/** Recent delivered app commands need another check-in to flush their result. Old abandoned commands do not. */
export function appChangeAwaitingResult(history: CommandHistoryItem[], freshnessAt: number): boolean {
  return history.some((c) => (c.type === 'app.install' || c.type === 'app.uninstall') &&
    c.status === 'delivered' && (c.deliveredAt ?? c.createdAt ?? 0) >= freshnessAt);
}
