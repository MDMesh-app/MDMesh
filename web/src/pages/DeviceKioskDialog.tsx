import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { scanApps, fetchIcons, getLatestScan, type AppInfo } from '../api/deviceApps';
import { listApplications, appCategory, type Application } from '../api/applications';
import { getConfiguration } from '../api/configurations';
import { Link } from 'react-router';
import { queueCommand, type CommandHistoryItem } from '../api/commands';
import {
  LOCK_VOLUME_AGENT,
  buildKioskPayload,
  cacheIcon,
  cachedIcon,
  configuredKioskSummary,
  EXIT_PIN_HELP,
  EXIT_PIN_PLACEHOLDER,
  kioskTheme,
  NO_PIN_MEANS,
  versionAtLeast,
  type ConfiguredKiosk,
  type KioskTheme,
} from '../data/kiosk';
import { paths } from '../routes';
import { fmtDateTime } from '../ui/format';
import {
  Badge,
  Button,
  Callout,
  Checkbox,
  Field,
  IconCheck,
  IconWarning,
  Input,
  KeyValueList,
  Modal,
  SearchInput,
  Segmented,
  Select,
  useToast,
} from '../ui';
import { DialogTarget } from './DeviceDetailControl';

type Mode = 'launcher' | 'single';
type Source = 'library' | 'device';
type ExitMode = 'gesture' | 'visible' | 'remote';

/** A row in either source, normalised so the list renders the same way. */
interface PickItem {
  pkg: string;
  label: string;
  iconUrl?: string;
  system: boolean;
  badge?: string;
}

const CATEGORY_LABEL: Record<string, string> = { uploaded: 'Uploaded', system: 'System', web: 'Web app' };

/**
 * Custom kiosk (classic KioskEnterModal on the console's Modal): a one-time kiosk the configuration doesn't define. Pick
 * apps from the library or a scan of the device, choose launcher grid or a single pinned app, the exit mode and an
 * optional exit PIN, then queue `kiosk.enter` with the configuration's launcher theme.
 */
