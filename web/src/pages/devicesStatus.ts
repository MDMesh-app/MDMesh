// Devices-list wording for the shared fleet health rules (web/src/data/fleetHealth.ts, useFleetSync.ts): the sync
// line's status kind and label, and the attention strip's filter chips. No per-device battery / network / agent
// version here: those are not in the list API.
import type { IssueKind, SyncVerdict } from '../data/fleetHealth';
import type { StatusKind } from '../ui';

/** Attention-strip filter chips: "1 config failed", "2 out of sync"… */
export const ISSUE_CHIP: Record<IssueKind, { tone: 'alert' | 'warn'; text: (n: number) => string }> = {
  failed: { tone: 'alert', text: (n) => `${n} config failed` },
  outOfSync: { tone: 'warn', text: (n) => `${n} out of sync` },
  agentTooOld: { tone: 'warn', text: (n) => `${n} agent too old` },
  deferred: { tone: 'warn', text: (n) => `${n} settings deferred` },
  reset: { tone: 'warn', text: (n) => `${n} reset by device` },
};

/** StatusKind for the sync line, by tone (the label is passed separately, so product wording is kept). */
export function syncKind(s: SyncVerdict): StatusKind {
  switch (s.tone) {
    case 'ok':
      return 'inSync';
    case 'warn':
      return 'applying';
    case 'alert':
      return 'applyFailed';
    default:
      return 'notReported';
  }
}

/** Sync-line wording: the board's "Not reported" for summarizeStatus's "Not reported yet". */
export function syncLabel(s: SyncVerdict): string {
  return s.label === 'Not reported yet' ? 'Not reported' : s.label;
}
