import { useEffect, useState } from 'react';
import { listApplications, type Application } from '../api/applications';
import { bulkQueueCommand } from '../api/commands';
import { getConfigurations, type Configuration } from '../api/configurations';
import { buildKioskPayload, kioskTheme, type KioskChoice } from './KioskEnterModal';
import { useToast } from '../ui/toast';

type Mode = 'launcher' | 'single';

export function BulkKioskModal({
  deviceIds, devices, onClose, onDone,
}: {
  deviceIds: number[];
  devices: { id: number; configurationId?: number }[];
  onClose: () => void;
  onDone: () => void;
}) {
  const toast = useToast();
  const n = deviceIds.length;
  const [apps, setApps] = useState<Application[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [mode, setMode] = useState<Mode>('launcher');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [exitMode, setExitMode] = useState<'gesture' | 'visible' | 'remote'>('gesture');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listApplications()
      .then((r) => { if (!cancelled) setApps(r); })
      .catch((e) => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load apps'); });
    return () => { cancelled = true; };
  }, []);

  function togglePkg(pkg: string) {
    setSelected((prev) => {
      if (mode === 'single') return new Set(prev.has(pkg) ? [] : [pkg]);
      const next = new Set(prev);
      next.has(pkg) ? next.delete(pkg) : next.add(pkg);
      return next;
    });
  }
  function switchMode(m: Mode) {
    setMode(m);
    if (m === 'single' && selected.size > 1) setSelected(new Set([Array.from(selected)[0]]));
  }

  const canApply = selected.size > 0 && (mode === 'launcher' || selected.size === 1);

  async function apply() {
    setBusy(true);
    try {
      const choice: KioskChoice = { mode, packages: Array.from(selected), exitMode, password };
      // One command per configuration, each carrying that configuration's launcher theme
      // (see KioskEnterModal). Without the configurations, fall back to no theme.
      const groups = new Map<number | undefined, number[]>();
      for (const id of deviceIds) {
        const cid = devices.find((d) => d.id === id)?.configurationId;
        groups.set(cid, [...(groups.get(cid) ?? []), id]);
      }
      const configs: Configuration[] = await getConfigurations().catch(() => []);
      let queued = 0;
      let skipped = 0;
      for (const [cid, ids] of groups) {
        const payload = buildKioskPayload(choice, kioskTheme(configs.find((c) => c.id === cid)));
        const res = await bulkQueueCommand(ids, {
          type: 'kiosk.enter', payload: JSON.stringify(payload),
        });
        queued += res.queued;
        skipped += res.skipped?.length ?? 0;
      }
      toast.push('ok', 'Kiosk queued',
        `Enter kiosk → ${queued} device${queued === 1 ? '' : 's'}` +
        (skipped ? ` (${skipped} skipped)` : '') + '.');
      onDone(); onClose();
    } catch (e) {
      toast.push('err', 'Kiosk failed', e instanceof Error ? e.message : '');
    } finally { setBusy(false); }
  }

  const filtered = (apps ?? []).filter(
    (a) => `${a.name} ${a.pkg}`.toLowerCase().includes(query.toLowerCase()));

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Enter kiosk on {n} device{n === 1 ? '' : 's'}</h3>
        <div className="kiosk-mode">
          <label><input type="radio" checked={mode === 'launcher'}
            onChange={() => switchMode('launcher')} /> Allowed apps (launcher grid)</label>
          <label><input type="radio" checked={mode === 'single'}
            onChange={() => switchMode('single')} /> Pin a single app</label>
        </div>

        <input className="field" placeholder="Filter Library apps" value={query}
               onChange={(e) => setQuery(e.target.value)} />
        {err && <p className="muted">{err}</p>}
        {!apps && !err && <p className="muted">Loading library…</p>}
        <div className="action-grid">
          {filtered.map((a) => (
            <button key={a.id ?? a.pkg}
                    className={`btn ${selected.has(a.pkg) ? 'btn-primary' : ''}`}
                    disabled={busy} title={a.pkg} onClick={() => togglePkg(a.pkg)}>
              {a.name}
            </button>
          ))}
        </div>

        <label className="field">
          <span>Exit mode</span>
          <select value={exitMode} onChange={(e) => setExitMode(e.target.value as typeof exitMode)}>
            <option value="gesture">Gesture</option>
            <option value="visible">Visible button</option>
            <option value="remote">Remote only</option>
          </select>
        </label>
        <label className="field">
          <span>Exit password</span>
          <input type="password" value={password} placeholder="optional"
                 onChange={(e) => setPassword(e.target.value)} />
        </label>

        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={busy || !canApply}
                  onClick={() => { void apply(); }}>
            {busy ? 'Queueing…' : `Enter kiosk on ${n}`}
          </button>
        </div>
      </div>
    </div>
  );
}
