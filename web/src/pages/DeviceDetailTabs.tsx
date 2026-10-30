import { useId, type ReactNode } from 'react';
import type { CommandHistoryItem } from '../api/commands';
import type { DeviceEvent } from '../api/events';
import type { LocationFix } from '../api/deviceLocations';
import type { TelemetrySnapshot } from '../api/telemetry';
import { DO_NOT_TRACK_HINT } from '../api/locationTracking';
import { fmtDateTime } from '../ui/format';
import {
  ActivityList,
  ActivityRow,
  Button,
  Callout,
  Card,
  CardHeader,
  EmptyState,
  IconAlert,
  IconBattery,
  IconControl,
  IconDownload,
  IconEnroll,
  IconHistory,
  IconInfo,
  IconLock,
  IconNetwork,
  IconPin,
  IconPower,
  IconPulse,
  IconSync,
  IconTrash,
  IconWarning,
  KeyValueGroup,
  RelativeTime,
  type KeyValueItem,
} from '../ui';
import { CommandsCard } from './DeviceDetailControl';
import { LocationMap } from '../ui/LocationMap';
import { EVENT_LABELS } from '../data/deviceActivity';
import {
  DASH,
  capitalise,
  eventTone,
  fmtBytes,
  fmtStorage,
  networkLabel,
  cleanLocation,
  providerLabel,
  teleNum,
  teleObj,
  teleStr,
} from './deviceDetailFormat';

/* ===============================================================================================================
   Telemetry: every key the agent reports, grouped and formatted; unknown keys listed raw so nothing is lost.
   =============================================================================================================== */

type Section = 'hardware' | 'dynamic' | 'security' | 'identity' | 'top';
type Snap = Record<Section, Record<string, unknown>>;

interface RowSpec {
  label: string;
  /** Keys this row reads (consumed, so they don't reappear under Other). */
  keys: Array<[Section, string]>;
  render: (s: Snap) => ReactNode | undefined;
  mono?: boolean;
}

const yesNo = (v: unknown, yes = 'Yes', no = 'No') => (v === true ? yes : v === false ? no : undefined);
const list = (v: unknown) =>
  Array.isArray(v) ? (v.length ? v.map((x) => teleStr(x) ?? '?').join(', ') : undefined) : teleStr(v);
const str = (v: unknown) => (Array.isArray(v) ? undefined : teleStr(v));

function duration(ms: number): string {
  const min = Math.floor(ms / 60000);
  const d = Math.floor(min / 1440);
  const h = Math.floor((min % 1440) / 60);
  const m = min % 60;
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  return `${m}m`;
}

const CHARGING: Record<string, string> = { none: 'Not charging', ac: 'Charging (AC)', usb: 'Charging (USB)', wireless: 'Charging (wireless)' };

