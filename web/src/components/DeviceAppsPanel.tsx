import { useCallback, useEffect, useRef, useState } from 'react';
import { scanFromHistory, scanInstalledApps, latestAppChange, appChangeAwaitingResult, type ScanSnapshot } from '../api/deviceApps';
import { listCommandHistory, forceSync } from '../api/commands';
import { fmtDateTime } from '../ui/format';

export function DeviceAppsPanel({ device }: {
  device: { number: string };
}) {
  const [snapshot, setSnapshot] = useState<ScanSnapshot | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const [awaitingResult, setAwaitingResult] = useState(false);
  const controller = useRef<AbortController | null>(null);

  const scannedAt = useRef(0);

  const refresh = useCallback(async () => {
    // A manual refresh and the monitor share one scan, so they cannot overwrite each other.
    if (controller.current) return;
    const request = new AbortController();
    controller.current = request;
    setBusy(true);
    setError(null);
    try {
      const scan = await scanInstalledApps(device.number, request.signal);
      if (!request.signal.aborted) {
        scannedAt.current = scan.freshnessAt ?? scan.scannedAt ?? 0;
        setSnapshot(scan);
      }
    } catch (e) {
      if (!request.signal.aborted) setError(e instanceof Error ? e.message : 'Scan failed.');
    } finally {
      if (controller.current === request) controller.current = null;
      if (!request.signal.aborted) setBusy(false);
    }
  }, [device.number]);

  useEffect(() => {
    const monitor = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    let initial = true;
    let lastAttempt: string | undefined;
    let nextSyncAt = 0;
    scannedAt.current = 0;
    setSnapshot(null);
    setAwaitingResult(false);
    setBusy(true);
    setError(null);

    const poll = async () => {
      try {
        const history = await listCommandHistory(device.number, 0, monitor.signal);
        if (monitor.signal.aborted) return;
        const saved = scanFromHistory(history, true);
        if (!controller.current && saved && (initial || (saved.freshnessAt ?? saved.scannedAt ?? 0) > scannedAt.current)) {
          scannedAt.current = saved.freshnessAt ?? saved.scannedAt ?? 0;
          setSnapshot(saved);
        }
        const awaiting = appChangeAwaitingResult(history, scannedAt.current);
        setAwaitingResult(awaiting);
        if (awaiting && Date.now() >= nextSyncAt) {
          nextSyncAt = Date.now() + 15000;
          await forceSync(device.number, monitor.signal).catch(() => undefined);
          if (monitor.signal.aborted) return;
        }
        const changed = latestAppChange(history, scannedAt.current);
        if (changed && String(changed.id) !== lastAttempt && !controller.current) {
          // Queueing, failure and unsupported results never invalidate the inventory.
          // Retry a failed scan manually, or automatically after the next successful app change.
          lastAttempt = String(changed.id);
          await refresh();
        }
      } catch (e) {
        if (initial && !monitor.signal.aborted) {
          setError(e instanceof Error ? e.message : 'Could not load inventory.');
        }
      } finally {
        if (!monitor.signal.aborted) {
          if (initial && !controller.current) setBusy(false);
          initial = false;
          timer = setTimeout(() => void poll(), 5000);
        }
      }
    };
    void poll();
    return () => {
      monitor.abort();
      clearTimeout(timer);
      controller.current?.abort();
      controller.current = null;
    };
  }, [device.number, refresh]);

  const term = query.trim().toLocaleLowerCase();
  const apps = (snapshot?.apps ?? []).filter((a) =>
    (showSystem || !a.system) && `${a.label} ${a.pkg}`.toLocaleLowerCase().includes(term),
  );
  const installed = snapshot?.apps.filter((a) => !a.system).length ?? 0;

  return (
    <div className="panel">
      <div className="device-apps-head">
        <h2 className="panel-title">Installed apps</h2>
        <button className="btn btn-primary" disabled={busy} onClick={() => void refresh()}>
          {busy ? 'Loading…' : 'Refresh inventory'}
        </button>
      </div>
      <p className="muted">Inventory for the Android user managed by MDMesh. Refresh requires the device to be online.</p>
      {snapshot && <p className="muted">{installed} user apps · {snapshot.apps.length - installed} system apps · Scanned {fmtDateTime(snapshot.scannedAt)}</p>}
      {busy && <p role="status">Waiting for inventory…</p>}
      {awaitingResult && !busy && <p role="status">Waiting for the device to confirm the app change…</p>}
      {error && <p className="banner banner-alert" role="alert">{error}</p>}
      <div className="device-apps-filters">
        <input className="input" type="search" aria-label="Search installed apps" placeholder="Search by name or package" value={query} onChange={(e) => setQuery(e.target.value)} />
        <label><input type="checkbox" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} /> Show system apps</label>
      </div>
      {!snapshot && !busy && <p>No saved inventory. Click Refresh inventory to request it from the device.</p>}
      {snapshot && <div className="device-apps-table"><table>
        <thead><tr><th>Application / package</th><th>Version</th><th>Type</th></tr></thead>
        <tbody>{apps.map((app) => {
          return <tr key={app.pkg}>
            <td><strong>{app.label}</strong><div className="mono muted">{app.pkg}</div></td>
            <td>{app.versionName || '—'}<div className="muted">{app.versionCode ?? ''}</div></td>
            <td>{app.system ? 'System' : 'User'}</td>
          </tr>;
        })}</tbody>
      </table>{!apps.length && <p>No applications match these filters.</p>}</div>}
      <p className="muted">The inventory refreshes automatically after a successful installation or uninstallation.</p>
    </div>
  );
}
