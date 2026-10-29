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
  kioskPinLockout: 'Exit PIN locked',
  kioskCrashLoop: 'Kiosk app keeps closing',
};

export function EventTimeline({ device }: { device: Device }) {
  const [events, setEvents] = useState<DeviceEvent[]>([]);
  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    // Self-scheduling poll — the next tick is armed only after the current one finishes.
    const load = async () => {
      await getEvents(device.number)
        .then((evs) => { if (on) setEvents(evs); })
        .catch(() => undefined);
      if (!on) return;
      t = setTimeout(() => void load(), 5000);
    };
    void load();
    return () => { on = false; clearTimeout(t); };
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
