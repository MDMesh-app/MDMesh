import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import {
  extensionErrorsOf,
  getConfiguration,
  getConfigurationApps,
  saveConfiguration,
  type ConfigApp,
  type Configuration,
} from '../api/configurations';
import { ApiError } from '../api/client';
import { listApplications, type Application } from '../api/applications';
import { getSyncSummary, type ConfigSyncSummary } from '../api/configSync';
import {
  CONFIG_FIELDS,
  GROUP_NOTES,
  GROUP_ORDER,
  LEGACY_FIELDS,
  PRIMARY_FIELDS,
  type FieldDef,
} from '../data/configFields';
import {
  affectedDeviceCount,
  cloneForNew,
  hasDefaultPin,
  hasNoPin,
  isLocked,
} from '../data/configurationRules';
import { kioskAffectingChanges } from '../data/kioskChanges';
import {
  Badge,
  Breadcrumb,
  Button,
  ButtonLink,
  Callout,
  Card,
  CardHeader,
  ChangedChip,
  EmptyState,
  Eyebrow,
  Field,
  FieldRow,
  IconChevronDown,
  IconConfigurations,
  IconCopy,
  IconInfo,
  IconLockSmall,
  IconPlus,
  IconTrash,
  IconWarning,
  Input,
  PageHeader,
  SectionLayout,
  SectionNav,
  StickySaveBar,
  Textarea,
  useLeaveGuard,
  useToast,
} from '../ui';
import { CORE_ACTION_ACCESS, CORE_PAGE_ACCESS } from '../ext/core/access';
import { CoreConfigProvider, type CoreConfigSection } from '../ext/core/config';
import { useConfigSections, type ConfigSectionMeta } from '../ext/hosts/ConfigSections';
import { NoAccess } from '../ext/hosts/RoutePage';
import { usePermission } from '../ext/permissions';
import { paths } from '../routes';
import { AssignedApps } from './configurations/AssignedApps';
import { AppSelect, FieldControl, LongHelp } from './configurations/controls';
import { AppPickerModal, CopyConfigModal, DeleteConfigModal, KioskConfirmModal } from './configurations/modals';
import { plural, usedByLine } from './configurations/sync';
import { WATCH_MS, type ConfigWatch } from './configurations/watch';
import './ConfigEditorPage.css';

/** Configuration editor; `:id` is a configuration id or "new" (optionally `?from=<id>`). */
export function ConfigEditorPage() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  // A new configuration, blank or from a template, needs add_config (ext/core/access.ts), as its save does. Without
  // it, /new says so, as a page the role cannot use does, instead of an editor whose save the server refuses.
  const canAdd = usePermission(CORE_ACTION_ACCESS.addConfig);
  if (id === 'new' && !canAdd) return <NoAccess />;
  const from = id === 'new' ? params.get('from') : null;
  // A new key per target: /new → /new?from=2 keeps the pathname, so the editor must remount by hand.
  return <EditorLoader key={`${id}|${from ?? ''}`} id={id} from={from} />;
}

type Loaded =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'missing'; template: boolean }
  | { kind: 'ready'; initial: Configuration; base: Configuration | null };

/** The app library for the picker and the main-app choices: loading, failed (with Retry), or the apps. */
type Library = Application[] | 'loading' | 'error';

/** A configuration id from the URL: digits only (Number() would also take "1e1", "0x2" or " 2"). */
const parseId = (s: string | null): number | null => (s != null && /^\d{1,9}$/.test(s) ? Number(s) : null);

