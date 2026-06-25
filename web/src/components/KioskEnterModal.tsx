import { useEffect, useMemo, useState } from 'react';
import { scanApps, fetchIcons, getLatestScan, type AppInfo } from '../api/deviceApps';
import { listApplications, appCategory, type Application } from '../api/applications';
import { queueCommand } from '../api/commands';
import { useToast } from '../ui/toast';

type Device = { number: string };
type Mode = 'launcher' | 'single';
type Source = 'library' | 'device';

/** A row in either source, normalised so the list renders the same way. */
interface PickItem {
  pkg: string;
  label: string;
  iconUrl?: string;
  system: boolean;
  badge?: string;
}

const iconKey = (pkg: string, v?: number) => `mdm.icon.${pkg}@${v ?? 0}`;
function cachedIcon(pkg: string, v?: number): string | undefined {
  try { return localStorage.getItem(iconKey(pkg, v)) ?? undefined; } catch { return undefined; }
}
function cacheIcon(pkg: string, v: number | undefined, dataUrl: string) {
  try { localStorage.setItem(iconKey(pkg, v), dataUrl); } catch { /* quota — ignore */ }
}

export function KioskEnterModal({
  device, onClose, onQueued,
}: { device: Device; onClose: () => void; onQueued: () => void }) {
  const toast = useToast();
  const [source, setSource] = useState<Source>('library');
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [mode, setMode] = useState<Mode>('launcher');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [exitMode, setExitMode] = useState<'gesture' | 'visible' | 'remote'>('gesture');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  // Library (default source) — the app catalog / uploaded apps.
  const [lib, setLib] = useState<Application[] | null>(null);
  const [libErr, setLibErr] = useState<string | null>(null);

  // Device scan (optional source).
  const [apps, setApps] = useState<AppInfo[] | null>(null);
  const [scannedAt, setScannedAt] = useState<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const [loadedSaved, setLoadedSaved] = useState(false);
  const [scanErr, setScanErr] = useState<string | null>(null);
  const [deviceIcons, setDeviceIcons] = useState<Record<string, string>>({});
  const [loadingIcons, setLoadingIcons] = useState(false);

  useEffect(() => {
    listApplications()
      .then(setLib)
      .catch((e) => setLibErr(e instanceof Error ? e.message : 'Failed to load library'));
  }, []);

  // First time the Device tab is opened, load the last saved scan (from command history) so apps
  // appear instantly without re-scanning. A scan only happens when the user asks for one.
  useEffect(() => {
    if (source !== 'device' || loadedSaved) return;
    setLoadedSaved(true);
    getLatestScan(device.number).then((snap) => {
      if (snap) { primeFrom(snap.apps); setApps(snap.apps); setScannedAt(snap.scannedAt ?? null); }
    }).catch(() => undefined);
  }, [source, loadedSaved, device.number]);

  function primeFrom(list: AppInfo[]) {
    const primed: Record<string, string> = {};
    for (const a of list) { const c = cachedIcon(a.pkg, a.versionCode); if (c) primed[a.pkg] = c; }
    setDeviceIcons((prev) => ({ ...prev, ...primed }));
  }

  async function scan() {
    setScanning(true);
    setScanErr(null);
    try {
      const list = await scanApps(device.number);
      setApps(list);
      setScannedAt(Date.now());
      primeFrom(list);
      if (!list.length) setScanErr('The device reported no apps. Give it a moment after an update, then retry.');
    } catch (e) {
      setScanErr(e instanceof Error ? e.message : 'Scan failed');
    } finally {
      setScanning(false);
    }
  }

  const items: PickItem[] = useMemo(() => {
    const q = query.trim().toLowerCase();
    const match = (label: string, pkg: string) => !q || label.toLowerCase().includes(q) || pkg.toLowerCase().includes(q);
    if (source === 'library') {
      // The library is a curated catalogue (~dozens) — show all of it, search-filtered, with the
      // uploaded/web apps first. (Most catalogue entries carry the "system" flag, so hiding them by
      // default would leave the list nearly empty.)
      return (lib ?? [])
        .filter((a) => a.pkg)
        .map((a) => ({ pkg: a.pkg, label: a.name || a.pkg, iconUrl: a.icon || undefined, system: appCategory(a) === 'system', badge: appCategory(a) }))
        .filter((i) => match(i.label, i.pkg))
        .sort((a, b) => Number(a.system) - Number(b.system) || a.label.localeCompare(b.label));
    }
    // Device scan: only launchable apps (kiosk can't pin a non-launchable package). New scans
    // already exclude them; this also filters any older saved scan that still carries them.
    // The toggle now reveals system apps; non-launchable are never shown.
    return (apps ?? [])
      .filter((a) => a.launchable !== false)
      .map((a) => ({ pkg: a.pkg, label: a.label, iconUrl: deviceIcons[a.pkg], system: !!a.system, badge: a.system ? 'system' : undefined }))
      .filter((i) => (showAll || !i.system) && match(i.label, i.pkg));
  }, [source, lib, apps, deviceIcons, query, showAll]);

  async function loadDeviceIcons() {
    if (source !== 'device' || !apps) return;
    const byPkg = new Map(apps.map((a) => [a.pkg, a]));
    const need = items.filter((i) => !deviceIcons[i.pkg]).map((i) => i.pkg);
    if (!need.length) return;
    setLoadingIcons(true);
    try {
      await fetchIcons(device.number, need, (batch) => {
        setDeviceIcons((prev) => ({ ...prev, ...batch }));
        for (const [pkg, url] of Object.entries(batch)) cacheIcon(pkg, byPkg.get(pkg)?.versionCode, url);
      });
    } catch (e) {
      toast.push('err', 'Icon fetch failed', e instanceof Error ? e.message : '');
    } finally {
      setLoadingIcons(false);
    }
  }

  function toggle(pkg: string) {
    setSelected((prev) => {
      if (mode === 'single') return new Set(prev.has(pkg) ? [] : [pkg]);
      const next = new Set(prev);
      if (next.has(pkg)) next.delete(pkg); else next.add(pkg);
      return next;
    });
  }

  function switchMode(m: Mode) {
    setMode(m);
    if (m === 'single' && selected.size > 1) setSelected(new Set());
  }

  const canApply = selected.size > 0 && (mode === 'launcher' || selected.size === 1);

  async function apply() {
    const pkgs = [...selected];
    const payload = mode === 'single'
      ? { mode: 'single', pinPackage: pkgs[0], allowedPackages: pkgs, exitMode, password: password || undefined, features: { home: true, notifications: true } }
      : { mode: 'launcher', allowedPackages: pkgs, exitMode, password: password || undefined, features: { home: true, notifications: true } };
    setBusy(true);
    try {
      await queueCommand(device.number, { type: 'kiosk.enter', payload: JSON.stringify(payload) });
      toast.push('ok', 'Enter kiosk queued', `${pkgs.length} app${pkgs.length === 1 ? '' : 's'}`);
      onQueued();
      onClose();
    } catch (e) {
      toast.push('err', 'Enter kiosk failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal kiosk-modal">
        <h3>Enter kiosk</h3>
        <p className="muted">Pick the apps to lock the device to — from your library, or by scanning the device.</p>

        <div className="kiosk-source">
          <button className={`seg-btn ${source === 'library' ? 'on' : ''}`} onClick={() => setSource('library')}>Library</button>
          <button className={`seg-btn ${source === 'device' ? 'on' : ''}`} onClick={() => setSource('device')}>Device apps</button>
        </div>

        <div className="kiosk-mode">
          <label><input type="radio" checked={mode === 'launcher'} onChange={() => switchMode('launcher')} /> Allowed apps (launcher grid)</label>
          <label><input type="radio" checked={mode === 'single'} onChange={() => switchMode('single')} /> Pin a single app</label>
        </div>

        <div className="kiosk-toolbar">
          <input className="kiosk-search" placeholder="Search apps…" value={query} onChange={(e) => setQuery(e.target.value)} />
          {source === 'device' && (
            <label className="kiosk-toggle">
              <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} /> Show all (incl. system)
            </label>
          )}
          {source === 'device' && apps && (
            <>
              <button className="btn" disabled={loadingIcons} onClick={() => { void loadDeviceIcons(); }}>
                {loadingIcons ? 'Loading icons…' : 'Load icons'}
              </button>
              <button className="btn" disabled={scanning} onClick={() => { void scan(); }}>
                {scanning ? 'Scanning…' : 'Re-scan'}
              </button>
            </>
          )}
        </div>

        {source === 'device' && apps && (
          <p className="kiosk-saved muted">
            {scannedAt ? `Saved scan from ${new Date(scannedAt).toLocaleString()} · ${apps.length} apps` : `${apps.length} apps`} — re-scan to refresh.
          </p>
        )}

        {source === 'device' && !apps && (
          <div className="kiosk-scan">
            <button className="btn btn-primary" disabled={scanning} onClick={() => { void scan(); }}>
              {scanning ? 'Scanning device…' : 'Scan device apps'}
            </button>
            {scanning && <p className="muted">Asking the device for its installed apps…</p>}
            {scanErr && <p className="err-text">{scanErr}</p>}
          </div>
        )}

        {(source === 'library' || apps) && (
          <div className="kiosk-applist">
            {libErr && source === 'library' && <p className="err-text" style={{ padding: 10 }}>{libErr}</p>}
            {source === 'library' && !lib && !libErr && <p className="muted" style={{ padding: 10 }}>Loading library…</p>}
            {items.map((i) => {
              const on = selected.has(i.pkg);
              return (
                <button key={i.pkg} type="button" className={`kiosk-app ${on ? 'on' : ''}`} onClick={() => toggle(i.pkg)} title={i.pkg}>
                  {i.iconUrl
                    ? <img className="kiosk-ico" src={i.iconUrl} alt="" onError={(e) => { (e.target as HTMLImageElement).style.visibility = 'hidden'; }} />
                    : <span className="kiosk-ico ph">{i.label.slice(0, 1).toUpperCase()}</span>}
                  <span className="kiosk-app-meta">
                    <span className="kiosk-app-label">{i.label}</span>
                    <span className="kiosk-app-pkg">{i.pkg}</span>
                  </span>
                  <span className="kiosk-badges">
                    {i.badge && <span className={`kiosk-badge ${i.system ? 'sys' : ''}`}>{i.badge}</span>}
                    {on && <span className="kiosk-badge on">✓</span>}
                  </span>
                </button>
              );
            })}
            {!items.length && (source === 'library' ? lib : apps) && <p className="muted" style={{ padding: 10 }}>No apps match.</p>}
          </div>
        )}

        <div className="kiosk-exit-row">
          <label className="field">
            <span>Exit mode</span>
            <select value={exitMode} onChange={(e) => setExitMode(e.target.value as typeof exitMode)}>
              <option value="gesture">Gesture (7-tap corner)</option>
              <option value="visible">Visible button</option>
              <option value="remote">Remote only</option>
            </select>
          </label>
          <label className="field">
            <span>Exit password</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="optional" />
          </label>
        </div>

        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={busy || !canApply} onClick={() => { void apply(); }}>
            {busy ? 'Sending…' : `Enter kiosk (${selected.size})`}
          </button>
        </div>
      </div>
    </div>
  );
}
