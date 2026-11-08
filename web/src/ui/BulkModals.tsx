// Dialogs that act on a set of devices (the Devices list's bulk bar and row menus; any page with a device selection):
// run an action, move to a configuration, delete. Same operations, payloads and toasts as the v0.3 console's bulk
// dialogs, on <Modal> (native <dialog>: Escape, focus trap, focus return). Not in the barrel (API code): import from
// '../ui/BulkModals'.
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { listApplications, type Application } from '../api/applications';
import {
  ACTION_TEMPLATES,
  buildInstallCommand,
  bulkQueueCommand,
  isBulkForbidden,
  type AppInstallSpec,
  type CommandTemplateExt,
} from '../api/commands';
import {
  getConfigurationApps,
  getConfigurations,
  type Configuration,
  type ConfigurationSummary,
} from '../api/configurations';
import { bulkSetConfiguration, deleteDevicesBulk } from '../api/devices';
import {
  buildKioskPayload,
  configuredKiosk,
  configuredKioskSummary,
  EXIT_PIN_HELP,
  EXIT_PIN_PLACEHOLDER,
  kioskTheme,
  type ConfiguredKiosk,
  type KioskChoice,
} from '../data/kiosk';
import { useToast } from './toast';
import { Button } from './Button';
import { Callout } from './Card';
import { EmptyState } from './EmptyState';
import { Eyebrow, Field } from './Field';
import { Input, SearchInput, Select } from './Input';
import { Modal } from './Modal';
import { PasswordInput } from './Password';
import { Segmented } from './Segmented';
import './BulkModals.css';

const plural = (n: number, word = 'device') => `${n} ${word}${n === 1 ? '' : 's'}`;
const errText = (e: unknown) => (e instanceof Error ? e.message : '');

// ---------------------------------------------------------------------------------------------------------------
// Run action
// ---------------------------------------------------------------------------------------------------------------

// Only safe + disruptive actions run in bulk: the server refuses the destructive ones (passcode-reset, wipe) there,
// and kiosk-enter has its own steps (Return to configured kiosk, and Custom kiosk's Library picker). Same rule as
// the classic BulkActionModal.
const BULK_GROUPS: Array<{ id: 'safe' | 'disruptive'; title: string }> = [
  { id: 'safe', title: 'Actions' },
  { id: 'disruptive', title: 'Disruptive' },
];

function bulkable(t: CommandTemplateExt): boolean {
  if (isBulkForbidden(t.request.type)) return false;
  return t.key !== 'kiosk-enter';
}

/** A Library app as an install spec (single url or split bundle parts); null when nothing is hosted. */
function specForApp(app: Application): AppInstallSpec | null {
  let parts: { url: string; sha256?: string }[] | undefined;
  if (app.parts) {
    try {
      const arr = JSON.parse(app.parts) as { url: string; sha256?: string }[];
      if (Array.isArray(arr) && arr.length) parts = arr;
    } catch {
      parts = undefined;
    }
  }
  if (!parts && !app.url) return null;
  return { url: app.url ?? '', packageName: app.pkg, versionCode: app.latestVersion, parts };
}

type Step =
  | { kind: 'catalog' }
  | { kind: 'params'; t: CommandTemplateExt }
  | { kind: 'apps' }
  | { kind: 'kiosk' }
  | { kind: 'configured' };

/** The selected devices of one configuration, and its kiosk (null: skipped, `skip` says why). */
interface KioskGroup {
  key: string;
  name: string;
  ids: number[];
  kiosk: ConfiguredKiosk | null;
  skip?: string;
}

/**
 * The selection by configuration, each with the kiosk its configuration defines (configuredKiosk), for "Return to
 * configured kiosk". Throws when the configurations or a kiosk configuration's apps can't be loaded.
 */