function EditorLoader({ id, from }: { id: string; from: string | null }) {
  const isNew = id === 'new';
  const [state, setState] = useState<Loaded>({ kind: 'loading' });
  const [library, setLibrary] = useState<Library>('loading');
  const [libraryAttempt, setLibraryAttempt] = useState(0);
  const [sync, setSync] = useState<ConfigSyncSummary[] | null>(null);
  const [syncFailed, setSyncFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!location.hash) window.scrollTo(0, 0);
    getSyncSummary().then(setSync, () => setSyncFailed(true));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLibrary('loading');
    listApplications()
      .then((a) => !cancelled && setLibrary(a.filter((x) => (x.type ?? 'app') !== 'web')))
      .catch(() => !cancelled && setLibrary('error'));
    return () => {
      cancelled = true;
    };
  }, [libraryAttempt]);

  useEffect(() => {
    let cancelled = false;
    const wanted = isNew ? from : id;
    if (isNew && !wanted) {
      setState({ kind: 'ready', initial: cloneForNew(null), base: null });
      return;
    }
    const num = parseId(wanted);
    if (num == null) {
      setState({ kind: 'missing', template: isNew });
      return;
    }
    setState({ kind: 'loading' });
    // The full configuration (GET /configurations/{id}): the list endpoint's rows lack `files`,
    // `applicationSettings` and `applicationUsageParameters`, and a save (PUT) replaces those lists with whatever it
    // carries, so editing from a list row would delete them. The assigned apps load separately (Editor).
    getConfiguration(num)
      .then((c) => {
        if (cancelled) return;
        if (!c || c.id !== num) setState({ kind: 'missing', template: isNew });
        else setState({ kind: 'ready', initial: isNew ? cloneForNew(c) : c, base: isNew ? c : null });
      })
      .catch((e) => {
        if (cancelled) return;
        // The server answers an id that does not exist, or one this user may not open, with "permission denied".
        const denied = e instanceof ApiError && e.message === 'error.permission.denied';
        setState(denied ? { kind: 'missing', template: isNew } : { kind: 'error' });
      });
    return () => {
      cancelled = true;
    };
  }, [id, from, isNew, attempt]);

  const crumbs = (label: string) => (
    <Breadcrumb items={[{ label: 'Configurations', to: paths.configurations }, { label }]} />
  );

  if (state.kind !== 'ready') {
    const title = isNew ? 'New configuration' : 'Configuration';
    return (
      <>
        <PageHeader title={title} breadcrumb={crumbs(title)} />
        {state.kind === 'loading' ? (
          <Card>
            <EmptyState icon={<IconConfigurations />} title="Loading configuration…" />
          </Card>
        ) : state.kind === 'error' ? (
          <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
            <span className="v2-cfged-calloutline">
              Could not load this configuration.
              <Button size="sm" onClick={() => setAttempt((n) => n + 1)}>
                Retry
              </Button>
            </span>
          </Callout>
        ) : (
          <Card>
            <EmptyState
              icon={<IconConfigurations />}
              title={state.template ? 'Template not found' : 'Configuration not found'}
              action={
                <ButtonLink size="lg" to={paths.configurations}>
                  All configurations
                </ButtonLink>
              }
            >
              {state.template
                ? 'The configuration to copy no longer exists, or you do not have access to it.'
                : 'It may have been deleted, or you do not have access to it.'}
            </EmptyState>
          </Card>
        )}
      </>
    );
  }

  const configId = isNew ? null : (state.initial.id ?? null);
  const syncRow = configId != null ? sync?.find((r) => r.configurationId === configId) : undefined;
  return (
    <Editor
      initial={state.initial}
      base={state.base}
      library={library}
      onRetryLibrary={() => setLibraryAttempt((n) => n + 1)}
      syncRow={syncRow}
      syncKnown={sync != null && !syncFailed}
      crumbs={crumbs}
    />
  );
}

// ---- section layout ----------------------------------------------------------------------------------------------

type SectionId = 'identity' | 'apps' | 'kiosk' | 'network' | 'security' | 'enrollment' | 'headwind';

const FIELD: Record<string, FieldDef> = Object.fromEntries(CONFIG_FIELDS.map((f) => [f.key, f]));
const LEGACY_KEYS: ReadonlySet<string> = new Set(LEGACY_FIELDS.map((f) => f.key));

/** Which nav section a field's change counts towards. */
function sectionOf(f: FieldDef): SectionId {
  if (LEGACY_KEYS.has(f.key)) return 'headwind';
  switch (f.group) {
    case 'Identity':
      return 'identity';
    case 'Apps':
      return 'apps';
    case 'Kiosk':
    case 'Kiosk appearance':
      return 'kiosk';
    case 'Network':
      return 'network';
    case 'Restrictions':
    case 'Security':
      return 'security';
    case 'Enrollment':
      return 'enrollment';
    default:
      return 'headwind';
  }
}

type Item = { eyebrow: string; help?: string; kiosk?: boolean } | string;

/** Field order and sub-groups; every primary field not listed falls through to the end of its section. */
const LAYOUT: Record<'kiosk' | 'network' | 'security' | 'enrollment', Item[]> = {
  kiosk: [
    'kioskMode',
    { eyebrow: 'Buttons and system screens', kiosk: true },
    'kioskHome',
    'kioskRecents',
    'kioskNotifications',
    'kioskSystemInfo',
    'kioskKeyguard',
    'kioskLockButtons',
    'kioskLockVolume',
    { eyebrow: 'Leaving kiosk mode', kiosk: true },
    'kioskExit',
    'password',
    { eyebrow: 'Launcher look', kiosk: true },
    'backgroundColor',
    'textColor',
    'iconSize',
  ],
  network: ['wifi', 'wifiRadio', 'bluetooth', 'requestUpdates', 'connectivityMode'],
  security: ['usbStorage', 'disableScreenshots', 'disableFactoryReset', 'disableSafeBoot'],
  enrollment: ['wifiSSID', 'wifiPassword', 'wifiSecurityType'],
};
for (const [sec, items] of Object.entries(LAYOUT) as Array<[keyof typeof LAYOUT, Item[]]>) {
  const placed = new Set(items.filter((i): i is string => typeof i === 'string'));
  for (const f of PRIMARY_FIELDS) if (sectionOf(f) === sec && !placed.has(f.key)) items.push(f.key);
}

