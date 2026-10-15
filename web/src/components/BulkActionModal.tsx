import { useEffect, useState } from 'react';
import {
  ACTION_TEMPLATES, type CommandTemplateExt, bulkQueueCommand,
  buildInstallCommand, type AppInstallSpec,
} from '../api/commands';
import { listApplications, type Application } from '../api/applications';
import { BulkKioskModal } from './BulkKioskModal';
import { useToast } from '../ui/toast';

// Only safe + disruptive actions run in bulk; the destructive group (passcode-reset, wipe) is excluded.
// kiosk-enter is handled by a dedicated Phase-3 flow, so it is filtered out here too.
const BULK_GROUPS: Array<{ id: 'safe' | 'disruptive'; title: string }> = [
  { id: 'safe', title: 'Actions' },
  { id: 'disruptive', title: 'Disruptive' },
];

function bulkable(t: CommandTemplateExt): boolean {
  const g = t.group ?? 'safe';
  if (g === 'destructive') return false;
  if (t.key === 'kiosk-enter') return false; // Phase 3 owns this
  return true;
}

/** Turn a Library app into an install spec (single url or split bundle parts). */
function specForApp(app: Application): AppInstallSpec | null {
  let parts: { url: string; sha256?: string }[] | undefined;
  if (app.parts) {
    try {
      const arr = JSON.parse(app.parts) as { url: string; sha256?: string }[];
      if (Array.isArray(arr) && arr.length) parts = arr;
    } catch { parts = undefined; }
  }
  if (!parts && !app.url) return null; // nothing installable
  return {
    url: app.url ?? '',
    packageName: app.pkg,
    versionCode: app.latestVersion,
    parts,
  };
}

export function BulkActionModal({
  deviceIds, devices, onClose, onDone,
}: {
  deviceIds: number[];
  devices: { id: number; configurationId?: number }[];
  onClose: () => void;
  onDone: () => void;
}) {
  const toast = useToast();
  const n = deviceIds.length;
  const [active, setActive] = useState<CommandTemplateExt | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const [appPicker, setAppPicker] = useState(false);
  const [apps, setApps] = useState<Application[] | null>(null);
  const [appErr, setAppErr] = useState<string | null>(null);
  const [appQuery, setAppQuery] = useState('');
  const [kioskOpen, setKioskOpen] = useState(false);

  useEffect(() => {
    if (!appPicker) return;
    let cancelled = false;
    listApplications()
      .then((r) => { if (!cancelled) setApps(r); })
      .catch((e) => { if (!cancelled) setAppErr(e instanceof Error ? e.message : 'Failed to load apps'); });
    return () => { cancelled = true; };
  }, [appPicker]);

  async function run(t: CommandTemplateExt, vals: Record<string, string>) {
    setBusy(true);
    try {
      const req = t.build ? t.build(vals) : t.request;
      const res = await bulkQueueCommand(deviceIds, req);
      const skipped = res.skipped?.length ?? 0;
      toast.push('ok', `${t.label} queued`,
        `Queued for ${res.queued} device${res.queued === 1 ? '' : 's'}` +
        (skipped ? ` (${skipped} skipped)` : '') + '.');
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', `${t.label} failed`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function runInstall(app: Application) {
    const spec = specForApp(app);
    if (!spec) { toast.push('err', 'Not installable', `${app.name} has no hosted APK.`); return; }
    setBusy(true);
    try {
      const res = await bulkQueueCommand(deviceIds, buildInstallCommand(spec));
      const skipped = res.skipped?.length ?? 0;
      toast.push('ok', 'Install queued',
        `${app.name} → ${res.queued} device${res.queued === 1 ? '' : 's'}` +
        (skipped ? ` (${skipped} skipped)` : '') + '.');
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', 'Install failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  function onPick(t: CommandTemplateExt) {
    if (t.params && t.params.length > 0) { setActive(t); setValues({}); return; }
    void run(t, {});
  }

  const canSend = !active
    ? false
    : !active.params?.some((p) => p.required && !values[p.key]);

  const showCatalog = !active && !appPicker;

  // Kiosk-enter has its own Library picker; swap it in wholesale (no stacked backdrops).
  if (kioskOpen) {
    return (
      <BulkKioskModal
        deviceIds={deviceIds}
        devices={devices}
        onClose={() => setKioskOpen(false)}
        onDone={() => { onDone(); onClose(); }}
      />
    );
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Run action on {n} device{n === 1 ? '' : 's'}</h3>

        {showCatalog && (
          <>
            {BULK_GROUPS.map((g) => (
              <section key={g.id} className="action-group">
                <h4 className="action-group-title">{g.title}</h4>
                <div className="action-grid">
                  {ACTION_TEMPLATES.filter(bulkable).filter((t) => (t.group ?? 'safe') === g.id).map((t) => (
                    <button
                      key={t.key}
                      className={`btn ${t.danger ? 'btn-danger' : ''}`}
                      disabled={busy}
                      title={t.description}
                      onClick={() => onPick(t)}
                    >
                      {t.label}
                    </button>
                  ))}
                </div>
              </section>
            ))}
            <section className="action-group">
              <h4 className="action-group-title">Apps</h4>
              <div className="action-grid">
                <button className="btn" disabled={busy} onClick={() => setAppPicker(true)}>Install app…</button>
              </div>
            </section>
            <section className="action-group">
              <h4 className="action-group-title">Kiosk</h4>
              <div className="action-grid">
                <button className="btn btn-danger" disabled={busy} onClick={() => setKioskOpen(true)}>Enter kiosk…</button>
              </div>
            </section>
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={onClose}>Close</button>
            </div>
          </>
        )}

        {active && (
          <>
            <h4>{active.label}</h4>
            <p className="muted">{active.description}</p>
            <p className="muted">This will run on <strong>{n}</strong> device{n === 1 ? '' : 's'}.</p>
            {active.params?.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                <input
                  type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                  placeholder={p.placeholder}
                  value={values[p.key] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                />
              </label>
            ))}
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setActive(null)}>Back</button>
              <button
                className={`btn ${active.danger ? 'btn-danger' : 'btn-primary'}`}
                disabled={busy || !canSend}
                onClick={() => { void run(active, values); }}
              >
                {busy ? 'Queueing…' : `Run on ${n}`}
              </button>
            </div>
          </>
        )}

        {appPicker && (
          <>
            <h4>Install app on {n} device{n === 1 ? '' : 's'}</h4>
            <input className="field" placeholder="Filter apps" value={appQuery}
                   onChange={(e) => setAppQuery(e.target.value)} />
            {appErr && <p className="muted">{appErr}</p>}
            {!apps && !appErr && <p className="muted">Loading library…</p>}
            <div className="action-grid">
              {(apps ?? [])
                .filter((a) => `${a.name} ${a.pkg}`.toLowerCase().includes(appQuery.toLowerCase()))
                .map((a) => (
                  <button key={a.id ?? a.pkg} className="btn" disabled={busy}
                          title={a.pkg} onClick={() => { void runInstall(a); }}>
                    {a.name}{a.version ? ` (${a.version})` : ''}
                  </button>
                ))}
            </div>
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setAppPicker(false)}>Back</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
