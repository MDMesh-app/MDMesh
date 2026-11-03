import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { getConfigurationApps, getConfigurations, type ConfigApp, type Configuration } from '../api/configurations';
import { listApplications, type Application } from '../api/applications';
import { getSyncSummary, type ConfigSyncSummary } from '../api/configSync';
import { searchDevices } from '../api/devices';
import { appNameForVersionId, hasDefaultPin, hasNoPin, isLocked } from '../data/configurationRules';
import {
  Badge,
  Button,
  ButtonLink,
  Callout,
  Card,
  EmptyState,
  IconConfigurations,
  IconCopy,
  IconLockSmall,
  IconPencil,
  IconPlus,
  IconSearch,
  IconTrash,
  IconWarning,
  Menu,
  MetaGrid,
  PageHeader,
  SearchInput,
  Segmented,
  StackedBar,
  type MenuEntry,
} from '../ui';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { usePermission } from '../ext/permissions';
import { paths } from '../routes';
import { CopyConfigModal, DeleteConfigModal, NewConfigChooser } from './configurations/modals';
import { syncLine } from './configurations/sync';
import type { ConfigWatch } from './configurations/watch';
import './ConfigurationsPage.css';

type Show = 'all' | 'yours' | 'builtin';

const POLL_MS = 10_000;
/** Concurrent /configurations/applications/{id} requests: each answer carries the whole app library. */
const APPS_IN_FLIGHT = 4;