const GROUPS: Array<{ title: string; rows: RowSpec[] }> = [
  {
    title: 'Hardware',
    rows: [
      { label: 'Manufacturer', keys: [['hardware', 'manufacturer']], render: (s) => str(s.hardware.manufacturer) && capitalise(String(s.hardware.manufacturer)) },
      { label: 'Brand', keys: [['hardware', 'brand']], render: (s) => str(s.hardware.brand) },
      { label: 'Model', keys: [['hardware', 'model']], render: (s) => str(s.hardware.model) },
      { label: 'Device code', keys: [['hardware', 'device']], render: (s) => str(s.hardware.device), mono: true },
      { label: 'Android', keys: [['hardware', 'osRelease']], render: (s) => str(s.hardware.osRelease) },
      { label: 'API level', keys: [['hardware', 'sdkInt']], render: (s) => str(s.hardware.sdkInt), mono: true },
      { label: 'Security patch', keys: [['hardware', 'securityPatch']], render: (s) => str(s.hardware.securityPatch), mono: true },
      {
        label: 'Screen',
        keys: [['hardware', 'screenWidthPx'], ['hardware', 'screenHeightPx'], ['hardware', 'densityDpi']],
        render: (s) => {
          const w = teleNum(s.hardware.screenWidthPx);
          const h = teleNum(s.hardware.screenHeightPx);
          const dpi = teleNum(s.hardware.densityDpi);
          if (!w || !h) return dpi ? `${dpi} dpi` : undefined;
          return `${w} × ${h} px${dpi ? ` · ${dpi} dpi` : ''}`;
        },
      },
      { label: 'ABIs', keys: [['hardware', 'abis']], render: (s) => list(s.hardware.abis), mono: true },
      { label: 'Build', keys: [['hardware', 'buildFingerprint']], render: (s) => str(s.hardware.buildFingerprint), mono: true },
    ],
  },
  {
    title: 'Storage and memory',
    rows: [
      {
        label: 'Storage used',
        keys: [['hardware', 'totalStorageBytes']],
        render: (s) => {
          const total = teleNum(s.hardware.totalStorageBytes);
          return total ? fmtStorage(total, teleNum(s.dynamic.freeStorageBytes)) : undefined;
        },
      },
      { label: 'Storage free', keys: [['dynamic', 'freeStorageBytes']], render: (s) => (teleNum(s.dynamic.freeStorageBytes) != null ? fmtBytes(teleNum(s.dynamic.freeStorageBytes)) : undefined) },
      { label: 'Memory', keys: [['hardware', 'totalRamBytes']], render: (s) => (teleNum(s.hardware.totalRamBytes) != null ? fmtBytes(teleNum(s.hardware.totalRamBytes)) : undefined) },
      { label: 'Memory free', keys: [['dynamic', 'freeRamBytes']], render: (s) => (teleNum(s.dynamic.freeRamBytes) != null ? fmtBytes(teleNum(s.dynamic.freeRamBytes)) : undefined) },
    ],
  },
  {
    title: 'Battery',
    rows: [
      { label: 'Level', keys: [['dynamic', 'batteryPct']], render: (s) => (teleNum(s.dynamic.batteryPct) != null && (s.dynamic.batteryPct as number) >= 0 ? `${s.dynamic.batteryPct}%` : undefined) },
      { label: 'Power', keys: [['dynamic', 'chargingSource']], render: (s) => (str(s.dynamic.chargingSource) ? CHARGING[String(s.dynamic.chargingSource)] ?? String(s.dynamic.chargingSource) : undefined) },
      {
        label: 'Health',
        keys: [['dynamic', 'batteryHealth']],
        render: (s) => (str(s.dynamic.batteryHealth) ? capitalise(String(s.dynamic.batteryHealth).replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()) : undefined),
      },
      { label: 'Temperature', keys: [['dynamic', 'batteryTempC']], render: (s) => (teleNum(s.dynamic.batteryTempC) != null ? `${(s.dynamic.batteryTempC as number).toFixed(1)} °C` : undefined) },
      { label: 'Voltage', keys: [['dynamic', 'batteryVoltageMv']], render: (s) => (teleNum(s.dynamic.batteryVoltageMv) ? `${((s.dynamic.batteryVoltageMv as number) / 1000).toFixed(2)} V` : undefined) },
    ],
  },
  {
    title: 'Network',
    rows: [
      { label: 'Connection', keys: [['dynamic', 'networkType']], render: (s) => networkLabel(str(s.dynamic.networkType)) },
      { label: 'Wi-Fi network', keys: [['dynamic', 'wifiSsid']], render: (s) => str(s.dynamic.wifiSsid) },
      { label: 'Access point', keys: [['dynamic', 'wifiBssid']], render: (s) => str(s.dynamic.wifiBssid), mono: true },
      { label: 'Wi-Fi signal', keys: [['dynamic', 'wifiRssi']], render: (s) => (teleNum(s.dynamic.wifiRssi) != null ? `${s.dynamic.wifiRssi} dBm` : undefined) },
      { label: 'Link speed', keys: [['dynamic', 'wifiLinkSpeedMbps']], render: (s) => (teleNum(s.dynamic.wifiLinkSpeedMbps) != null ? `${s.dynamic.wifiLinkSpeedMbps} Mbps` : undefined) },
      { label: 'Local IP', keys: [['dynamic', 'ipAddress']], render: (s) => str(s.dynamic.ipAddress), mono: true },
      { label: 'Public IP', keys: [['top', 'publicIp']], render: (s) => str(s.top.publicIp), mono: true },
      { label: 'Mobile operator', keys: [['dynamic', 'cellularOperator']], render: (s) => str(s.dynamic.cellularOperator) },
      { label: 'Mobile signal', keys: [['dynamic', 'cellularSignalLevel']], render: (s) => (teleNum(s.dynamic.cellularSignalLevel) != null ? `${s.dynamic.cellularSignalLevel} of 4` : undefined) },
      { label: 'Roaming', keys: [['dynamic', 'roaming']], render: (s) => yesNo(s.dynamic.roaming) },
    ],
  },
  {
    title: 'State',
    rows: [
      { label: 'Screen', keys: [['dynamic', 'screenOn']], render: (s) => yesNo(s.dynamic.screenOn, 'On', 'Off') },
      { label: 'Lock', keys: [['dynamic', 'locked']], render: (s) => yesNo(s.dynamic.locked, 'Locked', 'Unlocked') },
      { label: 'Kiosk', keys: [['dynamic', 'kioskActive']], render: (s) => yesNo(s.dynamic.kioskActive, 'Running', 'Off') },
      { label: 'Foreground app', keys: [['dynamic', 'foregroundApp']], render: (s) => str(s.dynamic.foregroundApp), mono: true },
      { label: 'Uptime', keys: [['dynamic', 'uptimeMs']], render: (s) => (teleNum(s.dynamic.uptimeMs) != null ? duration(s.dynamic.uptimeMs as number) : undefined) },
      { label: 'Last boot', keys: [['dynamic', 'lastBootAt']], render: (s) => (teleNum(s.dynamic.lastBootAt) ? fmtDateTime(s.dynamic.lastBootAt as number) : undefined) },
    ],
  },
  {
    title: 'Location',
    rows: [
      {
        label: 'Last fix',
        keys: [['dynamic', 'location']],
        render: (s) => {
          const l = cleanLocation(s.dynamic.location);
          if (!l) return undefined;
          const bits = [`${l.lat.toFixed(5)}, ${l.lon.toFixed(5)}`];
          if (l.accuracyM != null) bits.push(`±${Math.round(l.accuracyM)} m`);
          if (l.provider) bits.push(providerLabel(l.provider) ?? l.provider);
          if (l.capturedAt) bits.push(fmtDateTime(l.capturedAt));
          return bits.join(' · ');
        },
      },
    ],
  },
  {
    title: 'Security',
    rows: [
      { label: 'Device owner', keys: [['security', 'isDeviceOwner']], render: (s) => yesNo(s.security.isDeviceOwner) },
      { label: 'Storage encrypted', keys: [['security', 'storageEncrypted']], render: (s) => yesNo(s.security.storageEncrypted) },
      { label: 'Screen lock set', keys: [['security', 'deviceSecure']], render: (s) => yesNo(s.security.deviceSecure) },
      { label: 'USB debugging', keys: [['security', 'adbEnabled']], render: (s) => yesNo(s.security.adbEnabled, 'On', 'Off') },
      { label: 'Developer options', keys: [['security', 'devOptionsEnabled']], render: (s) => yesNo(s.security.devOptionsEnabled, 'On', 'Off') },
      { label: 'Unknown sources', keys: [['security', 'unknownSourcesAllowed']], render: (s) => yesNo(s.security.unknownSourcesAllowed, 'Allowed', 'Blocked') },
      {
        label: 'Patch age',
        keys: [['security', 'patchAgeDays']],
        render: (s) => (teleNum(s.security.patchAgeDays) != null ? `${s.security.patchAgeDays} day${s.security.patchAgeDays === 1 ? '' : 's'}` : undefined),
      },
    ],
  },
  {
    title: 'Identity',
    rows: [
      { label: 'Serial', keys: [['identity', 'serial']], render: (s) => str(s.identity.serial), mono: true },
      { label: 'IMEI', keys: [['identity', 'imei']], render: (s) => list(s.identity.imei), mono: true },
      { label: 'IMSI', keys: [['identity', 'imsi']], render: (s) => list(s.identity.imsi), mono: true },
      { label: 'ICCID', keys: [['identity', 'iccid']], render: (s) => list(s.identity.iccid), mono: true },
      { label: 'Phone number', keys: [['identity', 'phoneNumber']], render: (s) => list(s.identity.phoneNumber), mono: true },
    ],
  },
];

