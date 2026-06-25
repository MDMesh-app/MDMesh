import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useDevices } from '../data/useDevices';
import { statusMeta, isOnline as isOnlineByRecency } from '../ui/status';
import { fmtRelative, orDash } from '../ui/format';
import { getEvents, type DeviceEvent } from '../api/events';
import type { DeviceView, ConfigurationLookup } from '../api/devices';

type Bucket = 'online' | 'attention' | 'offline';

const EVENT_VERBS: Record<string, string> = {
  boot: 'booted',
  appInstalled: 'installed an app',
  appUninstalled: 'removed an app',
  commandResult: 'ran a command',
  connectivityChange: 'changed network',
  lowBattery: 'reported low battery',
  enrolled: 'enrolled',
};

function configName(
  d: DeviceView,
  configs: Record<string, ConfigurationLookup>,
): string {
  if (d.configurationId == null) return 'Unassigned';
  return configs[String(d.configurationId)]?.name ?? 'Unassigned';
}

function bucketOf(d: DeviceView): Bucket {
  // Offline first (recency of last check-in); a still-reporting device with a config issue is
  // "attention". statusCode alone can't say offline — it stays green after a factory reset.
  if (!isOnlineByRecency(d.lastUpdate)) return 'offline';
  return statusMeta(d.statusCode).tone === 'warn' ? 'attention' : 'online';
}

interface ActivityItem {
  key: string;
  device: DeviceView;
  ev: DeviceEvent;
}

function ActivityIcon({ type }: { type: string }) {
  const common = {
    width: 14,
    height: 14,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.7,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  switch (type) {
    case 'appInstalled':
    case 'enrolled':
      return (
        <svg {...common}>
          <path d="M12 5v14M5 12h14" />
        </svg>
      );
    case 'appUninstalled':
      return (
        <svg {...common}>
          <path d="M5 12h14" />
        </svg>
      );
    case 'boot':
      return (
        <svg {...common}>
          <path d="M21 12a9 9 0 1 1-3-6.7" />
          <path d="M21 4v5h-5" />
        </svg>
      );
    case 'commandResult':
      return (
        <svg {...common}>
          <path d="M20 6 9 17l-5-5" />
        </svg>
      );
    case 'lowBattery':
    case 'connectivityChange':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 8v4M12 16h.01" />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="3" />
        </svg>
      );
  }
}