/** Configurations list. Classic: pages/ConfigurationsPage.tsx. */
export function ConfigurationsPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [configs, setConfigs] = useState<Configuration[] | null>(null);
  const [error, setError] = useState(false);
  const [apps, setApps] = useState<Application[]>([]);
  const [libraryFailed, setLibraryFailed] = useState(false);
  const [sync, setSync] = useState<Record<number, ConfigSyncSummary>>({});
  const [syncFailed, setSyncFailed] = useState(false);
  const [syncLoaded, setSyncLoaded] = useState(false);
  const [assigned, setAssigned] = useState<Record<number, ConfigApp[] | null>>({});
  const [unassigned, setUnassigned] = useState<number | null>(null);
  const [q, setQ] = useState('');
  const [show, setShow] = useState<Show>('all');
  const [chooserOpen, setChooserOpen] = useState(false);
  const [copyOf, setCopyOf] = useState<Configuration | null>(null);
  const [deleting, setDeleting] = useState<Configuration | null>(null);
  // Creating needs add_config; duplicating and deleting copy_config (ext/core/access.ts). Opening one needs neither.
  const canAdd = usePermission(CORE_ACTION_ACCESS.addConfig);
  const canCopy = usePermission(CORE_ACTION_ACCESS.copyConfig);
  // Set by the editor after a save (location state), so the list follows that configuration's devices catching up.
  const [watch, setWatch] = useState<ConfigWatch | null>(() => {
    const w = (location.state as { watch?: ConfigWatch } | null)?.watch;
    return w && Date.now() < w.until ? w : null;
  });

  const applySync = (rows: ConfigSyncSummary[]) => setSync(Object.fromEntries(rows.map((r) => [r.configurationId, r])));

  // The assigned apps already fetched, by configuration id (kept across a reload after a delete).
  const assignedRef = useRef<Record<number, ConfigApp[] | null>>({});
  const loadGen = useRef(0);

  /** Loads the list. `refetchApps` false keeps the assigned apps already fetched (after a delete nothing changed). */
  const load = useCallback(async (refetchApps = true) => {
    const gen = ++loadGen.current;
    setError(false);
    let list: Configuration[];
    try {
      list = await getConfigurations();
    } catch {
      if (gen !== loadGen.current) return;
      setConfigs((prev) => prev ?? []);
      setError(true);
      return;
    }
    if (gen !== loadGen.current) return;
    setConfigs(list);
    getSyncSummary()
      .then((rows) => {
        if (gen !== loadGen.current) return;
        applySync(rows);
        setSyncFailed(false);
      })
      .catch(() => {
        if (gen === loadGen.current) setSyncFailed(true);
      })
      .finally(() => {
        if (gen === loadGen.current) setSyncLoaded(true);
      });
    // The list endpoint omits each configuration's apps: fetch them for the Apps count and the main app's version,
    // a few at a time. A newer load (Retry, delete) stops this one's queue.
    const ids = list.map((c) => c.id).filter((id): id is number => id != null);
    const kept: Record<number, ConfigApp[] | null> = {};
    if (!refetchApps) for (const id of ids) if (assignedRef.current[id]) kept[id] = assignedRef.current[id];
    assignedRef.current = kept;
    setAssigned(kept);
    const todo = ids.filter((id) => !(id in kept));
    const put = (id: number, a: ConfigApp[] | null) => {
      if (gen !== loadGen.current) return;
      assignedRef.current = { ...assignedRef.current, [id]: a };
      setAssigned(assignedRef.current);
    };
    let next = 0;
    const worker = async () => {
      while (gen === loadGen.current && next < todo.length) {
        const id = todo[next++];
        await getConfigurationApps(id).then(
          (a) => put(id, a),
          () => put(id, null),
        );
      }
    };
    await Promise.all(Array.from({ length: Math.min(APPS_IN_FLIGHT, todo.length) }, worker));
  }, []);

  const loadLibrary = useCallback(() => {
    setLibraryFailed(false);
    listApplications()
      .then((a) => setApps(a.filter((x) => (x.type ?? 'app') !== 'web')))
      .catch(() => setLibraryFailed(true));
  }, []);

  useEffect(() => {
    void load();
    loadLibrary();
    // Same limit as the Devices page (it loads up to 1000 and pages in the browser).
    searchDevices({ pageSize: 1000 })
      .then((r) => setUnassigned((r.devices?.items ?? []).filter((d) => d.configurationId == null).length))
      .catch(() => setUnassigned(null));
    // Leaving the page stops the app-list queue and drops answers still on their way.
    const gens = loadGen;
    return () => {
      gens.current++;
    };
  }, [load, loadLibrary]);

  // A save returns before devices have applied the new desired state, so poll the saved configuration's sync
  // summary for a short window: stop once its devices catch up or after two minutes (powered-off devices stay out
  // of sync). Self-scheduling: the next tick is armed only after the current one finishes; stops on leave.
  useEffect(() => {
    if (!watch) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const poll = async () => {
      const rows = await getSyncSummary().catch(() => null);
      if (!on) return;
      if (rows) applySync(rows);
      const row = rows?.find((r) => r.configurationId === watch.id);
      if ((rows && !row?.outOfSync) || Date.now() >= watch.until) {
        setWatch(null);
        return;
      }
      t = setTimeout(() => void poll(), POLL_MS);
    };
    t = setTimeout(() => void poll(), POLL_MS);
    return () => {
      on = false;
      clearTimeout(t);
    };
  }, [watch]);

  const counts = useMemo(() => {
    const all = configs ?? [];
    const builtin = all.filter(isLocked).length;
    return { all: all.length, builtin, yours: all.length - builtin };
  }, [configs]);

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (configs ?? [])
      .filter((c) => (show === 'all' ? true : show === 'builtin' ? isLocked(c) : !isLocked(c)))
      .filter((c) => !needle || `${c.name} ${c.description ?? ''}`.toLowerCase().includes(needle));
  }, [configs, q, show]);

  const templates = useMemo(() => (configs ?? []).filter(isLocked), [configs]);
  const newConfig = () => setChooserOpen(true);

  let body: ReactNode = null;
  if (configs === null) {
    body = (
      <Card>
        <EmptyState icon={<IconConfigurations />} title="Loading configurations…" />
      </Card>
    );
  } else if (configs.length === 0) {
    body = error ? null : (
      <Card>
        <EmptyState
          icon={<IconConfigurations />}
          title="No configurations"
          action={
            canAdd ? (
              <Button variant="primary" size="lg" icon={<IconPlus />} onClick={newConfig}>
                New configuration
              </Button>
            ) : undefined
          }
        >
          {canAdd ? 'Create one to use as a device template.' : undefined}
        </EmptyState>
      </Card>
    );
  } else if (shown.length === 0) {
    body = (
      <Card>
        <EmptyState
          icon={<IconSearch />}
          title="No configurations match"
          action={
            <Button
              size="lg"
              onClick={() => {
                setQ('');
                setShow('all');
              }}
            >
              Show all configurations
            </Button>
          }
        >
          Try another name or description.
        </EmptyState>
      </Card>
    );
  } else {
    body = (
      <ul className="v2-configs__grid">
        {shown.map((c) => (
          <ConfigCard
            key={c.id}
            c={c}
            apps={apps}
            assigned={c.id != null ? assigned[c.id] : undefined}
            sync={c.id != null ? sync[c.id] : undefined}
            syncFailed={syncFailed}
            syncLoaded={syncLoaded}
            onOpen={() => navigate(paths.configuration(c.id ?? ''))}
            onCopy={canCopy ? () => setCopyOf(c) : undefined}
            onDelete={canCopy ? () => setDeleting(c) : undefined}
          />
        ))}
      </ul>
    );
  }

  return (
    <>
      <PageHeader
        title="Configurations"
        subtitle="A configuration is the set of apps, kiosk rules and restrictions a device follows. Devices pick up changes on their next sync."
        subtitleMaxCh={72}
        actions={
          canAdd ? (
            <Button variant="primary" size="hero" icon={<IconPlus />} onClick={newConfig}>
              New configuration
            </Button>
          ) : undefined
        }
      />

      <div className="v2-configs-toolbar">
        <SearchInput
          className="v2-configs-toolbar__search"
          value={q}
          onChange={setQ}
          placeholder="Search configurations"
          aria-label="Search configurations"
        />
        <Segmented<Show>
          size="lg"
          aria-label="Show"
          value={show}
          onChange={setShow}
          options={[
            { value: 'all', label: 'All', count: counts.all },
            { value: 'yours', label: 'Yours', count: counts.yours },
            { value: 'builtin', label: 'Built-in', count: counts.builtin },
          ]}
        />
        <span className="v2-configs-toolbar__spacer" />
        {unassigned ? (
          <Link className="v2-configs-toolbar__unassigned" to={`${paths.devices}?config=none`}>
            <span aria-hidden="true" className="v2-configs-toolbar__ring" />
            {unassigned === 1 ? '1 device has no configuration' : `${unassigned} devices have no configuration`} · Assign
          </Link>
        ) : null}
      </div>

      {error ? (
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          <span className="v2-configs-callout">
            Could not load configurations.
            <Button size="sm" onClick={() => void load()}>
              Retry
            </Button>
          </span>
        </Callout>
      ) : null}

      {libraryFailed && !error ? (
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          <span className="v2-configs-callout">
            Could not load the app library, so some main apps may show as “—”.
            <Button size="sm" onClick={loadLibrary}>
              Retry
            </Button>
          </span>
        </Callout>
      ) : null}

      {body}

      <NewConfigChooser
        open={chooserOpen}
        templates={templates}
        onClose={() => setChooserOpen(false)}
        onPick={(base) => {
          setChooserOpen(false);
          navigate(base?.id != null ? `${paths.configuration('new')}?from=${base.id}` : paths.configuration('new'));
        }}
      />
      <CopyConfigModal
        source={copyOf}
        onClose={() => setCopyOf(null)}
        onDone={(_name, id) => {
          setCopyOf(null);
          // Open the copy ("Duplicate to edit"), or show it in the list when its id could not be found.
          if (id != null) navigate(paths.configuration(id));
          else void load(false);
        }}
      />
      <DeleteConfigModal
        target={deleting}
        deviceCount={deleting?.id != null ? sync[deleting.id]?.total : undefined}
        onClose={() => setDeleting(null)}
        onDeleted={() => {
          setDeleting(null);
          void load(false);
        }}
      />
    </>
  );
}

