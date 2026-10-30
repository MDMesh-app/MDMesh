import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import {
  getDeviceByNumber,
  searchDevices,
  updateDeviceDescription,
  type ConfigurationLookup,
  type DeviceView,
} from '../api/devices';
import { getTelemetry, type TelemetrySnapshot } from '../api/telemetry';
import { getConfigStatus, summarizeStatus, type ConfigStatus } from '../api/configSync';
import {
  forceSync,
  getDeviceState,
  listCommandHistory,
  queueCommand,
  syncConfigApps,
  type CommandHistoryItem,
  type CommandTemplateExt,
  type DeviceState,
  type QueueCommandRequest,
} from '../api/commands';
import { getEvents, type DeviceEvent } from '../api/events';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { getConfiguration, getConfigurationApps, type ConfigApp, type Configuration } from '../api/configurations';
import { listApplications, type Application } from '../api/applications';
import { ApiError } from '../api/client';
import { fmtDateTime } from '../ui/format';
import { paths } from '../routes';
import {
  Breadcrumb,
  Button,
  Callout,
  Card,
  DEVICE_KIND_LABEL,
  DeviceGlyph,
  IconAlert,
  IconDownload,
  IconLock,
  IconPencil,
  IconPower,
  IconRing,
  IconScreenMessage,
  IconSync,
  IconWarning,
  IconButton,
  Input,
  LinkButton,
  Menu,
  StatusText,
  deriveDeviceKind,
  kindInputFromTelemetry,
  useToast,
  type KeyValueItem,
} from '../ui';
import { ActionDialog, CommandsCard, ConfigPanel, RemoteActions, TEMPLATE, type KioskReturnOffer } from './DeviceDetailControl';
import { ActivityTab, LocationTab, TelemetryTab } from './DeviceDetailTabs';
import { ConfiguredKioskDialog, DeviceKioskDialog } from './DeviceKioskDialog';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { withCoreParts } from '../ext/core/device';
import { usePermission } from '../ext/permissions';
import { commandRequest, type DeviceCommand } from '../ext/types';
import { DeviceRailGroups } from '../ext/hosts/DeviceRail';
import { DeviceTabs, useDeviceTabs } from '../ext/hosts/DeviceTabs';
import { DeviceProvider } from '../ext/useDevice';
import {
  configuredKiosk,
  configuredKioskIds,
  configuredKioskState,
  rememberConfiguredKiosk,
} from '../data/kiosk';
import { ISSUE_REASON, issueKind, presenceOf } from '../data/fleetHealth';
import {
  DASH,
  cleanFixes,
  cleanLocation,
  fmtAge,
  isoTime,
  fmtStorage,
  locationSince,
  makeModel,
  networkLabel,
  powerLabel,
  providerLabel,
  teleNum,
  teleObj,
  teleStr,
  validTime,
} from './deviceDetailFormat';
import './DeviceDetailPage.css';

const POLL_MS = 5000;
const DAY = 24 * 3600 * 1000;

/** Re-render on an interval so relative times ("checked in 3m ago") and presence stay current. */
function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/* ===============================================================================================================
   Name (description) editing, in the rail header
   =============================================================================================================== */

function DeviceName({
  device,
  canRename,
  onSaved,
}: {
  device: DeviceView;
  /** The role holds edit_device_desc; without it the name shows without its pencil. */
  canRename: boolean;
  onSaved: (description: string) => void;
}) {
  const toast = useToast();
  const inputId = useId();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(device.description ?? '');
  const [saving, setSaving] = useState(false);
  const pencilRef = useRef<HTMLButtonElement>(null);
  const wasEditing = useRef(false);

  useEffect(() => {
    if (!editing) setValue(device.description ?? '');
  }, [device.description, editing]);

  // Focus goes back to the pencil when the editor closes.
  useEffect(() => {
    if (wasEditing.current && !editing) pencilRef.current?.focus();
    wasEditing.current = editing;
  }, [editing]);

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
      <form
        className="v2-device-rename"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <label htmlFor={inputId} className="v2-visually-hidden">
          Device name
        </label>
        <Input
          id={inputId}
          autoFocus
          value={value}
          maxLength={200}
          placeholder="Device name"
          disabled={saving}
          className="v2-device-rename__input"
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              e.preventDefault();
              cancel();
            }
          }}
        />
        <div className="v2-device-rename__actions">
          <Button type="submit" variant="primary" size="md" disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
          <Button size="md" disabled={saving} onClick={cancel}>
            Cancel
          </Button>
        </div>
      </form>
    );
  }

  const named = !!device.description;
  return (
    <div className="v2-device-name">
      <h1 className="v2-device-name__h">{named ? device.description : device.number}</h1>
      {canRename ? (
        <IconButton
          ref={pencilRef}
          size="md"
          className="v2-device-name__edit"
          label={named ? 'Rename device' : 'Add a name'}
          title={named ? 'Rename this device' : 'Add a name'}
          onClick={() => setEditing(true)}
        >
          <IconPencil size={15} />
        </IconButton>
      ) : null}
    </div>
  );
}

