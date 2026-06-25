import { useEffect, useState } from 'react';
import { getEvents, type DeviceEvent } from '../api/events';

type Device = { number: string };

const LABELS: Record<string, string> = {
  boot: 'Booted',
  appInstalled: 'App installed',
  appUninstalled: 'App uninstalled',
  commandResult: 'Command',
  connectivityChange: 'Network change',
  lowBattery: 'Low battery',
  enrolled: 'Enrolled',
};

export function EventTimeline({ device }: { device: Device }) {
  const [events, setEvents] = useState<DeviceEvent[]>([]);
  useEffect(() => {
    const load = () => getEvents(device.number).then(setEvents).catch(() => undefined);
    void load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [device.number]);

  return (
    <div className="panel">
      <h2 className="panel-title">Events</h2>
      {events.length === 0 ? (
        <p className="muted">No events yet.</p>
      ) : (
        <ul className="timeline">
          {events.map((e) => (
            <li key={e.id} className="timeline-item">
              <span className="t-status">{new Date(e.ts).toLocaleString()}</span>
              <span className="t-type">{LABELS[e.type] ?? e.type}</span>
              {e.detail && <span className="t-detail">{e.detail}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
