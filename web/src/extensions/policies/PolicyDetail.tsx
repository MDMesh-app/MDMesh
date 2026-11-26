// The selected policy, beside the policy list: its feature tabs (Overview, Device control, Allowed apps, Groups,
// Phones) and the save bar. Device controls, the kiosk switch and the allowed apps are the configuration's own columns
// and app list, so the configuration editor shows the same values and phones get them through config.apply; the host
// phone, the extra-app rules and the groups are this extension's data.
import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Checkbox,
  Field,
  IconConfigurations,
  IconCopy,
  IconSync,
  Input,
  Menu,
  Modal,
  useNavigate,
  usePermission,
  Callout,
  Card,
  CardHeader,
  ChangedChip,
  EmptyState,
  FieldRow,
  IconApps,
  IconClock,
  IconDevices,
  IconLock,
  IconLockSmall,
  IconOverview,
  IconSliders,
  IconWarning,
  KeyValueList,
  Segmented,
  Select,
  Tabs,
  TabPanel,
  TriState,
  paths,
  useToast,
} from '@mdmesh/console';
import { AllowedAppsSection, sameList, type AllowList } from './AllowedAppsSection';
import {
  copyConfig,
  createPackageApp,
  isBuiltIn,
  listLibrary,
  loadConfig,
  loadedApps,
  saveConfig,
  type LoadedConfig,
} from './api';
import { runBulk } from './bulk';
import { StatusView } from './StatusView';
import type { Fleet, FleetDevice } from './fleet';
import { PolicyGroupsCard } from './PolicyGroups';
import { SchedulesTab } from './SchedulesTab';
import {
  CONNECTIVITY_MODES,
  CONTROLS,
  controlValue,
  isAllowedRow,
  libraryRow,
  planAllowList,
  LOCATION_MODES,
  matchingPreset,
  NAMESPACE,
  parsePolicy,
  PRESETS,
  protectionSummary,
  samePolicy,
  type AllowPlan,
  type ControlDef,
  type PolicyData,
  type Preset,
} from './model';
import { APP_MODE_LABEL, errorText, plural } from './shared';

const ALLOW = [
  { value: null, label: 'Not managed' },
  { value: true, label: 'Allow' },
  { value: false, label: 'Block' },
];
const DISABLE = [
  { value: null, label: 'Not managed' },
  { value: false, label: 'Allow' },
  { value: true, label: 'Block' },
];
const ONOFF = [
  { value: null, label: 'Not managed' },
  { value: true, label: 'On' },
  { value: false, label: 'Off' },
];
const SEGMENTS = { allow: ALLOW, disable: DISABLE, onoff: ONOFF } as const;

/** The columns this page edits: every control, location and connectivity, and the kiosk switch and exit PIN. */
const COLUMNS = [...CONTROLS.map((c) => c.key), 'requestUpdates', 'connectivityMode', 'kioskMode', 'password'];

/** The configuration's allowed apps, package → name. */
function allowListOf(loaded: LoadedConfig): Map<string, string> {
  const m = new Map<string, string>();
  for (const a of loadedApps(loaded)) if (isAllowedRow(a) && !m.has(a.pkg!.trim())) m.set(a.pkg!.trim(), a.name || a.pkg!.trim());
  return m;
}

/** What a save does to the configuration's apps, worked out before asking. */
interface Pending {
  plan: AllowPlan | null;
  scanAfter: boolean;
}

export type PolicyTab = 'overview' | 'device' | 'apps' | 'schedules' | 'groups' | 'phones';
export const POLICY_TABS: readonly PolicyTab[] = ['overview', 'device', 'apps', 'schedules', 'groups', 'phones'];
const ID_BASE = 'x-policies-policy';

type Load = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; loaded: LoadedConfig };