/* ===============================================================================================================
   Page
   =============================================================================================================== */

/**
 * Device detail, left-rail layout with a tabbed panel. Classic: pages/DeviceDetailPage.tsx. The
 * page loads and polls the device; its tabs and rail groups are the registry's `device.tab` and `device.railGroup`
 * contributions, core's (ext/core/device.tsx) and extensions' alike, which read it through `useDevice()`.
 */
export function DeviceDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  const now = useNow(30_000);
  const tabsId = useId();
  // What the role may change (ext/core/access.ts); the reads stay for everyone.
  const canEdit = usePermission(CORE_ACTION_ACCESS.editDevices);
  const canRename = usePermission(CORE_ACTION_ACCESS.renameDevice);

  const [device, setDevice] = useState<DeviceView | null>(null);
  const [configs, setConfigs] = useState<Record<string, ConfigurationLookup>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [tele, setTele] = useState<TelemetrySnapshot | null>(null);
  const [teleLoaded, setTeleLoaded] = useState(false);
  const [ds, setDs] = useState<DeviceState | null>(null);
  const [dsLoaded, setDsLoaded] = useState(false);
  const [cfgStatus, setCfgStatus] = useState<ConfigStatus | null>(null);
  const [cfgLoaded, setCfgLoaded] = useState(false);
  const [history, setHistory] = useState<CommandHistoryItem[]>([]);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [events, setEvents] = useState<DeviceEvent[]>([]);
  const [eventsLoaded, setEventsLoaded] = useState(false);
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [fixesError, setFixesError] = useState<string | null>(null);
  const [fixesLoading, setFixesLoading] = useState(false);
  // The Location tab's time range, in hours (0: All). The rail shows the newest fix any load found, so a range with
  // no fix in it doesn't blank the device's location there.
  const [fixesRange, setFixesRange] = useState(0);
  const [latestFix, setLatestFix] = useState<LocationFix | null>(null);
  const [configuration, setConfiguration] = useState<Configuration | null>(null);
  const [configurationError, setConfigurationError] = useState<string | null>(null);
  // The configuration's assigned apps, loaded when its kiosk mode is on (the configured kiosk is built from them).
  const [cfgApps, setCfgApps] = useState<ConfigApp[] | null>(null);
  const [cfgAppsError, setCfgAppsError] = useState<string | null>(null);
  const [cfgAppsAttempt, setCfgAppsAttempt] = useState(0);
  // kiosk.enter command ids this browser sent as the configured kiosk (the history can't tell them from a custom one).
  const [configuredIds, setConfiguredIds] = useState<Set<string>>(() => new Set());
  const [library, setLibrary] = useState<Application[] | null>(null);

  const deviceTabs = useDeviceTabs();
  const [busy, setBusy] = useState(false);
  const [dialog, setDialog] = useState<CommandTemplateExt | null>(null);
  const [kioskOpen, setKioskOpen] = useState(false);
  const [returnOpen, setReturnOpen] = useState(false);
  const [powerRequested, setPowerRequested] = useState<string | null>(null);
  const [locationRequested, setLocationRequested] = useState<string | null>(null);

  /* ---- device lookup ---- */
  // The route param is the device number (every link in the console uses it) or, from old links, the database id.
  // An exact number match always wins; the id is tried only when no device has that number, and a substring match
  // is never taken (a device numbered "12" must not open the one whose id is 12, or "WH-12" for "12").
  // An old link moves to the number route with its query and hash kept. They are read through a ref, so opening a
  // tab (`?tab=`) does not load the device again.
  const queryRef = useRef('');
  useEffect(() => {
    queryRef.current = location.search + location.hash;
  }, [location.search, location.hash]);
  const loadSeq = useRef(0);
  const load = useCallback(async () => {
    const seq = ++loadSeq.current;
    const current = () => seq === loadSeq.current;
    let redirected = false;
    /** Found by database id (an old link), not by number. */
    let byId = false;
    setLoading(true);
    setError(null);
    setDevice(null);
    try {
      // The exact lookup decides; the narrow search supplies the configuration names and the list's richer row.
      const [lookupResult, res] = await Promise.all([
        getDeviceByNumber(id).then(
          (d) => ({ ok: true as const, d }),
          (e: unknown) => ({ ok: false as const, e }),
        ),
        searchDevices({ value: id, pageSize: 100 }),
      ]);
      if (!lookupResult.ok && lookupResult.e instanceof ApiError && lookupResult.e.httpStatus === 0) throw lookupResult.e;
      const exact = lookupResult.ok ? lookupResult.d : null;
      const row = (res.devices?.items ?? []).find((d) => d.number === id);
      let found: DeviceView | null = row && (!exact || exact.id === row.id) ? row : exact ?? row ?? null;
      let lookup = res.configurations ?? {};
      // The lookup failed and the search has no exact row: whether a device has this number is unknown, so the id
      // is never tried (it could open, and act on, a different device).
      if (!found && !lookupResult.ok) throw lookupResult.e;
      if (!found && /^[1-9]\d{0,9}$/.test(id)) {
        const all = await searchDevices({ pageSize: 1000 });
        const items = all.devices?.items ?? [];
        found = items.find((d) => d.number === id) ?? null;
        if (!found) {
          found = items.find((d) => d.id === Number(id)) ?? null;
          byId = found != null;
        }
        lookup = { ...lookup, ...(all.configurations ?? {}) };
      }
      if (!current()) return;
      if (found && byId) {
        // An old id-style link: show the device under its number, so the URL names what is open (and a bookmark
        // made now keeps working). Stay on the spinner until the number route loads.
        redirected = true;
        navigate(paths.device(found.number) + queryRef.current, { replace: true });
        return;
      }
      setConfigs(lookup);
      setDevice(found);
      if (!found) setError('Device not found.');
    } catch (err) {
      if (!current()) return;
      setDevice(null);
      if (err instanceof ApiError && err.httpStatus === 0) setError('Cannot reach the server.');
      else setError('Failed to load device.');
    } finally {
      if (current() && !redirected) setLoading(false);
    }
  }, [id, navigate]);

  useEffect(() => {
    void load();
  }, [load]);

  const number = device?.number;

  const fixesSeq = useRef(0);
  // A different device: forget the previous one's data (and drop its answers still in flight).
  useEffect(() => {
    fixesSeq.current++;
    setTele(null);
    setTeleLoaded(false);
    setDs(null);
    setDsLoaded(false);
    setCfgStatus(null);
    setCfgLoaded(false);
    setHistory([]);
    setHistoryLoaded(false);
    setEvents([]);
    setEventsLoaded(false);
    setFixes(null);
    setFixesError(null);
    setFixesLoading(false);
    setFixesRange(0);
    setLatestFix(null);
    setPowerRequested(null);
    setLocationRequested(null);
    setDialog(null);
    setKioskOpen(false);
    setReturnOpen(false);
    setConfiguredIds(number ? configuredKioskIds(number) : new Set());
  }, [number]);

  /* ---- polling: state, telemetry, config status, command history, events ----
     Self-scheduling and never overlapping; every answer is dropped once the page shows another device. A refresh
     (after an action) runs the next round now, or right after the one in flight. Paused while the tab is hidden. */
  const kickRef = useRef<() => void>(() => undefined);
  useEffect(() => {
    if (!number) return;
    let on = true;
    let running = false;
    let again = false;
    let t: ReturnType<typeof setTimeout> | undefined;
    const run = async () => {
      running = true;
      clearTimeout(t);
      await Promise.all([
        getTelemetry(number)
          .then((v) => {
            if (!on) return;
            // A snapshot that isn't an object counts as none (nothing below may read fields off a string).
            setTele(v && typeof v === 'object' && !Array.isArray(v) ? v : null);
            setTeleLoaded(true);
          })
          .catch(() => undefined),
        getDeviceState(number)
          .then((v) => {
            if (!on) return;
            setDs(v);
            setDsLoaded(true);
          })
          .catch(() => undefined),
        getConfigStatus(number)
          .then((v) => {
            if (!on) return;
            setCfgStatus(v);
            setCfgLoaded(true);
          })
          .catch(() => undefined),
        listCommandHistory(number)
          .then((v) => {
            if (!on) return;
            setHistory(Array.isArray(v) ? v : []);
            setHistoryLoaded(true);
          })
          .catch(() => undefined),
        getEvents(number)
          .then((v) => {
            if (!on) return;
            setEvents(Array.isArray(v) ? v : []);
            setEventsLoaded(true);
          })
          .catch(() => undefined),
      ]);
      running = false;
      if (!on) return;
      if (again) {
        again = false;
        void run();
      } else if (!document.hidden) {
        t = setTimeout(() => void run(), POLL_MS);
      }
    };
    const kick = () => {
      if (!on) return;
      if (running) again = true;
      else void run();
    };
    const onVisibility = () => {
      if (!document.hidden && !running) {
        clearTimeout(t);
        void run();
      }
    };
    kickRef.current = kick;
    document.addEventListener('visibilitychange', onVisibility);
    void run();
    return () => {
      on = false;
      clearTimeout(t);
      document.removeEventListener('visibilitychange', onVisibility);
      kickRef.current = () => undefined;
    };
  }, [number]);
  const refresh = useCallback(() => kickRef.current(), []);

  /* ---- location history: reloaded on every new state report, and on demand ---- */
  const loadFixes = useCallback(async () => {
    if (!number) return;
    const seq = ++fixesSeq.current;
    const current = () => seq === fixesSeq.current;
    setFixesLoading(true);
    setFixesError(null);
    try {
      const list = cleanFixes(await listLocations(number, locationSince(fixesRange, Date.now())));
      if (!current()) return;
      setFixes(list);
      // A range runs up to now, so a non-empty answer starts with the device's newest fix.
      if (list[0]) setLatestFix(list[0]);
    } catch (e) {
      if (current()) setFixesError(e instanceof Error ? e.message : 'Failed to load locations');
    } finally {
      if (current()) setFixesLoading(false);
    }
  }, [number, fixesRange]);

  useEffect(() => {
    void loadFixes();
  }, [loadFixes, ds?.updatedAt]);

  // Another range: say it is loading rather than show the last range's fixes under the new one's name.
  const changeFixesRange = (hours: number) => {
    if (hours === fixesRange) return;
    setFixesRange(hours);
    setFixes(null);
  };

  /* ---- the configuration (location tracking mode) ---- */
  const configurationId = device?.configurationId;
  useEffect(() => {
    setConfiguration(null);
    setConfigurationError(null);
    if (configurationId == null) return;
    let active = true;
    let t: ReturnType<typeof setTimeout> | undefined;
    // Retried until it loads: the Do-not-track lock and the configured kiosk below depend on it.
    const fetchConfiguration = () =>
      getConfiguration(configurationId)
        .then((c) => {
          if (!active) return;
          setConfiguration(c);
          setConfigurationError(null);
        })
        .catch((e: unknown) => {
          if (!active) return;
          setConfigurationError(e instanceof Error && e.message ? e.message : 'request failed');
          t = setTimeout(fetchConfiguration, 15_000);
        });
    void fetchConfiguration();
    return () => {
      active = false;
      clearTimeout(t);
    };
  }, [configurationId]);

  const kioskConfigured = configuration?.kioskMode === true;
  useEffect(() => {
    setCfgApps(null);
    setCfgAppsError(null);
    if (configurationId == null || !kioskConfigured) return;
    let active = true;
    getConfigurationApps(configurationId)
      .then((apps) => {
        if (active) setCfgApps(Array.isArray(apps) ? apps : []);
      })
      .catch((e: unknown) => {
        if (active) setCfgAppsError(e instanceof Error && e.message ? e.message : 'request failed');
      });
    return () => {
      active = false;
    };
  }, [configurationId, kioskConfigured, cfgAppsAttempt]);

  // Same rule as useDoNotTrack: unset counts as Do not track; null while unknown.
  const doNotTrack: boolean | null =
    configurationId == null || !configuration
      ? null
      : ((configuration.requestUpdates as string | undefined) ?? 'DONOTTRACK') === 'DONOTTRACK';

  /* ---- the kiosk app's name, from the library, while kiosk runs ---- */
  const dyn = teleObj(tele?.dynamic);
  const foreground = teleStr(dyn.foregroundApp);
  const kioskRunning = ds?.kioskActive === true;
  useEffect(() => {
    if (!kioskRunning || !foreground || library) return;
    let active = true;
    listApplications()
      .then((l) => {
        if (active) setLibrary(l);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [kioskRunning, foreground, library]);

  // A requested connection mode stops being "pending" once the device reports it.
  useEffect(() => {
    if (powerRequested && ds?.powerMode === powerRequested) setPowerRequested(null);
  }, [ds?.powerMode, powerRequested]);

  /* ---- document title ---- */
  const docName = device ? device.description || device.number : null;
  useEffect(() => {
    if (!docName) return;
    const prev = document.title;
    document.title = `${docName} · MDMesh`;
    return () => {
      document.title = prev;
    };
  }, [docName]);

  /* ---- actions ---- */
  async function syncNow() {
    if (!number) return;
    setBusy(true);
    try {
      await forceSync(number);
      refresh();
      toast.push('ok', 'Sync requested', '');
    } catch (e) {
      toast.push('err', 'Sync failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function installConfigApps() {
    if (!number) return;
    setBusy(true);
    try {
      const res = await syncConfigApps(number);
      toast.push('ok', 'Config apps queued', `${res.queued} install command${res.queued === 1 ? '' : 's'} queued.`);
      refresh();
    } catch (e) {
      toast.push('err', 'Sync apps failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  /**
   * Queue a command, toast the outcome, nudge the device and refresh. Resolves to whether it was queued. `onQueued`
   * runs as soon as the server has the command, before the nudge, which can take a while.
   */
  const queue = useCallback(
    async (label: string, request: () => QueueCommandRequest, onQueued?: () => void): Promise<boolean> => {
      if (!number) return false;
      try {
        const res = await queueCommand(number, request());
        const cmdId = (res?.id as number | string | undefined) ?? '';
        toast.push('ok', `${label} queued`, cmdId ? `Command ${cmdId}` : '');
        onQueued?.();
        await forceSync(number).catch(() => undefined); // nudge the device
        refresh();
        return true;
      } catch (e) {
        toast.push('err', `${label} failed`, e instanceof Error ? e.message : '');
        return false;
      }
    },
    [number, toast, refresh],
  );

  /**
   * One of the page's own commands: its actions are greyed out until the nudge is done; its dialog closes, and a mode
   * it requests shows pending, as soon as it is queued.
   */
  async function send(t: CommandTemplateExt, values: Record<string, string> = {}) {
    setBusy(true);
    try {
      await queue(
        t.label,
        () => (t.build ? t.build(values) : t.request),
        () => {
          if (t.key === 'power-always' || t.key === 'power-adaptive') {
            setPowerRequested(t.key === 'power-always' ? 'alwaysOn' : 'adaptive');
          }
          if (t.key === 'location-active' || t.key === 'location-passive') {
            setLocationRequested(t.key === 'location-active' ? 'active' : 'passive');
          }
          setDialog(null);
        },
      );
    } finally {
      setBusy(false);
    }
  }

  // An extension's command goes through `queue` alone: it never greys out the page's actions (the tab shows its own
  // progress), and whatever else the object carries (a `key`) cannot make it pass for one of the page's commands.
  const sendCommand = useCallback(
    (c: DeviceCommand, values: Record<string, string> = {}) => queue(c.label, () => commandRequest(c, values)),
    [queue],
  );

  function runAction(t: CommandTemplateExt) {
    if (t.key === 'kiosk-enter') {
      setKioskOpen(true);
      return;
    }
    if ((t.params && t.params.length > 0) || t.confirm) {
      setDialog(t);
      return;
    }
    void send(t, {});
  }

  /* ---- derived ---- */
  const hw = teleObj(tele?.hardware);
  const idn = teleObj(tele?.identity);
  const sec = teleObj(tele?.security);
  const topTele = teleObj(tele);

  const verdict = summarizeStatus(cfgStatus);
  const issue = cfgLoaded ? issueKind(verdict) : null;
  // Last contact: the device row's lastUpdate (loaded once) or the polled state report, whichever is newer, so a
  // device that keeps reporting doesn't turn Offline while the page stays open.
  const lastSeen = Math.max(validTime(device?.lastUpdate) ?? 0, validTime(ds?.updatedAt) ?? 0) || undefined;
  const presence = device ? presenceOf(lastSeen, issue !== null, now) : 'offline';

  const kind = deriveDeviceKind({
    ...kindInputFromTelemetry(tele),
    kioskMode: device?.kioskMode,
    kioskActive: ds?.kioskActive,
    name: device?.description,
  });
  const configName =
    device?.configurationId != null
      ? configs[String(device.configurationId)]?.name ??
        (configuration?.id === device.configurationId ? configuration.name : undefined)
      : undefined;

  const counts24h = useMemo(() => {
    const since = now - DAY;
    return events.filter((e) => e.ts >= since).length + history.filter((c) => (c.createdAt ?? 0) >= since).length;
  }, [events, history, now]);

  // Location mode: the agent doesn't report it. The configuration sets it on config.apply, so it is known
  // only while no device-level change was ever sent; a change sent from this page is shown as requested.
  const hasLocationCommand = history.some((c) => c.type === 'device.locationMode');
  const requestUpdates = configuration?.requestUpdates as string | undefined;
  const configLocationMode = requestUpdates === 'GPS' ? 'active' : requestUpdates === 'WIFI' ? 'passive' : undefined;
  const locationMode = locationRequested ?? (hasLocationCommand ? undefined : configLocationMode);
  const powerShown = powerRequested ?? ds?.powerMode ?? undefined;

  /* ---- the configured kiosk ("Return to configured kiosk") ---- */
  const kiosk = useMemo(
    () => (configuration && cfgApps ? configuredKiosk(configuration, cfgApps) : null),
    [configuration, cfgApps],
  );
  const kioskState = configuredKioskState({ history, kioskActive: ds?.kioskActive, inSync: cfgStatus?.inSync, configuredIds });
  const cfgStart = configName ?? 'Its configuration';
  const cfgMid = configName ?? 'its configuration';
  const openReturn = () => setReturnOpen(true);
  let kioskReturn: KioskReturnOffer;
  if (configurationId == null) {
    kioskReturn = { offered: false, onReturn: openReturn, hint: 'This device has no configuration, so there is no configured kiosk to return to.' };
  } else if (!configuration) {
    kioskReturn = {
      offered: false,
      onReturn: openReturn,
      hint: configurationError
        ? `Couldn’t load ${cfgMid}, so its kiosk isn’t available here: ${configurationError}. Trying again in 15 seconds.`
        : undefined,
    };
  } else if (configuration.kioskMode !== true) {
    kioskReturn = { offered: false, onReturn: openReturn, hint: `${cfgStart} doesn’t use kiosk mode, so there is no configured kiosk to return to.` };
  } else if (cfgAppsError) {
    kioskReturn = {
      offered: true,
      disabled: true,
      onReturn: openReturn,
      hint: (
        <>
          Couldn’t load the apps of {cfgMid}, so its kiosk can’t be built: {cfgAppsError}.{' '}
          <LinkButton onClick={() => setCfgAppsAttempt((n) => n + 1)}>Try again</LinkButton>
        </>
      ),
    };
  } else if (!kiosk) {
    kioskReturn = { offered: true, disabled: true, onReturn: openReturn, hint: `Loading the kiosk that ${cfgMid} defines…` };
  } else if (kiosk.apps.length === 0) {
    kioskReturn = {
      offered: true,
      disabled: true,
      onReturn: openReturn,
      hint: `${cfgStart} has kiosk mode on, but no app is set to Install and shown in kiosk, so its kiosk has nothing to show. Add one in the configuration.`,
    };
  } else if (kioskState === 'sending') {
    kioskReturn = { offered: true, disabled: true, onReturn: openReturn, hint: `Waiting for the device to enter the kiosk that ${cfgMid} defines.` };
  } else if (kioskState === 'active') {
    kioskReturn = { offered: true, disabled: true, onReturn: openReturn, hint: `The device is already in the kiosk that ${cfgMid} defines.` };
  } else {
    kioskReturn = { offered: true, onReturn: openReturn };
  }

  function onReturnQueued(cmdId: number | string | undefined) {
    if (!number) return;
    if (cmdId != null) {
      rememberConfiguredKiosk(number, cmdId);
      setConfiguredIds((prev) => new Set([...prev, String(cmdId)]));
    }
    void forceSync(number) // nudge the device
      .catch(() => undefined)
      .then(refresh);
  }

  /* ---- states without a device ---- */
  if (loading) {
    return (
      <>
        <Breadcrumb items={[{ label: 'Devices', to: paths.devices }, { label: id, mono: true }]} />
        <Card>
          <p className="v2-device-loading" role="status">
            <span className="v2-device-spin" aria-hidden="true" /> Loading device…
          </p>
        </Card>
      </>
    );
  }

  if (!device) {
    return (
      <>
        <Breadcrumb items={[{ label: 'Devices', to: paths.devices }, { label: id, mono: true }]} />
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          {error ?? 'Device not found.'}
        </Callout>
        {error !== 'Device not found.' ? (
          <div>
            <Button size="lg" icon={<IconSync />} onClick={() => void load()}>
              Try again
            </Button>
          </div>
        ) : null}
      </>
    );
  }

  /* ---- rail groups ---- */
  const batteryText =
    ds && typeof ds.battery === 'number' && Number.isFinite(ds.battery) && ds.battery >= 0
      ? `${Math.round(ds.battery)}% · ${ds.charging ? 'charging' : 'not charging'}`
      : DASH;
  const kioskApp = foreground ? library?.find((a) => a.pkg === foreground)?.name ?? foreground : undefined;
  const kioskText = ds ? (ds.kioskActive ? (kioskApp ? `Running · ${kioskApp}` : 'Running') : 'Off') : DASH;
  const net = networkLabel(teleStr(dyn.networkType));
  const power = powerLabel(ds?.powerMode);
  const connection = net && power ? `${net} · ${power.toLowerCase()}` : net ?? power ?? DASH;

  const statusItems: KeyValueItem[] = [
    { key: 'battery', label: 'Battery', value: batteryText },
    { key: 'screen', label: 'Screen', value: ds ? (ds.locked ? 'Locked' : 'Unlocked') : DASH },
    { key: 'kiosk', label: 'Kiosk', value: kioskText },
    { key: 'conn', label: 'Connection', value: connection },
  ];

  const loc = latestFix
    ? { lat: latestFix.lat, lon: latestFix.lon, accuracyM: latestFix.accuracy, provider: latestFix.provider, capturedAt: latestFix.capturedAt }
    : cleanLocation(dyn.location);
  const fixIso = isoTime(loc?.capturedAt);
  const locationItems: KeyValueItem[] = loc
    ? [
        { key: 'coords', label: 'Coordinates', value: `${loc.lat.toFixed(5)}, ${loc.lon.toFixed(5)}`, mono: true },
        { key: 'acc', label: 'Accuracy', value: loc.accuracyM != null ? `±${Math.round(loc.accuracyM)} m` : DASH },
        {
          key: 'src',
          label: 'Source',
          value: loc.provider ? <span title={loc.provider}>{providerLabel(loc.provider)}</span> : DASH,
        },
        {
          key: 'age',
          label: 'Fix age',
          value:
            loc.capturedAt && fixIso ? (
              <time dateTime={fixIso} title={fmtDateTime(loc.capturedAt)}>
                {fmtAge(loc.capturedAt, now)}
              </time>
            ) : (
              DASH
            ),
        },
      ]
    : [{ key: 'none', label: 'Location', value: doNotTrack ? 'Not tracked' : 'No fix reported yet' }];

  const model = teleStr(hw.model);
  const hardwareItems: KeyValueItem[] = [
    { key: 'model', label: 'Model', value: model ?? DASH },
    {
      key: 'android',
      label: 'Android',
      value: teleStr(hw.osRelease) ?? (ds?.androidRelease || undefined) ?? device.androidVersion ?? DASH,
    },
    { key: 'storage', label: 'Storage', value: fmtStorage(teleNum(hw.totalStorageBytes), teleNum(dyn.freeStorageBytes)) },
    { key: 'serial', label: 'Serial', value: teleStr(idn.serial) ?? device.serial ?? DASH, mono: true },
    { key: 'imei', label: 'IMEI', value: teleStr(idn.imei) ?? device.imei ?? DASH, mono: true },
  ];
  const networkItems: KeyValueItem[] = [
    { key: 'local', label: 'Local IP', value: teleStr(dyn.ipAddress) ?? DASH, mono: true },
    { key: 'public', label: 'Public IP', value: teleStr(topTele.publicIp) ?? device.publicIp ?? DASH, mono: true },
  ];
  const deviceOwner = typeof sec.isDeviceOwner === 'boolean' ? sec.isDeviceOwner : undefined;
  const ownerSource = deviceOwner ?? device.mdmMode;
  const managementItems: KeyValueItem[] = [
    { key: 'cfg', label: 'Configuration', value: configName ?? DASH },
    // The agent's own report only: device.launcherVersion is the configuration's main-app version.
    { key: 'agent', label: 'Agent', value: ds?.agentVersion || DASH, mono: !!ds?.agentVersion },
    { key: 'mdm', label: 'MDM mode', value: ownerSource == null ? DASH : ownerSource ? 'Device owner' : 'Not device owner' },
    { key: 'enrolled', label: 'Enrolled', value: device.enrollTime ? fmtDateTime(device.enrollTime) : DASH },
  ];

  const subline = [DEVICE_KIND_LABEL[kind], makeModel(teleStr(hw.manufacturer), model)].filter(Boolean).join(' · ');
  const reasonText = presence === 'attention' && issue ? ISSUE_REASON[issue] : undefined;
  const lastAge = lastSeen ? fmtAge(lastSeen, now) : null;
  const lastIso = isoTime(lastSeen);

  // The device as its tabs and rail groups read it, with core's own tab bodies and rail rows (ext/core/device.tsx).
  const deviceValue = withCoreParts(
    {
      device,
      telemetry: tele,
      telemetryLoaded: teleLoaded,
      state: ds,
      configuration,
      configStatus: cfgStatus,
      now,
      refresh,
      sendCommand,
    },
    {
      tabs: {
        control: (
          <>
            {cfgLoaded ? (
              <ConfigPanel
                status={cfgStatus}
                configName={configName}
                isDeviceOwner={deviceOwner}
                busy={busy}
                onRetry={canEdit ? () => void syncNow() : undefined}
                now={now}
              />
            ) : null}
            {canEdit ? (
              <RemoteActions
                busy={busy}
                onAction={runAction}
                onEnterKiosk={() => setKioskOpen(true)}
                kioskReturn={kioskReturn}
                onInstallConfigApps={() => void installConfigApps()}
                powerMode={powerShown}
                powerPending={!!powerRequested && ds?.powerMode !== powerRequested}
                locationMode={locationMode}
                locationPending={!!locationRequested}
                locationUnknown={!locationRequested && hasLocationCommand}
                doNotTrack={doNotTrack}
              />
            ) : null}
            <CommandsCard
              title="Recent commands"
              items={history}
              limit={5}
              now={now}
              loaded={historyLoaded}
              onAll={() => deviceTabs.select('activity')}
            />
          </>
        ),
        telemetry: <TelemetryTab telemetry={tele} loaded={teleLoaded} reportedAt={ds?.updatedAt} />,
        activity: (
          <ActivityTab events={events} eventsLoaded={eventsLoaded} commands={history} commandsLoaded={historyLoaded} now={now} />
        ),
        location: (
          <LocationTab
            fixes={fixes}
            error={fixesError}
            doNotTrack={doNotTrack}
            refreshing={fixesLoading}
            onRefresh={() => void loadFixes()}
            range={fixesRange}
            onRangeChange={changeFixesRange}
          />
        ),
      },
      rail: {
        status: statusItems,
        location: locationItems,
        hardware: hardwareItems,
        network: networkItems,
        management: managementItems,
      },
      activityCount: counts24h > 0 ? String(counts24h) : undefined,
    },
  );

  return (
    <DeviceProvider value={deviceValue}>
      <Breadcrumb items={[{ label: 'Devices', to: paths.devices }, { label: device.number, mono: true }]} />

      <div className="v2-device-cols">
        <aside aria-label="Device" className="v2-card v2-card--pad-rail v2-device-rail">
          <div className="v2-device-head">
            <div className="v2-device-head__top">
              <span className="v2-device-presence" title={reasonText}>
                <StatusText variant="meta" status={presence} />
                {reasonText ? <span className="v2-visually-hidden">: {reasonText}</span> : null}
                <span aria-hidden="true">·</span>
                <span>
                  checked in{' '}
                  {lastSeen && lastAge && lastIso ? (
                    <time dateTime={lastIso} title={fmtDateTime(lastSeen)}>
                      {lastAge === 'just now' ? lastAge : `${lastAge} ago`}
                    </time>
                  ) : (
                    'never'
                  )}
                </span>
              </span>
              <span className="v2-device-head__glyph">
                <DeviceGlyph kind={kind} size={22} title={DEVICE_KIND_LABEL[kind]} />
              </span>
            </div>
            <div className="v2-device-head__name">
              <DeviceName
                device={device}
                canRename={canRename}
                onSaved={(desc) => setDevice((d) => (d ? { ...d, description: desc } : d))}
              />
              <span className="v2-device-head__sub">
                {/* The number is always in the header: the name above replaces it once the device has one. */}
                {device.description ? (
                  <>
                    <span className="v2-device-head__num">{device.number}</span>
                    {subline ? ' · ' : null}
                  </>
                ) : null}
                {subline}
              </span>
            </div>
            {canEdit ? (
              <div role="toolbar" aria-label="Quick actions" className="v2-device-head__actions">
                <Button
                  variant="primary"
                  size="xl"
                  icon={<IconSync />}
                  disabled={busy}
                  className="v2-device-head__sync"
                  onClick={() => void syncNow()}
                >
                  Sync now
                </Button>
                <Button size="xl" icon={<IconLock />} disabled={busy} title={TEMPLATE.lock.description} onClick={() => runAction(TEMPLATE.lock)}>
                  Lock
                </Button>
                <Menu
                  trigger="button"
                  buttonSize="xl"
                  label="More actions"
                  disabled={busy}
                  items={[
                    { key: 'ring', label: 'Ring', icon: <IconRing />, onSelect: () => runAction(TEMPLATE.ring) },
                    { key: 'alert', label: 'Send alert…', icon: <IconAlert />, onSelect: () => runAction(TEMPLATE.alert) },
                    {
                      key: 'lsm',
                      label: 'Lock-screen message…',
                      icon: <IconScreenMessage />,
                      onSelect: () => runAction(TEMPLATE['lockscreen-message']),
                    },
                    'separator',
                    {
                      key: 'apps',
                      label: 'Install config apps',
                      icon: <IconDownload size={16} />,
                      onSelect: () => void installConfigApps(),
                    },
                    { key: 'reboot', label: 'Reboot…', icon: <IconPower />, onSelect: () => runAction(TEMPLATE.reboot) },
                  ]}
                />
              </div>
            ) : null}
          </div>

          <DeviceRailGroups />
          {loc ? (
            <a
              className="v2-device-maps"
              href={`https://www.google.com/maps?q=${loc.lat},${loc.lon}`}
              target="_blank"
              rel="noopener noreferrer"
            >
              Open location in Maps ↗<span className="v2-visually-hidden"> (opens in a new tab)</span>
            </a>
          ) : null}
        </aside>

        <div className="v2-device-main">
          <DeviceTabs {...deviceTabs} idBase={tabsId} />
        </div>
      </div>

      {dialog ? (
        <ActionDialog
          key={dialog.key}
          template={dialog}
          device={device}
          busy={busy}
          onCancel={() => setDialog(null)}
          onSend={(values) => void send(dialog, values)}
        />
      ) : null}
      {kioskOpen ? (
        <DeviceKioskDialog device={device} configName={configName} onClose={() => setKioskOpen(false)} onQueued={refresh} />
      ) : null}
      {returnOpen && kiosk ? (
        <ConfiguredKioskDialog
          device={device}
          configName={cfgMid}
          kiosk={kiosk}
          history={history}
          agentVersion={ds?.agentVersion}
          agentReported={!dsLoaded || ds != null}
          onClose={() => setReturnOpen(false)}
          onQueued={onReturnQueued}
        />
      ) : null}
    </DeviceProvider>
  );
}
