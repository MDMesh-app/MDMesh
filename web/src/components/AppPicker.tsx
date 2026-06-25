import { useMemo, useState } from 'react';
import { appCategory, type AppCategory, type Application } from '../api/applications';

const CAT_LABEL: Record<AppCategory, string> = {
  uploaded: 'Uploaded',
  system: 'System',
  web: 'Web apps',
};
const CAT_ORDER: AppCategory[] = ['uploaded', 'system', 'web'];

/** A searchable, categorised, multi-select app picker. Defaults to "Uploaded". */
export function AppPicker({
  apps,
  excludeIds,
  onAdd,
  onClose,
}: {
  apps: Application[];
  excludeIds: Set<number>;
  onAdd: (apps: Application[]) => void;
  onClose: () => void;
}) {
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState<Set<number>>(new Set());

  const available = useMemo(
    () => apps.filter((a) => !excludeIds.has(a.id)),
    [apps, excludeIds],
  );

  const counts = useMemo(() => {
    const c: Record<AppCategory, number> = { uploaded: 0, system: 0, web: 0 };
    for (const a of available) c[appCategory(a)]++;
    return c;
  }, [available]);

  const tabs = CAT_ORDER.filter((c) => counts[c] > 0);
  const [cat, setCat] = useState<AppCategory>(
    counts.uploaded > 0 ? 'uploaded' : (tabs[0] ?? 'uploaded'),
  );

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return available
      .filter((a) => appCategory(a) === cat)
      .filter((a) => !needle || `${a.name} ${a.pkg}`.toLowerCase().includes(needle))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [available, cat, q]);

  function toggle(id: number) {
    setPicked((p) => {
      const n = new Set(p);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  function add() {
    const chosen = apps.filter((a) => picked.has(a.id));
    if (chosen.length) onAdd(chosen);
    onClose();
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal app-picker" onClick={(e) => e.stopPropagation()}>
        <h3>Add apps</h3>

        <div className="picker-top">
          <div className="dv-search" style={{ flex: 1 }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="7" />
              <path d="M21 21l-4-4" />
            </svg>
            <input type="search" placeholder="Search apps" value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
          </div>
          <span className="seg">
            {tabs.map((c) => (
              <button key={c} className={cat === c ? 'on' : ''} onClick={() => setCat(c)}>
                {CAT_LABEL[c]} <span style={{ opacity: 0.6 }}>{counts[c]}</span>
              </button>
            ))}
          </span>
        </div>

        <div className="picker-list">
          {shown.length === 0 ? (
            <div className="cfg-empty" style={{ padding: 20 }}>No apps here.</div>
          ) : (
            shown.map((a) => {
              const on = picked.has(a.id);
              return (
                <label key={a.id} className={`picker-row ${on ? 'on' : ''}`}>
                  <input type="checkbox" className="dev-check" checked={on} onChange={() => toggle(a.id)} />
                  <span className="picker-ic" aria-hidden="true">{(a.name.trim()[0] ?? '?').toUpperCase()}</span>
                  <span className="picker-meta">
                    <span className="picker-nm">{a.name}</span>
                    <span className="picker-pkg mono">{a.pkg}</span>
                  </span>
                  {a.version && <span className="picker-ver">v{a.version}</span>}
                </label>
              );
            })
          )}
        </div>

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={picked.size === 0} onClick={add}>
            Add {picked.size || ''}
          </button>
        </div>
      </div>
    </div>
  );
}
