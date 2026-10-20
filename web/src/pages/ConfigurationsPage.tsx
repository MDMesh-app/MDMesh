import { useEffect, useMemo, useRef, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import {
  getConfigurations,
  getConfiguration,
  getConfigurationApps,
  saveConfiguration,
  deleteConfiguration,
  copyConfiguration,
  type Configuration,
  type ConfigApp,
} from '../api/configurations';
import { listApplications, type Application } from '../api/applications';
import { getSyncSummary, type ConfigSyncSummary } from '../api/configSync';
import {
  PRIMARY_FIELDS,
  LEGACY_FIELDS,
  GROUP_ORDER,
  GROUP_NOTES,
  DEFAULT_SEGMENTS,
  flagOffValue,
  type FieldDef,
} from '../data/configFields';
import { AppPicker } from '../components/AppPicker';
import { SyncBar } from '../components/SyncBar';
import { KioskChangeConfirm, kioskAffectingChanges } from '../components/KioskChangeConfirm';

// The seeded device-template defaults are locked: view-only, and used as bases
// for new configs (start from scratch or from one of these). Both the fixed seed
// id (the seed SQL inserts ids 1-3) and the seed name must match: a user config
// that happens to reuse a seed name stays editable, and so does a seed row an admin
// renamed through the API. This never locks more than the name-only check did.
// A future seed rename must update both sets in the same release.
const SEED_IDS: ReadonlySet<number> = new Set([1, 2, 3]);
const SEED_NAMES: ReadonlySet<string> = new Set([
  'Managed Launcher',
  'MIUI (Xiaomi Redmi)',
  'Background (Agent) Mode',
]);
const isLocked = (c: Configuration) => c.id != null && SEED_IDS.has(c.id) && SEED_NAMES.has(c.name);

/** The server flags a kiosk exit PIN that is a well-known default (absent on older servers). */
const hasDefaultPin = (c: Configuration) => c.defaultKioskPin === true;
/** Kiosk on with no exit PIN: anyone can leave kiosk mode without a prompt. */
const hasNoPin = (c: Configuration) => c.kioskMode === true && !c.password;

// The configurations INSERT sets every column explicitly, so NOT-NULL columns
// can't fall back to their DB defaults — a blank config must supply them or the
// insert fails (e.g. "null value in column pushoptions"). customerId is set
// server-side (insertRecord); applications must be a (possibly empty) array.
const NEW_CONFIG_DEFAULTS: Partial<Configuration> = {
  type: 0,
  pushOptions: 'mqttWorker',
  appPermissions: 'GRANTALL',
  requestUpdates: 'DONOTTRACK',
  downloadUpdates: 'UNLIMITED',
  desktopHeader: 'NO_HEADER',
  iconSize: 'SMALL',
  defaultFilePath: '/',
  systemUpdateType: 0,
  useDefaultDesignSettings: true,
};

/**
 * The configuration to edit: GET /configurations/{id} (every column plus its files, app settings and usage
 * parameters) with its assigned apps. The /configurations/search row lacks those lists, and a save replaces
 * them on the server with what it is sent, so the editor never starts from a list row.
 */
async function loadFull(id: number): Promise<Configuration> {
  const [full, applications] = await Promise.all([getConfiguration(id), getConfigurationApps(id)]);
  return { ...full, applications };
}

/** A fresh editable draft, optionally seeded from a full base config (see loadFull). */
function cloneForNew(base: Configuration | null): Configuration {
  if (!base) return { ...NEW_CONFIG_DEFAULTS, name: '', applications: [] } as Configuration;
  const c: Configuration = { ...base, name: `${base.name} copy` };
  delete c.id;
  delete c.qrCodeKey;
  c.applications = (base.applications ?? []).map((a) => ({ ...a }));
  return c;
}

export function ConfigurationsPage() {
  const toast = useToast();
  const [configs, setConfigs] = useState<Configuration[] | null>(null);
  const [apps, setApps] = useState<Application[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Configuration | null>(null);
  const [readOnly, setReadOnly] = useState(false);
  const [chooserOpen, setChooserOpen] = useState(false);
  const [copyOf, setCopyOf] = useState<Configuration | null>(null);
  const [sync, setSync] = useState<Record<number, ConfigSyncSummary>>({});
  const [watch, setWatch] = useState<{ id: number; until: number } | null>(null);
  // Remounts the editor for every configuration it opens, so no draft carries over (e.g. Duplicate to edit).
  const [editorKey, setEditorKey] = useState(0);
  const [opening, setOpening] = useState(false);
  const openSeq = useRef(0);

  const startEditing = (c: Configuration, ro: boolean) => {
    setReadOnly(ro);
    setEditing(c);
    setEditorKey((k) => k + 1);
  };

  /** Load the full configuration, then edit it (or start a new one from it). Nothing opens if the load fails. */
  async function openConfig(c: Configuration, asTemplate: boolean) {
    if (c.id == null) return;
    const seq = ++openSeq.current;
    setOpening(true);
    try {
      const full = await loadFull(c.id);
      if (seq !== openSeq.current) return;
      setChooserOpen(false);
      if (asTemplate) startEditing(cloneForNew(full), false);
      else startEditing(full, isLocked(c));
    } catch (e) {
      if (seq === openSeq.current) {
        toast.push('err', 'Could not open the configuration', e instanceof Error ? e.message : '');
      }
    } finally {
      if (seq === openSeq.current) setOpening(false);
    }
  }

  const refreshSync = () => getSyncSummary()
    .then((rows) => setSync(Object.fromEntries(rows.map((r) => [r.configurationId, r]))))
    .catch(() => undefined);

  const load = () =>
    getConfigurations()
      .then(setConfigs)
      .catch(() => {
        setConfigs([]);
        setError('Could not load configurations.');
      })
      .then(() => refreshSync());

  useEffect(() => {
    void load();
    listApplications().then((a) => setApps(a.filter((x) => (x.type ?? 'app') !== 'web'))).catch(() => undefined);
  }, []);

  // A save returns before devices have applied the new desired state, so poll the saved
  // configuration's sync summary for a short window: stop once its devices catch up or after
  // two minutes (powered-off devices stay out of sync), and pause while the editor is open.
  useEffect(() => {
    if (!watch || editing) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    // Self-scheduling poll: the next tick is armed only after the current one finishes.
    const poll = async () => {
      const rows = await getSyncSummary().catch(() => null);
      if (!on) return;
      if (rows) setSync(Object.fromEntries(rows.map((r) => [r.configurationId, r])));
      const row = rows?.find((r) => r.configurationId === watch.id);
      if ((rows && !row?.outOfSync) || Date.now() >= watch.until) {
        setWatch(null);
        return;
      }
      t = setTimeout(() => void poll(), 10_000);
    };
    t = setTimeout(() => void poll(), 10_000);
    return () => { on = false; clearTimeout(t); };
  }, [watch, editing]);

  if (editing) {
    return (
      <AppShell title="Configuration">
        <ConfigEditor
          key={editorKey}
          initial={editing}
          apps={apps}
          readOnly={readOnly}
          deviceCount={editing.id != null ? affectedDeviceCount(sync[editing.id]) : 0}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            if (editing.id != null) setWatch({ id: editing.id, until: Date.now() + 120_000 });
            setEditing(null);
            void load();
          }}
          onDuplicate={() => startEditing(cloneForNew(editing), false)}
        />
      </AppShell>
    );
  }

  return (
    <AppShell title="Configurations">
      <div className="page-head">
        <h1>Configurations</h1>
        <button className="btn btn-dark" onClick={() => setChooserOpen(true)}>
          New configuration
        </button>
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {configs === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Loading…</div></div>
      ) : configs.length === 0 ? (
        <div className="panel"><div className="empty"><span className="label">No configurations</span>Create one to use as a device template.</div></div>
      ) : (
        <div className="cfg-grid">
          {configs.map((c) => (
            <ConfigCard
              key={c.id}
              c={c}
              locked={isLocked(c)}
              appName={appNameForVersionId(apps, c.mainAppId as number | undefined)}
              sync={c.id != null ? sync[c.id] : undefined}
              busy={opening}
              onEdit={() => void openConfig(c, false)}
              onCopy={() => setCopyOf(c)}
              onDelete={() => void doDelete(c)}
            />
          ))}
        </div>
      )}

      {copyOf && (
        <CopyModal
          source={copyOf}
          onClose={() => setCopyOf(null)}
          onDone={() => {
            setCopyOf(null);
            void load();
          }}
        />
      )}

      {chooserOpen && (
        <NewChooser
          defaults={(configs ?? []).filter(isLocked)}
          busy={opening}
          onClose={() => {
            openSeq.current++; // a template still loading must not open after the chooser closed
            setOpening(false);
            setChooserOpen(false);
          }}
          onPick={(base) => {
            if (base) {
              void openConfig(base, true);
              return;
            }
            startEditing(cloneForNew(null), false);
            setChooserOpen(false);
          }}
        />
      )}
    </AppShell>
  );

  async function doDelete(c: Configuration) {
    if (c.id == null) return;
    if (!window.confirm(`Delete configuration "${c.name}"? This cannot be undone.`)) return;
    try {
      await deleteConfiguration(c.id);
      toast.push('ok', 'Configuration deleted', c.name);
      void load();
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      toast.push('err', 'Delete failed', /device/i.test(msg) ? 'Devices still use this configuration.' : msg);
    }
  }
}

