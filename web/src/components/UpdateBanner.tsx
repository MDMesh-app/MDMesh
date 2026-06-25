import { useEffect, useRef, useState } from 'react';
import { getUpdateStatus, applyUpdate, isApplyTerminal, type UpdateStatus } from '../api/updates';

const PHASE_LABEL: Record<string, string> = {
  authorizing: 'Authorizing…',
  backup: 'Backing up database…',
  pull: 'Downloading new images…',
  recreate: 'Restarting services…',
  healthcheck: 'Verifying health…',
  done: 'Update complete',
  rollback: 'Rolling back…',
  rolled_back: 'Update failed — rolled back',
  failed: 'Update failed',
};

/** "Update available" banner + one-click apply with live phase progress. Talks to the decoupled
 *  supervisor (origin /update/*), so it works even while the API server is mid-restart. */
export function UpdateBanner() {
  const [s, setS] = useState<UpdateStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const poke = useRef<() => void>(() => {});

  useEffect(() => {
    let on = true;
    const loop = async () => {
      const x = await getUpdateStatus();
      if (!on) return;
      setS(x);
      const active = !!x?.apply && !isApplyTerminal(x.apply.phase);
      clearTimeout(timer.current);
      timer.current = setTimeout(loop, active ? 3000 : 30 * 60 * 1000); // poll fast while an apply runs
    };
    poke.current = () => { clearTimeout(timer.current); void loop(); };
    void loop();
    return () => { on = false; clearTimeout(timer.current); };
  }, []);

  if (!s) return null;
  const apply = s.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');

  // While an apply is running (or just failed), that takes over the banner.
  if (apply && (active || failed)) {
    return (
      <div className={`update-banner ${failed ? 'ub-fail' : 'ub-busy'}`}>
        <span>
          {failed ? '⚠ ' : ''}{PHASE_LABEL[apply.phase] || apply.phase}
          {apply.toVersion ? <span className="ub-ch"> → v{apply.toVersion}</span> : null}
          {apply.error ? <span className="ub-warn"> · {apply.error}</span> : null}
        </span>
        {failed
          ? <a className="btn btn-sm" href="/recovery">Recovery…</a>
          : <span className="ub-spin" aria-label="working" />}
      </div>
    );
  }

  if (!s.updateAvailable) return null;

  const onClick = async () => {
    if (!window.confirm(
      `Update to v${s.latest}?\n\nThe server restarts briefly. The database is backed up first and the `
      + `update rolls back automatically if it fails.`,
    )) return;
    setBusy(true);
    setErr(null);
    const r = await applyUpdate();
    setBusy(false);
    if (!r.ok) setErr(r.error || 'Failed to start update');
    else poke.current(); // re-poll immediately so progress shows right away
  };

  return (
    <div className="update-banner">
      <span>
        Update available — <b>v{s.latest}</b>
        {s.verified ? <span className="ub-ok"> ✓ verified</span> : <span className="ub-warn"> · unverified</span>}
        {s.channel ? <span className="ub-ch"> ({s.channel})</span> : null}
        {err ? <span className="ub-warn"> · {err}</span> : null}
      </span>
      <button className="btn btn-sm" onClick={onClick} disabled={busy || !s.verified}>
        {busy ? 'Starting…' : 'Update…'}
      </button>
    </div>
  );
}
