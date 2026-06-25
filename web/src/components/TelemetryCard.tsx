import { useEffect, useState } from 'react';
import { getTelemetry, type TelemetrySnapshot } from '../api/telemetry';

type Device = { number: string };

function Group({ title, data }: { title: string; data?: Record<string, unknown> }) {
  if (!data || Object.keys(data).length === 0) return null;
  return (
    <section className="tele-group">
      <h3 className="action-group-title">{title}</h3>
      <dl className="state-grid">
        {Object.entries(data).map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{Array.isArray(v) ? v.join(', ') : v === null ? '—' : String(v)}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function TelemetryCard({ device }: { device: Device }) {
  const [t, setT] = useState<TelemetrySnapshot | null>(null);
  useEffect(() => {
    const load = () => getTelemetry(device.number).then(setT).catch(() => setT(null));
    void load();
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, [device.number]);

  if (!t) {
    return (
      <div className="panel">
        <h2 className="panel-title">Telemetry</h2>
        <p className="muted">No telemetry yet.</p>
      </div>
    );
  }
  return (
    <div className="panel">
      <h2 className="panel-title">Telemetry</h2>
      <Group title="Hardware" data={t.hardware} />
      <Group title="Network & Battery" data={t.dynamic} />
      <Group title="Security" data={t.security} />
      <Group title="Identity" data={t.identity} />
    </div>
  );
}
