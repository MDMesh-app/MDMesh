import { useEffect, useState } from 'react';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { LocationMap } from './LocationMap';
import { fmtRelative } from '../ui/format';
import { DO_NOT_TRACK_HINT, useDoNotTrack } from '../api/locationTracking';

const RANGES: ReadonlyArray<{ hours: number; label: string }> = [
  { hours: 1, label: 'Last hour' },
  { hours: 6, label: 'Last 6 hours' },
  { hours: 24, label: 'Last 24 hours' },
  { hours: 72, label: 'Last 3 days' },
  { hours: 168, label: 'Last 7 days' },
  { hours: 0, label: 'All' },
];

export function LocationPanel({ device }: { device: { number: string; configurationId?: number } }) {
  const doNotTrack = useDoNotTrack(device.configurationId);
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  // 0 = no lower bound (the previous behaviour: the newest 500 fixes the server returns).
  const [hours, setHours] = useState<number>(0);

  async function load() {
    setErr(null);
    try {
      setFixes(await listLocations(device.number, hours > 0 ? Date.now() - hours * 3_600_000 : 0));
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load locations');
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device.number, hours]);

  return (
    <div className="panel">
      <div className="panel-head">
        <h2 className="panel-title">Location history</h2>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {fixes && (
            <span className="muted">
              {fixes.length} fix{fixes.length === 1 ? '' : 'es'}
              {fixes[0] ? ` · latest ${fmtRelative(fixes[0].capturedAt)}` : ''}
            </span>
          )}
        </div>
      </div>
      {/* The panel head is hidden inside the device page's tab body, so the range gets its own row. */}
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 10 }}>
        <select
          className="sel"
          value={hours}
          onChange={(e) => setHours(Number(e.target.value))}
          aria-label="Time range"
        >
          {RANGES.map((r) => (
            <option key={r.hours} value={r.hours}>{r.label}</option>
          ))}
        </select>
        {fixes && (
          <span className="muted">
            {fixes.length} fix{fixes.length === 1 ? '' : 'es'}
          </span>
        )}
        <button className="btn" onClick={() => void load()}>Refresh</button>
      </div>
      {err && <p className="err-text">{err}</p>}
      {doNotTrack && (
        <p className="muted">
          {DO_NOT_TRACK_HINT}
          {fixes && fixes.length > 0 ? ' The fixes below were reported before tracking was turned off.' : ''}
        </p>
      )}
      {!fixes && !err && <p className="muted">Loading location…</p>}
      {fixes && fixes.length === 0 && !doNotTrack && (
        <p className="muted">No location reported yet. Wake the device, or switch it to Accurate location mode.</p>
      )}
      {fixes && fixes.length > 0 && <LocationMap fixes={fixes} />}
    </div>
  );
}