/**
 * Devices that will actually re-apply kiosk: agents too old for config.apply ("unsupported") won't.
 * null = no sync summary for this configuration — the caller fails closed (dialog without a number).
 */
function affectedDeviceCount(row: ConfigSyncSummary | undefined): number | null {
  return row ? Math.max(0, row.total - row.unsupported) : null;
}

// configurations.mainAppId is an applicationVersions.id (NOT an applications.id): the server
// maps it back to the assigned app via configurationApplications.applicationVersionId.

/** The version id the main-app picker writes for an app: its assigned version, else its latest. */
function versionIdForApp(app: Application, assigned: ConfigApp[]): number | undefined {
  return assigned.find((x) => x.id === app.id)?.usedVersionId ?? app.latestVersion;
}

/** Display name for a mainAppId (a version id); a dash when it can't be resolved. */
function appNameForVersionId(apps: Application[], versionId?: number, assigned: ConfigApp[] = []): string {
  if (versionId == null) return '—';
  const a = assigned.find((x) => x.usedVersionId === versionId);
  if (a) return a.name ?? a.pkg ?? '—';
  return apps.find((x) => x.latestVersion === versionId)?.name ?? '—';
}

function ConfigCard({
  c,
  locked,
  appName,
  sync,
  busy,
  onEdit,
  onCopy,
  onDelete,
}: {
  c: Configuration;
  locked: boolean;
  appName: string;
  sync?: ConfigSyncSummary;
  /** A configuration is being opened. */
  busy: boolean;
  onEdit: () => void;
  onCopy: () => void;
  onDelete: () => void;
}) {
  const appCount = (c.applications?.length ?? 0);
  return (
    <div className="cfg-card">
      <div className="cfg-top">
        <div className="cfg-nm">{c.name}</div>
        {locked ? <span className="cfg-badge default">Default</span> : null}
        {c.kioskMode ? <span className="cfg-badge">Kiosk</span> : null}
        {hasDefaultPin(c) ? (
          <span className="cfg-badge warn" title="The kiosk exit PIN is a well-known default. Change it.">Default PIN</span>
        ) : null}
        {hasNoPin(c) ? (
          <span className="cfg-badge warn" title="Kiosk mode has no exit PIN, so anyone can leave it. Set one.">No PIN</span>
        ) : null}
      </div>
      {c.description ? <div className="cfg-desc">{String(c.description)}</div> : null}
      <div className="cfg-meta">
        <span><span className="k">Main app</span><span className="v">{appName}</span></span>
        <span><span className="k">Apps</span><span className="v">{appCount}</span></span>
      </div>
      <SyncBar s={sync} />
      <div className="cfg-actions">
        <button className="btn btn-sm btn-primary" onClick={onEdit} disabled={busy}>{locked ? 'View' : 'Edit'}</button>
        <button className="btn btn-sm" onClick={onCopy}>{locked ? 'Use as template' : 'Copy'}</button>
        {!locked && <button className="btn btn-sm btn-danger" onClick={onDelete}>Delete</button>}
      </div>
    </div>
  );
}

