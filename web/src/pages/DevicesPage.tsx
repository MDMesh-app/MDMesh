import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import { useDevices } from '../data/useDevices';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { useToast } from '../ui/toast';
import { fmtRelative, orDash } from '../ui/format';
import {
  bulkSetConfiguration,
  deleteDevicesBulk,
  type DeviceView,
  type ConfigurationLookup,
} from '../api/devices';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';

type View = 'grid' | 'list';
type StatusFilter = 'all' | 'online' | 'offline';

function configName(
  d: DeviceView,
  configs: Record<string, ConfigurationLookup>,
): string {
  if (d.configurationId == null) return '—';
  return configs[String(d.configurationId)]?.name ?? '—';
}

// Online = checked in recently. statusCode is config-compliance colour (green even for a device
// that was factory-reset and stopped reporting), so it must NOT drive the online/offline dot.
const isOnline = (d: DeviceView) => isOnlineByRecency(d.lastUpdate);

function IconSearch() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4-4" />
    </svg>
  );
}
function IconGrid() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </svg>
  );
}
function IconList() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <line x1="4" y1="6" x2="20" y2="6" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="18" x2="20" y2="18" />
    </svg>
  );
}

export function DevicesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const { devices, configurations, loading, error, reload } = useDevices();
  const [view, setView] = useState<View>('grid');
  const [status, setStatus] = useState<StatusFilter>('all');
  const [config, setConfig] = useState('all');
  const [android, setAndroid] = useState('all');
  const [q, setQ] = useState('');
  const [dupOnly, setDupOnly] = useState(false);

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [allConfigs, setAllConfigs] = useState<ConfigurationSummary[]>([]);
  const [moveOpen, setMoveOpen] = useState(false);
  const [delOpen, setDelOpen] = useState(false);
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    listConfigurations()
      .then((l) => setAllConfigs([...l].sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => undefined);
  }, []);

  const onlineCount = useMemo(() => devices.filter(isOnline).length, [devices]);

  // Group by hardware id: a value shared by >1 row = same physical device enrolled twice.
  const dupCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of devices) if (d.hardwareId) m.set(d.hardwareId, (m.get(d.hardwareId) ?? 0) + 1);
    return m;
  }, [devices]);
  const dupOf = (d: DeviceView) => (d.hardwareId ? dupCount.get(d.hardwareId) ?? 0 : 0);
  const dupTotal = useMemo(
    () => devices.filter((d) => (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0) > 1).length,
    [devices, dupCount],
  );

  const configOptions = useMemo(() => {
    const names = new Set<string>();
    for (const d of devices) {
      const n = configName(d, configurations);
      if (n !== '—') names.add(n);
    }
    return [...names].sort();
  }, [devices, configurations]);

  const androidOptions = useMemo(() => {
    const v = new Set<string>();
    for (const d of devices) if (d.androidVersion) v.add(d.androidVersion);
    return [...v].sort();
  }, [devices]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return devices.filter((d) => {
      if (status === 'online' && !isOnline(d)) return false;
      if (status === 'offline' && isOnline(d)) return false;
      if (config !== 'all' && configName(d, configurations) !== config) return false;
      if (android !== 'all' && d.androidVersion !== android) return false;
      if (dupOnly && (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0) <= 1) return false;
      if (needle) {
        const hay = `${d.number ?? ''} ${d.description ?? ''}`.toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
  }, [devices, status, config, android, q, dupOnly, dupCount, configurations]);

  const go = (d: DeviceView) => navigate(`/devices/${d.id}`);

  const toggle = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const clearSel = () => setSelected(new Set());
  const selectAllFiltered = () => setSelected(new Set(filtered.map((d) => d.id)));
  const selectionActive = selected.size > 0;
  const allFilteredSelected =
    filtered.length > 0 && filtered.every((d) => selected.has(d.id));
  const toggleAll = () => (allFilteredSelected ? clearSel() : selectAllFiltered());

  async function applyMove() {
    if (!target) return;
    setBusy(true);
    try {
      await bulkSetConfiguration([...selected], Number(target));
      const name = allConfigs.find((c) => c.id === Number(target))?.name ?? 'configuration';
      toast.push('ok', 'Configuration changed', `${selected.size} device(s) → ${name}.`);
      setMoveOpen(false);
      setTarget('');
      clearSel();
      await reload();
    } catch (e) {
      toast.push('err', 'Change failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function applyDelete() {
    setBusy(true);
    try {
      await deleteDevicesBulk([...selected]);
      toast.push('ok', 'Devices deleted', `${selected.size} device(s) removed.`);
      setDelOpen(false);
      clearSel();
      await reload();
    } catch (e) {
      toast.push('err', 'Delete failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AppShell title="Devices">
      <div className="dv-head">
        <h1>Devices</h1>
        <span className="dv-count">
          {devices.length} total · {onlineCount} online
        </span>
        <div className="dv-spacer" />
        <div className="dv-search">
          <IconSearch />
          <input
            type="search"
            placeholder="Search devices"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="toggle" role="group" aria-label="View">
          <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')} aria-label="Grid view" title="Grid">
            <IconGrid />
          </button>
          <button className={view === 'list' ? 'on' : ''} onClick={() => setView('list')} aria-label="List view" title="List">
            <IconList />
          </button>
        </div>
        <button className="btn btn-dark" onClick={() => navigate('/enroll')}>
          Enroll device
        </button>
      </div>

      <div className="filters">
        <button className={`filter-chip ${status === 'all' ? 'on' : ''}`} onClick={() => setStatus('all')}>
          All <b>{devices.length}</b>
        </button>
        <button className={`filter-chip ${status === 'online' ? 'on' : ''}`} onClick={() => setStatus('online')}>
          Online <b>{onlineCount}</b>
        </button>
        <button className={`filter-chip ${status === 'offline' ? 'on' : ''}`} onClick={() => setStatus('offline')}>
          Offline <b>{devices.length - onlineCount}</b>
        </button>
        {dupTotal > 0 && (
          <button
            className={`filter-chip dup ${dupOnly ? 'on' : ''}`}
            onClick={() => setDupOnly((v) => !v)}
            title="Devices that share a hardware id with another row — likely the same physical device enrolled more than once"
          >
            ⚠ Duplicates <b>{dupTotal}</b>
          </button>
        )}
        <span className="filter-div" />
        <select className="sel" value={config} onChange={(e) => setConfig(e.target.value)} aria-label="Filter by configuration">
          <option value="all">Config: All</option>
          {configOptions.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        <select className="sel" value={android} onChange={(e) => setAndroid(e.target.value)} aria-label="Filter by Android version">
          <option value="all">Android: All</option>
          {androidOptions.map((a) => (
            <option key={a} value={a}>Android {a}</option>
          ))}
        </select>
      </div>

      {selected.size > 0 && (
        <div className="bulk-bar">
          <span className="bulk-count">{selected.size} selected</span>
          <button className="btn btn-sm" onClick={() => setMoveOpen(true)}>
            Change configuration
          </button>
          <button className="btn btn-sm btn-danger" onClick={() => setDelOpen(true)}>
            Delete
          </button>
          <div style={{ flex: 1 }} />
          {selected.size < filtered.length && (
            <button className="btn btn-sm btn-ghost" onClick={selectAllFiltered}>
              Select all {filtered.length}
            </button>
          )}
          <button className="btn btn-sm btn-ghost" onClick={clearSel}>
            Clear
          </button>
        </div>
      )}

      {error && <div className="banner banner-alert">{error}</div>}

      {loading ? (
        <div className="panel">
          <div className="empty">
            <span className="spin" /> Loading devices…
          </div>
        </div>
      ) : filtered.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">No devices</span>
            {devices.length === 0 ? 'No devices are enrolled yet.' : 'No devices match these filters.'}
          </div>
        </div>
      ) : (
        <>
          <div className="select-all">
            <input
              type="checkbox"
              className="dev-check"
              checked={allFilteredSelected}
              ref={(el) => {
                if (el) el.indeterminate = selectionActive && !allFilteredSelected;
              }}
              onChange={toggleAll}
              aria-label="Select all devices"
            />
            <span onClick={toggleAll} style={{ cursor: 'pointer' }}>
              {allFilteredSelected ? 'Clear selection' : 'Select all'} · {filtered.length} device
              {filtered.length === 1 ? '' : 's'}
            </span>
          </div>
          {view === 'grid' ? (
            <div className="dev-grid">
              {filtered.map((d) => (
                <DeviceCard
                  key={d.id}
                  d={d}
                  config={configName(d, configurations)}
                  dup={dupOf(d)}
                  selected={selected.has(d.id)}
                  selectionActive={selectionActive}
                  onToggle={() => toggle(d.id)}
                  onOpen={() => go(d)}
                />
              ))}
            </div>
          ) : (
            <div className="dev-list">
              {filtered.map((d) => (
                <DeviceRow
                  key={d.id}
                  d={d}
                  config={configName(d, configurations)}
                  dup={dupOf(d)}
                  selected={selected.has(d.id)}
                  selectionActive={selectionActive}
                  onToggle={() => toggle(d.id)}
                  onOpen={() => go(d)}
                />
              ))}
            </div>
          )}
        </>
      )}

      {moveOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setMoveOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Change configuration</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              Move {selected.size} device{selected.size === 1 ? '' : 's'} to a configuration.
            </p>
            <label className="field">
              <span>Configuration</span>
              <select className="sel" value={target} onChange={(e) => setTarget(e.target.value)} style={{ width: '100%' }}>
                <option value="">Select a configuration…</option>
                {allConfigs.map((c) => (
                  <option key={c.id} value={String(c.id)}>{c.name}</option>
                ))}
              </select>
            </label>
            <div className="modal-actions">
              <button className="btn" onClick={() => setMoveOpen(false)} disabled={busy}>Cancel</button>
              <button className="btn btn-primary" disabled={busy || !target} onClick={() => void applyMove()}>
                {busy ? 'Moving…' : 'Move'}
              </button>
            </div>
          </div>
        </div>
      )}

      {delOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setDelOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Delete devices</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              Permanently remove {selected.size} device{selected.size === 1 ? '' : 's'} from MDMesh?
              The device(s) will re-appear if they check in again.
            </p>
            <div className="modal-actions">
              <button className="btn" onClick={() => setDelOpen(false)} disabled={busy}>Cancel</button>
              <button className="btn btn-danger" disabled={busy} onClick={() => void applyDelete()}>
                {busy ? 'Deleting…' : `Delete ${selected.size}`}
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
}

function SelectBox({ selected, onToggle }: { selected: boolean; onToggle: () => void }) {
  return (
    <input
      type="checkbox"
      className="dev-check"
      checked={selected}
      onClick={(e) => e.stopPropagation()}
      onChange={onToggle}
      aria-label="Select device"
    />
  );
}

function DupBadge({ n }: { n: number }) {
  return (
    <span
      className="dup-badge"
      title={`Shares a hardware id with ${n - 1} other device${n - 1 === 1 ? '' : 's'} — likely the same physical device enrolled more than once`}
    >
      ⚠ {n}×
    </span>
  );
}

function DeviceCard({
  d,
  config,
  dup,
  selected,
  selectionActive,
  onToggle,
  onOpen,
}: {
  d: DeviceView;
  config: string;
  dup: number;
  selected: boolean;
  selectionActive: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const online = isOnline(d);
  // Once a selection is in progress, clicking a card toggles it instead of opening it.
  const act = selectionActive ? onToggle : onOpen;
  return (
    <div
      className={`dev ${selected ? 'sel' : ''}`}
      role="button"
      tabIndex={0}
      onClick={act}
      onKeyDown={(e) => e.key === 'Enter' && act()}
    >
      <div className="h">
        <SelectBox selected={selected} onToggle={onToggle} />
        <span className={`dot ${online ? 'on' : 'off'}`} />
        <span className="nm">{orDash(d.number)}</span>
        {dup > 1 && <DupBadge n={dup} />}
        <DeviceGlyph className="ico" name={d.description || d.number} size={16} />
      </div>
      {d.description && <div className="sub">{d.description}</div>}
      <div className="kv">
        <div>
          <div className="k">Android</div>
          <div className="v">{orDash(d.androidVersion)}</div>
        </div>
        <div>
          <div className="k">Config</div>
          <div className="v">{config}</div>
        </div>
        <div>
          <div className="k">Seen</div>
          <div className="v">{fmtRelative(d.lastUpdate)}</div>
        </div>
      </div>
    </div>
  );
}

function DeviceRow({
  d,
  config,
  dup,
  selected,
  selectionActive,
  onToggle,
  onOpen,
}: {
  d: DeviceView;
  config: string;
  dup: number;
  selected: boolean;
  selectionActive: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const online = isOnline(d);
  const act = selectionActive ? onToggle : onOpen;
  return (
    <div
      className={`dev-row ${selected ? 'sel' : ''}`}
      role="button"
      tabIndex={0}
      onClick={act}
      onKeyDown={(e) => e.key === 'Enter' && act()}
    >
      <div className="id">
        <SelectBox selected={selected} onToggle={onToggle} />
        <span className={`dot ${online ? 'on' : 'off'}`} />
        <DeviceGlyph className="ico" name={d.description || d.number} size={15} />
        <div style={{ minWidth: 0 }}>
          <div className="nm">{orDash(d.number)}</div>
          {d.description && <div className="sub">{d.description}</div>}
        </div>
        {dup > 1 && <DupBadge n={dup} />}
      </div>
      <div className="lc">
        <span className="lk">Android</span>
        <span className="lv">{orDash(d.androidVersion)}</span>
      </div>
      <div className="lc">
        <span className="lk">Config</span>
        <span className="lv">{config}</span>
      </div>
      <div className="lc">
        <span className="lk">Seen</span>
        <span className="lv">{fmtRelative(d.lastUpdate)}</span>
      </div>
    </div>
  );
}
