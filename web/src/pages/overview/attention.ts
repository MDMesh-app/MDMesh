// Overview wording on top of the shared fleet health rules (web/src/data/fleetHealth.ts): the attention row's text,
// tone and action, and the subtitle's "mostly for …" phrase.
import type { DeviceView } from '../../api/devices';
import { ISSUE_REASON, ISSUE_TONE, issueKind, presenceOf, type IssueKind, type SyncVerdict } from '../../data/fleetHealth';
import type { StatusTone } from '../../ui';

export type Bucket = 'online' | 'attention' | 'offline';

/** A device's configuration issue (summarizeStatus output that isConfigIssue accepted). */
export type ConfigIssue = SyncVerdict;

/** Offline first (recency of last check-in); a still-reporting device whose configuration didn't apply is attention. */
export function bucketOf(d: DeviceView, issues: Record<number, ConfigIssue>, now?: number): Bucket {
  return presenceOf(d.lastUpdate, !!issues[d.id], now);
}

export type RowAction = 'sync' | 'agent' | 'open';

export interface AttentionReason {
  /** Board wording for the row. */
  text: string;
  tone: StatusTone;
  action: RowAction;
  /** Subtitle phrase: "… need attention, mostly for <phrase>". */
  phrase: string;
}

const ROW: Record<IssueKind, { action: RowAction; phrase: string }> = {
  failed: { action: 'open', phrase: 'a configuration that failed to apply' },
  outOfSync: { action: 'sync', phrase: 'a configuration out of sync' },
  agentTooOld: { action: 'agent', phrase: 'an out-of-date agent' },
  deferred: { action: 'open', phrase: 'deferred settings' },
  reset: { action: 'sync', phrase: 'Wi-Fi turned back on by the device' },
};

/** Row wording, tone and action for a configuration issue (summarizeStatus output) or offline. */
export function reasonFor(issue: ConfigIssue | undefined): AttentionReason {
  if (!issue) return { text: 'Offline', tone: 'idle', action: 'open', phrase: 'being offline' };
  const k = issueKind(issue);
  if (!k) return { text: issue.label, tone: issue.tone, action: 'open', phrase: issue.label.toLowerCase() };
  return { text: ISSUE_REASON[k], tone: ISSUE_TONE[k], ...ROW[k] };
}

/** The most common reason among the attention devices, and whether it covers all of them or most (> half). */
export function topReason(
  devices: ReadonlyArray<DeviceView>,
  issues: Record<number, ConfigIssue>,
  now: number,
): { phrase: string; share: 'all' | 'most' } | null {
  const tally = new Map<string, number>();
  let n = 0;
  for (const d of devices) {
    if (bucketOf(d, issues, now) !== 'attention') continue;
    n++;
    const p = reasonFor(issues[d.id]).phrase;
    tally.set(p, (tally.get(p) ?? 0) + 1);
  }
  let best: [string, number] | null = null;
  for (const e of tally) if (!best || e[1] > best[1]) best = e;
  if (!best || n === 0) return null;
  if (best[1] === n) return { phrase: best[0], share: 'all' };
  if (best[1] * 2 > n) return { phrase: best[0], share: 'most' };
  return null;
}