function NewChooser({
  defaults,
  busy,
  onClose,
  onPick,
}: {
  defaults: Configuration[];
  /** A base template is being loaded. */
  busy: boolean;
  onClose: () => void;
  onPick: (base: Configuration | null) => void;
}) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>New configuration</h3>
        <p className="muted" style={{ margin: '2px 0 14px' }}>Start from scratch, or base it on a default template.</p>
        <div className="chooser-list">
          <button className="chooser-opt" disabled={busy} onClick={() => onPick(null)}>
            <span className="chooser-nm">Blank configuration</span>
            <span className="chooser-sub">Empty template — set everything yourself.</span>
          </button>
          {defaults.map((d) => (
            <button key={d.id} className="chooser-opt" disabled={busy} onClick={() => onPick(d)}>
              <span className="chooser-nm">Based on “{d.name}”</span>
              <span className="chooser-sub">Copy this default’s settings and apps, then customise.</span>
            </button>
          ))}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

function CopyModal({ source, onClose, onDone }: { source: Configuration; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [name, setName] = useState(`${source.name} copy`);
  const [busy, setBusy] = useState(false);
  async function go() {
    if (!name.trim() || source.id == null) return;
    setBusy(true);
    try {
      await copyConfiguration(source.id, name.trim(), source.description as string | undefined);
      toast.push('ok', 'Configuration copied', name.trim());
      onDone();
    } catch (e) {
      toast.push('err', 'Copy failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Copy configuration</h3>
        <label className="field"><span>New name</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </label>
        <div className="modal-actions">
          <button className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="btn btn-primary" disabled={busy || !name.trim()} onClick={() => void go()}>
            {busy ? 'Copying…' : 'Copy'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Editor ────────────────────────────────────────────────────────────────-
function ConfigEditor({
  initial,
  apps,
  readOnly,
  deviceCount,
  onCancel,
  onSaved,
  onDuplicate,
}: {
  initial: Configuration;
  apps: Application[];
  readOnly: boolean;
  /** Devices that will re-apply kiosk; null when unknown (confirm without a number). */
  deviceCount: number | null;
  onCancel: () => void;
  onSaved: () => void;
  onDuplicate: () => void;
}) {
  const toast = useToast();
  // `initial` is the full configuration with its assigned apps (loadFull), or a new draft. The save sends
  // the whole draft, so the server keeps every list and column the editor doesn't render.
  const [draft, setDraft] = useState<Configuration>(() => ({ ...initial }));
  // The unmodified starting point for change detection (e.g. kioskAffectingChanges).
  const baseline = initial;
  const [advanced, setAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [confirmKeys, setConfirmKeys] = useState<string[] | null>(null);
  const isNew = initial.id == null;

  const set = (key: string, value: unknown) => setDraft((d) => ({ ...d, [key]: value }));

  const allowed: ConfigApp[] = (draft.applications as ConfigApp[] | undefined) ?? [];
  const allowedIds = useMemo(() => new Set(allowed.map((a) => a.id)), [allowed]);
  // Only an installed app that is shown in kiosk can be the main app.
  const mainAppChoices = apps.filter((a) => allowed.some((x) => x.id === a.id && (x.action ?? 1) === 1 && x.showIcon !== false));

  function addApps(chosen: Application[]) {
    const entries: ConfigApp[] = chosen.map((app) => ({
      id: app.id,
      name: app.name,
      pkg: app.pkg,
      version: app.version,
      // Same version the server would default to; lets the main-app picker resolve it.
      usedVersionId: app.latestVersion,
      action: 1,
      showIcon: true,
      remove: false,
    }));
    set('applications', [...allowed, ...entries]);
  }
  function removeApp(id: number) {
    set('applications', allowed.filter((a) => a.id !== id));
  }
  function setAppAction(id: number, action: number) {
    set('applications', allowed.map((a) => (a.id === id ? { ...a, action } : a)));
  }
  function setAppKioskVisibility(id: number, showIcon: boolean) {
    setDraft((d) => {
      const assigned = (d.applications as ConfigApp[] | undefined) ?? [];
      const changed = assigned.find((a) => a.id === id);
      const applications = assigned.map((a) => (a.id === id ? { ...a, showIcon } : a));
      // A hidden app cannot remain the kiosk's pinned main app. Clearing the stale
      // selection makes the resulting launcher state explicit rather than silently
      // granting it kiosk access again.
      const mainAppId = !showIcon && changed?.usedVersionId === d.mainAppId ? null : d.mainAppId;
      return { ...d, applications, mainAppId };
    });
  }

  function requestSave() {
    if (!String(draft.name ?? '').trim()) {
      toast.push('err', 'Name required', 'Give the configuration a name.');
      return;
    }
    const keys = isNew ? [] : kioskAffectingChanges(baseline, draft);
    // deviceCount null = unknown -> confirm anyway (fail closed); 0 = no device will re-apply.
    if (keys.length > 0 && deviceCount !== 0) { setConfirmKeys(keys); return; }
    void doSave();
  }

  async function doSave() {
    setBusy(true);
    try {
      await saveConfiguration(draft);
      toast.push('ok', isNew ? 'Configuration created' : 'Configuration saved', String(draft.name));
      onSaved();
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      toast.push('err', 'Save failed', /duplicate/i.test(msg) ? 'A configuration with that name exists.' : msg);
    } finally {
      setBusy(false);
    }
  }

  const enforcedByGroup = GROUP_ORDER.map((g) => ({
    group: g,
    fields: PRIMARY_FIELDS.filter((f) => f.group === g),
  })).filter((x) => x.fields.length > 0);

  const legacyByGroup = GROUP_ORDER.map((g) => ({
    group: g,
    fields: LEGACY_FIELDS.filter((f) => f.group === g),
  })).filter((x) => x.fields.length > 0);

  // The server marks a well-known default PIN; once the PIN is edited here that flag is stale.
  const defaultPin = hasDefaultPin(draft) && (draft.password ?? '') === (baseline.password ?? '');
  const noPin = hasNoPin(draft);

  return (
    <>
      <div className="crumb">
        <a href="/configs" onClick={(e) => { e.preventDefault(); onCancel(); }}>Configurations</a>
        {' / '}{isNew ? 'New' : String(initial.name)}
      </div>

      <div className="cfg-editbar">
        <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>
          {isNew ? 'New configuration' : String(initial.name)}
        </h1>
        <div style={{ flex: 1 }} />
        <button className="btn" onClick={onCancel} disabled={busy}>{readOnly ? 'Back' : 'Cancel'}</button>
        {readOnly ? (
          <button className="btn btn-primary" onClick={onDuplicate}>Duplicate to edit</button>
        ) : (
          <button className="btn btn-primary" onClick={requestSave} disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        )}
      </div>

      {confirmKeys ? (
        <KioskChangeConfirm
          count={deviceCount}
          keys={confirmKeys}
          onCancel={() => setConfirmKeys(null)}
          onConfirm={() => { setConfirmKeys(null); void doSave(); }}
        />
      ) : null}

      {readOnly && (
        <div className="banner cfg-default-note">
          This is a built-in default template — view only. Use <b>Duplicate to edit</b> to make
          your own editable copy.
        </div>
      )}

      {defaultPin && (
        <div className="banner banner-warn" role="alert">
          <b>Default kiosk exit PIN.</b>{' '}
          {readOnly
            ? 'Devices on this template use a well-known PIN, so anyone who knows it can leave kiosk mode. Duplicate it, set your own PIN, and move the devices to the copy.'
            : 'This configuration uses a well-known PIN, so anyone who knows it can leave kiosk mode. Set your own under Kiosk → Kiosk exit PIN.'}
        </div>
      )}

      {noPin && (
        <div className="banner banner-warn" role="alert">
          <b>No kiosk exit PIN.</b>{' '}
          {readOnly
            ? 'Devices on this template can leave kiosk mode without a PIN. Duplicate it, set a PIN, and move the devices to the copy.'
            : 'Anyone can leave kiosk mode on these devices without a prompt. Set a PIN under Kiosk → Kiosk exit PIN.'}
        </div>
      )}

      {enforcedByGroup.map(({ group, fields }) => (
        <section className="panel cfg-panel" key={group}>
          <div className="cfg-sec-h">{group}</div>
          {GROUP_NOTES[group] ? <p className="cfg-legacy-note">{GROUP_NOTES[group]}</p> : null}
          {fields.map((f) => (
            <Field
              key={f.key}
              def={f}
              value={draft[f.key]}
              stored={baseline[f.key]}
              kioskOn={draft.kioskMode === true}
              warn={f.key !== 'password' ? undefined
                : defaultPin ? 'Well-known default PIN: change it.'
                : noPin ? 'No PIN: anyone can leave kiosk. Set one.' : undefined}
              apps={f.key === 'mainAppId' ? mainAppChoices : apps}
              assigned={allowed}
              disabled={readOnly}
              onChange={(v) => set(f.key, v)}
            />
          ))}
        </section>
      ))}

      <section className="panel cfg-panel">
        <div className="cfg-sec-h" style={{ display: 'flex', alignItems: 'center' }}>
          <span>Managed apps</span>
          {!readOnly && (
            <button className="btn btn-sm" style={{ marginLeft: 'auto' }} onClick={() => setPickerOpen(true)}>
              Add apps
            </button>
          )}
        </div>
        <p className="note" style={{ margin: '0 0 12px' }}>
          Apps this template installs on its devices. “Do not install” keeps an app listed without installing it.
          “Remove” takes the app out of this configuration; it is not uninstalled from devices yet.
          {draft.kioskMode === true ? ' “Show in kiosk” controls whether an installed app appears in the kiosk launcher.' : ''}
        </p>
        {allowed.length === 0 && <div className="cfg-empty">No apps assigned.</div>}
        {allowed.map((a) => (
          <div className="cfg-app" key={a.id}>
            <span className="cfg-app-nm">{a.name ?? a.pkg ?? `#${a.id}`}</span>
            <span className="cfg-app-pkg mono">{a.pkg}</span>
            <span className="app-ver">{a.version ? `v${a.version}` : '—'}</span>
            <select
              className="sel"
              value={a.action ?? 1}
              disabled={readOnly}
              onChange={(e) => setAppAction(a.id, Number(e.target.value))}
            >
              <option value={1}>Install</option>
              <option value={2}>Remove</option>
              <option value={0}>Do not install</option>
            </select>
            {draft.kioskMode === true && (
              <label className="cfg-app-kiosk" title="Allow this installed app to appear and launch in kiosk mode">
                <input
                  type="checkbox"
                  checked={a.showIcon !== false}
                  disabled={readOnly || (a.action ?? 1) !== 1}
                  onChange={(e) => setAppKioskVisibility(a.id, e.target.checked)}
                />
                Show in kiosk
              </label>
            )}
            {!readOnly && (
              <button className="btn btn-sm btn-ghost" onClick={() => removeApp(a.id)} aria-label="Remove app">✕</button>
            )}
          </div>
        ))}
      </section>

      <button className="cfg-adv-toggle" onClick={() => setAdvanced((v) => !v)}>
        {advanced ? '▾' : '▸'} Headwind launcher only (not used by the MDMesh agent) · {LEGACY_FIELDS.length}
      </button>
      {advanced && (
        <p className="cfg-legacy-note">Only the Headwind launcher read these settings; the MDMesh agent does not apply them. They stay stored with the configuration, and saving keeps their values.</p>
      )}

      {advanced &&
        legacyByGroup.map(({ group, fields }) => (
          <section className="panel cfg-panel" key={group}>
            <div className="cfg-sec-h">{group}</div>
            {fields.map((f) => (
              <Field key={f.key} def={f} value={draft[f.key]} stored={baseline[f.key]} apps={apps} assigned={allowed} disabled={readOnly} onChange={(v) => set(f.key, v)} />
            ))}
          </section>
        ))}

      {pickerOpen && (
        <AppPicker
          apps={apps}
          excludeIds={allowedIds}
          onAdd={addApps}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </>
  );
}

// ── Field control ──────────────────────────────────────────────────────────
function Field({
  def,
  value,
  stored,
  kioskOn,
  warn,
  apps,
  assigned,
  disabled,
  onChange,
}: {
  def: FieldDef;
  value: unknown;
  /** The value as loaded, before edits: a `flag` switched off restores a stored null. */
  stored: unknown;
  kioskOn?: boolean;
  warn?: string;
  apps: Application[];
  assigned: ConfigApp[];
  disabled?: boolean;
  onChange: (v: unknown) => void;
}) {
  return (
    <div className="cfg-field">
      <div className="cfg-field-label">
        <label>{def.label}</label>
        {def.enforced ? <span className="chip chip-enforced" title="Applied on devices by the MDMesh agent">Enforced</span> : null}
        {def.kioskOnly ? (
          <span
            className={`chip chip-enforced chip-scope${kioskOn ? '' : ' off'}`}
            title={kioskOn ? 'Applies only while kiosk mode is on' : 'Kiosk mode is off, so this has no effect until it is turned on'}
          >
            Applies in kiosk mode
          </span>
        ) : null}
        {def.enrollQr ? (
          <span className="chip chip-enforced chip-scope" title="Read by the Enroll page's QR code; not sent to devices">Enrollment QR</span>
        ) : null}
        <span className="cfg-field-help">{def.help}</span>
        {warn ? <span className="cfg-field-help cfg-field-warn">{warn}</span> : null}
      </div>
      <div className="cfg-field-ctl">
        <FieldControl def={def} value={value} stored={stored} apps={apps} assigned={assigned} disabled={disabled} onChange={onChange} />
      </div>
    </div>
  );
}

/** Password input with a show/hide toggle (the kiosk exit PIN must stay readable for admins). */
function PasswordInput({ label, value, disabled, onChange }: { label: string; value: unknown; disabled?: boolean; onChange: (v: unknown) => void }) {
  const [shown, setShown] = useState(false);
  return (
    <span className="cfg-reveal">
      <input
        className="input"
        type={shown ? 'text' : 'password'}
        value={value == null ? '' : String(value)}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        autoComplete="new-password"
        spellCheck={false}
        aria-label={label}
      />
      <button type="button" className="btn btn-sm btn-ghost" onClick={() => setShown((v) => !v)} aria-pressed={shown}>
        {shown ? 'Hide' : 'Show'}
      </button>
    </span>
  );
}

function FieldControl({ def, value, stored, apps, assigned, disabled, onChange }: { def: FieldDef; value: unknown; stored: unknown; apps: Application[]; assigned: ConfigApp[]; disabled?: boolean; onChange: (v: unknown) => void }) {
  switch (def.type) {
    case 'switch':
      return (
        <input type="checkbox" className="dev-check" checked={value === true} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      );
    case 'flag': {
      // null and false mean the same on the device; switching off never turns a stored null into false.
      const set = (on: boolean) => onChange(on ? true : flagOffValue(stored));
      const labels = def.flagLabels;
      if (labels) {
        return (
          <span className="seg">
            {[false, true].map((on) => (
              <button key={String(on)} className={(value === true) === on ? 'on' : ''} aria-pressed={(value === true) === on} disabled={disabled} onClick={() => set(on)}>
                {labels[on ? 1 : 0]}
              </button>
            ))}
          </span>
        );
      }
      return (
        <input type="checkbox" className="dev-check" checked={value === true} disabled={disabled} onChange={(e) => set(e.target.checked)} aria-label={def.label} />
      );
    }
    case 'tri':
      return (
        <span className="seg">
          {(def.segments ?? DEFAULT_SEGMENTS).map((o) => (
            <button key={String(o.value)} className={value === o.value || (o.value === null && value == null) ? 'on' : ''} aria-pressed={value === o.value || (o.value === null && value == null)} disabled={disabled} onClick={() => onChange(o.value)}>
              {o.label}
            </button>
          ))}
        </span>
      );
    case 'enum': {
      const numeric = typeof def.options?.[0]?.value === 'number';
      return (
        <select className="sel" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : numeric ? Number(e.target.value) : e.target.value)}>
          <option value="">—</option>
          {def.options?.map((o) => (
            <option key={String(o.value)} value={String(o.value)}>{o.label}</option>
          ))}
        </select>
      );
    }
    case 'app': {
      // The stored value is an applicationVersions.id (see versionIdForApp).
      const options = apps
        .map((a) => ({ a, vid: versionIdForApp(a, assigned) }))
        .filter((o): o is { a: Application; vid: number } => o.vid != null);
      const known = value == null || options.some((o) => o.vid === value);
      return (
        <select className="sel" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}>
          <option value="">— none —</option>
          {!known && <option value={String(value)}>{appNameForVersionId(apps, value as number, assigned)} (version #{String(value)})</option>}
          {options.map(({ a, vid }) => (
            <option key={a.id} value={vid}>{a.name} ({a.pkg})</option>
          ))}
        </select>
      );
    }
    case 'int':
      return (
        <input className="input" type="number" min={def.min} max={def.max} value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
      );
    case 'textarea':
      return <textarea className="input" rows={2} value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case 'time':
      return <input className="input" type="time" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value || null)} />;
    case 'color':
      // Unset = the kiosk launcher's own default, so say "Default" instead of implying white.
      return (
        <span className="cfg-color">
          <span className="cfg-color-v">{value ? String(value) : 'Default'}</span>
          <input type="color" value={value ? String(value) : '#ffffff'} disabled={disabled} onChange={(e) => onChange(e.target.value)} aria-label={def.label} />
          {value && !disabled ? (
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => onChange(null)} title="Use the kiosk launcher's default color">
              Reset
            </button>
          ) : null}
        </span>
      );
    case 'password':
      return <PasswordInput label={def.label} value={value} disabled={disabled} onChange={onChange} />;
    default:
      return <input className="input" type="text" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
  }
}
