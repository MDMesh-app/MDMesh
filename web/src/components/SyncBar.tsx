import type { ConfigSyncSummary } from '../api/configSync';

/** "N of M in sync" for one configuration, same visual language as the rollout cohort bar. */
export function SyncBar({ s }: { s: ConfigSyncSummary | undefined }) {
  if (!s || s.total === 0) return <div className="cfg-sync muted">No devices</div>;
  const pct = Math.round((s.inSync / s.total) * 100);
  const extras = [
    s.outOfSync > 0 ? `${s.outOfSync} applying` : null,
    s.neverSeen > 0 ? `${s.neverSeen} not reported` : null,
    s.unsupported > 0 ? `${s.unsupported} too old` : null,
  ].filter(Boolean).join(' · ');
  return (
    <div className="cfg-sync" title={extras || undefined}>
      <span className="mono">{s.inSync}/{s.total} in sync</span>
      <div className="rollout-track"><div className="rollout-fill" style={{ width: `${pct}%` }} /></div>
      {s.unsupported > 0 ? <span className="ub-warn">{s.unsupported} too old</span> : null}
    </div>
  );
}