function ConfigCard({
  c,
  apps,
  assigned,
  sync,
  syncFailed,
  syncLoaded,
  onOpen,
  onCopy,
  onDelete,
}: {
  c: Configuration;
  apps: Application[];
  /** undefined = loading, null = failed. */
  assigned: ConfigApp[] | null | undefined;
  sync?: ConfigSyncSummary;
  syncFailed: boolean;
  syncLoaded: boolean;
  onOpen: () => void;
  /** Duplicate and Delete, left out for a role without copy_config. */
  onCopy?: () => void;
  onDelete?: () => void;
}) {
  const locked = isLocked(c);
  const to = paths.configuration(c.id ?? '');
  const mainId = (c.mainAppId as number | null | undefined) ?? undefined;
  const main = mainId != null ? assigned?.find((a) => a.usedVersionId === mainId) : undefined;
  const mainLabel = main ? (
    <>
      {main.name ?? main.pkg}
      {main.version ? ` ${main.version}` : null}
    </>
  ) : (
    appNameForVersionId(apps, mainId, assigned ?? [])
  );
  const line = syncLoaded ? syncLine(sync, syncFailed) : { segments: [], text: 'Checking sync…', tone: 'idle' as const };
  const devices = sync?.total ?? (syncFailed ? '—' : syncLoaded ? 0 : '…');

  const menu: MenuEntry[] = [{ key: 'open', label: locked ? 'View' : 'Edit', icon: <IconPencil />, onSelect: onOpen }];
  if (onCopy) {
    menu.push({
      key: 'copy',
      label: locked ? 'Duplicate to edit…' : 'Duplicate…',
      textValue: 'Duplicate',
      icon: <IconCopy />,
      onSelect: onCopy,
    });
  }
  if (!locked && onDelete) {
    menu.push('separator', {
      key: 'delete',
      label: 'Delete…',
      textValue: 'Delete',
      icon: <IconTrash />,
      danger: true,
      onSelect: onDelete,
    });
  }

  return (
    <li className="v2-configs-card">
      <div className="v2-configs-card__body">
        <div className="v2-configs-card__top">
          <div className="v2-configs-card__titles">
            <div className="v2-configs-card__nameline">
              <Link to={to} className="v2-configs-card__name">
                {c.name}
              </Link>
              {locked ? (
                <Badge tone="neutral" icon={<IconLockSmall />}>
                  Built-in
                </Badge>
              ) : null}
              {c.kioskMode ? <Badge>Kiosk</Badge> : null}
              {hasDefaultPin(c) ? (
                <Badge tone="warn" title="The kiosk exit PIN is a well-known default. Change it.">
                  Default PIN
                </Badge>
              ) : null}
              {hasNoPin(c) ? (
                <Badge tone="warn" title="Kiosk mode has no exit PIN. Agents before 0.3.6 let anyone leave it; 0.3.6 and later have no exit on the device. Set one.">
                  No PIN
                </Badge>
              ) : null}
            </div>
            {c.description ? <p className="v2-configs-card__desc">{String(c.description)}</p> : null}
          </div>
          <Menu items={menu} label={`More for ${c.name}`} iconSize="sm" />
        </div>
        <MetaGrid
          boxed
          className="v2-configs-card__meta"
          columns="minmax(0, 1.6fr) repeat(2, minmax(0, 1fr))"
          cells={[
            { key: 'main', label: 'Main app', value: mainLabel },
            {
              key: 'apps',
              label: 'Apps',
              value: assigned === undefined ? '…' : assigned === null ? '—' : assigned.length,
              mono: true,
            },
            { key: 'devices', label: 'Devices', value: devices, mono: true },
          ]}
        />
        <div className="v2-configs-card__sync">
          <StackedBar segments={line.segments} track />
          <span className={`v2-configs-card__synctext is-${line.tone}`}>{line.text}</span>
        </div>
      </div>
      <div className="v2-configs-card__foot">
        <ButtonLink to={to} size="sm" variant={locked ? 'secondary' : 'primary'} strong className="v2-configs-card__open">
          {locked ? 'View' : 'Edit'}
        </ButtonLink>
        {onCopy ? (
          <Button size="sm" icon={<IconCopy />} onClick={onCopy}>
            {locked ? 'Duplicate to edit' : 'Duplicate'}
          </Button>
        ) : null}
        {locked ? <span className="v2-configs-card__footnote">Ships with MDMesh</span> : null}
      </div>
    </li>
  );
}
