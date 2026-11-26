// Small pieces the Policies pages share.
import { Badge, StatusPill, type StatusKind } from '@mdmesh/console';
import { brandLabel, type AppMode, type SyncState, type Verdict } from './model';

const SYNC_KIND: Record<SyncState, { status: StatusKind; label: string }> = {
  inSync: { status: 'inSync', label: 'Applied' },
  applying: { status: 'applying', label: 'Applying' },
  failed: { status: 'applyFailed', label: 'Failed' },
  agentTooOld: { status: 'agentTooOld', label: 'Agent too old' },
  notReported: { status: 'notReported', label: 'Not reported' },
  outOfSync: { status: 'applying', label: 'Waiting' },
  unknown: { status: 'notReported', label: 'Unknown' },
};

export function SyncPill({ state }: { state: SyncState }) {
  const k = SYNC_KIND[state];
  return <StatusPill status={k.status} label={k.label} />;
}

const VERDICT: Record<Verdict, { status: StatusKind; label: string }> = {
  compliant: { status: 'applied', label: 'Compliant' },
  attention: { status: 'attention', label: 'Needs attention' },
  unknown: { status: 'notReported', label: 'Checking' },
};

export function VerdictPill({ verdict }: { verdict: Verdict }) {
  const v = VERDICT[verdict];
  return <StatusPill status={v.status} label={v.label} />;
}

export function BrandChips({ brands, empty = '—' }: { brands: readonly string[]; empty?: string }) {
  if (!brands.length) return <span className="x-policies-muted">{empty}</span>;
  return (
    <span className="x-policies-chips">
      {brands.map((b) => (
        <Badge key={b} tone="outline" size="sm">
          {brandLabel(b)}
        </Badge>
      ))}
    </span>
  );
}

export const APP_MODE_LABEL: Record<AppMode, string> = {
  off: 'Off',
  report: 'Report only',
  remove: 'Report + one-click remove',
  auto: 'Auto-remove',
};

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

export function errorText(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback;
}

/**
 * A future moment as a date and time ("15 Nov 2026, 15:20"). Core's RelativeTime only says how long ago something
 * was, and shows any future time as "just now", so it can't be used for an expiry or a next run.
 */
export function dateTimeText(ms: number): string {
  try {
    return new Date(ms).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
  } catch {
    return new Date(ms).toLocaleString();
  }
}