/** Board help where it is clearer and still accurate; otherwise configFields help is used. */
const HELP: Record<string, string> = {
  kioskMode: 'When off, the kiosk settings below are ignored and the device uses its normal launcher.',
  kioskHome: 'The Android home button or gesture.',
  kioskRecents: 'The app switcher. Needs Home button on (Android requires it).',
  kioskNotifications: 'Pulling down the notification shade. Needs Home button on (Android requires it).',
};

const SECTION_SUB = {
  kiosk: 'Lock the screen to the apps marked “Show in kiosk”.',
  network: `Radios, location reporting and connectivity. ${GROUP_NOTES.Network ?? ''}`.trim(),
  security: `What people on the device can change. ${GROUP_NOTES.Restrictions ?? ''}`.trim(),
  enrollment: GROUP_NOTES.Enrollment ?? '',
};

const LEGACY_NOTE =
  'Only the Headwind launcher read these settings; the MDMesh agent does not apply them. They stay stored with the configuration, and saving keeps their values.';

// ---- change detection ----------------------------------------------------------------------------------------------

const norm = (v: unknown) => (v === undefined ? null : v);

function appsSignature(c: Configuration): string {
  return ((c.applications ?? []) as ConfigApp[])
    .map((a) => `${a.id}:${a.action ?? 1}:${a.showIcon === false ? 0 : 1}:${a.usedVersionId ?? ''}`)
    .sort()
    .join(',');
}

function changedKeys(before: Configuration, after: Configuration): Set<string> {
  const out = new Set<string>();
  for (const f of CONFIG_FIELDS) if (norm(before[f.key]) !== norm(after[f.key])) out.add(f.key);
  if (appsSignature(before) !== appsSignature(after)) out.add('applications');
  return out;
}

const changedLabel = (n: number) => (n > 0 ? `${n} changed` : '');

/** Save-bar sentence for a configuration devices use: agents too old for config.apply never pick a change up. */
function syncMessage(row: ConfigSyncSummary): string {
  const old = Math.min(row.unsupported, row.total);
  if (old === 0) return 'Devices using this configuration pick up the changes on their next sync.';
  const tooOld = (n: number) => `${n === 1 ? 'has an agent' : 'have agents'} too old for configuration sync`;
  if (old === row.total)
    return `${row.total === 1 ? 'The device' : `All ${row.total} devices`} using this configuration ${tooOld(row.total)}, so ${row.total === 1 ? 'it does' : 'they do'} not pick up changes until the agent is updated.`;
  return `Devices using this configuration pick up the changes on their next sync, except ${plural(old, 'device')} that ${tooOld(old)}.`;
}

const FOCUSABLE = ['input', 'select', 'textarea', 'button'].map((tag) => `${tag}:not(:disabled)`).join(', ');

/** Scroll to the section a save stopped at, and put the cursor in its first field. */
function focusSection(anchor: string) {
  const section = document.getElementById(anchor);
  section?.scrollIntoView?.({ block: 'start' });
  section?.querySelector<HTMLElement>(FOCUSABLE)?.focus({ preventScroll: true });
}

// ---- editor ------------------------------------------------------------------------------------------------------

