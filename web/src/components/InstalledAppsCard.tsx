import { useEffect, useMemo, useRef, useState } from 'react';
import { getLatestScan, scanApps, type AppInfo } from '../api/deviceApps';
import { fmtRelative } from '../ui/format';

type Device = { number: string };

function byLabel(a: AppInfo, b: AppInfo): number {
  return a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }) || a.pkg.localeCompare(b.pkg);
}

/**
 * One CSV field (RFC 4180). App names come from the device, so a value a spreadsheet would read as a formula
 * (=, +, -, @, tab, CR) gets a leading apostrophe.
 */
function csvField(v: string | number | undefined): string {
  let s = v == null ? '' : String(v);
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function appsCsv(apps: AppInfo[]): string {
  const header = ['name', 'package', 'versionName', 'versionCode', 'system'];
  const rows = apps.map((a) => [a.label, a.pkg, a.versionName, a.versionCode, a.system ? 'yes' : 'no']);
  return [header, ...rows].map((r) => r.map(csvField).join(',')).join('\r\n') + '\r\n';
}

/**
 * The device's installed apps: the latest saved `apps.scan` result (the agent lists apps with a launcher icon), a
 * Scan now that asks the device again, and a CSV export for app audits. Same data and command as the kiosk picker.
 */
export function InstalledAppsCard({ device }: { device: Device }) {
  const [apps, setApps] = useState<AppInfo[] | null>(null); // null = loading the saved scan
  const [scannedAt, setScannedAt] = useState<number | undefined>();
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const scanRef = useRef<AbortController | null>(null);

  useEffect(() => {
    let on = true;
    setApps(null);
    setScannedAt(undefined);
    setError(null);
    getLatestScan(device.number)
      .then((s) => {
        if (!on) return;
        setApps(s?.apps ?? []);
        setScannedAt(s?.scannedAt);
      })
      .catch(() => { if (on) setApps([]); });
    return () => {
      on = false;
      scanRef.current?.abort();
    };
  }, [device.number]);

  async function scanNow() {
    scanRef.current?.abort();
    const ctrl = new AbortController();
    scanRef.current = ctrl;
    setScanning(true);
    setError(null);
    try {
      const list = await scanApps(device.number, ctrl.signal);
      setApps(list);
      setScannedAt(Date.now());
    } catch (e) {
      if (!ctrl.signal.aborted) setError(e instanceof Error ? e.message : 'Scan failed');
    } finally {
      if (scanRef.current === ctrl) setScanning(false);
    }
  }

  const all = useMemo(() => [...(apps ?? [])].sort(byLabel), [apps]);
  const systemCount = all.filter((a) => a.system).length;
  const needle = q.trim().toLowerCase();
  const shown = all.filter(
    (a) =>
      (showSystem || !a.system) &&
      (!needle || a.label.toLowerCase().includes(needle) || a.pkg.toLowerCase().includes(needle)),
  );

  function exportCsv() {
    const url = URL.createObjectURL(new Blob([appsCsv(all)], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `apps-${device.number}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="panel">
      <h2 className="panel-title">Installed apps</h2>
      <p className="muted">
        {apps === null
          ? 'Loading the last scan…'
          : scannedAt
            ? `Scanned ${fmtRelative(scannedAt)}: ${all.length} apps, ${all.length - systemCount} user and ${systemCount} system.`
            : 'This device has not been scanned yet.'}{' '}
        Apps with a launcher icon, as the agent reports them.
      </p>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', margin: '12px 0 16px' }}>
        <button className="btn btn-sm btn-primary" onClick={() => void scanNow()} disabled={scanning}>
          {scanning ? 'Scanning…' : 'Scan now'}
        </button>
        <button className="btn btn-sm" onClick={exportCsv} disabled={all.length === 0} title="All apps, system ones included">
          Export CSV
        </button>
        <div className="dv-search" style={{ width: 240 }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="7" />
            <path d="M21 21l-4-4" />
          </svg>
          <input type="search" placeholder="Search apps" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <label className="muted" style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} />
          Show system apps
        </label>
      </div>

      {scanning && <p className="muted">Asking the device for its installed apps…</p>}
      {error && <div className="banner banner-alert">{error}</div>}

      {apps !== null && shown.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>App</th>
              <th>Package</th>
              <th>Version</th>
              <th>Type</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((a) => (
              <tr key={a.pkg}>
                <td>{a.label}</td>
                <td className="mono">{a.pkg}</td>
                <td className="mono">
                  {a.versionName ?? '—'}
                  {a.versionCode != null ? ` (${a.versionCode})` : ''}
                </td>
                <td>{a.system ? 'System' : 'User'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {apps !== null && all.length > 0 && shown.length === 0 && (
        <div className="empty">No apps match. {!showSystem && systemCount > 0 ? 'System apps are hidden.' : ''}</div>
      )}
    </div>
  );
}