export function DashboardPage() {
  const navigate = useNavigate();
  const { devices, total, configurations, loading, error } = useDevices();
  const [activity, setActivity] = useState<ActivityItem[] | null>(null);

  const counts = useMemo(() => {
    let online = 0,
      attention = 0,
      offline = 0;
    for (const d of devices) {
      const b = bucketOf(d);
      if (b === 'online') online++;
      else if (b === 'attention') attention++;
      else offline++;
    }
    return { online, attention, offline };
  }, [devices]);

  const shown = devices.length;
  const pct = (n: number) => (shown ? `${(n / shown) * 100}%` : '0%');

  const attention = useMemo(
    () =>
      [...devices]
        .filter((d) => bucketOf(d) !== 'online')
        .sort((a, b) => (a.lastUpdate ?? 0) - (b.lastUpdate ?? 0))
        .slice(0, 6),
    [devices],
  );

  const byConfig = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of devices) {
      const n = configName(d, configurations);
      m.set(n, (m.get(n) ?? 0) + 1);
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [devices, configurations]);

  // Cross-fleet activity: pull recent events for the most-recently-active
  // devices and merge. Per-device endpoint, so cap the fan-out.
  useEffect(() => {
    if (loading) return;
    if (devices.length === 0) {
      setActivity([]);
      return;
    }
    let cancelled = false;
    const top = [...devices]
      .sort((a, b) => (b.lastUpdate ?? 0) - (a.lastUpdate ?? 0))
      .slice(0, 12);
    void Promise.allSettled(
      top.map((d) => getEvents(d.number).then((evs) => ({ d, evs }))),
    ).then((results) => {
      if (cancelled) return;
      const items: ActivityItem[] = [];
      for (const r of results) {
        if (r.status !== 'fulfilled') continue;
        const { d, evs } = r.value;
        for (const ev of evs ?? [])
          items.push({ key: `${d.id}-${ev.id}`, device: d, ev });
      }
      items.sort((a, b) => b.ev.ts - a.ev.ts);
      setActivity(items.slice(0, 8));
    });
    return () => {
      cancelled = true;
    };
  }, [loading, devices]);

  return (
    <AppShell title="Overview">
      <div className="page-head">
        <h1>Overview</h1>
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      <div className="panel fleet">
        <div className="fleet-big">
          {total}
          <small>{total === 1 ? 'device' : 'devices'}</small>
        </div>
        <div className="fleet-barwrap">
          <div className="fleet-legend">
            <span>
              <i style={{ background: 'var(--online)' }} />
              {counts.online} online
            </span>
            <span>
              <i style={{ background: 'var(--warn)' }} />
              {counts.attention} need attention
            </span>
            <span>
              <i style={{ background: 'var(--offline)' }} />
              {counts.offline} offline
            </span>
          </div>
          <div className="fleet-bar">
            <i style={{ background: 'var(--online)', width: pct(counts.online) }} />
            <i style={{ background: 'var(--warn)', width: pct(counts.attention) }} />
            <i style={{ background: 'var(--offline)', width: pct(counts.offline) }} />
          </div>
        </div>
      </div>

      <div className="ov-grid">
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Recent activity</h2>
            <button
              className="btn btn-sm btn-ghost"
              onClick={() => navigate('/devices')}
            >
              All devices →
            </button>
          </div>
          <div className="ov-listpad">
            {activity === null ? (
              <div className="empty">
                <span className="spin" /> Loading activity…
              </div>
            ) : activity.length === 0 ? (
              <div className="empty">
                <span className="label">Activity</span>
                No recent events.
              </div>
            ) : (
              activity.map((a) => (
                <div
                  key={a.key}
                  className="feed-item"
                  role="button"
                  tabIndex={0}
                  style={{ cursor: 'pointer' }}
                  onClick={() => navigate(`/devices/${a.device.id}`)}
                  onKeyDown={(e) =>
                    e.key === 'Enter' && navigate(`/devices/${a.device.id}`)
                  }
                >
                  <span className="feed-ic">
                    <ActivityIcon type={a.ev.type} />
                  </span>
                  <div className="feed-tx">
                    <span>
                      <b>{orDash(a.device.number)}</b>{' '}
                      {EVENT_VERBS[a.ev.type] ?? a.ev.type}
                    </span>
                    {(a.device.description || a.ev.detail) && (
                      <div className="feed-sub">
                        {a.ev.detail || a.device.description}
                      </div>
                    )}
                  </div>
                  <span className="feed-tm">{fmtRelative(a.ev.ts)}</span>
                </div>
              ))
            )}
          </div>
        </section>

        <div className="ov-col">
          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title">Needs attention</h2>
            </div>
            <div className="ov-listpad">
              {loading ? (
                <div className="empty">
                  <span className="spin" /> Loading…
                </div>
              ) : attention.length === 0 ? (
                <div className="empty">
                  <span className="label">All clear</span>
                  Every device is online.
                </div>
              ) : (
                attention.map((d) => {
                  const m = statusMeta(d.statusCode);
                  return (
                    <div
                      key={d.id}
                      className="att-row"
                      role="button"
                      tabIndex={0}
                      onClick={() => navigate(`/devices/${d.id}`)}
                      onKeyDown={(e) =>
                        e.key === 'Enter' && navigate(`/devices/${d.id}`)
                      }
                    >
                      <span className={`dot dot-${m.tone}`} />
                      <span className="att-nm">{orDash(d.number)}</span>
                      <span className="att-reason">
                        {m.label} · {fmtRelative(d.lastUpdate)}
                      </span>
                    </div>
                  );
                })
              )}
            </div>
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title">By configuration</h2>
            </div>
            <div className="ov-listpad">
              {byConfig.length === 0 ? (
                <div className="empty">
                  <span className="label">Configurations</span>
                  No devices yet.
                </div>
              ) : (
                byConfig.map(([name, n]) => (
                  <div className="cfg-row" key={name}>
                    <span className="k">{name}</span>
                    <span className="v">{n}</span>
                  </div>
                ))
              )}
            </div>
          </section>
        </div>
      </div>
    </AppShell>
  );
}
