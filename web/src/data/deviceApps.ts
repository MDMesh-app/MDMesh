// A device's installed apps, from its apps.scan results in command history (the agent lists the apps with a
// launcher icon). Device-supplied JSON, so every field is checked before the page reads it.
import type { CommandHistoryItem } from '../api/commands';
import type { AppInfo } from '../api/deviceApps';

export interface AppScan {
  apps: AppInfo[];
  /** The server's completion time of the scan command. */
  scannedAt?: number;
}

/**
 * A scan's app list: entries without a package name are dropped, as is a second entry for the same package. A label
 * that is missing or not a string falls back to the package name, and a version of the wrong type is left out.
 */
export function normaliseApps(v: unknown): AppInfo[] {
  if (!Array.isArray(v)) return [];
  const seen = new Set<string>();
  const out: AppInfo[] = [];
  for (const a of v as unknown[]) {
    if (!a || typeof a !== 'object') continue;
    const { pkg, label, system, versionName, versionCode } = a as Record<string, unknown>;
    if (typeof pkg !== 'string' || !pkg || seen.has(pkg)) continue;
    seen.add(pkg);
    out.push({
      pkg,
      label: typeof label === 'string' && label ? label : pkg,
      system: system === true,
      versionName: typeof versionName === 'string' && versionName ? versionName : undefined,
      versionCode: typeof versionCode === 'number' && Number.isFinite(versionCode) ? versionCode : undefined,
    });
  }
  return out;
}

/**
 * The newest finished apps.scan in the history (newest first, as the server sends it) whose result parses. Null when
 * there is none: the server sends only the newest 200 commands, so that means no recent scan, not never scanned.
 */
export function latestScan(history: readonly CommandHistoryItem[]): AppScan | null {
  for (const c of history) {
    if (c.type !== 'apps.scan' || c.status !== 'done' || !c.detail) continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(c.detail);
    } catch {
      continue;
    }
    if (!parsed || typeof parsed !== 'object' || !Array.isArray((parsed as { apps?: unknown }).apps)) continue;
    return { apps: normaliseApps((parsed as { apps: unknown }).apps), scannedAt: c.completedAt };
  }
  return null;
}

/** Statuses a command ends in without a result (as api/deviceApps runForResult reads them). */
const FAILED = new Set(['failed', 'unsupported', 'expired']);

/**
 * Where a queued scan is, from the history: pending until the device answers (also while the history doesn't list
 * it yet), done, or failed with the device's or server's reason.
 */
export function scanProgress(
  history: readonly CommandHistoryItem[],
  id: string,
): { state: 'pending' } | { state: 'done' } | { state: 'failed'; reason: string } {
  const c = history.find((h) => String(h.id) === id);
  if (c?.status === 'done') return { state: 'done' };
  if (c && FAILED.has(c.status)) return { state: 'failed', reason: c.detail || `Device returned ${c.status}` };
  return { state: 'pending' };
}

/** By name, then package, ignoring case and accents. */
export function byLabel(a: AppInfo, b: AppInfo): number {
  return a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }) || a.pkg.localeCompare(b.pkg);
}

/** The apps a search (name or package) and the system-apps toggle leave. */
export function filterApps(apps: readonly AppInfo[], query: string, showSystem: boolean): AppInfo[] {
  const q = query.trim().toLowerCase();
  return apps.filter(
    (a) => (showSystem || !a.system) && (!q || a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q)),
  );
}

/**
 * One CSV field (RFC 4180). App names come from the device, so a value a spreadsheet would read as a formula
 * (=, +, -, @, tab, CR) gets a leading apostrophe.
 */
export function csvField(v: string | number | undefined): string {
  let s = v == null ? '' : String(v);
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function appsCsv(apps: readonly AppInfo[]): string {
  const header = ['name', 'package', 'versionName', 'versionCode', 'system'];
  const rows = apps.map((a) => [a.label, a.pkg, a.versionName, a.versionCode, a.system ? 'yes' : 'no']);
  return [header, ...rows].map((r) => r.map(csvField).join(',')).join('\r\n') + '\r\n';
}
