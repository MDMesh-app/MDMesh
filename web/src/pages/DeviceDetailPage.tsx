import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import {
  searchDevices, updateDeviceDescription, type DeviceView, type ConfigurationLookup,
} from '../api/devices';
import { ActionConsole } from '../components/ActionConsole';
import { TelemetryCard } from '../components/TelemetryCard';
import { EventTimeline } from '../components/EventTimeline';
import { LocationPanel } from '../components/LocationPanel';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { ConfigStatusCard } from '../components/ConfigStatusCard';
import { getTelemetry, type TelemetrySnapshot } from '../api/telemetry';
import { getConfigStatus, type ConfigStatus } from '../api/configSync';
import {
  getDeviceState, forceSync, queueCommand, syncConfigApps, type DeviceState,
} from '../api/commands';
import { ApiError } from '../api/client';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { useToast } from '../ui/toast';
import { fmtBytes, fmtDateTime, fmtRelative, orDash } from '../ui/format';

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

/** Inline editor for the device's friendly name (description). Click to edit,
 *  Enter/Save to persist, Esc/Cancel to revert. An empty value clears the name. */
function NameField({
  device,
  onSaved,
}: {
  device: DeviceView;
  onSaved: (description: string) => void;
}) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(device.description ?? '');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!editing) setValue(device.description ?? '');
  }, [device.description, editing]);

  const cancel = () => {
    setValue(device.description ?? '');
    setEditing(false);
  };

  async function save() {
    const next = value.trim();
    if (next === (device.description ?? '')) {
      setEditing(false);
      return;
    }
    setSaving(true);
    try {
      await updateDeviceDescription(device.id, next);
      onSaved(next);
      setEditing(false);
      toast.push('ok', 'Name saved', next || 'Name cleared.');
    } catch (e) {
      toast.push('err', 'Rename failed', e instanceof Error ? e.message : '');
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="dd-name-edit">
        <input
          autoFocus
          value={value}
          maxLength={200}
          placeholder="Device name"
          disabled={saving}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void save();
            else if (e.key === 'Escape') cancel();
          }}
        />
        <button className="pri" disabled={saving} onClick={() => void save()}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button className="sec" disabled={saving} onClick={cancel}>
          Cancel
        </button>
      </div>
    );
  }

  return (
    <button type="button" className="dd-name" onClick={() => setEditing(true)} title="Rename this device">
      <span className={`mfr ${device.description ? '' : 'muted'}`}>
        {device.description || 'Add a name'}
      </span>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 20h9" />
        <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
      </svg>
    </button>
  );
}

const NUMBER_PAGE = 500;
const NUMBER_PAGES_MAX = 20;
const ID_PAGE = 1000;
const ID_PAGES_MAX = 10;
/** Database ids are small integers; a longer all-digit value (an IMEI, a serial) is only ever a number. */
const ID_LIKE = /^\d{1,9}$/;

/**
 * The device a /devices/:id link names. Every link carries the device number, so an exact number match
 * wins. The database id is only a fallback for old id-style links, used when no device has that number:
 * /devices/12 must never open the device whose id is 12 while a device numbered "12" exists.
 */
