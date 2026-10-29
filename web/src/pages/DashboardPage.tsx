import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { AppShell } from '../ui/AppShell';
import { useDevices } from '../data/useDevices';
import { isOnline as isOnlineByRecency, type StatusTone } from '../ui/status';
import { fmtRelative, orDash } from '../ui/format';
import { getEvents, type DeviceEvent } from '../api/events';
import { DEVICE_RESET_LABEL, SOME_DEFERRED_LABEL, getConfigStatus, getSyncSummary, summarizeStatus } from '../api/configSync';
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
  kioskPinLockout: 'locked the exit PIN',
  kioskCrashLoop: 'reported the kiosk app keeps closing',
};

function configName(
  d: DeviceView,
  configs: Record<string, ConfigurationLookup>,
): string {
  if (d.configurationId == null) return 'Unassigned';
  return configs[String(d.configurationId)]?.name ?? 'Unassigned';
}

interface ConfigIssue {
  tone: StatusTone;
  label: string;
}

/** Online devices whose configuration state is probed per device (one request each). */
const ATTENTION_PROBE_CAP = 50;

/** A configuration state worth an admin's look; "Applying…" and "Not reported yet" are transient. */
function isConfigIssue(v: ConfigIssue): boolean {
  const labels = ['Out of sync', DEVICE_RESET_LABEL, SOME_DEFERRED_LABEL, 'Agent too old'];
  return v.tone === 'alert' || labels.includes(v.label);
}

function bucketOf(d: DeviceView, issues: Record<number, ConfigIssue>, now?: number): Bucket {
  // Offline first (recency of last check-in); a still-reporting device whose configuration
  // didn't apply is "attention". statusCode can't say either: it is a Headwind recency colour.
  if (!isOnlineByRecency(d.lastUpdate, now)) return 'offline';
  return issues[d.id] ? 'attention' : 'online';
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
    case 'kioskPinLockout':
      return (
        <svg {...common}>
          <rect x="5" y="11" width="14" height="10" rx="2" />
          <path d="M8 11V7a4 4 0 0 1 8 0v4" />
        </svg>
      );
    case 'lowBattery':
    case 'connectivityChange':
    case 'kioskCrashLoop':
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
  const [issues, setIssues] = useState<Record<number, ConfigIssue>>({});
  // How complete the configuration check is: 'complete' only when the summary loaded and every
  // suspect device was probed successfully; 'partial' when the probe was capped or some probes failed.
  const [syncCheck, setSyncCheck] = useState<'pending' | 'failed' | 'partial' | 'complete'>('pending');

  // Tick every 30s so the online/attention/offline split decays as devices go quiet.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);

  const counts = useMemo(() => {
    let online = 0,
      attention = 0,
      offline = 0;
    for (const d of devices) {
      const b = bucketOf(d, issues, now);
      if (b === 'online') online++;
      else if (b === 'attention') attention++;
      else offline++;
    }
    return { online, attention, offline };
  }, [devices, issues, now]);

  const shown = devices.length;
  const pct = (n: number) => (shown ? `${(n / shown) * 100}%` : '0%');

  const attention = useMemo(
    () =>
      [...devices]
        .filter((d) => bucketOf(d, issues, now) !== 'online')
        // Online devices with a configuration problem first, then the longest-silent.
        .sort((a, b) => Number(!!issues[b.id]) - Number(!!issues[a.id]) || (a.lastUpdate ?? 0) - (b.lastUpdate ?? 0))
        .slice(0, 6),
    [devices, issues, now],
  );

  // Configuration problems: the sync summary names the configurations with devices not in sync (or deferring settings),
  // then only those configurations' online devices are probed (capped), so a healthy fleet costs
  // one request.
  useEffect(() => {
    if (loading) return;
    let cancelled = false;
    setSyncCheck('pending');
    void (async () => {
      const summary = await getSyncSummary().catch(() => null);
      if (cancelled) return;
      if (!summary) {
        setSyncCheck('failed');
        return;
      }
      const suspect = new Set(
        summary
          .filter((s) => s.outOfSync > 0 || s.unsupported > 0 || s.neverSeen > 0 || (s.deferred ?? 0) > 0)
          .map((s) => s.configurationId),
      );
      const suspects = devices.filter(
        (d) => d.configurationId != null && suspect.has(d.configurationId) && isOnlineByRecency(d.lastUpdate),
      );
      const probe = suspects.slice(0, ATTENTION_PROBE_CAP);
      const results = await Promise.allSettled(
        probe.map((d) => getConfigStatus(d.number).then((s) => ({ id: d.id, v: summarizeStatus(s) }))),
      );
      if (cancelled) return;
      const next: Record<number, ConfigIssue> = {};
      let failed = 0;
      for (const r of results) {
        if (r.status !== 'fulfilled') failed++;
        else if (isConfigIssue(r.value.v)) next[r.value.id] = r.value.v;
      }
      setIssues(next);
      setSyncCheck(suspects.length > probe.length || failed > 0 ? 'partial' : 'complete');
    })();
    return () => {
      cancelled = true;
    };
  }, [loading, devices]);

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
              {counts.attention}
              {syncCheck === 'partial' ? '+' : ''} need attention
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
                  onClick={() => navigate(`/devices/${encodeURIComponent(a.device.number)}`)}
                  onKeyDown={(e) =>
                    e.key === 'Enter' && navigate(`/devices/${encodeURIComponent(a.device.number)}`)
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
                  <span className="label">{syncCheck === 'complete' ? 'All clear' : 'All online'}</span>
                  {syncCheck === 'complete'
                    ? 'Every device is online and in sync.'
                    : syncCheck === 'pending'
                      ? 'Every device is online. Checking configuration sync…'
                      : syncCheck === 'failed'
                        ? 'Every device is online. Configuration sync could not be checked.'
                        : 'Every device is online. Configuration sync was only partly checked.'}
                </div>
              ) : (
                attention.map((d) => {
                  const issue = isOnlineByRecency(d.lastUpdate, now) ? issues[d.id] : undefined;
                  const m: ConfigIssue = issue ?? { tone: 'alert', label: 'Offline' };
                  return (
                    <div
                      key={d.id}
                      className="att-row"
                      role="button"
                      tabIndex={0}
                      onClick={() => navigate(`/devices/${encodeURIComponent(d.number)}`)}
                      onKeyDown={(e) =>
                        e.key === 'Enter' && navigate(`/devices/${encodeURIComponent(d.number)}`)
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
