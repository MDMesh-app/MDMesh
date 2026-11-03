// Fleet health rules shared by the Overview, the Devices list and the device page (the v0.3 console's DashboardPage rules):
// a device is offline by recency of its last check-in; an online device whose configuration needs an admin's look
// is "attention". Pure functions, no React.
import { DEVICE_RESET_LABEL, SOME_DEFERRED_LABEL, type ConfigSyncSummary } from '../api/configSync';
import type { DeviceView } from '../api/devices';
import { isOnline } from './presence';

/** A configuration verdict (summarizeStatus output). */
export interface SyncVerdict {
  tone: 'ok' | 'warn' | 'alert' | 'idle';
  label: string;
}

export type Presence = 'online' | 'attention' | 'offline';

/** How complete the configuration check is: 'partial' when the probe was capped or some probes failed. */
export type SyncCheck = 'pending' | 'failed' | 'partial' | 'complete';

/** Online devices whose configuration state is probed per device (one request each). */
export const ATTENTION_PROBE_CAP = 50;

/** Kinds of configuration problem worth an admin's look; "Applying…" and "Not reported yet" are transient. */
export type IssueKind = 'failed' | 'outOfSync' | 'agentTooOld' | 'deferred' | 'reset';

export const ISSUE_ORDER: ReadonlyArray<IssueKind> = ['failed', 'outOfSync', 'agentTooOld', 'deferred', 'reset'];

export function issueKind(v: SyncVerdict | null | undefined): IssueKind | null {
  if (!v) return null;
  if (v.tone === 'alert') return 'failed';
  if (v.label === 'Out of sync') return 'outOfSync';
  if (v.label === 'Agent too old') return 'agentTooOld';
  if (v.label === SOME_DEFERRED_LABEL) return 'deferred';
  if (v.label === DEVICE_RESET_LABEL) return 'reset';
  return null;
}

export function isConfigIssue(v: SyncVerdict | null | undefined): boolean {
  return issueKind(v) !== null;
}

/** Why a device needs attention, in the same words on every page. */
export const ISSUE_REASON: Record<IssueKind, string> = {
  failed: 'Configuration failed to apply',
  outOfSync: 'Configuration out of sync',
  agentTooOld: 'Agent too old for configuration sync',
  deferred: 'Some settings deferred',
  reset: 'Wi-Fi turned back on by the device',
};

export const ISSUE_TONE: Record<IssueKind, 'alert' | 'warn'> = {
  failed: 'alert',
  outOfSync: 'warn',
  agentTooOld: 'warn',
  deferred: 'warn',
  reset: 'warn',
};

/** Offline first (recency of the last check-in); a still-reporting device with a configuration issue is attention. */
export function presenceOf(lastUpdate: number | undefined, hasIssue: boolean, now?: number): Presence {
  if (!isOnline(lastUpdate, now)) return 'offline';
  return hasIssue ? 'attention' : 'online';
}

/** A configuration whose devices are all in sync: nothing out of sync, unsupported, never seen or deferred. */
export function isCleanSummary(s: ConfigSyncSummary): boolean {
  return s.outOfSync === 0 && s.unsupported === 0 && s.neverSeen === 0 && (s.deferred ?? 0) === 0;
}

/**
 * Online devices whose configuration state has to be asked per device: those in a configuration the summary does not
 * call clean (or does not mention). The caller probes at most ATTENTION_PROBE_CAP of them.
 */
export function suspectDevices(devices: ReadonlyArray<DeviceView>, summary: ReadonlyArray<ConfigSyncSummary>, now?: number): DeviceView[] {
  const byConfig = new Map(summary.map((s) => [s.configurationId, s]));
  return devices.filter((d) => {
    if (d.configurationId == null || !isOnline(d.lastUpdate, now)) return false;
    const s = byConfig.get(d.configurationId);
    return !s || !isCleanSummary(s);
  });
}
