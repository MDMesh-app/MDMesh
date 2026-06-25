import { queueCommand, listCommandHistory, type QueueCommandRequest } from './commands';

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
): Promise<string> {
  const queued = await queueCommand(deviceId, req);
  const id = queued?.id;
  if (id == null) throw new Error('Command was not queued');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(1500);
    const hist = await listCommandHistory(deviceId).catch(() => []);
    const cmd = hist.find((c) => String(c.id) === String(id));
    if (!cmd) continue;
    if (cmd.status === 'done') return cmd.detail ?? '';
    if (cmd.status === 'failed' || cmd.status === 'unsupported' || cmd.status === 'expired') {
      throw new Error(cmd.detail || `Device returned ${cmd.status}`);
    }
  }
  throw new Error('Timed out waiting for the device (is it online?)');
}

/** Scan the device for installed packages (metadata only — no icons). */
export async function scanApps(deviceId: number | string): Promise<AppInfo[]> {
  const detail = await runForResult(deviceId, { type: 'apps.scan' });
  const parsed = JSON.parse(detail || '{}') as { apps?: AppInfo[] };
  return parsed.apps ?? [];
}

export interface ScanSnapshot {
  apps: AppInfo[];
  scannedAt?: number;
}

/**
 * The device's most recent saved scan, read from command history (every apps.scan result is
 * durably stored per device in the command's detail). Returns null if the device was never scanned,
 * so the picker can show the saved list instantly instead of re-scanning every time.
 */
export async function getLatestScan(deviceId: number | string): Promise<ScanSnapshot | null> {
  const hist = await listCommandHistory(deviceId).catch(() => []); // newest-first (id DESC)
  const scan = hist.find((c) => c.type === 'apps.scan' && c.status === 'done' && !!c.detail);
  if (!scan?.detail) return null;
  try {
    const apps = (JSON.parse(scan.detail).apps ?? []) as AppInfo[];
    return apps.length ? { apps, scannedAt: scan.completedAt } : null;
  } catch {
    return null;
  }
}

function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

/** Fetch base64-PNG icons for the given packages, in batches. Returns a pkg→dataURL map. */
export async function fetchIcons(
  deviceId: number | string,
  packages: string[],
  onBatch?: (icons: Record<string, string>) => void,
): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const batch of chunk(packages, 24)) {
    const detail = await runForResult(deviceId, {
      type: 'apps.icons',
      payload: JSON.stringify({ packages: batch }),
    });
    const parsed = JSON.parse(detail || '{}') as { icons?: Array<{ pkg: string; pngBase64: string }> };
    const got: Record<string, string> = {};
    for (const ic of parsed.icons ?? []) got[ic.pkg] = `data:image/png;base64,${ic.pngBase64}`;
    Object.assign(out, got);
    onBatch?.(got);
  }
  return out;
}
