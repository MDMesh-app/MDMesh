import type { ConfigSyncSummary } from '../../api/configSync';
import type { BarSegment } from '../../ui';

export type SyncTone = 'ok' | 'warn' | 'idle';

export interface SyncLine {
  segments: BarSegment[];
  text: string;
  tone: SyncTone;
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/**
 * The sync bar and line under a configuration card. The summary's four counts are disjoint
 * (in sync + out of sync + agent too old + never reported = total). Out of sync reads "applying", as on the classic
 * SyncBar; a "failed" split needs data the server doesn't send.
 */
export function syncLine(s: ConfigSyncSummary | undefined, unavailable: boolean): SyncLine {
  if (!s || s.total === 0) {
    return {
      segments: [],
      text: unavailable && !s ? 'Sync status unavailable' : 'Not used by any device yet',
      tone: 'idle',
    };
  }
  const segments: BarSegment[] = [
    { value: s.inSync, tone: 'ok' },
    { value: s.outOfSync + s.unsupported, tone: 'warn' },
    { value: s.neverSeen, tone: 'idle' },
  ];
  if (s.inSync === s.total) {
    return { segments, text: s.total === 1 ? 'In sync' : `All ${s.total} in sync`, tone: 'ok' };
  }
  const parts = [
    `${s.inSync} of ${s.total} in sync`,
    s.outOfSync > 0 ? `${s.outOfSync} applying` : null,
    s.unsupported > 0 ? `${plural(s.unsupported, 'agent')} too old` : null,
    s.neverSeen > 0 ? `${s.neverSeen} not reported yet` : null,
  ].filter(Boolean);
  const tone: SyncTone = s.outOfSync + s.unsupported > 0 ? 'warn' : 'idle';
  return { segments, text: parts.join(' · '), tone };
}

/** "Used by 2 devices, both in sync." (editor subtitle). */
export function usedByLine(s: ConfigSyncSummary | undefined): { count: number; rest: string } | null {
  if (!s || s.total === 0) return null;
  const n = s.total;
  let rest: string;
  if (s.inSync === n) rest = n === 1 ? ', in sync.' : n === 2 ? ', both in sync.' : ', all in sync.';
  else if (s.inSync === 0) rest = ', none in sync yet.';
  else rest = `, ${s.inSync} in sync.`;
  return { count: n, rest };
}

export { plural };
