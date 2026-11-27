import { apiClient } from './client';
import type { DeviceEvent } from './events';

export interface ConfigStatus {
  configurationId?: number | null;
  currentRevision?: string | null;
  appliedRevision?: string | null;
  appliedAt?: number | null;
  inSync: boolean;
  supported: boolean;
  lastCommand?: { id: number; status: string; detail?: string | null; createdAt?: number; completedAt?: number } | null;
  /**
   * The kiosk background image the device's configuration specifies (absent without one, and on older servers). Not
   * what the device shows: agents don't report that yet.
   */
  kioskBackgroundImage?: {
    url: string;
    sha256: string;
    /** As config.apply 1.10 sends them: only when not the default (fill, centre). */
    fit?: 'fit';
    focalX?: number;
    focalY?: number;
  } | null;
}

export interface ConfigSyncSummary {
  configurationId: number; total: number; inSync: number; outOfSync: number; unsupported: number; neverSeen: number;
  /** Of inSync: devices whose latest config.apply deferred some settings (absent on older servers). */
  deferred?: number;
  /** Wiped devices still on this configuration: outside total, but they block deleting it (absent on older servers). */
  wiped?: number;
}

/** Shape of `lastCommand.detail` for config.apply (proto/payloads/config-apply-result.schema.json). */
export interface ConfigOutcomes { revision?: string; outcomes: Record<string, string> }

export function getConfigStatus(deviceId: number | string): Promise<ConfigStatus | null> {
  return apiClient.get<ConfigStatus | null>(`/private/agent/v1/devices/${deviceId}/configStatus`);
}

export function getSyncSummary(): Promise<ConfigSyncSummary[]> {
  return apiClient.get<ConfigSyncSummary[]>('/private/agent/v1/configurations/syncSummary');
}

export function parseOutcomes(detail: string | null | undefined): ConfigOutcomes | null {
  if (!detail) return null;
  try {
    const v = JSON.parse(detail) as Partial<ConfigOutcomes>;
    return v && typeof v === 'object' && v.outcomes && typeof v.outcomes === 'object' ? { revision: v.revision, outcomes: v.outcomes } : null;
  } catch { return null; }
}

/**
 * Applied revision an agent reports when its configuration is out of date on the device, so the server re-sends it:
 * after turning Wi-Fi back on by itself (Wi-Fi radio self-heal, 0.3.4+), recovering storage, lifting Factory reset:
 * Block for a wipe, holding a newer configuration that didn't fully apply, failing to re-apply it after a restart, or
 * holding none (its store was lost, or nothing was applied yet; 0.6.0+).
 */
export const DEVICE_RESET_REVISION = '0'.repeat(64);
export const DEVICE_RESET_LABEL = 'Out of date on the device';

/** Device events that leave the configuration out of date on the device, and what each means for it. */
const STALE_CAUSES: Readonly<Record<string, string>> = {
  storageRecovered: 'The device restored settings its storage had damaged.',
  wipeNotStarted: 'A factory reset didn’t start, so the device went back to its settings.',
  configReapplyFailed: 'The device couldn’t fully apply its configuration again after a restart.',
};

/**
 * How long before the device first reported the configuration out of date an event may be and still explain it. The
 * agent records the event just before a check-in (the heartbeat's is at most about 10 minutes away), and the event
 * carries the device's clock while the report time is the server's. An older one (a device offline for hours after it)
 * names no cause rather than a wrong one.
 */
export const STALE_CAUSE_WINDOW_MS = 15 * 60_000;

/**
 * Why the configuration is out of date on the device: the latest event that explains it, from around [appliedAt] (when
 * the device first reported it, ConfigStatus.appliedAt) on, and after the last config.apply that completed. That apply
 * re-applied the whole configuration, so an event before it was resolved, and the device is out of date again for
 * another reason (PR #227 review). Only a config.apply brings it back in date: the device's own re-apply leaves it
 * marked out of date. Null when none explains it: the Wi-Fi radio self-heal records no event, and an older event would
 * mislabel today's state. Events carry the device's clock, the rest the server's, so this assumes a roughly right device
 * clock: one running fast can still name an event the apply resolved.
 */
export function staleCause(
  events: ReadonlyArray<DeviceEvent> | undefined,
  status: Pick<ConfigStatus, 'appliedAt' | 'lastCommand'>,
): string | null {
  if (status.appliedAt == null) return null;
  const since = status.appliedAt - STALE_CAUSE_WINDOW_MS;
  const applied = status.lastCommand?.status === 'done' ? status.lastCommand.completedAt : undefined;
  let latest: DeviceEvent | null = null;
  for (const e of events ?? []) {
    if (!(e.type in STALE_CAUSES) || e.ts < since || (applied != null && e.ts <= applied)) continue;
    if (!latest || e.ts > latest.ts) latest = e;
  }
  return latest ? STALE_CAUSES[latest.type] : null;
}

/** In sync, but the agent skipped some settings on purpose (e.g. Wi-Fi radio Off while Wi-Fi is the only network). */
export const SOME_DEFERRED_LABEL = 'In sync (some settings deferred)';

/** One-line verdict for badges. Pure, so it can be unit-tested. */
export function summarizeStatus(s: ConfigStatus | null): { tone: 'ok' | 'warn' | 'alert' | 'idle'; label: string } {
  if (!s || s.configurationId == null) return { tone: 'idle', label: 'No configuration' };
  if (!s.supported) return { tone: 'warn', label: 'Agent too old' };
  if (s.inSync) {
    const outcomes = parseOutcomes(s.lastCommand?.detail)?.outcomes ?? {};
    const deferred = Object.values(outcomes).some((o) => o.startsWith('deferred'));
    return deferred ? { tone: 'warn', label: SOME_DEFERRED_LABEL } : { tone: 'ok', label: 'In sync' };
  }
  const st = s.lastCommand?.status;
  if (st === 'pending' || st === 'delivered') return { tone: 'warn', label: 'Applying…' };
  if (st === 'failed') return { tone: 'alert', label: 'Apply failed' };
  if (!s.appliedRevision) return { tone: 'idle', label: 'Not reported yet' };
  if (s.appliedRevision === DEVICE_RESET_REVISION) return { tone: 'warn', label: DEVICE_RESET_LABEL };
  return { tone: 'warn', label: 'Out of sync' };
}