function Editor({
  initial,
  base,
  library,
  onRetryLibrary,
  syncRow,
  syncKnown,
  crumbs,
}: {
  initial: Configuration;
  /** The template a new configuration copies (`?from=`). */
  base: Configuration | null;
  library: Library;
  onRetryLibrary: () => void;
  syncRow: ConfigSyncSummary | undefined;
  syncKnown: boolean;
  crumbs: (label: string) => ReactNode;
}) {
  const toast = useToast();
  const apps = useMemo(() => (Array.isArray(library) ? library : []), [library]);
  const libraryReady = Array.isArray(library);
  const isNew = initial.id == null;
  const readOnly = !isNew && isLocked(initial);
  const [draft, setDraft] = useState<Configuration>(() => ({ ...initial }));
  // The unmodified starting point for change detection. Kept separate from `initial` because the configuration
  // endpoint omits `applications`: once the real assigned apps load, both draft and baseline get them.
  const [baseline, setBaseline] = useState<Configuration>(() => ({ ...initial }));
  const [busy, setBusy] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [confirmKeys, setConfirmKeys] = useState<string[] | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [copying, setCopying] = useState(false);
  // Duplicate (PUT /configurations/copy) and Delete both need copy_config (ext/core/access.ts).
  const canCopy = usePermission(CORE_ACTION_ACCESS.copyConfig);
  // What the server checks for a save (ConfigurationResource PUT): configurations, and add_config for a new one.
  const mayEdit = usePermission(CORE_PAGE_ACCESS.configurations);
  const mayAdd = usePermission(CORE_ACTION_ACCESS.addConfig);
  const canSave = mayEdit && (!isNew || mayAdd);
  const [nameError, setNameError] = useState<string | null>(null);
  const [advanced, setAdvanced] = useState(false);
  // Discards so far: the apps table files its rows again by their restored state.
  const [discards, setDiscards] = useState(0);
  const nameRef = useRef<HTMLInputElement>(null);
  const ids = useId();

  // The assigned apps: the configuration's own, or the template's for a copy. Until they arrive, saving is blocked
  // (a PUT replaces the whole app set and, with desired state, the kiosk allowlist on every device). A failed fetch
  // does not unblock it (fail closed); Retry instead.
  const appsSource = isNew ? (base?.id ?? null) : (initial.id ?? null);
  const [appsReady, setAppsReady] = useState(appsSource == null);
  const [appsError, setAppsError] = useState(false);
  const [appsAttempt, setAppsAttempt] = useState(0);
  useEffect(() => {
    if (appsSource == null) return;
    let cancelled = false;
    setAppsError(false);
    getConfigurationApps(appsSource)
      .then((assigned) => {
        if (cancelled) return;
        setDraft((d) => ({ ...d, applications: assigned.map((a) => ({ ...a })) }));
        setBaseline((b) => ({ ...b, applications: assigned.map((a) => ({ ...a })) }));
        setAppsReady(true);
      })
      .catch(() => {
        if (!cancelled) setAppsError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [appsSource, appsAttempt]);

  const set = useCallback((key: string, value: unknown) => setDraft((d) => ({ ...d, [key]: value })), []);

  const changed = useMemo(() => changedKeys(baseline, draft), [baseline, draft]);

  const kioskOn = draft.kioskMode === true;
  const allowed: ConfigApp[] = (draft.applications as ConfigApp[] | undefined) ?? [];
  const allowedIds = useMemo(() => new Set(allowed.map((a) => a.id)), [allowed]);
  // Only an installed app that is shown in kiosk can be the main app.
  const mainAppChoices = apps.filter((a) =>
    allowed.some((x) => x.id === a.id && (x.action ?? 1) === 1 && x.showIcon !== false),
  );

  // The server marks a well-known default PIN; once the PIN is edited here that flag is stale.
  const defaultPin = hasDefaultPin(draft) && (draft.password ?? '') === (baseline.password ?? '');
  const noPin = hasNoPin(draft);

  const deviceCount = isNew ? 0 : affectedDeviceCount(syncRow);
  const kioskKeys = isNew ? [] : kioskAffectingChanges(baseline, draft);

  // ---- sections and their changes ----
  const changedIn = (sec: SectionId) => {
    let n = 0;
    for (const k of changed) {
      if (k === 'applications') n += sec === 'apps' ? 1 : 0;
      else if (FIELD[k] && sectionOf(FIELD[k]) === sec) n++;
    }
    return readOnly ? 0 : n;
  };
  const kioskChanged = changedIn('kiosk');
  const appsChanged = changedIn('apps');
  const hwChanged = changedIn('headwind');
  const coreMeta: Record<CoreConfigSection, ConfigSectionMeta> = {
    identity: { meta: changedLabel(changedIn('identity')) },
    apps: { meta: appsChanged ? changedLabel(appsChanged) : String(allowed.length) },
    kiosk: {
      meta: kioskChanged ? changedLabel(kioskChanged) : defaultPin ? 'Default PIN' : noPin ? 'No PIN' : '',
      metaTone: !kioskChanged && (defaultPin || noPin) ? 'warn' : 'default',
    },
    network: { meta: changedLabel(changedIn('network')) },
    security: { meta: changedLabel(changedIn('security')) },
    enrollment: { meta: changedLabel(changedIn('enrollment')) },
    headwind: { meta: hwChanged ? changedLabel(hwChanged) : String(LEGACY_FIELDS.length) },
  };
  // Extension sections: their namespaces of `extensions`, read only when the template is or the role may not save.
  const host = useConfigSections({ draft, baseline, setDraft, readOnly: readOnly || !canSave, coreMeta, idBase: ids });
  const count = readOnly ? 0 : changed.size + host.count;
  const guard = useLeaveGuard(count > 0, count);

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
  /** Change the assigned apps; a main app that is no longer installed and shown in kiosk is cleared. */
  function updateApps(id: number, next: (assigned: ConfigApp[]) => ConfigApp[]) {
    setDraft((d) => {
      const assigned = (d.applications as ConfigApp[] | undefined) ?? [];
      const target = assigned.find((a) => a.id === id);
      const applications = next(assigned);
      const after = applications.find((a) => a.id === id);
      const stillMain = after != null && (after.action ?? 1) === 1 && after.showIcon !== false;
      const mainAppId = !stillMain && target?.usedVersionId != null && target.usedVersionId === d.mainAppId ? null : d.mainAppId;
      return { ...d, applications, mainAppId };
    });
  }
  const removeApp = (id: number) => updateApps(id, (list) => list.filter((a) => a.id !== id));
  const setAppAction = (id: number, action: number) =>
    updateApps(id, (list) => list.map((a) => (a.id === id ? { ...a, action } : a)));
  // A hidden app cannot remain the kiosk's pinned main app: updateApps clears the stale selection.
  const setAppKioskVisibility = (id: number, showIcon: boolean) =>
    updateApps(id, (list) => list.map((a) => (a.id === id ? { ...a, showIcon } : a)));

  function requestSave() {
    if (!String(draft.name ?? '').trim()) {
      setNameError('Give the configuration a name.');
      toast.push('err', 'Name required', 'Give the configuration a name.');
      nameRef.current?.focus();
      return;
    }
    if (!appsReady) {
      toast.push('err', 'Still loading', 'The assigned apps are still loading — try again in a moment.');
      return;
    }
    const invalid = host.validate();
    if (invalid) {
      toast.push('err', `Fix ${plural(invalid.problems, 'problem')} before saving`, `${invalid.title}: ${invalid.message}`);
      focusSection(invalid.anchor);
      return;
    }
    // deviceCount null = unknown -> confirm anyway (fail closed); 0 = no device will re-apply.
    if (kioskKeys.length > 0 && deviceCount !== 0) {
      setConfirmKeys(kioskKeys);
      return;
    }
    void doSave();
  }

  async function doSave() {
    setBusy(true);
    try {
      // The whole configuration goes back (the server replaces every column and list), with the name as validated.
      // Its `extensions` are the loaded ones, with the namespaces the sections changed on top (saveConfiguration).
      const name = String(draft.name ?? '').trim();
      await saveConfiguration({ ...draft, name, extensions: baseline.extensions }, host.changed);
      toast.push('ok', isNew ? 'Configuration created' : 'Configuration saved', name);
      const watch: ConfigWatch | undefined =
        initial.id != null ? { id: initial.id, until: Date.now() + WATCH_MS } : undefined;
      guard.exit(paths.configurations, watch ? { watch } : undefined);
    } catch (e) {
      // A plugin's validator (or core's own checks) refused the sections' data: say why, in the toast and the sections.
      const refused = extensionErrorsOf(e);
      if (refused) {
        host.showServerErrors(refused);
        toast.push('err', 'Save failed', refused.map((r) => r.message).join(' '));
        setBusy(false);
        return;
      }
      const msg = e instanceof Error ? e.message : '';
      const dup = /duplicate/i.test(msg);
      if (dup) setNameError('A configuration with that name exists.');
      toast.push('err', 'Save failed', dup ? 'A configuration with that name exists.' : msg);
      setBusy(false);
    }
  }

  function discard() {
    // Back to the loaded values; the leave guard then drops its extra history entry by itself.
    setDraft(baseline);
    setDiscards((n) => n + 1);
    setNameError(null);
    host.reset();
  }

  // ---- rows ----
  const pinWarn = defaultPin
    ? readOnly
      ? 'Devices on this template use a well-known default PIN, so anyone who knows it can leave kiosk mode. Duplicate it, set your own PIN, and move the devices to the copy.'
      : 'This is still a well-known default PIN. Anyone who knows it can leave kiosk mode. Choose a PIN only your staff know.'
    : noPin
      ? readOnly
        ? 'This template has no exit PIN: agents before 0.3.6 let anyone leave kiosk mode, 0.3.6 and later have no exit on the device. Duplicate it, set a PIN, and move the devices to the copy.'
        : 'There is no exit PIN: agents before 0.3.6 let anyone leave kiosk mode without a prompt, 0.3.6 and later have no exit on the device (end kiosk from the console). Set a PIN only your staff know.'
      : null;

  const row = (key: string, opts: { divider?: boolean } = {}) => {
    const def = FIELD[key];
    if (!def) return null;
    const off = def.kioskOnly === true && !kioskOn;
    const help = HELP[key] ?? def.help;
    // The PIN callout asks for a new PIN, which a disabled row (kiosk off) cannot take; the nav still flags it.
    const showPinWarn = key === 'password' && pinWarn != null && (readOnly || !off);
    return (
      <FieldRow
        key={key}
        id={`${ids}-${key}`}
        label={def.label}
        help={<LongHelp text={help} />}
        adornment={changed.has(key) && !readOnly ? <ChangedChip /> : undefined}
        disabled={off}
        divider={opts.divider}
        className={def.type === 'textarea' ? 'v2-cfged-row--wide' : undefined}
        below={
          showPinWarn ? (
            <Callout tone="warn" icon={<IconWarning size={16} />}>
              {pinWarn}
            </Callout>
          ) : undefined
        }
      >
        {({ labelId, helpId }) => (
          <FieldControl
            def={def}
            value={draft[key]}
            stored={baseline[key]}
            onChange={(v) => set(key, v)}
            disabled={readOnly}
            labelId={labelId}
            helpId={helpId}
            apps={apps}
            assigned={allowed}
            tone={showPinWarn ? 'warn' : undefined}
          />
        )}
      </FieldRow>
    );
  };

  const renderItems = (items: Item[]) =>
    items.map((it) => {
      if (typeof it === 'string') return row(it);
      const help = it.kiosk ? (kioskOn ? 'Only applies in kiosk mode' : 'Turn on kiosk mode to change these') : it.help;
      return (
        <div key={it.eyebrow} className="v2-cfged-eyebrow">
          <Eyebrow as="h3" help={help}>
            {it.eyebrow}
          </Eyebrow>
        </div>
      );
    });

  const section = (id: 'kiosk' | 'network' | 'security' | 'enrollment', title: string) => (
    <Card as="section" id={id} padding="rows" aria-labelledby={`${ids}-${id}-h`}>
      <CardHeader titleId={`${ids}-${id}-h`} title={title} subtitle={SECTION_SUB[id]} spacing={12} />
      {renderItems(LAYOUT[id])}
    </Card>
  );

  const legacyGroups = GROUP_ORDER.map((g) => ({ group: g, fields: LEGACY_FIELDS.filter((f) => f.group === g) })).filter(
    (x) => x.fields.length > 0,
  );

  // ---- header ----
  const savedName = isNew ? 'New configuration' : String(baseline.name ?? '');
  const usedBy = usedByLine(syncRow);
  let subtitle: ReactNode = null;
  if (isNew) subtitle = base ? `Based on “${base.name}”. Not saved yet.` : 'Not saved yet.';
  else if (usedBy && initial.id != null)
    subtitle = (
      <>
        Used by{' '}
        <Link to={`${paths.devices}?config=${initial.id}`} className="v2-cfged-devlink">
          {plural(usedBy.count, 'device')}
        </Link>
        {usedBy.rest}
      </>
    );
  else if (syncKnown) subtitle = 'Not used by any device yet.';

  // Duplicate = the server copy (PUT /configurations/copy), the same path as the list's Duplicate: it copies every
  // setting, the apps, configuration files and app settings of the saved configuration.
  const actions = isNew ? (
    <Button size="lg" variant="primary" onClick={requestSave} disabled={busy || !appsReady}>
      {busy ? 'Creating…' : 'Create configuration'}
    </Button>
  ) : !canCopy ? null : readOnly ? (
    <Button size="lg" variant="primary" icon={<IconCopy />} onClick={() => setCopying(true)}>
      Duplicate to edit
    </Button>
  ) : (
    <>
      <Button size="lg" icon={<IconCopy />} onClick={() => setCopying(true)}>
        Duplicate
      </Button>
      <Button size="lg" variant="danger" icon={<IconTrash />} onClick={() => setDeleting(true)}>
        Delete…
      </Button>
    </>
  );

  // ---- save bar sentence ----
  let message: ReactNode;
  if (appsError && !appsReady) message = 'Saving is off until the assigned apps load.';
  else if (!appsReady) message = 'Loading the assigned apps…';
  else if (kioskKeys.length > 0 && deviceCount != null && deviceCount > 0)
    message = `Kiosk settings change on ${plural(deviceCount, 'device')}. ${deviceCount === 1 ? 'It re-applies' : 'They re-apply'} kiosk at the next check-in.`;
  else if (kioskKeys.length > 0 && deviceCount == null)
    message = 'Kiosk settings change on the devices that use this configuration, at their next check-in.';
  else if (isNew) message = 'This configuration is not saved yet.';
  else if (syncRow && syncRow.total > 0) message = syncMessage(syncRow);
  else if (syncKnown) message = 'No device uses this configuration yet.';

  // ---- sections ----
  // Core's cards, which its contributions render among the extensions' sections (ext/core/config.tsx).
  const coreParts: Record<CoreConfigSection, ReactNode> = {
    identity: (
      <Card as="section" id="identity" aria-labelledby={`${ids}-identity-h`} gap={14}>
        <h2 id={`${ids}-identity-h`} className="v2-cfged-h2">
          Identity
        </h2>
        <div className="v2-cfged-identity">
          <Field
            label={
              <span className="v2-cfged-labelline">
                {FIELD.name.label}
                {changed.has('name') && !readOnly ? <ChangedChip /> : null}
              </span>
            }
            error={nameError}
          >
            <Input
              ref={nameRef}
              value={draft.name == null ? '' : String(draft.name)}
              disabled={readOnly}
              required
              onChange={(e) => {
                set('name', e.target.value);
                if (nameError) setNameError(null);
              }}
            />
          </Field>
          <Field
            label={
              <span className="v2-cfged-labelline">
                {FIELD.description.label}
                {changed.has('description') && !readOnly ? <ChangedChip /> : null}
              </span>
            }
          >
            <Textarea
              rows={2}
              value={draft.description == null ? '' : String(draft.description)}
              disabled={readOnly}
              onChange={(e) =>
                set('description', e.target.value === '' && baseline.description == null ? baseline.description : e.target.value)
              }
            />
          </Field>
        </div>
      </Card>
    ),
    apps: (
      <Card as="section" id="apps" padding="rows" aria-labelledby={`${ids}-apps-h`}>
        <CardHeader
          titleId={`${ids}-apps-h`}
          title="Apps"
          subtitle="What gets installed, and which apps the kiosk shows."
          spacing={12}
          actions={
            readOnly ? undefined : (
              <Button size="lg" icon={<IconPlus />} onClick={() => setPickerOpen(true)} disabled={!appsReady || !libraryReady}>
                Add apps
              </Button>
            )
          }
        />
        {library === 'error' ? (
          <div className="v2-cfged-libnote">
            <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
              <span className="v2-cfged-calloutline">
                Could not load the app library, so apps cannot be added and the main-app choices are missing.
                <Button size="sm" onClick={onRetryLibrary}>
                  Retry
                </Button>
              </span>
            </Callout>
          </div>
        ) : null}
        <FieldRow
          id={`${ids}-mainAppId`}
          label={FIELD.mainAppId.label}
          help={
            kioskOn
              ? `${FIELD.mainAppId.help} Only apps set to Install and shown in kiosk can be chosen.`
              : `${FIELD.mainAppId.help} Applies in kiosk mode only: turn kiosk mode on to choose one.`
          }
          adornment={changed.has('mainAppId') && !readOnly ? <ChangedChip /> : undefined}
          disabled={!kioskOn}
          className="v2-cfged-mainrow"
        >
          {({ labelId, helpId }) => (
            <AppSelect
              aria-labelledby={labelId}
              aria-describedby={helpId}
              value={draft.mainAppId}
              apps={mainAppChoices}
              assigned={allowed}
              disabled={readOnly}
              onChange={(v) => set('mainAppId', v)}
            />
          )}
        </FieldRow>
        <AssignedApps
          apps={allowed}
          ready={appsReady}
          error={appsError}
          readOnly={readOnly}
          kioskColumn={kioskOn}
          onAction={setAppAction}
          onKioskVisibility={setAppKioskVisibility}
          onRemove={removeApp}
          resetKey={discards}
        />
        <p className="v2-cfged-apps__note">
          “Do not install” keeps an app listed without installing it. “Remove” takes the app out of this configuration;
          it is not uninstalled from devices yet.
          {kioskOn ? ' “Show in kiosk” controls whether an installed app appears in the kiosk launcher.' : ''}
        </p>
      </Card>
    ),
    kiosk: section('kiosk', 'Kiosk'),
    network: section('network', 'Network'),
    security: section('security', 'Security and restrictions'),
    enrollment: section('enrollment', 'Enrollment'),
    headwind: (
      <Card as="section" id="headwind" tone="dashed" padding="none" className="v2-cfged-hw" aria-labelledby={`${ids}-hw-h`}>
        <div className="v2-cfged-hw__head">
          <div className="v2-cfged-hw__text">
            <h2 id={`${ids}-hw-h`} className="v2-cfged-h2">
              Headwind launcher only <span className="v2-cfged-hw__count">{LEGACY_FIELDS.length}</span>
            </h2>
            <span className="v2-cfged-hw__sub">Older settings the MDMesh agent does not apply. {hwChanged ? `${hwChanged} changed.` : ''}</span>
          </div>
          <Button
            size="lg"
            aria-expanded={advanced}
            aria-controls={`${ids}-hw-body`}
            iconEnd={<IconChevronDown className={advanced ? 'v2-cfged-chev is-open' : 'v2-cfged-chev'} />}
            onClick={() => setAdvanced((v) => !v)}
          >
            {advanced ? 'Hide settings' : 'Show settings'}
          </Button>
        </div>
        {advanced ? (
          <div id={`${ids}-hw-body`} className="v2-cfged-hw__body">
            <p className="v2-cfged-hw__note">{LEGACY_NOTE}</p>
            {legacyGroups.map(({ group, fields }) => (
              <div key={group} className="v2-cfged-hw__group">
                <div className="v2-cfged-eyebrow">
                  <Eyebrow as="h3">{group}</Eyebrow>
                </div>
                {fields.map((f) => row(f.key))}
              </div>
            ))}
          </div>
        ) : null}
      </Card>
    ),
  };

  return (
    <>
      <PageHeader
        title={savedName}
        docTitle={savedName}
        breadcrumb={crumbs(savedName)}
        titleAdornment={
          readOnly || baseline.kioskMode === true ? (
            <span className="v2-cfged-tags">
              {readOnly ? (
                <Badge tone="neutral" size="md" icon={<IconLockSmall />}>
                  Built-in
                </Badge>
              ) : null}
              {baseline.kioskMode === true ? <Badge size="md">Kiosk</Badge> : null}
            </span>
          ) : undefined
        }
        subtitle={subtitle}
        actions={actions}
        actionsGap={8}
      />

      {readOnly ? (
        <Callout tone="info" icon={<IconInfo size={16} />}>
          This is a built-in template, so it is view only. Use <b>Duplicate to edit</b> to make your own editable copy.
        </Callout>
      ) : null}

      {appsError && !appsReady ? (
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          <span className="v2-cfged-calloutline">
            Could not load this configuration’s assigned apps. Saving is disabled so the app list (and kiosk
            allowlist) is not wiped.
            <Button size="sm" onClick={() => setAppsAttempt((n) => n + 1)}>
              Retry
            </Button>
          </span>
        </Callout>
      ) : null}

      <SectionLayout nav={<SectionNav aria-label="Sections" items={host.nav} />}>
        <CoreConfigProvider parts={coreParts}>{host.sections}</CoreConfigProvider>

        {readOnly ? null : (
          <StickySaveBar
            count={count}
            message={message}
            onDiscard={discard}
            onSave={requestSave}
            saveLabel={isNew ? 'Create configuration' : 'Save and apply'}
            busy={busy}
            busyLabel={isNew ? 'Creating…' : 'Saving…'}
            saveDisabled={!appsReady}
          />
        )}
      </SectionLayout>

      <AppPickerModal
        open={pickerOpen}
        apps={apps}
        excludeIds={allowedIds}
        onAdd={addApps}
        onClose={() => setPickerOpen(false)}
      />
      <KioskConfirmModal
        keys={confirmKeys}
        count={deviceCount}
        onCancel={() => setConfirmKeys(null)}
        onConfirm={() => {
          setConfirmKeys(null);
          void doSave();
        }}
      />
      <CopyConfigModal
        source={copying ? initial : null}
        note={count > 0 ? 'Unsaved changes on this page are not copied.' : undefined}
        onClose={() => setCopying(false)}
        onDone={(_name, copyId) => {
          setCopying(false);
          // Open the copy; unsaved edits here still get the leave prompt.
          guard.navigate(copyId != null ? paths.configuration(copyId) : paths.configurations);
        }}
      />
      <DeleteConfigModal
        target={deleting ? initial : null}
        deviceCount={syncRow?.total}
        onClose={() => setDeleting(false)}
        onDeleted={() => {
          setDeleting(false);
          guard.exit(paths.configurations);
        }}
      />
      {guard.dialog}
    </>
  );
}