async function loadKioskGroups(
  deviceIds: number[],
  devices: ReadonlyArray<{ id: number; configurationId?: number }>,
): Promise<KioskGroup[]> {
  const byConfig = new Map<number | undefined, number[]>();
  for (const id of deviceIds) {
    const cid = devices.find((d) => d.id === id)?.configurationId;
    byConfig.set(cid, [...(byConfig.get(cid) ?? []), id]);
  }
  const configs = await getConfigurations();
  const groups: KioskGroup[] = [];
  for (const [cid, ids] of byConfig) {
    const cfg = cid == null ? undefined : configs.find((c) => c.id === cid);
    const key = String(cid ?? 'none');
    if (cid == null) {
      groups.push({ key, name: 'No configuration', ids, kiosk: null, skip: 'no configuration, so no configured kiosk' });
    } else if (!cfg) {
      groups.push({ key, name: `Configuration ${cid}`, ids, kiosk: null, skip: 'its configuration isn’t available to you' });
    } else if (cfg.kioskMode !== true) {
      groups.push({ key, name: cfg.name, ids, kiosk: null, skip: 'kiosk mode is off in this configuration' });
    } else {
      const kiosk = configuredKiosk(cfg, await getConfigurationApps(cid));
      groups.push(
        kiosk && kiosk.apps.length
          ? { key, name: cfg.name, ids, kiosk }
          : { key, name: cfg.name, ids, kiosk: null, skip: 'no app is set to Install and shown in kiosk' },
      );
    }
  }
  // Devices that return first, then the skipped ones; by name within each.
  return groups.sort((a, b) => Number(!a.kiosk) - Number(!b.kiosk) || a.name.localeCompare(b.name));
}

