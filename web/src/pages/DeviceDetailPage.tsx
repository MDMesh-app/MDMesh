import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import { searchDevices, type DeviceView, type ConfigurationLookup } from '../api/devices';
import { ActionConsole } from '../components/ActionConsole';
import { TelemetryCard } from '../components/TelemetryCard';
import { EventTimeline } from '../components/EventTimeline';
import { LocationPanel } from '../components/LocationPanel';
import { getTelemetry, type TelemetrySnapshot } from '../api/telemetry';
import {
  getDeviceState, forceSync, queueCommand, type DeviceState,
} from '../api/commands';
import { ApiError } from '../api/client';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { useToast } from '../ui/toast';
import { fmtDateTime, fmtRelative, orDash } from '../ui/format';

type Tab = 'control' | 'telemetry' | 'events' | 'location';

interface Row {
  k: string;
  v: import('react').ReactNode;
  mono?: boolean;
}

function powerLabel(mode?: string | null): string {
  if (mode === 'alwaysOn') return 'Always-on';
  if (mode === 'adaptive') return 'Battery-saver';
  return '—';
}

export function DeviceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToast();
  const [device, setDevice] = useState<DeviceView | null>(null);
  const [configs, setConfigs] = useState<Record<string, ConfigurationLookup>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tele, setTele] = useState<TelemetrySnapshot | null>(null);
  const [ds, setDs] = useState<DeviceState | null>(null);
  const [tab, setTab] = useState<Tab>('control');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await searchDevices({ pageSize: 1000 });
      const items = res.devices?.items ?? [];
      const found = items.find((d) => String(d.id) === id) ?? null;
      setConfigs(res.configurations ?? {});
      setDevice(found);
      if (!found) setError('Device not found.');
    } catch (err) {
      if (err instanceof ApiError && err.httpStatus === 0)
        setError('Cannot reach the server.');
      else setError('Failed to load device.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!device) return;
    const poll = () => {
      void getTelemetry(device.number).then(setTele).catch(() => undefined);
      void getDeviceState(device.number).then(setDs).catch(() => undefined);
    };
    poll();
    const t = setInterval(poll, 5000);
    return () => clearInterval(t);
  }, [device]);

  const configName =
    device?.configurationId != null
      ? configs[String(device.configurationId)]?.name ?? '—'
      : '—';

  const hw = (tele?.hardware ?? {}) as Record<string, unknown>;
  const idn = (tele?.identity ?? {}) as Record<string, unknown>;
  const sec = (tele?.security ?? {}) as Record<string, unknown>;
  const dyn = (tele?.dynamic ?? {}) as Record<string, unknown>;
  const teleStr = (v: unknown): string | undefined =>
    v == null ? undefined : Array.isArray(v) ? (v[0] != null ? String(v[0]) : undefined) : String(v);
  const onOff = (v: unknown, fallback: boolean | null | undefined): string =>
    v === true ? 'On' : v === false ? 'Off' : fallback == null ? '—' : fallback ? 'On' : 'Off';

  async function syncNow() {
    if (!device) return;
    setBusy(true);
    try {
      await forceSync(device.number);
      void getDeviceState(device.number).then(setDs).catch(() => undefined);
      toast.push('ok', 'Sync requested', '');
    } catch (e) {
      toast.push('err', 'Sync failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function lock() {
    if (!device) return;
    setBusy(true);
    try {
      await queueCommand(device.number, {
        type: 'device.lock',
        requiresCapability: 'device.lock',
      });
      await forceSync(device.number).catch(() => undefined);
      toast.push('ok', 'Lock queued', '');
    } catch (e) {
      toast.push('err', 'Lock failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <AppShell title="Device">
        <div className="panel">
          <div className="empty">
            <span className="spin" /> Loading device…
          </div>
        </div>
      </AppShell>
    );
  }

  if (!device) {
    return (
      <AppShell title="Device">
        <div className="crumb">
          <a href="/devices" onClick={(e) => { e.preventDefault(); navigate('/devices'); }}>
            Devices
          </a>
        </div>
        <div className="banner banner-alert">{error ?? 'Device not found.'}</div>
      </AppShell>
    );
  }

  // Online/offline is recency of last check-in — NOT statusCode (which is config compliance and
  // stays green for a device that was factory-reset and stopped reporting).
  const online = isOnlineByRecency(device.lastUpdate);
  const statusLabel = online ? 'Online' : 'Offline';

  const statusRows: Row[] = [
    { k: 'Battery', v: ds ? (ds.battery < 0 ? '—' : `${ds.battery}% · ${ds.charging ? 'charging' : 'not charging'}`) : '—' },
    { k: 'Screen', v: ds ? (ds.locked ? 'Locked' : 'Unlocked') : '—' },
    { k: 'Kiosk', v: ds ? (ds.kioskActive ? 'On' : 'Off') : '—' },
    { k: 'Connectivity', v: powerLabel(ds?.powerMode) },
  ];
  const hardwareRows: Row[] = [
    { k: 'Android', v: orDash(teleStr(hw.osRelease) ?? ds?.androidRelease ?? device.androidVersion) },
    { k: 'Storage', v: orDash(teleStr(hw.storage) ?? teleStr(hw.storageFree)) },
    { k: 'Serial', v: orDash(teleStr(idn.serial) ?? device.serial), mono: true },
    { k: 'IMEI', v: orDash(teleStr(idn.imei) ?? device.imei), mono: true },
  ];
  const networkRows: Row[] = [
    { k: 'Type', v: orDash(teleStr(dyn.networkType) ?? teleStr(dyn.network)) },
    { k: 'Local IP', v: orDash(teleStr(dyn.localIp) ?? teleStr(hw.localIp)), mono: true },
    { k: 'Public IP', v: orDash(teleStr((tele as Record<string, unknown> | null)?.publicIp) ?? device.publicIp), mono: true },
  ];
  const managementRows: Row[] = [
    { k: 'Config', v: configName },
    { k: 'Agent', v: orDash(ds?.agentVersion ?? device.launcherVersion) },
    { k: 'MDM mode', v: onOff(sec.isDeviceOwner, device.mdmMode) },
    { k: 'Enrolled', v: fmtDateTime(device.enrollTime) },
  ];

  const loc = dyn.location as
    | { lat?: number; lon?: number; accuracyM?: number; provider?: string; capturedAt?: number }
    | undefined;
  const hasFix = !!loc && typeof loc.lat === 'number' && typeof loc.lon === 'number';
  const locationRows: Row[] = hasFix
    ? [
        {
          k: 'Coordinates',
          v: (
            <a href={`https://www.google.com/maps?q=${loc!.lat},${loc!.lon}`} target="_blank" rel="noopener noreferrer">
              {loc!.lat!.toFixed(5)}, {loc!.lon!.toFixed(5)} ↗
            </a>
          ),
          mono: true,
        },
        { k: 'Accuracy', v: loc!.accuracyM != null ? `±${Math.round(loc!.accuracyM)} m` : '—' },
        { k: 'Source', v: orDash(loc!.provider) },
        { k: 'Fix age', v: loc!.capturedAt ? fmtRelative(loc!.capturedAt) : '—' },
      ]
    : [{ k: 'Location', v: 'No fix reported yet' }];

  const groups: Array<{ title: string; rows: Row[] }> = [
    { title: 'Status', rows: statusRows },
    { title: 'Location', rows: locationRows },
    { title: 'Hardware', rows: hardwareRows },
    { title: 'Network', rows: networkRows },
    { title: 'Management', rows: managementRows },
  ];

  return (
    <AppShell title={device.number}>
      <div className="crumb">
        <a href="/devices" onClick={(e) => { e.preventDefault(); navigate('/devices'); }}>
          Devices
        </a>{' '}
        / {device.number}
      </div>

      <div className="dd-cols">
        {/* LEFT: the device */}
        <aside className="panel detail-rail">
          <div className="top">
            <span className={`dot ${online ? 'on' : 'off'}`} />
            <span className={`st ${online ? 'on' : 'off'}`}>{statusLabel}</span>
            <span className="ago">· {fmtRelative(device.lastUpdate)}</span>
            <DeviceGlyph className="ico" name={device.description || device.number} size={20} />
          </div>
          <h1>{device.number}</h1>
          {device.description && <div className="mfr">{device.description}</div>}

          <div className="actions">
            <button className="pri" disabled={busy} onClick={() => void syncNow()}>
              Sync now
            </button>
            <button className="sec" disabled={busy} onClick={() => void lock()}>
              Lock
            </button>
          </div>

          {groups.map((g) => (
            <div key={g.title}>
              <div className="grp">{g.title}</div>
              {g.rows.map((r) => (
                <div className="row" key={r.k}>
                  <span className="k">{r.k}</span>
                  <span className={`v ${r.mono ? 'mono' : ''}`}>{r.v}</span>
                </div>
              ))}
            </div>
          ))}
        </aside>

        {/* RIGHT: work */}
        <section className="panel detail-main">
          <div className="tabs" role="tablist">
            <button className={tab === 'control' ? 'on' : ''} onClick={() => setTab('control')}>
              Control
            </button>
            <button className={tab === 'telemetry' ? 'on' : ''} onClick={() => setTab('telemetry')}>
              Telemetry
            </button>
            <button className={tab === 'events' ? 'on' : ''} onClick={() => setTab('events')}>
              Events
            </button>
            <button className={tab === 'location' ? 'on' : ''} onClick={() => setTab('location')}>
              Location
            </button>
          </div>

          <div className="tabbody">
            {tab === 'control' && <ActionConsole device={device} />}
            {tab === 'telemetry' && <TelemetryCard device={device} />}
            {tab === 'events' && <EventTimeline device={device} />}
            {tab === 'location' && <LocationPanel device={device} />}
          </div>
        </section>
      </div>
    </AppShell>
  );
}
