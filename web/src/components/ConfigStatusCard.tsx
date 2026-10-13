import { DEVICE_RESET_REVISION, parseOutcomes, summarizeStatus, type ConfigStatus } from '../api/configSync';
import { fmtRelative, DASH } from '../ui/format';

const short = (r?: string | null): string => {
  if (r === DEVICE_RESET_REVISION) return 'Reset by device';
  return r ? r.slice(0, 8) : DASH;
};

/** Left-rail card: does this device run its configuration's current revision, and what happened per key. */
export function ConfigStatusCard({ status }: { status: ConfigStatus | null }) {
  const v = summarizeStatus(status);
  const outcomes = parseOutcomes(status?.lastCommand?.detail);
  return (
    <div>
      <div className="grp">Configuration status</div>
      <div className="row">
        <span className="k">State</span>
        <span className={`v chip tone-${v.tone}`}>{v.label}</span>
      </div>
      <div className="row">
        <span className="k">Applied</span>
        <span className="v mono" title={status?.appliedRevision ?? undefined}>
          {short(status?.appliedRevision)}
          {status?.appliedAt ? ` · ${fmtRelative(status.appliedAt)}` : ''}
        </span>
      </div>
      <div className="row">
        <span className="k">Current</span>
        <span className="v mono" title={status?.currentRevision ?? undefined}>
          {short(status?.currentRevision)}
        </span>
      </div>
      {v.label === 'Agent too old' ? (
        <p className="muted">Update the agent (Settings → Updates) to enable configuration enforcement.</p>
      ) : null}
      {outcomes ? (
        <ul className="cfg-outcomes">
          {Object.entries(outcomes.outcomes).map(([k, o]) => (
            <li key={k} className={outcomeClass(o)}>
              <span className="mono">{k}</span> <span>{o}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** applied = ok; unsupported = muted; deferred (protocol 1.4, not applied yet on purpose, does not block) = warn. */
function outcomeClass(outcome: string): string {
  if (outcome === 'applied') return 'ok';
  if (outcome === 'unsupported') return 'muted';
  if (outcome.startsWith('deferred')) return 'warn';
  return 'alert';
}