/** Library apps, loaded when a step needs them. */
function useLibrary(enabled: boolean) {
  const [apps, setApps] = useState<Application[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (!enabled || apps) return;
    let cancelled = false;
    listApplications()
      .then((r) => {
        if (!cancelled) setApps(r);
      })
      .catch((e) => {
        if (!cancelled) setErr(errText(e) || 'Failed to load apps');
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, apps]);
  return { apps, err };
}

export function RunActionModal({
  deviceIds,
  devices,
  onClose,
  onDone,
}: {
  deviceIds: number[];
  devices: ReadonlyArray<{ id: number; configurationId?: number }>;
  onClose: () => void;
  /** After a command was queued (clears the selection). */
  onDone: () => void;
}) {
  const toast = useToast();
  const n = deviceIds.length;
  const [step, setStep] = useState<Step>({ kind: 'catalog' });
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [appQuery, setAppQuery] = useState('');
  const library = useLibrary(step.kind === 'apps' || step.kind === 'kiosk');

  // kiosk step state
  const [mode, setMode] = useState<KioskChoice['mode']>('launcher');
  const [pkgs, setPkgs] = useState<Set<string>>(new Set());
  const [exitMode, setExitMode] = useState<KioskChoice['exitMode']>('gesture');
  const [password, setPassword] = useState('');

  // configured-kiosk step state
  const [groups, setGroups] = useState<KioskGroup[] | null>(null);
  const [groupsErr, setGroupsErr] = useState<string | null>(null);
  const [groupsAttempt, setGroupsAttempt] = useState(0);
  const [sendErr, setSendErr] = useState<string | null>(null);
  useEffect(() => {
    if (step.kind !== 'configured') return;
    let cancelled = false;
    setGroups(null);
    setGroupsErr(null);
    loadKioskGroups(deviceIds, devices)
      .then((g) => {
        if (!cancelled) setGroups(g);
      })
      .catch((e) => {
        if (!cancelled) setGroupsErr(errText(e) || 'request failed');
      });
    return () => {
      cancelled = true;
    };
  }, [step.kind, deviceIds, devices, groupsAttempt]);

  const finish = () => {
    onDone();
    onClose();
  };

  async function run(t: CommandTemplateExt, vals: Record<string, string>) {
    setBusy(true);
    try {
      const req = t.build ? t.build(vals) : t.request;
      const res = await bulkQueueCommand(deviceIds, req);
      const skipped = res.skipped?.length ?? 0;
      toast.push(
        'ok',
        `${t.label} queued`,
        `Queued for ${plural(res.queued)}` + (skipped ? ` (${skipped} skipped)` : '') + '.',
      );
      finish();
    } catch (e) {
      toast.push('err', `${t.label} failed`, errText(e));
    } finally {
      setBusy(false);
    }
  }

  async function runInstall(app: Application) {
    const spec = specForApp(app);
    if (!spec) {
      toast.push('err', 'Not installable', `${app.name} has no hosted APK.`);
      return;
    }
    setBusy(true);
    try {
      const res = await bulkQueueCommand(deviceIds, buildInstallCommand(spec));
      const skipped = res.skipped?.length ?? 0;
      toast.push('ok', 'Install queued', `${app.name} → ${plural(res.queued)}` + (skipped ? ` (${skipped} skipped)` : '') + '.');
      finish();
    } catch (e) {
      toast.push('err', 'Install failed', errText(e));
    } finally {
      setBusy(false);
    }
  }

  async function enterKiosk() {
    setBusy(true);
    try {
      const choice: KioskChoice = { mode, packages: Array.from(pkgs), exitMode, password };
      // One command per configuration, each carrying that configuration's launcher theme (classic BulkKioskModal).
      const groups = new Map<number | undefined, number[]>();
      for (const id of deviceIds) {
        const cid = devices.find((d) => d.id === id)?.configurationId;
        groups.set(cid, [...(groups.get(cid) ?? []), id]);
      }
      const configs: Configuration[] = await getConfigurations().catch(() => []);
      let queued = 0;
      let skipped = 0;
      for (const [cid, ids] of groups) {
        const payload = buildKioskPayload(choice, kioskTheme(configs.find((c) => c.id === cid)));
        const res = await bulkQueueCommand(ids, { type: 'kiosk.enter', payload: JSON.stringify(payload) });
        queued += res.queued ?? 0;
        skipped += res.skipped?.length ?? 0;
      }
      toast.push('ok', 'Custom kiosk queued', `Queued for ${plural(queued)}` + (skipped ? ` (${skipped} skipped)` : '') + '.');
      finish();
    } catch (e) {
      toast.push('err', 'Custom kiosk failed', errText(e));
    } finally {
      setBusy(false);
    }
  }

  /** One `kiosk.enter` per configuration, each carrying the kiosk that configuration defines. */
  async function returnToConfiguredKiosk() {
    if (!groups) return;
    setBusy(true);
    setSendErr(null);
    let queued = 0;
    let skipped = groups.filter((g) => !g.kiosk).reduce((n, g) => n + g.ids.length, 0);
    try {
      for (const g of groups) {
        if (!g.kiosk) continue;
        const res = await bulkQueueCommand(g.ids, { type: 'kiosk.enter', payload: JSON.stringify(g.kiosk.payload) });
        queued += res.queued ?? 0;
        skipped += res.skipped?.length ?? 0;
      }
      toast.push(
        'ok',
        'Return to configured kiosk queued',
        `Queued for ${plural(queued)}` + (skipped ? ` (${skipped} skipped)` : '') + '.',
      );
      finish();
    } catch (e) {
      setSendErr(
        `Couldn’t queue the command: ${errText(e) || 'the server didn’t accept it'}.` +
          (queued ? ` It was already queued for ${plural(queued)}.` : ''),
      );
    } finally {
      setBusy(false);
    }
  }

  function pick(t: CommandTemplateExt) {
    // Inputs or a confirmation (Reboot) first; everything else queues at once, as in the v0.3 console.
    if ((t.params && t.params.length > 0) || t.confirm) {
      setValues({});
      setStep({ kind: 'params', t });
      return;
    }
    void run(t, {});
  }

  function togglePkg(pkg: string) {
    setPkgs((prev) => {
      if (mode === 'single') return new Set(prev.has(pkg) ? [] : [pkg]);
      const next = new Set(prev);
      if (next.has(pkg)) next.delete(pkg);
      else next.add(pkg);
      return next;
    });
  }
  function switchMode(m: KioskChoice['mode']) {
    setMode(m);
    if (m === 'single' && pkgs.size > 1) setPkgs(new Set([Array.from(pkgs)[0]]));
  }

  const filteredApps = useMemo(
    () => (library.apps ?? []).filter((a) => `${a.name} ${a.pkg}`.toLowerCase().includes(appQuery.toLowerCase())),
    [library.apps, appQuery],
  );

  const back = (
    <Button disabled={busy} onClick={() => setStep({ kind: 'catalog' })}>
      Back
    </Button>
  );

  let title = `Run action on ${plural(n)}`;
  let description: string | undefined;
  let body: ReactNode;
  let footer: ReactNode;

  if (step.kind === 'catalog') {
    body = (
      <>
        {BULK_GROUPS.map((g) => (
          <ActionGroup key={g.id} title={g.title}>
            {ACTION_TEMPLATES.filter(bulkable)
              .filter((t) => (t.group ?? 'safe') === g.id)
              .map((t) => (
                <Button
                  key={t.key}
                  size="lg"
                  variant={t.danger ? 'danger' : 'secondary'}
                  disabled={busy}
                  title={t.description}
                  onClick={() => pick(t)}
                >
                  {t.label}
                </Button>
              ))}
          </ActionGroup>
        ))}
        <ActionGroup title="Apps">
          <Button size="lg" disabled={busy} onClick={() => setStep({ kind: 'apps' })}>
            Install app…
          </Button>
        </ActionGroup>
        <ActionGroup title="Kiosk">
          <Button
            size="lg"
            variant="primary"
            disabled={busy}
            title="Put each device back into the kiosk its configuration defines."
            onClick={() => {
              setSendErr(null);
              setStep({ kind: 'configured' });
            }}
          >
            Return to configured kiosk…
          </Button>
          <Button
            size="lg"
            disabled={busy}
            title="Lock the devices to apps you pick now, as a one-time kiosk that their configurations don’t define."
            onClick={() => setStep({ kind: 'kiosk' })}
          >
            Custom kiosk…
          </Button>
        </ActionGroup>
      </>
    );
    footer = (
      <Button disabled={busy} onClick={onClose}>
        Close
      </Button>
    );
  } else if (step.kind === 'params') {
    const t = step.t;
    const canSend = !t.params?.some((p) => p.required && !values[p.key]);
    title = t.label;
    description = t.description;
    body = (
      <>
        <p className="v2-bulkmodal__text">
          This will run on <strong>{plural(n)}</strong>.
        </p>
        {t.params?.map((p) =>
          p.kind === 'password' ? (
            <Field key={p.key} label={p.label}>
              <PasswordInput
                placeholder={p.placeholder}
                value={values[p.key] ?? ''}
                onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
              />
            </Field>
          ) : (
            <Field key={p.key} label={p.required ? p.label : `${p.label} (optional)`}>
              <Input
                type={p.kind === 'number' ? 'number' : 'text'}
                placeholder={p.placeholder}
                required={p.required}
                value={values[p.key] ?? ''}
                onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
              />
            </Field>
          ),
        )}
      </>
    );
    footer = (
      <>
        {back}
        <Button
          variant={t.danger ? 'danger' : 'primary'}
          disabled={busy || !canSend}
          onClick={() => {
            void run(t, values);
          }}
        >
          {busy ? 'Queueing…' : `Run on ${n}`}
        </Button>
      </>
    );
  } else if (step.kind === 'apps') {
    title = `Install app on ${plural(n)}`;
    body = (
      <>
        <SearchInput aria-label="Filter apps" placeholder="Filter apps" value={appQuery} onChange={setAppQuery} />
        <LibraryState apps={library.apps} err={library.err} empty={filteredApps.length === 0} />
        {filteredApps.length > 0 ? (
          <div className="v2-bulkmodal__grid">
            {filteredApps.map((a) => (
              <Button
                key={a.id ?? a.pkg}
                size="lg"
                disabled={busy}
                title={a.pkg}
                onClick={() => {
                  void runInstall(a);
                }}
              >
                {a.name}
                {a.version ? ` (${a.version})` : ''}
              </Button>
            ))}
          </div>
        ) : null}
      </>
    );
    footer = back;
  } else if (step.kind === 'configured') {
    const ready = groups?.filter((g) => g.kiosk) ?? [];
    const readyCount = ready.reduce((k, g) => k + g.ids.length, 0);
    title = 'Return to configured kiosk';
    description =
      'Puts each device back into the kiosk its configuration defines: its main app, allowed apps, kiosk options, exit PIN and launcher look. Devices whose configuration has no kiosk are skipped.';
    body = (
      <>
        {groupsErr ? (
          <Callout tone="alert">
            Couldn’t load the configurations’ kiosks: {groupsErr}.{' '}
            <Button size="sm" onClick={() => setGroupsAttempt((k) => k + 1)}>
              Try again
            </Button>
          </Callout>
        ) : !groups ? (
          <p className="v2-bulkmodal__text" role="status">
            Loading the configurations’ kiosks…
          </p>
        ) : (
          <ul className="v2-bulkmodal__kiosks" aria-label="By configuration">
            {groups.map((g) => (
              <li key={g.key} className={g.kiosk ? undefined : 'is-skipped'}>
                <strong>{g.name}</strong> · {plural(g.ids.length)}:{' '}
                {g.kiosk ? (
                  <>
                    {configuredKioskSummary(g.kiosk)}
                    {g.kiosk.mainAppLeftOut ? '. Its main app isn’t set to Install and shown in kiosk, so the kiosk leaves it out' : ''}
                  </>
                ) : (
                  `skipped, ${g.skip}`
                )}
              </li>
            ))}
          </ul>
        )}
        {sendErr ? <Callout tone="alert">{sendErr}</Callout> : null}
      </>
    );
    footer = (
      <>
        {back}
        <Button
          variant="primary"
          disabled={busy || readyCount === 0}
          onClick={() => {
            void returnToConfiguredKiosk();
          }}
        >
          {busy ? 'Queueing…' : `Return ${plural(readyCount)} to configured kiosk`}
        </Button>
      </>
    );
  } else {
    const canApply = pkgs.size > 0 && (mode === 'launcher' || pkgs.size === 1);
    title = `Custom kiosk on ${plural(n)}`;
    description = 'A one-time kiosk with apps you pick now. The devices’ configurations don’t define it.';
    body = (
      <>
        <Segmented
          aria-label="Kiosk mode"
          value={mode}
          onChange={switchMode}
          options={[
            { value: 'launcher', label: 'Allowed apps (launcher grid)' },
            { value: 'single', label: 'Pin a single app' },
          ]}
        />
        <SearchInput aria-label="Filter Library apps" placeholder="Filter Library apps" value={appQuery} onChange={setAppQuery} />
        <LibraryState apps={library.apps} err={library.err} empty={filteredApps.length === 0} />
        {filteredApps.length > 0 ? (
          <div className="v2-bulkmodal__grid" role="group" aria-label={mode === 'single' ? 'App to pin' : 'Allowed apps'}>
            {filteredApps.map((a) => {
              const on = pkgs.has(a.pkg);
              return (
                <Button
                  key={a.id ?? a.pkg}
                  size="lg"
                  variant={on ? 'primary' : 'secondary'}
                  aria-pressed={on}
                  disabled={busy}
                  title={a.pkg}
                  onClick={() => togglePkg(a.pkg)}
                >
                  {a.name}
                </Button>
              );
            })}
          </div>
        ) : null}
        <Field label="Exit mode">
          <Select value={exitMode} onChange={(e) => setExitMode(e.target.value as KioskChoice['exitMode'])}>
            <option value="gesture">Gesture</option>
            <option value="visible">Visible button</option>
            <option value="remote">Remote only</option>
          </Select>
        </Field>
        <Field label="Kiosk exit PIN">
          <PasswordInput
            value={password}
            autoComplete="new-password"
            placeholder={EXIT_PIN_PLACEHOLDER}
            title={EXIT_PIN_HELP}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
      </>
    );
    footer = (
      <>
        {back}
        <Button
          variant="primary"
          disabled={busy || !canApply}
          onClick={() => {
            void enterKiosk();
          }}
        >
          {busy ? 'Queueing…' : `Enter custom kiosk on ${n}`}
        </Button>
      </>
    );
  }

  return (
    <Modal
      open
      onClose={busy ? () => undefined : onClose}
      title={title}
      description={description}
      dismissOnBackdrop={step.kind === 'catalog'}
      footer={footer}
    >
      {body}
    </Modal>
  );
}

function ActionGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="v2-bulkmodal__group" aria-label={title}>
      <Eyebrow as="h3">{title}</Eyebrow>
      <div className="v2-bulkmodal__grid">{children}</div>
    </section>
  );
}

