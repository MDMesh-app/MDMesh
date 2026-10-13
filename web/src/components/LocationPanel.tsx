import { useEffect, useState } from 'react';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { LocationMap } from './LocationMap';
import { fmtRelative } from '../ui/format';
import { DO_NOT_TRACK_HINT, useDoNotTrack } from '../api/locationTracking';

export function LocationPanel({ device }: { device: { number: string; configurationId?: number } }) {
  const doNotTrack = useDoNotTrack(device.configurationId);
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setErr(null);
    try {
      setFixes(await listLocations(device.number));
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load locations');
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device.number]);

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
          <button className="btn" onClick={() => void load()}>Refresh</button>
        </div>
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