async function resolveDevice(param: string): Promise<{
  device: DeviceView | null;
  configurations: Record<string, ConfigurationLookup>;
  /** Found by database id, not by number. */
  byId?: boolean;
}> {
  // A blank value would drop the server's filter and page through the whole fleet.
  if (!param.trim()) return { device: null, configurations: {} };
  // fastSearch makes the server match the number exactly (or its fast-search suffix), not as a substring.
  for (let pageNum = 1; pageNum <= NUMBER_PAGES_MAX; pageNum++) {
    const res = await searchDevices({ value: param, fastSearch: true, pageSize: NUMBER_PAGE, pageNum });
    const items = res.devices?.items ?? [];
    const hit = items.find((d) => d.number === param);
    if (hit) return { device: hit, configurations: res.configurations ?? {} };
    if (items.length < NUMBER_PAGE) break;
  }
  if (ID_LIKE.test(param)) {
    for (let pageNum = 1; pageNum <= ID_PAGES_MAX; pageNum++) {
      const res = await searchDevices({ pageSize: ID_PAGE, pageNum });
      const items = res.devices?.items ?? [];
      const hit = items.find((d) => String(d.id) === param);
      if (hit) return { device: hit, configurations: res.configurations ?? {}, byId: true };
      if (items.length < ID_PAGE) break;
    }
  }
  return { device: null, configurations: {} };
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
  const [cfgStatus, setCfgStatus] = useState<ConfigStatus | null>(null);
  const [latestLocation, setLatestLocation] = useState<LocationFix | null>(null);
  const [tab, setTab] = useState<Tab>('control');
  const [busy, setBusy] = useState(false);
  const loadSeq = useRef(0);

  const load = useCallback(async () => {
    // A slower answer for the previous route must not replace this one's device.
    const seq = ++loadSeq.current;
    let redirected = false;
    setLoading(true);
    setError(null);
    try {
      // The route param is the device number (see DevicesPage/DashboardPage links).
      const { device: found, configurations, byId } = await resolveDevice(id ?? '');
      if (seq !== loadSeq.current) return;
      if (found && byId) {
        // An old id-style link: show the device under its number, so the URL names what is open.
        redirected = true; // stay on the spinner until the number route loads
        navigate(`/devices/${encodeURIComponent(found.number)}`, { replace: true });
        return;
      }
      setConfigs(configurations);
      setDevice(found);
      if (!found) setError('Device not found.');
    } catch (err) {
      if (seq !== loadSeq.current) return;
      setDevice(null);
      if (err instanceof ApiError && err.httpStatus === 0)
        setError('Cannot reach the server.');
      else setError('Failed to load device.');
    } finally {
      if (seq === loadSeq.current && !redirected) setLoading(false);
    }
  }, [id, navigate]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!device) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    // Self-scheduling poll: the next tick is armed only after the current one finishes, so slow
    // responses can't stack overlapping requests.
    const poll = async () => {
      await Promise.all([
        getTelemetry(device.number).then((v) => { if (on) setTele(v); }).catch(() => undefined),
        getDeviceState(device.number).then((v) => { if (on) setDs(v); }).catch(() => undefined),
        getConfigStatus(device.number).then((v) => { if (on) setCfgStatus(v); }).catch(() => undefined),
      ]);
      if (!on) return;
      t = setTimeout(() => void poll(), 5000);
    };
    void poll();
    return () => { on = false; clearTimeout(t); };
  }, [device]);

  // Location is persisted independently from the compact telemetry snapshot. Refresh it when a
  // real device-state update arrives, so the summary does not claim “no fix” merely because a
  // later check-in omitted its optional dynamic.location object.
  useEffect(() => setLatestLocation(null), [device?.number]);
  useEffect(() => {
    if (!device) return;
    let active = true;
    listLocations(device.number)
      .then((fixes) => { if (active) setLatestLocation(fixes[0] ?? null); })
      .catch(() => undefined);
    return () => { active = false; };
  }, [device?.number, ds?.updatedAt]);

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
      void getConfigStatus(device.number).then(setCfgStatus).catch(() => undefined);
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

  // Re-queue the device's configuration apps (action=install) — for devices enrolled before the
  // config's app list changed, or enrolled before config-driven install existed.
  async function installConfigApps() {
    if (!device) return;
    setBusy(true);
    try {
      const res = await syncConfigApps(device.number);
      toast.push('ok', 'Config apps queued', `${res.queued} install command${res.queued === 1 ? '' : 's'} queued.`);
    } catch (e) {
      toast.push('err', 'Sync apps failed', e instanceof Error ? e.message : '');
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
  // The MDMesh agent reports storage as byte counts (hardware.totalStorageBytes, dynamic.freeStorageBytes) and the
  // local address as dynamic.ipAddress; hw.storage / hw.storageFree / localIp are the Headwind-era names.
  const storageFree = fmtBytes(dyn.freeStorageBytes);
  const storageTotal = fmtBytes(hw.totalStorageBytes);
  const storage = storageFree && storageTotal ? `${storageFree} free of ${storageTotal}` : storageTotal ?? storageFree;
  const hardwareRows: Row[] = [
    { k: 'Android', v: orDash(teleStr(hw.osRelease) ?? ds?.androidRelease ?? device.androidVersion) },
    { k: 'Storage', v: orDash(storage ?? teleStr(hw.storage) ?? teleStr(hw.storageFree)) },
    { k: 'Serial', v: orDash(teleStr(idn.serial) ?? device.serial), mono: true },
    { k: 'IMEI', v: orDash(teleStr(idn.imei) ?? device.imei), mono: true },
  ];
  const networkRows: Row[] = [
    { k: 'Type', v: orDash(teleStr(dyn.networkType) ?? teleStr(dyn.network)) },
    { k: 'Local IP', v: orDash(teleStr(dyn.ipAddress) ?? teleStr(dyn.localIp) ?? teleStr(hw.localIp)), mono: true },
    { k: 'Public IP', v: orDash(teleStr((tele as Record<string, unknown> | null)?.publicIp) ?? device.publicIp), mono: true },
  ];
  const managementRows: Row[] = [
    { k: 'Config', v: configName },
    // Only the agent's own report: device.launcherVersion is the configuration's main-app version
    // (Headwind assumed the main app was its launcher), so a kiosk app's version would show here.
    { k: 'Agent', v: orDash(ds?.agentVersion) },
    { k: 'MDM mode', v: onOff(sec.isDeviceOwner, device.mdmMode) },
    { k: 'Enrolled', v: fmtDateTime(device.enrollTime) },
  ];

  const telemetryLocation = dyn.location as
    | { lat?: number; lon?: number; accuracyM?: number; provider?: string; capturedAt?: number }
    | undefined;
  const storedLocation = latestLocation
    ? {
        lat: latestLocation.lat,
        lon: latestLocation.lon,
        accuracyM: latestLocation.accuracy,
        provider: latestLocation.provider,
        capturedAt: latestLocation.capturedAt,
      }
    : undefined;
  const loc = storedLocation ?? telemetryLocation;
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
          <NameField
            device={device}
            onSaved={(desc) => setDevice((d) => (d ? { ...d, description: desc } : d))}
          />

          <div className="actions">
            <button className="pri" disabled={busy} onClick={() => void syncNow()}>
              Sync now
            </button>
            <button className="sec" disabled={busy} onClick={() => void lock()}>
              Lock
            </button>
            <button className="sec" disabled={busy} onClick={() => void installConfigApps()}>
              Install config apps
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

          <ConfigStatusCard status={cfgStatus} />
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