/** Raw value as the classic TelemetryCard showed it. */
function raw(v: unknown): string {
  if (v === null || v === undefined) return DASH;
  if (Array.isArray(v)) return v.length ? v.map((x) => (typeof x === 'object' ? JSON.stringify(x) : String(x))).join(', ') : DASH;
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function telemetryGroups(t: TelemetrySnapshot): Array<{ title: string; items: KeyValueItem[] }> {
  const top = teleObj(t);
  const s: Snap = {
    hardware: teleObj(top.hardware),
    dynamic: teleObj(top.dynamic),
    security: teleObj(top.security),
    identity: teleObj(top.identity),
    top,
  };
  const used = new Set<string>();
  const out: Array<{ title: string; items: KeyValueItem[] }> = [];
  for (const g of GROUPS) {
    const items: KeyValueItem[] = [];
    for (const r of g.rows) {
      const present = r.keys.some(([sec, k]) => k in s[sec]);
      r.keys.forEach(([sec, k]) => used.add(`${sec}.${k}`));
      if (!present) continue;
      const v = r.render(s);
      items.push({ key: r.label, label: r.label, value: v ?? DASH, mono: r.mono && v != null });
    }
    if (items.length) out.push({ title: g.title, items });
  }
  // Anything else the agent sent (older or newer agents): listed raw, like the classic dump.
  const other: KeyValueItem[] = [];
  for (const sec of ['hardware', 'dynamic', 'security', 'identity'] as const) {
    for (const [k, v] of Object.entries(s[sec])) {
      if (!used.has(`${sec}.${k}`)) other.push({ key: `${sec}.${k}`, label: k, value: raw(v), mono: true });
    }
  }
  for (const [k, v] of Object.entries(top)) {
    if (['hardware', 'dynamic', 'security', 'identity'].includes(k) || used.has(`top.${k}`)) continue;
    other.push({ key: `top.${k}`, label: k, value: raw(v), mono: true });
  }
  if (other.length) out.push({ title: 'Other', items: other });
  return out;
}

export function TelemetryTab({ telemetry, loaded, reportedAt }: { telemetry: TelemetrySnapshot | null; loaded: boolean; reportedAt?: number }) {
  if (!telemetry) {
    return (
      <Card>
        <EmptyState icon={<IconPulse />} title={loaded ? 'No telemetry yet' : 'Loading telemetry…'}>
          {loaded ? 'The agent reports its hardware, network and battery details at check-in.' : null}
        </EmptyState>
      </Card>
    );
  }
  const groups = telemetryGroups(telemetry);
  return (
    <div className="v2-device-tele">
      {reportedAt ? (
        <p className="v2-device-meta v2-device-tele__when">
          Reported <RelativeTime value={reportedAt} style="long" seconds />
        </p>
      ) : null}
      <div className="v2-device-tele__grid">
        {groups.map((g) => (
          <Card key={g.title} padding="facts" className="v2-device-tele__card">
            <KeyValueGroup title={g.title} items={g.items} last />
          </Card>
        ))}
      </div>
    </div>
  );
}

/* ===============================================================================================================
   Activity: device events and the full command history
   =============================================================================================================== */

function EventIcon({ type }: { type: string }) {
  switch (type) {
    case 'boot':
      return <IconPower size={15} />;
    case 'appInstalled':
      return <IconDownload size={15} />;
    case 'appUninstalled':
      return <IconTrash size={15} />;
    case 'commandResult':
      return <IconControl size={15} />;
    case 'connectivityChange':
      return <IconNetwork size={15} />;
    case 'lowBattery':
      return <IconBattery size={15} />;
    case 'enrolled':
      return <IconEnroll size={15} />;
    case 'kioskPinLockout':
      return <IconLock size={15} />;
    case 'kioskCrashLoop':
      return <IconAlert size={15} />;
    default:
      return <IconInfo size={15} />;
  }
}

export function ActivityTab({
  events,
  eventsLoaded,
  commands,
  commandsLoaded,
  now,
}: {
  events: DeviceEvent[];
  eventsLoaded: boolean;
  commands: CommandHistoryItem[];
  commandsLoaded: boolean;
  now: number;
}) {
  const titleId = useId();
  return (
    <>
      <Card padding="list" clip aria-labelledby={titleId}>
        <CardHeader
          inset
          spacing={8}
          title="Events"
          titleId={titleId}
          actions={events.length ? <span className="v2-device-meta">{events.length} event{events.length === 1 ? '' : 's'}</span> : null}
        />
        {events.length ? (
          <ActivityList aria-label="Device events">
            {events.map((e) => (
              <ActivityRow
                key={e.id}
                icon={<EventIcon type={e.type} />}
                tone={eventTone(e.type)}
                who={EVENT_LABELS[e.type] ?? e.type}
                what={e.detail ? <span className="v2-device-evdetail">{e.detail}</span> : ''}
                when={<RelativeTime value={e.ts} />}
              />
            ))}
          </ActivityList>
        ) : (
          <div className="v2-device-cmds__empty">
            <EmptyState bare icon={<IconHistory />} title={eventsLoaded ? 'No events yet' : 'Loading events…'}>
              {eventsLoaded ? 'Boots, app installs, network changes and low battery show up here.' : null}
            </EmptyState>
          </div>
        )}
      </Card>
      <CommandsCard title="Commands" items={commands} now={now} loaded={commandsLoaded} />
    </>
  );
}

/* ===============================================================================================================
   Location history (classic LocationPanel)
   =============================================================================================================== */

export function LocationTab({
  fixes,
  error,
  doNotTrack,
  onRefresh,
  refreshing,
}: {
  fixes: LocationFix[] | null;
  error: string | null;
  doNotTrack: boolean | null;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const titleId = useId();
  return (
    <Card aria-labelledby={titleId} gap={14} className="v2-device-loc">
      <CardHeader
        title="Location history"
        titleId={titleId}
        actions={
          <>
            {fixes ? (
              <span className="v2-device-meta">
                {fixes.length} fix{fixes.length === 1 ? '' : 'es'}
                {fixes[0] ? (
                  <>
                    {' · latest '}
                    <RelativeTime value={fixes[0].capturedAt} />
                  </>
                ) : null}
              </span>
            ) : null}
            <Button size="md" icon={<IconSync />} disabled={refreshing} onClick={onRefresh}>
              Refresh
            </Button>
          </>
        }
      />
      {error ? (
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          {error}
        </Callout>
      ) : null}
      {doNotTrack ? (
        <Callout tone="info" icon={<IconInfo />}>
          {DO_NOT_TRACK_HINT}
          {fixes && fixes.length > 0 ? ' The fixes below were reported before tracking was turned off.' : ''}
        </Callout>
      ) : null}
      {!fixes && !error ? (
        <p className="v2-device-meta" role="status">
          Loading location…
        </p>
      ) : null}
      {fixes && fixes.length === 0 && !doNotTrack ? (
        <EmptyState bare icon={<IconPin />} title="No location reported yet">
          Wake the device, or switch it to Accurate location mode.
        </EmptyState>
      ) : null}
      {fixes && fixes.length > 0 ? <LocationMap fixes={fixes} label="Map of the device’s recent locations" /> : null}
    </Card>
  );
}