export function PolicyDetail({
  id,
  fleet,
  tab,
  onTab,
  onDirty,
  onManageGroups,
  onActions,
}: {
  id: number;
  fleet: Fleet;
  tab: PolicyTab;
  onTab: (t: PolicyTab) => void;
  /** Whether there are unsaved changes, so the page can ask before switching policy. */
  onDirty: (dirty: boolean) => void;
  onManageGroups: () => void;
  /** Open Client actions for these phones. */
  onActions: (phones: FleetDevice[], scope: string) => void;
}) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let on = true;
    setLoad({ kind: 'loading' });
    loadConfig(id)
      .then((loaded) => on && setLoad({ kind: 'ready', loaded }))
      .catch((e: unknown) => on && setLoad({ kind: 'error', message: errorText(e, 'Could not load the policy.') }));
    return () => {
      on = false;
    };
  }, [id, attempt]);

  if (load.kind === 'loading') {
    return (
      <Card>
        <EmptyState icon={<IconLock />} title="Loading the policy…" />
      </Card>
    );
  }
  if (load.kind === 'error') {
    return (
      <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
        <span className="x-policies-inline">
          {load.message}
          <Button size="sm" onClick={() => setAttempt((n) => n + 1)}>
            Retry
          </Button>
        </span>
      </Callout>
    );
  }
  return (
    <Editor
      key={`${id}:${attempt}`}
      loaded={load.loaded}
      fleet={fleet}
      tab={tab}
      onTab={onTab}
      onDirty={onDirty}
      onManageGroups={onManageGroups}
      onActions={onActions}
      onSaved={() => {
        setAttempt((n) => n + 1);
        void fleet.reloadPolicies();
      }}
    />
  );
}

function pick(config: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(COLUMNS.map((k) => [k, config[k] ?? null]));
}

