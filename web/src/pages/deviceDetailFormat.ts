// Pure display helpers for the device detail page. No React here.
import { DEVICE_RESET_REVISION, type ConfigStatus } from '../api/configSync';
import type { Outcome } from '../data/configOutcomes';
import { LOCATION_HISTORY_LIMIT, type LocationFix } from '../api/deviceLocations';
import { formatRelative } from '../ui/RelativeTime';
import type { StatusKind, StatusTone } from '../ui/Status';

export const DASH = '—';

/** A telemetry value as a single string (arrays → first element, as the classic page reads IMEI lists). */
export function teleStr(v: unknown): string | undefined {
  const one = Array.isArray(v) ? v[0] : v;
  if (one == null || one === '') return undefined;
  // Only scalars: a device sending an object or a nested list gets "—", not "[object Object]".
  return typeof one === 'string' || typeof one === 'number' || typeof one === 'boolean' ? String(one) : undefined;
}

/** A telemetry section as a plain object ({} for anything else: a device can send a string or a list). */
export function teleObj(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

export function teleNum(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
}

/** Decimal gigabytes (what Android's own storage screen shows). "18.2", "32", "0.4". */
function gb(bytes: number): string {
  const v = bytes / 1e9;
  const s = v >= 100 ? v.toFixed(0) : v.toFixed(1);
  return s.endsWith('.0') ? s.slice(0, -2) : s;
}

/** Bytes as "3.8 GB", "512 MB", "12 KB". */
export function fmtBytes(bytes: number | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return DASH;
  if (bytes >= 1e9) return `${gb(bytes)} GB`;
  if (bytes >= 1e6) return `${Math.round(bytes / 1e6)} MB`;
  if (bytes >= 1e3) return `${Math.round(bytes / 1e3)} KB`;
  return `${bytes} B`;
}

/** Storage as used / total ("18.2 / 32 GB"), from the agent's totalStorageBytes and freeStorageBytes. */
export function fmtStorage(total: number | undefined, free: number | undefined): string {
  if (total != null && total > 0) {
    if (free != null && free >= 0 && free <= total) return `${gb(total - free)} / ${gb(total)} GB`;
    return `${gb(total)} GB`;
  }
  if (free != null && free >= 0) return `${fmtBytes(free)} free`;
  return DASH;
}

/** "Always-on" / "Battery-saver" for the agent's power mode. */
export function powerLabel(mode?: string | null): string | undefined {
  if (mode === 'alwaysOn') return 'Always-on';
  if (mode === 'adaptive') return 'Battery-saver';
  return undefined;
}

/** The agent's networkType (wifi | cellular | none) as a label. */
export function networkLabel(type?: string): string | undefined {
  if (!type) return undefined;
  const t = type.toLowerCase();
  if (t === 'wifi' || t === 'wi-fi') return 'Wi-Fi';
  if (t === 'cellular' || t === 'mobile') return 'Mobile data';
  if (t === 'none') return 'No network';
  if (t === 'ethernet') return 'Ethernet';
  return type;
}

/** Android location provider → label; the raw value goes in a tooltip. */
export function providerLabel(p?: unknown): string | undefined {
  if (typeof p !== 'string' || !p) return undefined;
  const map: Record<string, string> = { fused: 'Fused', gps: 'GPS', network: 'Network', passive: 'Passive' };
  return map[p.toLowerCase()] ?? p;
}

/** A timestamp (epoch ms) the browser can turn into a date, or undefined (bad telemetry must not throw). */
export function validTime(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) && v > 0 && !Number.isNaN(new Date(v).getTime()) ? v : undefined;
}

/** `<time dateTime>` for an epoch-ms value, or undefined when it isn't a valid date. */
export function isoTime(v: unknown): string | undefined {
  const t = validTime(v);
  return t === undefined ? undefined : new Date(t).toISOString();
}

/** A location as the page shows it: numbers checked, provider a string, time a valid date. */
export interface CleanLocation {
  lat: number;
  lon: number;
  accuracyM?: number;
  provider?: string;
  capturedAt?: number;
}

const isLat = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 90;
const isLon = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 180;
const accuracyOf = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : undefined);
const providerOf = (v: unknown) => (typeof v === 'string' && v ? v : undefined);

/** The telemetry's dynamic.location, or undefined when it has no usable coordinates. */
export function cleanLocation(v: unknown): CleanLocation | undefined {
  if (!v || typeof v !== 'object') return undefined;
  const l = v as Record<string, unknown>;
  if (!isLat(l.lat) || !isLon(l.lon)) return undefined;
  return { lat: l.lat, lon: l.lon, accuracyM: accuracyOf(l.accuracyM), provider: providerOf(l.provider), capturedAt: validTime(l.capturedAt) };
}

