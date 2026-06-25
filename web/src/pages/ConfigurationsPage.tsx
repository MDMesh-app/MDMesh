import { useEffect, useMemo, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import {
  getConfigurations,
  saveConfiguration,
  deleteConfiguration,
  copyConfiguration,
  type Configuration,
  type ConfigApp,
} from '../api/configurations';
import { listApplications, type Application } from '../api/applications';
import {
  FOCUSED_FIELDS,
  ADVANCED_FIELDS,
  GROUP_ORDER,
  type FieldDef,
} from '../data/configFields';
import { AppPicker } from '../components/AppPicker';

// The seeded device-template defaults are locked: view-only, and used as bases
// for new configs (start from scratch or from one of these).
const DEFAULT_CONFIG_NAMES = new Set([
  'Managed Launcher',
  'MIUI (Xiaomi Redmi)',
  'Background (Agent) Mode',
]);
const isLocked = (c: Configuration) => DEFAULT_CONFIG_NAMES.has(c.name);

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

/** A fresh editable draft, optionally seeded from a base config. */
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

  const load = () =>
    getConfigurations()
      .then(setConfigs)
      .catch(() => {
        setConfigs([]);
        setError('Could not load configurations.');
      });

  useEffect(() => {
    void load();
    listApplications().then((a) => setApps(a.filter((x) => (x.type ?? 'app') !== 'web'))).catch(() => undefined);
  }, []);

  if (editing) {
    return (
      <AppShell title="Configuration">
        <ConfigEditor
          initial={editing}
          apps={apps}
          readOnly={readOnly}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void load();
          }}
          onDuplicate={() => {
            setEditing(cloneForNew(editing));
            setReadOnly(false);
          }}
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
              appName={appName(apps, c.mainAppId as number | undefined)}
              onEdit={() => {
                setReadOnly(isLocked(c));
                setEditing(c);
              }}
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
          onClose={() => setChooserOpen(false)}
          onPick={(base) => {
            setEditing(cloneForNew(base));
            setReadOnly(false);
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

function appName(apps: Application[], id?: number): string {
  if (id == null) return '—';
  return apps.find((a) => a.id === id)?.name ?? `#${id}`;
}

function ConfigCard({
  c,
  locked,
  appName,
  onEdit,
  onCopy,
  onDelete,
}: {
  c: Configuration;
  locked: boolean;
  appName: string;
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
      </div>
      {c.description ? <div className="cfg-desc">{String(c.description)}</div> : null}
      <div className="cfg-meta">
        <span><span className="k">Main app</span><span className="v">{appName}</span></span>
        <span><span className="k">Apps</span><span className="v">{appCount}</span></span>
      </div>
      <div className="cfg-actions">
        <button className="btn btn-sm btn-primary" onClick={onEdit}>{locked ? 'View' : 'Edit'}</button>
        <button className="btn btn-sm" onClick={onCopy}>{locked ? 'Use as template' : 'Copy'}</button>
        {!locked && <button className="btn btn-sm btn-danger" onClick={onDelete}>Delete</button>}
      </div>
    </div>
  );
}

function NewChooser({
  defaults,
  onClose,
  onPick,
}: {
  defaults: Configuration[];
  onClose: () => void;
  onPick: (base: Configuration | null) => void;
}) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>New configuration</h3>
        <p className="muted" style={{ margin: '2px 0 14px' }}>Start from scratch, or base it on a default template.</p>
        <div className="chooser-list">
          <button className="chooser-opt" onClick={() => onPick(null)}>
            <span className="chooser-nm">Blank configuration</span>
            <span className="chooser-sub">Empty template — set everything yourself.</span>
          </button>
          {defaults.map((d) => (
            <button key={d.id} className="chooser-opt" onClick={() => onPick(d)}>
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
  onCancel,
  onSaved,
  onDuplicate,
}: {
  initial: Configuration;
  apps: Application[];
  readOnly: boolean;
  onCancel: () => void;
  onSaved: () => void;
  onDuplicate: () => void;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<Configuration>(() => ({ ...initial }));
  const [advanced, setAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const isNew = initial.id == null;

  const set = (key: string, value: unknown) => setDraft((d) => ({ ...d, [key]: value }));

  const allowed: ConfigApp[] = (draft.applications as ConfigApp[] | undefined) ?? [];
  const allowedIds = useMemo(() => new Set(allowed.map((a) => a.id)), [allowed]);

  function addApps(chosen: Application[]) {
    const entries: ConfigApp[] = chosen.map((app) => ({
      id: app.id,
      name: app.name,
      pkg: app.pkg,
      version: app.version,
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

  async function save() {
    if (!String(draft.name ?? '').trim()) {
      toast.push('err', 'Name required', 'Give the configuration a name.');
      return;
    }
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

  const advByGroup = GROUP_ORDER.map((g) => ({
    group: g,
    fields: ADVANCED_FIELDS.filter((f) => f.group === g),
  })).filter((x) => x.fields.length > 0);

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
          <button className="btn btn-primary" onClick={() => void save()} disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        )}
      </div>

      {readOnly && (
        <div className="banner cfg-default-note">
          This is a built-in default template — view only. Use <b>Duplicate to edit</b> to make
          your own editable copy.
        </div>
      )}

      <section className="panel cfg-panel">
        {FOCUSED_FIELDS.map((f) => (
          <Field key={f.key} def={f} value={draft[f.key]} apps={apps} disabled={readOnly} onChange={(v) => set(f.key, v)} />
        ))}
      </section>

      <section className="panel cfg-panel">
        <div className="cfg-sec-h" style={{ display: 'flex', alignItems: 'center' }}>
          <span>Allowed apps</span>
          {!readOnly && (
            <button className="btn btn-sm" style={{ marginLeft: 'auto' }} onClick={() => setPickerOpen(true)}>
              Add apps
            </button>
          )}
        </div>
        <p className="note" style={{ margin: '0 0 12px' }}>
          Apps this template installs on its devices. Set an app to “Remove” to uninstall it.
        </p>
        {allowed.length === 0 && <div className="cfg-empty">No apps assigned.</div>}
        {allowed.map((a) => (
          <div className="cfg-app" key={a.id}>
            <span className="cfg-app-nm">{a.name ?? a.pkg ?? `#${a.id}`}</span>
            <span className="cfg-app-pkg mono">{a.pkg}</span>
            <select
              className="sel"
              value={a.action ?? 1}
              disabled={readOnly}
              onChange={(e) => setAppAction(a.id, Number(e.target.value))}
            >
              <option value={1}>Install</option>
              <option value={2}>Remove</option>
              <option value={0}>Hide icon</option>
            </select>
            {!readOnly && (
              <button className="btn btn-sm btn-ghost" onClick={() => removeApp(a.id)} aria-label="Remove app">✕</button>
            )}
          </div>
        ))}
      </section>

      <button className="cfg-adv-toggle" onClick={() => setAdvanced((v) => !v)}>
        {advanced ? '▾' : '▸'} Advanced settings ({ADVANCED_FIELDS.length} options)
      </button>

      {advanced &&
        advByGroup.map(({ group, fields }) => (
          <section className="panel cfg-panel" key={group}>
            <div className="cfg-sec-h">{group}</div>
            {fields.map((f) => (
              <Field key={f.key} def={f} value={draft[f.key]} apps={apps} disabled={readOnly} onChange={(v) => set(f.key, v)} />
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
  apps,
  disabled,
  onChange,
}: {
  def: FieldDef;
  value: unknown;
  apps: Application[];
  disabled?: boolean;
  onChange: (v: unknown) => void;
}) {
  return (
    <div className="cfg-field">
      <div className="cfg-field-label">
        <label>{def.label}</label>
        <span className="cfg-field-help">{def.help}</span>
      </div>
      <div className="cfg-field-ctl">
        <FieldControl def={def} value={value} apps={apps} disabled={disabled} onChange={onChange} />
      </div>
    </div>
  );
}

function FieldControl({ def, value, apps, disabled, onChange }: { def: FieldDef; value: unknown; apps: Application[]; disabled?: boolean; onChange: (v: unknown) => void }) {
  switch (def.type) {
    case 'switch':
      return (
        <input type="checkbox" className="dev-check" checked={value === true} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      );
    case 'tri':
      return (
        <span className="seg">
          {[
            { v: null, l: 'Auto' },
            { v: true, l: 'On' },
            { v: false, l: 'Off' },
          ].map((o) => (
            <button key={String(o.v)} className={value === o.v || (o.v === null && value == null) ? 'on' : ''} disabled={disabled} onClick={() => onChange(o.v)}>
              {o.l}
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
    case 'app':
      return (
        <select className="sel" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}>
          <option value="">— none —</option>
          {apps.map((a) => (
            <option key={a.id} value={a.id}>{a.name} ({a.pkg})</option>
          ))}
        </select>
      );
    case 'int':
      return (
        <input className="input" type="number" min={def.min} max={def.max} value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
      );
    case 'textarea':
      return <textarea className="input" rows={2} value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case 'time':
      return <input className="input" type="time" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value || null)} />;
    case 'color':
      return <input type="color" value={value ? String(value) : '#ffffff'} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case 'password':
      return <input className="input" type="password" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} autoComplete="new-password" />;
    default:
      return <input className="input" type="text" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
  }
}