function LibraryState({ apps, err, empty }: { apps: Application[] | null; err: string | null; empty: boolean }) {
  if (err) return <Callout tone="alert">{err}</Callout>;
  if (!apps) return <p className="v2-bulkmodal__text">Loading library…</p>;
  if (empty)
    return (
      <EmptyState bare title={apps.length ? 'No apps match' : 'The Library is empty'}>
        {apps.length ? 'Try another name or package.' : 'Add apps under Apps first.'}
      </EmptyState>
    );
  return null;
}

// ---------------------------------------------------------------------------------------------------------------
// Change configuration
// ---------------------------------------------------------------------------------------------------------------

export function ChangeConfigurationModal({
  ids,
  configurations,
  configurationsError,
  label,
  onClose,
  onDone,
}: {
  ids: number[];
  /** Every configuration, sorted by name. */
  configurations: ConfigurationSummary[];
  /** Shown instead of the choices when the configurations could not be loaded (the list is then empty). */
  configurationsError?: string | null;
  /** "KSK-0151" for a single device from its menu; otherwise "N devices". */
  label?: string;
  onClose: () => void;
  onDone: () => void | Promise<void>;
}) {
  const toast = useToast();
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);
  const n = ids.length;

  async function apply() {
    if (!target) return;
    setBusy(true);
    try {
      await bulkSetConfiguration(ids, Number(target));
      const name = configurations.find((c) => c.id === Number(target))?.name ?? 'configuration';
      toast.push('ok', 'Configuration changed', `${n} device(s) → ${name}.`);
      onClose();
      await onDone();
    } catch (e) {
      toast.push('err', 'Change failed', errText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      size="sm"
      onClose={busy ? () => undefined : onClose}
      title="Change configuration"
      description={`Move ${label ?? plural(n)} to a configuration.`}
      footer={
        <>
          <Button disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={busy || !target}
            onClick={() => {
              void apply();
            }}
          >
            {busy ? 'Moving…' : 'Move'}
          </Button>
        </>
      }
    >
      {configurationsError ? (
        <Callout tone="alert" role="alert">
          {configurationsError}
        </Callout>
      ) : null}
      <Field label="Configuration">
        <Select value={target} disabled={!!configurationsError} onChange={(e) => setTarget(e.target.value)}>
          <option value="">Select a configuration…</option>
          {configurations.map((c) => (
            <option key={c.id} value={String(c.id)}>
              {c.name}
            </option>
          ))}
        </Select>
      </Field>
    </Modal>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Delete
// ---------------------------------------------------------------------------------------------------------------

export function DeleteDevicesModal({
  ids,
  label,
  onClose,
  onDone,
}: {
  ids: number[];
  label?: string;
  onClose: () => void;
  onDone: () => void | Promise<void>;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const n = ids.length;

  async function apply() {
    setBusy(true);
    try {
      await deleteDevicesBulk(ids);
      toast.push('ok', 'Devices deleted', `${n} device(s) removed.`);
      onClose();
      await onDone();
    } catch (e) {
      toast.push('err', 'Delete failed', errText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      size="sm"
      role="alertdialog"
      onClose={busy ? () => undefined : onClose}
      title={n === 1 ? 'Delete device' : 'Delete devices'}
      footer={
        <>
          <Button disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={busy}
            onClick={() => {
              void apply();
            }}
          >
            {busy ? 'Deleting…' : `Delete ${label && n === 1 ? label : n}`}
          </Button>
        </>
      }
    >
      <p className="v2-bulkmodal__text">
        Permanently remove {label ?? plural(n)} from MDMesh? {n === 1 ? 'It' : 'They'} will re-appear if{' '}
        {n === 1 ? 'it checks' : 'they check'} in again.
      </p>
    </Modal>
  );
}
