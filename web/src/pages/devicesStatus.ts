// Devices-list wording for the shared fleet health rules (web/src/data/fleetHealth.ts, useFleetSync.ts): the sync
// line's status kind and label, and the attention strip's filter chips. No per-device battery / network / agent
// version here: those are not in the list API.
import type { IntlShape } from 'react-intl';
import { AGENT_UPDATE_NEEDED } from '../api/configSync';
import { sectionList } from '../data/configSections';
import type { IssueKind, SyncVerdict } from '../data/fleetHealth';
import type { StatusKind } from '../ui';

/** Attention-strip filter chips: "1 configuration failed", "2 out of sync"… */
export const ISSUE_CHIP: Record<IssueKind, { tone: 'alert' | 'warn'; text: (n: number) => string }> = {
  failed: { tone: 'alert', text: (n) => `${n} configuration failed` },
  outOfSync: { tone: 'warn', text: (n) => `${n} out of sync` },
  agentTooOld: { tone: 'warn', text: (n) => `${n} agent too old` },
  deferred: { tone: 'warn', text: (n) => `${n} settings deferred` },
  reset: { tone: 'warn', text: (n) => `${n} out of date on the device` },
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

/** Sync-line wording: the board's "Not reported" for summarizeStatus's "Not reported yet", and the sections' names. */
export function syncLabel(intl: IntlShape, s: SyncVerdict): string {
  if (s.label === AGENT_UPDATE_NEEDED) {
    return intl.formatMessage(
      { id: 'configSync.agentUpdate.label', defaultMessage: 'Agent update needed for: {sections}' },
      { sections: sectionList(intl, s.sections ?? []) },
    );
  }
  return s.label === 'Not reported yet' ? 'Not reported' : s.label;
}