export function DeviceKioskDialog({
  device,
  configName,
  onClose,
  onQueued,
}: {
  device: { number: string; configurationId?: number; description?: string };
  /** The device's configuration, named in the description; undefined when it has none (or it isn't loaded). */
  configName?: string;
  onClose: () => void;
  onQueued: () => void;
}) {
  const toast = useToast();
  const ids = useId();
  const [source, setSource] = useState<Source>('library');
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [mode, setMode] = useState<Mode>('launcher');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [exitMode, setExitMode] = useState<ExitMode>('gesture');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [theme, setTheme] = useState<KioskTheme | undefined>();
  const [themeLoading, setThemeLoading] = useState(device.configurationId != null);
  const [themeError, setThemeError] = useState<string | null>(null);

  const [lib, setLib] = useState<Application[] | null>(null);
  const [libErr, setLibErr] = useState<string | null>(null);

  const [apps, setApps] = useState<AppInfo[] | null>(null);
  const [scannedAt, setScannedAt] = useState<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const [loadedSaved, setLoadedSaved] = useState(false);
  const [scanErr, setScanErr] = useState<string | null>(null);
  const [deviceIcons, setDeviceIcons] = useState<Record<string, string>>({});
  const [loadingIcons, setLoadingIcons] = useState(false);

  // Cancels in-flight scan / icon polls when the dialog closes (it unmounts on close).
  const abortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    const ac = new AbortController();
    abortRef.current = ac;
    return () => ac.abort();
  }, []);

  // A manual kiosk action must carry the assigned configuration's theme, or the launcher falls back to black.
  useEffect(() => {
    if (device.configurationId == null) return;
    let cancelled = false;
    getConfiguration(device.configurationId)
      .then((c) => {
        if (!cancelled) setTheme(kioskTheme(c));
      })
      .catch(() => {
        if (!cancelled) setThemeError('Could not load the configuration theme; the kiosk will use the default look.');
      })
      .finally(() => {
        if (!cancelled) setThemeLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [device.configurationId]);

  useEffect(() => {
    listApplications()
      .then(setLib)
      .catch((e) => setLibErr(e instanceof Error ? e.message : 'Failed to load library'));
  }, []);

  function primeFrom(list: AppInfo[]) {
    const primed: Record<string, string> = {};
    for (const a of list) {
      const c = cachedIcon(a.pkg, a.versionCode);
      if (c) primed[a.pkg] = c;
    }
    setDeviceIcons((prev) => ({ ...prev, ...primed }));
  }

  // First visit to Device apps: show the last saved scan (from command history) instead of scanning again.
  useEffect(() => {
    if (source !== 'device' || loadedSaved) return;
    setLoadedSaved(true);
    getLatestScan(device.number)
      .then((snap) => {
        if (snap) {
          primeFrom(snap.apps);
          setApps(snap.apps);
          setScannedAt(snap.scannedAt ?? null);
        }
      })
      .catch(() => undefined);
  }, [source, loadedSaved, device.number]);

  async function scan() {
    setScanning(true);
    setScanErr(null);
    try {
      const list = await scanApps(device.number, abortRef.current?.signal);
      setApps(list);
      setScannedAt(Date.now());
      primeFrom(list);
      if (!list.length) setScanErr('The device reported no apps. Give it a moment after an update, then retry.');
    } catch (e) {
      if (abortRef.current?.signal.aborted) return;
      setScanErr(e instanceof Error ? e.message : 'Scan failed');
    } finally {
      setScanning(false);
    }
  }

  const items: PickItem[] = useMemo(() => {
    const q = query.trim().toLowerCase();
    const match = (label: string, pkg: string) => !q || label.toLowerCase().includes(q) || pkg.toLowerCase().includes(q);
    if (source === 'library') {
      // The library is a curated catalogue: all of it, search-filtered, uploaded and web apps first.
      return (lib ?? [])
        .filter((a) => a.pkg)
        .map((a) => ({
          pkg: a.pkg,
          label: a.name || a.pkg,
          iconUrl: a.icon || undefined,
          system: appCategory(a) === 'system',
          badge: appCategory(a),
        }))
        .filter((i) => match(i.label, i.pkg))
        .sort((a, b) => Number(a.system) - Number(b.system) || a.label.localeCompare(b.label));
    }
    // Device scan: launchable apps only (kiosk can't pin anything else); the toggle reveals system apps.
    return (apps ?? [])
      .filter((a) => typeof a.pkg === 'string' && a.pkg && a.launchable !== false)
      .map((a) => ({
        pkg: a.pkg,
        // A scan entry from the device may lack a label (or send a non-string): fall back to the package name.
        label: typeof a.label === 'string' && a.label ? a.label : a.pkg,
        iconUrl: deviceIcons[a.pkg],
        system: !!a.system,
        badge: a.system ? 'system' : undefined,
      }))
      .filter((i) => (showAll || !i.system) && match(i.label, i.pkg));
  }, [source, lib, apps, deviceIcons, query, showAll]);

  async function loadDeviceIcons() {
    if (source !== 'device' || !apps) return;
    const byPkg = new Map(apps.map((a) => [a.pkg, a]));
    const need = items.filter((i) => !deviceIcons[i.pkg]).map((i) => i.pkg);
    if (!need.length) return;
    setLoadingIcons(true);
    try {
      await fetchIcons(
        device.number,
        need,
        (batch) => {
          setDeviceIcons((prev) => ({ ...prev, ...batch }));
          for (const [pkg, url] of Object.entries(batch)) cacheIcon(pkg, byPkg.get(pkg)?.versionCode, url);
        },
        abortRef.current?.signal,
      );
    } catch (e) {
      if (abortRef.current?.signal.aborted) return;
      toast.push('err', 'Icon fetch failed', e instanceof Error ? e.message : '');
    } finally {
      setLoadingIcons(false);
    }
  }

  function toggle(pkg: string) {
    setSelected((prev) => {
      if (mode === 'single') return new Set(prev.has(pkg) ? [] : [pkg]);
      const next = new Set(prev);
      if (next.has(pkg)) next.delete(pkg);
      else next.add(pkg);
      return next;
    });
  }

  function switchMode(m: Mode) {
    setMode(m);
    if (m === 'single' && selected.size > 1) setSelected(new Set());
  }

  const canApply = !themeLoading && selected.size > 0 && (mode === 'launcher' || selected.size === 1);

  async function apply() {
    const pkgs = [...selected];
    const payload = buildKioskPayload({ mode, packages: pkgs, exitMode, password }, theme);
    setBusy(true);
    try {
      await queueCommand(device.number, { type: 'kiosk.enter', payload: JSON.stringify(payload) });
      toast.push('ok', 'Custom kiosk queued', `${pkgs.length} app${pkgs.length === 1 ? '' : 's'}`);
      onQueued();
      onClose();
    } catch (e) {
      toast.push('err', 'Custom kiosk failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  const listShown = source === 'library' || apps != null;

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      dismissOnBackdrop={false}
      title="Custom kiosk"
      description={
        'A one-time kiosk with apps you pick now, from your library or from a scan of the device.' +
        (device.configurationId != null ? ` ${configName ?? 'Its configuration'} doesn’t define it.` : '')
      }
      className="v2-device-kiosk"
      footer={
        <>
          <Button size="lg" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button size="lg" variant="primary" disabled={busy || !canApply} onClick={() => void apply()}>
            {busy ? 'Sending…' : `Enter custom kiosk (${selected.size})`}
          </Button>
        </>
      }
    >
      <div className="v2-device-kiosk__body">
        <DialogTarget device={device} />
        {themeLoading ? (
          <p className="v2-device-kiosk__note" role="status">
            Loading configured launcher appearance…
          </p>
        ) : null}
        {themeError ? (
          <Callout tone="warn" icon={<IconWarning size={16} />}>
            {themeError}
          </Callout>
        ) : null}

        <div className="v2-device-kiosk__row">
          <span className="v2-device-kiosk__label" id={`${ids}-src`}>
            Apps from
          </span>
          <Segmented<Source>
            aria-labelledby={`${ids}-src`}
            value={source}
            onChange={setSource}
            options={[
              { value: 'library', label: 'Library' },
              { value: 'device', label: 'Device apps' },
            ]}
          />
        </div>
        <div className="v2-device-kiosk__row">
          <span className="v2-device-kiosk__label" id={`${ids}-mode`}>
            Kiosk
          </span>
          <Segmented<Mode>
            aria-labelledby={`${ids}-mode`}
            value={mode}
            onChange={switchMode}
            options={[
              { value: 'launcher', label: 'Allowed apps (launcher grid)' },
              { value: 'single', label: 'Pin a single app' },
            ]}
          />
        </div>

        <div className="v2-device-kiosk__toolbar">
          <SearchInput
            className="v2-device-kiosk__search"
            aria-label="Search apps"
            placeholder="Search apps…"
            value={query}
            onChange={setQuery}
          />
          {source === 'device' ? (
            <Checkbox label="Show all (incl. system)" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} />
          ) : null}
          {source === 'device' && apps ? (
            <>
              <Button size="md" disabled={loadingIcons} onClick={() => void loadDeviceIcons()}>
                {loadingIcons ? 'Loading icons…' : 'Load icons'}
              </Button>
              <Button size="md" disabled={scanning} onClick={() => void scan()}>
                {scanning ? 'Scanning…' : 'Re-scan'}
              </Button>
            </>
          ) : null}
        </div>

        {source === 'device' && apps ? (
          <p className="v2-device-kiosk__note">
            {scannedAt ? `Saved scan from ${fmtDateTime(scannedAt)} · ${apps.length} apps` : `${apps.length} apps`}. Re-scan to
            refresh.
          </p>
        ) : null}
        {source === 'device' && apps && scanErr ? (
          <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
            {scanErr}
          </Callout>
        ) : null}

        {source === 'device' && !apps ? (
          <div className="v2-device-kiosk__scan">
            <Button variant="primary" size="lg" disabled={scanning} onClick={() => void scan()}>
              {scanning ? 'Scanning device…' : 'Scan device apps'}
            </Button>
            {scanning ? (
              <p className="v2-device-kiosk__note" role="status">
                Asking the device for its installed apps…
              </p>
            ) : null}
            {scanErr ? (
              <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
                {scanErr}
              </Callout>
            ) : null}
          </div>
        ) : null}

        {listShown ? (
          <div className="v2-device-kiosk__list" role="group" aria-label="Apps">
            {source === 'library' && libErr ? (
              <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
                {libErr}
              </Callout>
            ) : null}
            {source === 'library' && !lib && !libErr ? (
              <p className="v2-device-kiosk__note" role="status">
                Loading library…
              </p>
            ) : null}
            {items.map((i) => {
              const on = selected.has(i.pkg);
              return (
                <button
                  key={i.pkg}
                  type="button"
                  aria-pressed={on}
                  className={`v2-device-kiosk__app${on ? ' is-on' : ''}`}
                  onClick={() => toggle(i.pkg)}
                  title={i.pkg}
                >
                  {i.iconUrl ? (
                    <img
                      className="v2-device-kiosk__ico"
                      src={i.iconUrl}
                      alt=""
                      onError={(e) => {
                        (e.target as HTMLImageElement).style.visibility = 'hidden';
                      }}
                    />
                  ) : (
                    <span className="v2-device-kiosk__ico is-letter" aria-hidden="true">
                      {i.label.slice(0, 1).toUpperCase()}
                    </span>
                  )}
                  <span className="v2-device-kiosk__meta">
                    <span className="v2-device-kiosk__name">{i.label}</span>
                    <span className="v2-device-kiosk__pkg">{i.pkg}</span>
                  </span>
                  {i.badge ? (
                    <Badge tone={i.system ? 'neutral' : 'outline'} size="sm">
                      {CATEGORY_LABEL[i.badge] ?? i.badge}
                    </Badge>
                  ) : null}
                  <span className="v2-device-kiosk__tick" aria-hidden="true">
                    {on ? <IconCheck size={13} /> : null}
                  </span>
                </button>
              );
            })}
            {!items.length && (source === 'library' ? lib : apps) ? (
              <p className="v2-device-kiosk__note v2-device-kiosk__empty">No apps match.</p>
            ) : null}
          </div>
        ) : null}

        <div className="v2-device-kiosk__exit">
          <Field label="Exit mode">
            <Select value={exitMode} onChange={(e) => setExitMode(e.target.value as ExitMode)}>
              <option value="gesture">Gesture (7-tap corner)</option>
              <option value="visible">Visible button</option>
              <option value="remote">Remote only</option>
            </Select>
          </Field>
          <Field label="Kiosk exit PIN">
            <Input
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={EXIT_PIN_PLACEHOLDER}
              title={EXIT_PIN_HELP}
            />
          </Field>
        </div>
      </div>
    </Modal>
  );
}

/* ===============================================================================================================
   Return to configured kiosk: confirm, then queue the kiosk the configuration defines
   =============================================================================================================== */

/** Packages in the device's newest saved app scan, and when it ran; null when it was never scanned. */
function lastScan(history: CommandHistoryItem[]): { id: number; pkgs: Set<string>; at?: number } | null {
  let scan: CommandHistoryItem | undefined;
  for (const c of history) {
    if (c.type === 'apps.scan' && c.status === 'done' && c.detail && (!scan || Number(c.id) > Number(scan.id))) scan = c;
  }
  if (!scan) return null;
  try {
    const apps = (JSON.parse(scan.detail!) as { apps?: unknown } | null)?.apps;
    if (!Array.isArray(apps)) return null;
    const pkgs = new Set(apps.map((a) => (a && typeof a === 'object' ? (a as { pkg?: unknown }).pkg : undefined)).filter((x): x is string => typeof x === 'string'));
    return pkgs.size ? { id: Number(scan.id), pkgs, at: scan.completedAt } : null;
  } catch {
    return null;
  }
}

/**
 * Confirms "Return to configured kiosk": says what the configuration's kiosk is, warns about what would make it
 * differ on this device, then queues `kiosk.enter` with the configuration's own kiosk document.
 */
export function ConfiguredKioskDialog({
  device,
  configName,
  kiosk,
  history,
  agentVersion,
  agentReported,
  onClose,
  onQueued,
}: {
  device: { number: string; description?: string };
  configName: string;
  kiosk: ConfiguredKiosk;
  /** The device's command history: its last app scan and installs since. */
  history: CommandHistoryItem[];
  agentVersion: string | null | undefined;
  /** False when the device has never sent an agent state report. */
  agentReported: boolean;
  onClose: () => void;
  /** After the command was queued, with its id. */
  onQueued: (id: number | string | undefined) => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const p = kiosk.payload;

  const missing = useMemo(() => {
    const scan = lastScan(history);
    if (!scan) return null;
    // Installed since the scan (the history names an app.install's package).
    const installed = new Set(
      history
        .filter((c) => c.type === 'app.install' && c.status === 'done' && Number(c.id) > scan.id && typeof c.subject === 'string')
        .map((c) => c.subject as string),
    );
    const list = kiosk.apps.filter((a) => !scan.pkgs.has(a.pkg) && !installed.has(a.pkg));
    return list.length ? { list, at: scan.at } : null;
  }, [history, kiosk.apps]);

  const scanWhen = missing?.at ? ` (${fmtDateTime(missing.at)})` : '';
  const pinnedMissing = p.mode === 'single' && missing?.list.some((a) => a.pkg === p.pinPackage);
  const oldAgentForVolume = p.features.lockVolume === true && versionAtLeast(agentVersion, LOCK_VOLUME_AGENT) === false;

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const res = await queueCommand(device.number, { type: 'kiosk.enter', payload: JSON.stringify(p) });
      toast.push('ok', 'Return to configured kiosk queued', res?.id != null ? `Command ${res.id}` : '');
      onQueued(res?.id);
      onClose();
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : 'The server didn’t accept the command.');
    } finally {
      setBusy(false);
    }
  }

  const appNames = kiosk.apps.map((a) => a.name).join(', ');
  return (
    <Modal
      open
      size="sm"
      onClose={busy ? () => undefined : onClose}
      title="Return to configured kiosk"
      description={`Puts the device back into the kiosk that ${configName} defines: its main app, allowed apps, kiosk options, exit PIN and launcher look.`}
      className="v2-device-kreturn"
      footer={
        <>
          <Button size="lg" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button size="lg" variant="primary" disabled={busy} onClick={() => void send()}>
            {busy ? 'Sending…' : 'Return to configured kiosk'}
          </Button>
        </>
      }
    >
      <DialogTarget device={device} />
      <KeyValueList
        variant="facts"
        items={[
          { key: 'kiosk', label: 'Kiosk', value: configuredKioskSummary(kiosk) },
          ...(p.mode === 'launcher' ? [{ key: 'apps', label: 'Apps', value: appNames }] : []),
          {
            key: 'exit',
            label: 'Exit',
            value: p.exitMode === 'visible' ? 'Visible exit button' : 'Hidden gesture: 7 taps in the top-right corner',
          },
          { key: 'pin', label: 'Exit PIN', value: p.password ? 'The configuration’s PIN' : `None: ${NO_PIN_MEANS}` },
        ]}
      />
      {kiosk.mainAppLeftOut ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          The main app set in {configName} isn’t set to Install and shown in kiosk, so this kiosk leaves it out. The
          configuration’s own kiosk does the same.
        </Callout>
      ) : null}
      {missing ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          {pinnedMissing
            ? `${missing.list.find((a) => a.pkg === p.pinPackage)?.name} wasn’t on the device at its last app scan${scanWhen}. Until it is installed, the kiosk shows a launcher grid instead of pinning it.`
            : `Not on the device at its last app scan${scanWhen}: ${missing.list.map((a) => a.name).join(', ')}. The kiosk can’t open an app that isn’t installed.`}{' '}
          Use Install config apps first.
        </Callout>
      ) : null}
      {oldAgentForVolume ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          This device runs agent {agentVersion}. Agents before {LOCK_VOLUME_AGENT} ignore Lock volume buttons, so the
          kiosk starts without that lock. <Link to={`${paths.settings}#updates`}>Update the agent</Link> to apply it.
        </Callout>
      ) : null}
      {!agentReported ? (
        <Callout tone="info">
          This device hasn’t reported from the MDMesh agent yet. The command waits until it checks in.
        </Callout>
      ) : null}
      {error ? (
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          Couldn’t queue the command: {error}
        </Callout>
      ) : null}
    </Modal>
  );
}