function Editor({
  loaded,
  fleet,
  tab,
  onTab,
  onDirty,
  onManageGroups,
  onActions,
  onSaved,
}: {
  loaded: LoadedConfig;
  fleet: Fleet;
  tab: PolicyTab;
  onTab: (t: PolicyTab) => void;
  onDirty: (dirty: boolean) => void;
  onManageGroups: () => void;
  onActions: (phones: FleetDevice[], scope: string) => void;
  onSaved: () => void;
}) {
  const toast = useToast();
  const config = loaded.config;
  const id = config.id ?? 0;
  const readOnly = isBuiltIn({ id: config.id, name: config.name });
  const storedColumns = useMemo(() => pick(config), [config]);
  const storedPolicy = useMemo(() => parsePolicy(config.extensions?.[NAMESPACE]), [config]);
  const [columns, setColumns] = useState<Record<string, unknown>>(storedColumns);
  const [policy, setPolicy] = useState<PolicyData>(storedPolicy);
  const storedAllow = useMemo(() => allowListOf(loaded), [loaded]);
  const [allow, setAllow] = useState<AllowList>(storedAllow);
  const [busy, setBusy] = useState(false);
  const [copying, setCopying] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const canAct = usePermission({ permission: 'edit_devices' });
  const canCopy = usePermission({ permission: 'copy_config' });
  const canLibrary = usePermission({ permission: 'edit_applications' });

  const changedColumns = COLUMNS.filter((k) => (columns[k] ?? null) !== (storedColumns[k] ?? null));
  const policyChanged = !samePolicy(policy, storedPolicy);
  const allowChanged = !sameList(allow, storedAllow);
  const changes = changedColumns.length + (policyChanged ? 1 : 0) + (allowChanged ? 1 : 0);
  const kioskOn = columns.kioskMode === true;
  const pin = typeof columns.password === 'string' ? columns.password : '';
  const kioskTurnsOn = kioskOn && storedColumns.kioskMode !== true;
  /** The save changes what phones may open: ask first, with what changes. */
  const touchesKiosk = allowChanged || changedColumns.includes('kioskMode') || (kioskOn && changedColumns.includes('password'));
  const preset = matchingPreset(columns);
  const prot = protectionSummary(columns);
  const phones = (fleet.devices ?? []).filter((d) => d.device.configurationId === id);
  const compliant = phones.filter((d) => d.compliance.verdict === 'compliant').length;
  const found = phones.reduce((n, d) => n + (d.violations?.length ?? 0), 0);

  useEffect(() => onDirty(changes > 0), [changes, onDirty]);
  useEffect(() => () => onDirty(false), [onDirty]);

  // Closing or reloading the tab with unsaved changes asks first.
  useEffect(() => {
    if (!changes) return;
    const onBefore = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', onBefore);
    return () => window.removeEventListener('beforeunload', onBefore);
  }, [changes]);

  const set = (key: string, value: unknown) => setColumns((c) => ({ ...c, [key]: value }));
  const applyPreset = (p: Preset) =>
    setColumns((c) => {
      const next = { ...c };
      for (const def of CONTROLS) next[def.key] = controlValue(def, PRESETS[p].states[def.key]);
      return next;
    });
  const changed = (key: string) => ((columns[key] ?? null) !== (storedColumns[key] ?? null) ? <ChangedChip /> : undefined);

  const navigate = useNavigate();
  async function reapplyAll() {
    try {
      const text = await runBulk(fleet, phones, { kind: 'reapply' }, `Reapply policy · ${config.name}`);
      toast.push('ok', 'Policy reapplied', text);
      fleet.refreshDevices(phones.map((d) => d.device.id));
    } catch (e) {
      toast.push('err', 'Could not reapply the policy', errorText(e, 'Try again.'));
    }
  }

  /** Work out the app changes, then save at once or ask first. */
  async function requestSave() {
    if (kioskTurnsOn && !pin) {
      toast.push('err', 'Set an exit PIN first', 'Kiosk can’t be turned on from here without an exit PIN.');
      onTab('apps');
      return;
    }
    setBusy(true);
    try {
      const plan = allowChanged
        ? planAllowList(loadedApps(loaded), new Set(allow.keys()), await listLibrary(), (config.mainAppId as number | null | undefined) ?? null)
        : null;
      if (plan?.missing.length && !canLibrary) {
        throw new Error(`${plural(plan.missing.length, 'app')} must be added to the Library first, and your role can’t add Library apps.`);
      }
      if (touchesKiosk && phones.length) {
        setPending({ plan, scanAfter: kioskOn });
        return;
      }
      await save({ plan, scanAfter: false });
    } catch (e) {
      toast.push('err', 'Could not save the policy', errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  async function save({ plan, scanAfter }: Pending) {
    const cols: Record<string, unknown> = Object.fromEntries(changedColumns.map((k) => [k, columns[k] ?? null]));
    if (plan) {
      const rows = [...plan.rows];
      // Apps the Library doesn't know yet get a package-only entry (no APK: nothing is installed for them).
      for (const pkg of plan.missing) rows.push(libraryRow(await createPackageApp(pkg, allow.get(pkg) ?? pkg)));
      cols.applications = rows;
      if (plan.mainAppId !== ((config.mainAppId as number | null | undefined) ?? null)) cols.mainAppId = plan.mainAppId;
    }
    await saveConfig(loaded, cols, policyChanged ? policy : undefined);
    let scanned = '';
    if (scanAfter && phones.length) {
      const r = await runBulk(fleet, phones, { kind: 'scan' }, `Scan apps · ${config.name}`).catch(() => null);
      if (r) scanned = ` App scan: ${r}`;
    }
    toast.push('ok', 'Policy saved', `${config.name}: phones apply it at their next sync.${scanned}`);
    onSaved();
  }

  async function confirmSave() {
    if (!pending) return;
    setBusy(true);
    try {
      await save(pending);
      setPending(null);
    } catch (e) {
      toast.push('err', 'Could not save the policy', errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  const control = (def: ControlDef) => (
    <FieldRow key={def.key} label={def.label} help={def.help} adornment={changed(def.key)} divider>
      {({ labelId }) => (
        <TriState
          aria-labelledby={labelId}
          segments={SEGMENTS[def.kind]}
          value={columns[def.key] as boolean | null}
          disabled={readOnly}
          onChange={(v) => set(def.key, v)}
        />
      )}
    </FieldRow>
  );

  const myGroups = fleet.groups.filter((g) => g.policyId === id);

  return (
    <div className="x-policies-main">
      <div className="x-policies-head">
        <div className="x-policies-head__name">
          <h2 className="x-policies-head__h">{config.name}</h2>
          {readOnly ? (
            <Badge tone="neutral" icon={<IconLockSmall />}>
              Built-in
            </Badge>
          ) : (
            <Badge tone="outline">{preset ? PRESETS[preset].label : prot.managed ? 'Custom' : 'No rules'}</Badge>
          )}
        </div>
        <span className="x-policies-inline">
          {canAct ? (
            <Button size="sm" icon={<IconSliders />} disabled={!phones.length} onClick={() => onActions(phones, config.name)}>
              Client actions
            </Button>
          ) : null}
          <Menu
            label={`More for ${config.name}`}
            iconSize="sm"
            items={[
              ...(canAct
                ? [
                    {
                      key: 'reapply',
                      label: 'Reapply to its phones',
                      icon: <IconSync />,
                      disabled: !phones.length,
                      onSelect: () => void reapplyAll(),
                    },
                  ]
                : []),
              ...(canCopy ? [{ key: 'copy', label: 'Duplicate policy…', textValue: 'Duplicate policy', icon: <IconCopy />, onSelect: () => setCopying(true) }] : []),
              { key: 'config', label: 'Open configuration', icon: <IconConfigurations />, onSelect: () => navigate(paths.configuration(id)) },
            ]}
          />
        </span>
      </div>

      <Tabs<PolicyTab>
        aria-label="Policy settings"
        idBase={ID_BASE}
        value={tab}
        onChange={(k) => onTab(k)}
        items={[
          { key: 'overview', label: 'Overview', icon: <IconOverview size={14} /> },
          { key: 'device', label: 'Device control', icon: <IconSliders size={14} />, count: prot.managed || undefined },
          { key: 'apps', label: 'Allowed apps', icon: <IconApps size={14} />, count: allow.size || undefined },
          { key: 'schedules', label: 'Schedules', icon: <IconClock size={14} /> },
          { key: 'groups', label: 'Groups', icon: <IconDevices size={14} />, count: myGroups.length || undefined },
          { key: 'phones', label: 'Phones', icon: <IconDevices size={14} />, count: phones.length || undefined },
        ]}
      />

      <TabPanel idBase={ID_BASE} tabKey={tab} className="x-policies-panel">
        {readOnly ? (
          <Callout tone="info" icon={<IconLock size={16} />}>
            This configuration ships with MDMesh and can&rsquo;t be changed. Duplicate it in Configurations to make a
            policy of your own.
          </Callout>
        ) : null}

        {tab === 'overview' ? (
          <>
            <Card>
              <CardHeader
                title="Security level"
                subtitle="Sets every device control at once. Change any control afterwards and the level shows as Custom."
                level={3}
              />
              <Segmented<Preset | 'custom'>
                aria-label="Security level"
                className="x-policies-fit"
                size="lg"
                disabled={readOnly}
                value={preset ?? 'custom'}
                onChange={(v) => v !== 'custom' && applyPreset(v)}
                options={[
                  { value: 'strict', label: 'Strict' },
                  { value: 'balanced', label: 'Balanced' },
                  { value: 'open', label: 'Open' },
                  { value: 'custom', label: 'Custom', disabled: true },
                ]}
              />
              <p className="x-policies-help is-below">{preset ? PRESETS[preset].help : 'Your own mix of device controls.'}</p>
            </Card>
            <Card>
              <CardHeader title="Summary" level={3} />
              <KeyValueList
                variant="rows"
                items={[
                  { key: 'device', label: 'Device control', value: prot.managed ? `${prot.blocked} blocked · ${prot.managed} of ${prot.total} managed` : 'Nothing managed' },
                  { key: 'kiosk', label: 'Kiosk screen', value: kioskOn ? 'On: only allowed apps open' : 'Off: every app opens' },
                  { key: 'allowed', label: 'Allowed apps', value: allow.size, mono: true },
                  {
                    key: 'host',
                    label: 'Host phone',
                    value: policy.host ? ((fleet.devices ?? []).find((d) => d.device.number === policy.host)?.device.description || policy.host) : 'None',
                  },
                  { key: 'apps', label: 'Extra apps', value: APP_MODE_LABEL[policy.apps.mode] },
                  { key: 'exceptions', label: 'Exceptions', value: policy.apps.exceptions.length, mono: true },
                  { key: 'groups', label: 'Groups', value: myGroups.length ? myGroups.map((g) => g.name).join(', ') : 'None' },
                  { key: 'phones', label: 'Phones', value: phones.length ? `${phones.length} · ${compliant} compliant` : 'None' },
                  { key: 'found', label: 'Extra apps found', value: found, mono: true },
                ]}
              />
            </Card>
          </>
        ) : null}

        {tab === 'device' ? (
          <>
            <Card>
              <CardHeader title="Device" subtitle="Hardware and data paths. Not managed leaves each phone as it is, including a block set earlier." level={3} />
              {CONTROLS.filter((c) => c.section === 'device').map(control)}
            </Card>
            <Card>
              <CardHeader title="Network" level={3} />
              {CONTROLS.filter((c) => c.section === 'network').map(control)}
            </Card>
            <Card>
              <CardHeader title="System protection" subtitle="Keep the phone under management." level={3} />
              {CONTROLS.filter((c) => c.section === 'system').map(control)}
            </Card>
            <Card>
              <CardHeader title="Location and connectivity" level={3} />
              <FieldRow
                label="Location reporting"
                help="What the phone reports at each check-in. A fresh GPS fix is more accurate and uses more battery."
                adornment={changed('requestUpdates')}
                divider
              >
                {({ labelId }) => (
                  <Segmented<string>
                    aria-labelledby={labelId}
                    disabled={readOnly}
                    value={typeof columns.requestUpdates === 'string' ? columns.requestUpdates : 'DONOTTRACK'}
                    onChange={(v) => set('requestUpdates', v)}
                    options={LOCATION_MODES.map((m) => ({ value: m.value, label: m.label }))}
                  />
                )}
              </FieldRow>
              <FieldRow
                label="Live connection"
                help="Always-on lets Lock, Ring and Wipe reach a locked phone at once, at a battery cost. Needs agent 0.4.0."
                adornment={changed('connectivityMode')}
                divider
              >
                {({ labelId }) => (
                  <Select
                    aria-labelledby={labelId}
                    disabled={readOnly}
                    value={typeof columns.connectivityMode === 'string' ? columns.connectivityMode : ''}
                    onChange={(e) => set('connectivityMode', e.target.value || null)}
                  >
                    {CONNECTIVITY_MODES.map((m) => (
                      <option key={m.value} value={m.value}>
                        {m.label}
                      </option>
                    ))}
                  </Select>
                )}
              </FieldRow>
            </Card>
          </>
        ) : null}

        {tab === 'apps' ? (
          <AllowedAppsSection
            readOnly={readOnly}
            canAct={canAct}
            phones={phones}
            hostChoices={phones.length ? phones : (fleet.devices ?? [])}
            host={policy.host}
            storedHost={storedPolicy.host}
            onHost={(host) =>
              setPolicy((p) => {
                const { host: _old, ...rest } = p;
                return host ? { ...rest, host } : rest;
              })
            }
            hostSeen={policy.hostSeen}
            onHostSeen={(pkgs) => setPolicy((p) => ({ ...p, hostSeen: [...new Set(pkgs)].sort() }))}
            allow={allow}
            storedAllow={storedAllow}
            onAllow={setAllow}
            kiosk={kioskOn}
            storedKiosk={storedColumns.kioskMode === true}
            onKiosk={(on) => set('kioskMode', on)}
            pin={pin}
            storedPin={typeof storedColumns.password === 'string' ? storedColumns.password : ''}
            onPin={(v) => set('password', v)}
            control={policy.apps}
            storedControl={storedPolicy.apps}
            onControl={(apps) => setPolicy((p) => ({ ...p, apps }))}
          />
        ) : null}

        {tab === 'schedules' ? (
          <SchedulesTab
            policyId={id}
            allowed={storedAllow}
            phones={phones}
            groups={fleet.groups}
            readOnly={readOnly}
            canEdit={canAct}
            available={fleet.source === 'plugin'}
          />
        ) : null}

        {tab === 'groups' ? (
          <PolicyGroupsCard fleet={fleet} policyId={id} policyName={config.name} readOnly={readOnly} onManageGroups={onManageGroups} />
        ) : null}

        {tab === 'phones' ? <StatusView fleet={fleet} policyId={id} /> : null}
      </TabPanel>

      {pending ? (
        <ApplyModal
          name={config.name}
          phones={phones.length}
          kiosk={kioskOn}
          kioskChanged={changedColumns.includes('kioskMode')}
          allowed={allow.size}
          pending={pending}
          busy={busy}
          onScan={(scanAfter) => setPending({ ...pending, scanAfter })}
          onClose={() => setPending(null)}
          onConfirm={() => void confirmSave()}
        />
      ) : null}

      {copying ? (
        <DuplicateModal
          name={config.name}
          onClose={() => setCopying(false)}
          onDone={(name) => {
            setCopying(false);
            toast.push('ok', 'Policy duplicated', `${name} is in the policy list. Its groups stay with ${config.name}.`);
            void fleet.reloadPolicies();
          }}
          copy={(name) => copyConfig(id, name, config.description)}
        />
      ) : null}

      {!readOnly && changes > 0 ? (
        <div className="x-policies-savebar" role="region" aria-label="Unsaved changes">
          <span className="x-policies-savebar__count">
            <span className="x-policies-savebar__dot" aria-hidden="true" />
            {plural(changes, 'change')}
          </span>
          <span className="x-policies-savebar__msg">
            {changedColumns.length === 0 && !allowChanged
              ? 'The host phone and extra-app rules are console settings: nothing is sent to the phones.'
              : phones.length
                ? `${plural(phones.length, 'phone')} apply the policy at their next sync.`
                : 'No phone uses this policy yet.'}
          </span>
          <span className="x-policies-savebar__actions">
            <Button
              disabled={busy}
              onClick={() => {
                setColumns(storedColumns);
                setPolicy(storedPolicy);
                setAllow(storedAllow);
              }}
            >
              Discard
            </Button>
            <Button variant="primary" disabled={busy} onClick={() => void requestSave()}>
              {busy ? 'Saving…' : 'Save and apply'}
            </Button>
          </span>
        </div>
      ) : null}
    </div>
  );
}

/** Asks before a save that changes what the policy's phones may open, saying what changes. */
function ApplyModal({
  name,
  phones,
  kiosk,
  kioskChanged,
  allowed,
  pending,
  busy,
  onScan,
  onClose,
  onConfirm,
}: {
  name: string;
  phones: number;
  kiosk: boolean;
  kioskChanged: boolean;
  allowed: number;
  pending: Pending;
  busy: boolean;
  onScan: (on: boolean) => void;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const plan = pending.plan;
  const items = [
    kiosk
      ? { key: 'screen', label: 'Kiosk screen', value: kioskChanged ? 'Turns on' : 'On' }
      : { key: 'screen', label: 'Kiosk screen', value: kioskChanged ? 'Turns off: every app opens again' : 'Off: the list is kept, not enforced' },
    { key: 'allowed', label: 'Open on the kiosk screen', value: plural(allowed, 'allowed app') },
    ...(kiosk ? [{ key: 'blocked', label: 'Blocked from opening', value: 'Every other app, including apps the host doesn’t have' }] : []),
    ...(plan?.missing.length
      ? [{ key: 'library', label: 'Added to the Library', value: `${plural(plan.missing.length, 'package-only entry', 'package-only entries')} (nothing to install)` }]
      : []),
    ...(plan?.installs.length ? [{ key: 'installs', label: 'Installed where missing', value: plan.installs.join(', ') }] : []),
  ];
  return (
    <Modal
      open
      onClose={busy ? () => undefined : onClose}
      size="md"
      title={`Apply ${name} to ${plural(phones, 'phone')}?`}
      description="Phones apply it at their next sync (at once on an Always-on connection). Nothing is uninstalled."
      footer={
        <>
          <Button disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={busy} onClick={onConfirm}>
            {busy ? 'Saving…' : 'Save and apply'}
          </Button>
        </>
      }
    >
      <div className="x-policies-stack">
        <KeyValueList variant="rows" items={items} />
        {kiosk ? (
          <Checkbox
            checked={pending.scanAfter}
            onChange={(e) => onScan(e.target.checked)}
            label="Scan the phones after saving, to find apps outside the list"
          />
        ) : null}
        {kiosk && kioskChanged ? (
          <p className="x-policies-help">
            Try it on one phone first: put only the host on this policy, check it, then move the group&rsquo;s phones.
          </p>
        ) : null}
      </div>
    </Modal>
  );
}

function DuplicateModal({ name, onClose, onDone, copy }: { name: string; onClose: () => void; onDone: (name: string) => void; copy: (name: string) => Promise<unknown> }) {
  const [value, setValue] = useState(`${name} (copy)`);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title={`Duplicate ${name}`}
      description="The copy gets every setting and app of this policy, and no phones."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || !value.trim()}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await copy(value.trim());
                onDone(value.trim());
              } catch (e) {
                setError(errorText(e, 'Could not duplicate the policy.'));
              } finally {
                setBusy(false);
              }
            }}
          >
            Duplicate
          </Button>
        </>
      }
    >
      <Field label="Name" error={error ?? undefined}>
        <Input value={value} onChange={(e) => setValue(e.target.value)} maxLength={100} autoFocus />
      </Field>
    </Modal>
  );
}