/** The location history with malformed fixes dropped (no coordinates or no valid time) and fields type-checked. */
export function cleanFixes(list: unknown): LocationFix[] {
  if (!Array.isArray(list)) return [];
  const out: LocationFix[] = [];
  for (const raw of list) {
    if (!raw || typeof raw !== 'object') continue;
    const f = raw as Record<string, unknown>;
    const capturedAt = validTime(f.capturedAt);
    if (!isLat(f.lat) || !isLon(f.lon) || capturedAt === undefined) continue;
    out.push({ ...(f as unknown as LocationFix), lat: f.lat, lon: f.lon, capturedAt, accuracy: accuracyOf(f.accuracy), provider: providerOf(f.provider) });
  }
  return out;
}

/** The Location tab's time ranges, in hours back from now. 0 is All: the newest fixes, however old. */
export const LOCATION_RANGES: ReadonlyArray<{ value: number; label: string }> = [
  { value: 1, label: '1h' },
  { value: 24, label: '24h' },
  { value: 168, label: '7d' },
  { value: 0, label: 'All' },
];

/** The `since` of a range (listLocations): `hours` before `now`, or 0 for All. */
export function locationSince(hours: number, now: number): number {
  return hours > 0 ? now - hours * 3600 * 1000 : 0;
}

/** "the last hour", "the last 24 hours", "the last 7 days"; undefined for All. */
export function rangePhrase(hours: number): string | undefined {
  if (hours <= 0) return undefined;
  if (hours === 1) return 'the last hour';
  return hours > 24 && hours % 24 === 0 ? `the last ${hours / 24} days` : `the last ${hours} hours`;
}

/** "12 fixes", or "newest 500 in this range" when the server's cap cut the list (the range holds more). */
export function fixesCount(count: number, hours: number): string {
  if (count >= LOCATION_HISTORY_LIMIT) return hours > 0 ? `newest ${count} in this range` : `newest ${count} fixes`;
  return `${count} fix${count === 1 ? '' : 'es'}`;
}

/** "6m", "2h", "just now": an age without the trailing "ago" (rail "Fix age"). */
export function fmtAge(ms: number, now: number): string {
  return formatRelative(ms, now, 'short').replace(/ ago$/, '');
}

/** Recent-commands time: "3m ago", "2h ago" within a day, then "Yesterday", "3 days ago", the date. */
export function fmtWhen(ms: number, now: number): string {
  return now - ms < 24 * 3600 * 1000 ? formatRelative(ms, now, 'short') : formatRelative(ms, now, 'long');
}

export function capitalise(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

/** "Samsung SM-X200" (manufacturer, then model unless the model already starts with it). */
export function makeModel(manufacturer?: string, model?: string): string | undefined {
  if (!model && !manufacturer) return undefined;
  if (!manufacturer) return model;
  if (!model) return capitalise(manufacturer);
  return model.toLowerCase().startsWith(manufacturer.toLowerCase()) ? model : `${capitalise(manufacturer)} ${model}`;
}

/* ---------------------------------------------------------------------------------------------------------------
   Configuration status
   --------------------------------------------------------------------------------------------------------------- */

/** Short revision for display ("8b04d7aa"), "Reset by device" for the agent's self-heal marker. */
export function shortRevision(r?: string | null): string {
  if (r === DEVICE_RESET_REVISION) return 'Reset by device';
  return r ? r.slice(0, 8) : DASH;
}

/** Whether the configuration panel shows in full (anything but a clean "In sync"). */
export function configNeedsPanel(s: ConfigStatus | null, outcomes: Outcome[]): boolean {
  if (!s || s.configurationId == null) return false;
  if (!s.supported || !s.inSync) return true;
  return outcomes.some((o) => o.kind !== 'applied');
}

/** Command status → status pill (Done / Failed / Expired). */
export function commandStatus(status: string): { kind: StatusKind; label?: string } {
  switch (status) {
    case 'done':
      return { kind: 'done' };
    case 'failed':
      return { kind: 'failed' };
    case 'expired':
      return { kind: 'expired' };
    case 'unsupported':
      return { kind: 'notSupported' };
    case 'pending':
      return { kind: 'pending' };
    case 'delivered':
      return { kind: 'pending', label: 'Delivered' };
    case 'accepted':
      return { kind: 'pending', label: 'Running' };
    default:
      return { kind: 'pending', label: capitalise(status) };
  }
}

export function eventTone(type: string): StatusTone {
  if (type === 'kioskPinLockout') return 'alert';
  if (type === 'lowBattery' || type === 'kioskCrashLoop') return 'warn';
  if (type === 'enrolled' || type === 'appInstalled') return 'ok';
  return 'idle';
}
