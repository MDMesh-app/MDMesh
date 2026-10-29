import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { forceSync } from '../api/commands';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import type { DeviceView } from '../api/devices';
import { useDevices } from '../data/useDevices';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { withCoreDevicesParts } from '../ext/core/lists';
import { ORDER } from '../ext/core/order';
import {
  BulkActions,
  useBulkActions,
  useDeviceActionHelpers,
  useListColumns,
  useRowMenu,
  type PlacedColumn,
} from '../ext/hosts/lists';
import { usePermission } from '../ext/permissions';
import { orDash } from '../ui/format';
import { paths } from '../routes';
import {
  BulkSelectionBar,
  Button,
  ButtonLink,
  Callout,
  Card,
  Checkbox,
  Chip,
  ConfigSync,
  DEVICE_KIND_LABEL,
  DeviceIdentity,
  EmptyState,
  IconButton,
  IconCards,
  IconDevices,
  IconPlus,
  IconSync,
  IconTable,
  IconWarning,
  LabelledSelect,
  Menu,
  Notice,
  PageHeader,
  Pagination,
  PresenceStatus,
  RelativeTime,
  SearchInput,
  Segmented,
  Table,
  cx,
  deriveDeviceKind,
  useMediaQuery,
  useToast,
  type DeviceKind,
  type SortDir,
  type TableProps,
} from '../ui';
import { ChangeConfigurationModal, DeleteDevicesModal, RunActionModal } from '../ui/BulkModals';
import { ISSUE_ORDER, ISSUE_REASON, issueKind, type IssueKind, type Presence } from '../data/fleetHealth';
import { useFleetSync } from '../data/useFleetSync';
import { ISSUE_CHIP, syncKind, syncLabel } from './devicesStatus';
import './DevicesPage.css';

type View = 'table' | 'cards';
type StatusFilter = 'all' | Presence;
type SortKey = 'attention' | 'lastSeen' | 'name' | 'config' | 'android';
interface Sort {
  /** One of core's keys, or an extension column's (table view only). */
  key: SortKey | string;
  dir: SortDir;
}

const VIEW_KEY = 'mdmesh-v2-devices-view';
const PAGE_SIZE_KEY = 'mdmesh-v2-devices-page-size';
const TABLE_PAGE_SIZES = [10, 50, 100] as const;
/** Cards page: 12 fills 2-, 3- and 4-column grids evenly (shows 9 at 3 columns). */
const CARDS_PAGE_SIZE = 12;
/** Below this the table becomes cards and the switch hides. */
const NARROW_QUERY = '(max-width: 900px)';

const SORT_DEFAULT_DIR: Record<SortKey, SortDir> = {
  attention: 'ascending',
  lastSeen: 'descending',
  name: 'ascending',
  config: 'ascending',
  android: 'descending',
};
const SORT_LABEL: Record<SortKey, string> = {
  attention: 'Needs attention first',
  lastSeen: 'Last seen',
  name: 'Name',
  config: 'Configuration',
  android: 'Android version',
};
const VIEW_DEFAULT_SORT: Record<View, Sort> = {
  table: { key: 'lastSeen', dir: 'descending' },
  cards: { key: 'attention', dir: 'ascending' },
};
const PRESENCE_RANK: Record<Presence, number> = { attention: 0, online: 1, offline: 2 };
const isCoreSort = (key: string): key is SortKey => Object.prototype.hasOwnProperty.call(SORT_LABEL, key);

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function writeStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode: the choice lasts for this visit only */
  }
}

const deviceName = (d: DeviceView) => d.description?.trim() || d.number;
const compareText = (a: string, b: string) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
const errText = (e: unknown) => (e instanceof Error ? e.message : '');

/** Interactive descendants handle their own clicks; a click elsewhere on a row or card opens (or selects) it. */
const INTERACTIVE = 'a, button, input, select, textarea, label, [role="menu"], [role="menuitem"]';

/** Devices, table and cards. Classic: pages/DevicesPage.tsx. */
export function DevicesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const { devices, total, configurations, loading, error, query: serverQuery, reload } = useDevices();
  // Sync now and Enroll write to devices (ext/core/access.ts); the row and bulk actions say so themselves
  // (ext/core/lists.tsx). A role without edit_devices gets the list to read.
  const canEdit = usePermission(CORE_ACTION_ACCESS.editDevices);
  const extensionColumns = useListColumns('devices');
  const bulkActions = useBulkActions();

  // ---- view: URL ?view=cards wins, then the remembered choice; narrow screens always get cards ----
  const narrow = useMediaQuery(NARROW_QUERY);
  const urlView = params.get('view');
  const chosenView: View =
    urlView === 'cards' || urlView === 'table' ? urlView : readStorage(VIEW_KEY) === 'cards' ? 'cards' : 'table';
  const view: View = narrow ? 'cards' : chosenView;
  const setView = (v: View) => {
    writeStorage(VIEW_KEY, v);
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        if (v === 'cards') next.set('view', 'cards');
        else next.delete('view');
        return next;
      },
      { replace: true },
    );
  };

  // ---- filters ----
  const [q, setQ] = useState('');
  // ?status=online|attention|offline; the Overview legend links here with it.
  const urlStatus = params.get('status');
  const status: StatusFilter =
    urlStatus === 'online' || urlStatus === 'attention' || urlStatus === 'offline' ? urlStatus : 'all';
  const setStatus = (v: StatusFilter) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        if (v === 'all') next.delete('status');
        else next.set('status', v);
        return next;
      },
      { replace: true },
    );
  const [issue, setIssue] = useState<IssueKind | null>(null);
  const [type, setType] = useState<'all' | DeviceKind>('all');
  const [android, setAndroid] = useState('all');
  const [dupOnly, setDupOnly] = useState(false);
  // ?config=<id> or ?config=none (Unassigned); the Configurations page links here with it.
  const configFilter = params.get('config') ?? 'all';
  const setConfigFilter = (v: string) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        if (v === 'all') next.delete('config');
        else next.set('config', v);
        return next;
      },
      { replace: true },
    );

  const [sortChoice, setSortChoice] = useState<Sort | null>(null);
  // An extension column's sort applies to the table; the cards then sort as they do by default, and so does the
  // table once that column is gone (a role refresh, a plugin turned off).
  const sortable = (key: string) =>
    isCoreSort(key) || (view === 'table' && extensionColumns.compare(key, 'ascending') !== undefined);
  const sort = sortChoice && sortable(sortChoice.key) ? sortChoice : VIEW_DEFAULT_SORT[view];

  const [tablePageSize, setTablePageSize] = useState<number>(() => {
    const n = Number(readStorage(PAGE_SIZE_KEY));
    return (TABLE_PAGE_SIZES as readonly number[]).includes(n) ? n : TABLE_PAGE_SIZES[0];
  });
  const pageSize = view === 'cards' ? CARDS_PAGE_SIZE : tablePageSize;
  const [page, setPage] = useState(1);

  // ---- selection + dialogs ----
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [dialog, setDialog] = useState<
    | { kind: 'run'; ids: number[]; devices: DeviceView[] }
    | { kind: 'move' | 'delete'; ids: number[]; label?: string; fromSelection: boolean }
    | null
  >(null);
  const [allConfigs, setAllConfigs] = useState<ConfigurationSummary[] | null>(null);
  const [allConfigsError, setAllConfigsError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState<Set<number>>(new Set());

  useEffect(() => {
    listConfigurations()
      .then((l) => setAllConfigs([...l].sort((a, b) => compareText(a.name, b.name))))
      .catch((e) => {
        const detail = errText(e);
        setAllConfigsError(`Could not load the configurations${detail ? ` (${detail})` : ''}. Reload the page to try again.`);
      });
  }, []);

  // Online / offline decays as devices go quiet.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);

  // Server-side search (debounced): the list is one request of up to 1000, so the query goes to the API too. Until
  // that answer arrives the loaded list is filtered here; after it, the server's matches are shown as they are (it
  // also matches serial, model, IMEI, group names and other fields this page does not hold).
  // useDevices applies only the latest request's answer and reports which search it answers (serverQuery).
  const firstSearch = useRef(true);
  const requested = useRef('');
  useEffect(() => {
    if (firstSearch.current) {
      firstSearch.current = false;
      return;
    }
    const value = q.trim();
    const t = setTimeout(() => {
      requested.current = value;
      void reload(value);
    }, 300);
    return () => clearTimeout(t);
  }, [q, reload]);

  const configName = useCallback(
    (d: DeviceView) =>
      d.configurationId == null
        ? 'Unassigned'
        : (configurations[String(d.configurationId)]?.name ?? `Configuration #${d.configurationId}`),
    [configurations],
  );

  // Type is derived: kiosk mode → kiosk, else hints from the name. The list API has no telemetry and no
  // admin-set type.
  const kindOf = useMemo(() => {
    const m = new Map<number, DeviceKind>();
    for (const d of devices) m.set(d.id, deriveDeviceKind({ kioskMode: d.kioskMode, name: deviceName(d) }));
    return m;
  }, [devices]);
  const kind = useCallback((d: DeviceView): DeviceKind => kindOf.get(d.id) ?? 'phone', [kindOf]);

  // A hardware id shared by more than one row = the same physical device enrolled twice.
  const dupCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of devices) if (d.hardwareId) m.set(d.hardwareId, (m.get(d.hardwareId) ?? 0) + 1);
    return m;
  }, [devices]);
  const dupOf = useCallback((d: DeviceView) => (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0), [dupCount]);
  const dupTotal = useMemo(() => devices.filter((d) => dupOf(d) > 1).length, [devices, dupOf]);
  useEffect(() => {
    if (dupTotal === 0 && dupOnly) setDupOnly(false);
  }, [dupTotal, dupOnly]);

  // ---- filter, sort, page ----
  const needle = q.trim().toLowerCase();
  const textFilter = needle !== '' && needle !== serverQuery.toLowerCase();
  // Everything except presence (which needs the sync state of what is on screen).
  const baseFiltered = useMemo(
    () =>
      devices.filter((d) => {
        if (configFilter === 'none') {
          if (d.configurationId != null) return false;
        } else if (configFilter !== 'all' && String(d.configurationId) !== configFilter) return false;
        if (android !== 'all' && d.androidVersion !== android) return false;
        if (type !== 'all' && kind(d) !== type) return false;
        if (dupOnly && dupOf(d) <= 1) return false;
        if (textFilter) {
          const hay = [d.number, d.description, d.serial, d.imei, d.phone, configName(d), ...(d.groups ?? []).map((g) => g.name)]
            .filter(Boolean)
            .join(' ')
            .toLowerCase();
          if (!hay.includes(needle)) return false;
        }
        return true;
      }),
    [devices, configFilter, android, type, kind, dupOnly, dupOf, textFilter, needle, configName],
  );

  const [visible, setVisible] = useState<DeviceView[]>([]);
  const { syncOf, presenceOf, check, refresh } = useFleetSync(devices, loading, visible);
  const presence = useCallback((d: DeviceView) => presenceOf(d, now), [presenceOf, now]);

  const counts = useMemo(() => {
    const c = { online: 0, attention: 0, offline: 0 };
    for (const d of devices) c[presence(d)]++;
    return c;
  }, [devices, presence]);

  const issues = useMemo(() => {
    const m: Partial<Record<IssueKind, number>> = {};
    for (const d of devices) {
      if (presence(d) !== 'attention') continue;
      const k = issueKind(syncOf(d));
      if (k) m[k] = (m[k] ?? 0) + 1;
    }
    return m;
  }, [devices, presence, syncOf]);

  const filtered = useMemo(
    () =>
      baseFiltered.filter((d) => {
        if (status !== 'all' && presence(d) !== status) return false;
        if (issue && issueKind(syncOf(d)) !== issue) return false;
        return true;
      }),
    [baseFiltered, status, issue, presence, syncOf],
  );

  const sorted = useMemo(() => {
    const sign = sort.dir === 'ascending' ? 1 : -1;
    const bySeen = (a: DeviceView, b: DeviceView) => (b.lastUpdate ?? 0) - (a.lastUpdate ?? 0);
    const byName = (a: DeviceView, b: DeviceView) => compareText(deviceName(a), deviceName(b));
    const byExtension = extensionColumns.compare(sort.key, sort.dir);
    const cmp = (a: DeviceView, b: DeviceView): number => {
      if (byExtension) return byExtension(a, b) || byName(a, b);
      switch (sort.key) {
        case 'attention': {
          const diff = PRESENCE_RANK[presence(a)] - PRESENCE_RANK[presence(b)];
          if (diff !== 0) return sign * diff;
          // Failed applies before other problems, then the most recently seen.
          const fa = syncOf(a)?.tone === 'alert' ? 0 : 1;
          const fb = syncOf(b)?.tone === 'alert' ? 0 : 1;
          return fa - fb || bySeen(a, b) || byName(a, b);
        }
        case 'lastSeen':
          return -sign * bySeen(a, b) || byName(a, b);
        case 'name':
          return sign * byName(a, b);
        case 'config':
          return sign * compareText(configName(a), configName(b)) || byName(a, b);
        case 'android':
          return sign * compareText(a.androidVersion ?? '', b.androidVersion ?? '') || byName(a, b);
        default:
          return byName(a, b); // not reached: `sort` is core's or a live extension column's
      }
    };
    return [...filtered].sort(cmp);
  }, [filtered, sort, presence, syncOf, configName, extensionColumns]);

  const pages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const current = Math.min(page, pages);
  const pageRows = useMemo(() => sorted.slice((current - 1) * pageSize, current * pageSize), [sorted, current, pageSize]);

  // The sync probe asks about the page on screen; keyed by ids so a re-render alone does not refire it.
  const pageKey = pageRows.map((d) => d.id).join(',');
  useEffect(() => {
    setVisible(pageRows);
    // pageKey stands in for pageRows
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageKey]);

  // Back to the first page when the question changes.
  useEffect(() => {
    setPage(1);
  }, [q, status, issue, type, configFilter, android, dupOnly, sort.key, sort.dir, view, pageSize]);

  // ---- options for the selects (values present in the list) ----
  const typeOptions = useMemo(() => {
    const s = new Set<DeviceKind>();
    for (const d of devices) s.add(kind(d));
    return (Object.keys(DEVICE_KIND_LABEL) as DeviceKind[]).filter((k) => s.has(k) || k === type);
  }, [devices, kind, type]);
  const configOptions = useMemo(() => {
    const m = new Map<string, string>();
    let unassigned = false;
    for (const d of devices) {
      if (d.configurationId == null) unassigned = true;
      else m.set(String(d.configurationId), configName(d));
    }
    // A linked configuration with no devices loaded still shows as selected.
    if (configFilter !== 'all' && configFilter !== 'none' && !m.has(configFilter)) {
      const known = configurations[configFilter]?.name ?? allConfigs?.find((c) => String(c.id) === configFilter)?.name;
      m.set(configFilter, known ?? `Configuration #${configFilter}`);
    }
    return {
      list: [...m.entries()].sort((a, b) => compareText(a[1], b[1])),
      unassigned: unassigned || configFilter === 'none',
    };
  }, [devices, configName, configFilter, configurations, allConfigs]);
  const androidOptions = useMemo(() => {
    const s = new Set<string>();
    for (const d of devices) if (d.androidVersion) s.add(d.androidVersion);
    if (android !== 'all') s.add(android);
    return [...s].sort((a, b) => compareText(b, a));
  }, [devices, android]);

  // ---- selection ----
  // Only rows that match the filters stay selected: a reload, search or filter change drops the rest, so the bar's
  // count, "Select all" and every bulk dialog act on devices the user can see (classic: filtered.every(...)).
  // While the list reloads, or the sync picture a filter depends on (Attention, an issue chip) is still being
  // rebuilt, rows drop out of `filtered` only for a moment: the selection waits rather than being wiped (counts and
  // bulk actions use `selectedIds`, which only ever holds visible rows).
  const syncFiltered = status === 'attention' || issue !== null;
  const settling = loading || (syncFiltered && (check === 'pending' || check === 'partial'));
  useEffect(() => {
    if (settling) return;
    setSelected((prev) => {
      if (prev.size === 0) return prev;
      const shown = new Set(filtered.map((d) => d.id));
      const next = new Set([...prev].filter((id) => shown.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [filtered, settling]);
  const selectedIds = useMemo(() => filtered.filter((d) => selected.has(d.id)).map((d) => d.id), [filtered, selected]);
  // What a bulk action gets: the same devices, in the order the list shows them.
  const selectedRows = useMemo(() => sorted.filter((d) => selected.has(d.id)), [sorted, selected]);
  const toggle = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const clearSel = () => setSelected(new Set());
  // Selecting is for the bulk actions, so a role without any gets no checkboxes, and a role refresh that takes the
  // last one away drops the selection, so a row click opens the device again.
  const canSelect = bulkActions.length > 0;
  useEffect(() => {
    if (!canSelect) setSelected((prev) => (prev.size === 0 ? prev : new Set()));
  }, [canSelect]);
  const selectionActive = canSelect && selectedIds.length > 0;

  // Once a selection is in progress, a click on a row or card (outside its links and buttons) toggles it, as in the
  // v0.3 console; otherwise it opens the device. The route takes the number, like the v0.3 links.
  const activate = (d: DeviceView) => (selectionActive ? toggle(d.id) : navigate(paths.device(d.number)));

  // The latest search asked for (its answer may still be on the way), so a refresh never brings back an older one.
  const refreshList = useCallback(() => reload(requested.current), [reload]);
  const listHelpers = useDeviceActionHelpers(refreshList);

  // ---- per-device actions ----
  async function syncNow(d: DeviceView) {
    setSyncing((s) => new Set(s).add(d.id));
    try {
      await forceSync(d.number);
      toast.push('ok', 'Sync requested', d.number);
      refresh(d);
    } catch (e) {
      toast.push('err', 'Sync failed', errText(e));
    } finally {
      setSyncing((s) => {
        const next = new Set(s);
        next.delete(d.id);
        return next;
      });
    }
  }

  // The row menu and the bulk bar come from the registry: core's actions (ext/core/lists.tsx) pick the page's dialogs
  // out of these helpers, and extensions' get the same helpers' public part.
  const helpers = withCoreDevicesParts(listHelpers, {
    row: {
      move: (d) => ({
        key: 'move',
        label: 'Change configuration…',
        onSelect: () => setDialog({ kind: 'move', ids: [d.id], label: d.number, fromSelection: false }),
      }),
      delete: (d) => ({
        key: 'delete',
        label: 'Delete…',
        danger: true,
        onSelect: () => setDialog({ kind: 'delete', ids: [d.id], label: d.number, fromSelection: false }),
      }),
    },
    bulk: {
      run: () => setDialog({ kind: 'run', ids: selectedIds, devices: devices.filter((d) => selected.has(d.id)) }),
      move: () => setDialog({ kind: 'move', ids: selectedIds, fromSelection: true }),
      delete: () => setDialog({ kind: 'delete', ids: selectedIds, fromSelection: true }),
    },
  });
  const rowMenu = useRowMenu('devices', helpers);
  const hasRowActions = canEdit || rowMenu.available;

  const rowActions = (d: DeviceView, size: 'sm' | 'md') => {
    const items = rowMenu.items(d);
    return (
      <div className={cx('v2-devices-actions', size === 'sm' && 'v2-devices-actions--card')}>
        {canEdit ? (
          <IconButton label={`Sync ${d.number} now`} size={size} disabled={syncing.has(d.id)} onClick={() => void syncNow(d)}>
            <IconSync size={size === 'sm' ? 14 : 15} />
          </IconButton>
        ) : null}
        {items.length ? <Menu label={`More actions for ${d.number}`} iconSize={size} items={items} /> : null}
      </div>
    );
  };

  // ---- cells ----
  const statusOf = (d: DeviceView) => {
    const p = presence(d);
    const k = issueKind(syncOf(d));
    let reason: string | undefined;
    if (p === 'attention' && k) reason = ISSUE_REASON[k];
    else if (p === 'offline' && !d.lastUpdate) reason = 'Never checked in';
    return { p, reason };
  };

  const configCell = (d: DeviceView, layout: 'stack' | 'row') => {
    const s = syncOf(d);
    const name = configName(d);
    if (s) return <ConfigSync layout={layout} name={name} sync={syncKind(s)} syncLabel={syncLabel(s)} />;
    // Sync state unknown (summary unavailable, or the per-device check failed): the name alone.
    return layout === 'row' ? (
      <div className="v2-cfgsync v2-cfgsync--row">
        <span className="v2-cfgsync__key">Configuration</span>
        <span className="v2-cfgsync__name">{name}</span>
      </div>
    ) : (
      <span className="v2-cfgsync v2-cfgsync--stack">
        <span>{name}</span>
      </span>
    );
  };

  const identity = (d: DeviceView, variant: 'row' | 'card') => (
    <DeviceIdentity
      variant={variant}
      to={paths.device(d.number)}
      name={deviceName(d)}
      number={d.number}
      kind={kind(d)}
      dupCount={dupOf(d)}
    />
  );

  // Core's columns stay the page's own: their cells and sorting read the page's sync and presence state, and the
  // cards view and its sort menu share them. Extension columns take their places among them (ORDER.devicesColumn).
  const C = ORDER.devicesColumn;
  const coreColumns: PlacedColumn<DeviceView>[] = [
    { key: 'name', order: C.device, header: 'Device', sortable: true, cell: (d) => identity(d, 'row') },
    {
      key: 'attention',
      order: C.status,
      header: 'Status',
      sortable: true,
      cell: (d) => {
        const s = statusOf(d);
        return <PresenceStatus status={s.p} reason={s.reason} />;
      },
    },
    { key: 'config', order: C.configuration, header: 'Configuration', sortable: true, cell: (d) => configCell(d, 'stack') },
    { key: 'android', order: C.android, header: 'Android', sortable: true, mono: true, cell: (d) => orDash(d.androidVersion) },
    {
      key: 'lastSeen',
      order: C.lastSeen,
      header: 'Last seen',
      sortable: true,
      className: 'v2-devices-seen',
      cell: (d) => <RelativeTime value={d.lastUpdate} />,
    },
  ];
  const columns = extensionColumns.place(
    coreColumns,
    hasRowActions ? [{ key: 'actions', header: 'Actions', headerHidden: true, shrink: true, cell: (d) => rowActions(d, 'md') }] : [],
  );

  const onSort = (key: string) => {
    setSortChoice(
      sort.key === key
        ? { key, dir: sort.dir === 'ascending' ? 'descending' : 'ascending' }
        : { key, dir: isCoreSort(key) ? SORT_DEFAULT_DIR[key] : 'ascending' },
    );
  };

  // ---- header text ----
  const partial = check === 'partial';
  const attentionKnown = check === 'partial' || check === 'complete';
  const attentionText = `${counts.attention}${partial ? '+' : ''}`;
  const mono = (v: number | string) => <span className="v2-mono">{v}</span>;
  let subtitle: ReactNode;
  if (!(loading && devices.length === 0) && !error && devices.length > 0) {
    subtitle = (
      <>
        {/* A capped list (over 1000) counts only the loaded devices: say so next to the server total. */}
        {total > devices.length ? <>{mono(total)} devices; </> : null}
        {mono(counts.online)} of {total > devices.length ? 'the first ' : ''}
        {mono(devices.length)} online now
        {attentionKnown && counts.attention > 0 ? <>, {mono(attentionText)} need attention</> : null}
        {counts.offline > 0 ? <>, {mono(counts.offline)} haven’t checked in</> : null}.
      </>
    );
  }

  const filtersActive =
    q.trim() !== '' ||
    status !== 'all' ||
    issue !== null ||
    type !== 'all' ||
    configFilter !== 'all' ||
    android !== 'all' ||
    dupOnly;
  const clearFilters = () => {
    setQ('');
    setIssue(null);
    setType('all');
    // One URL update for both (separate setParams calls would each start from this render's params).
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        next.delete('status');
        next.delete('config');
        return next;
      },
      { replace: true },
    );
    setAndroid('all');
    setDupOnly(false);
  };

  const pickIssue = (k: IssueKind) => {
    if (issue === k) {
      setIssue(null);
      return;
    }
    setStatus('attention');
    setIssue(k);
  };

  const bulkBar = selectionActive ? (
    <BulkSelectionBar
      variant={view === 'table' ? 'attached' : 'standalone'}
      count={selectedIds.length}
      total={filtered.length}
      onSelectAll={() => setSelected(new Set(filtered.map((d) => d.id)))}
      onClear={clearSel}
    >
      <BulkActions actions={bulkActions} rows={selectedRows} helpers={helpers} />
    </BulkSelectionBar>
  ) : null;

  const pager =
    sorted.length > 0 ? (
      <Pagination
        variant={view === 'table' ? 'inset' : 'plain'}
        noun="devices"
        page={current}
        pageSize={pageSize}
        total={sorted.length}
        onPageChange={setPage}
        pageSizeOptions={view === 'table' ? TABLE_PAGE_SIZES : undefined}
        onPageSizeChange={
          view === 'table'
            ? (s) => {
                setTablePageSize(s);
                writeStorage(PAGE_SIZE_KEY, String(s));
              }
            : undefined
        }
        suffix={view === 'cards' && isCoreSort(sort.key) ? `· ${SORT_LABEL[sort.key].toLowerCase()}` : undefined}
      />
    ) : null;

  const empty =
    devices.length === 0 ? (
      <EmptyState
        icon={<IconDevices />}
        title={serverQuery ? 'No devices match this search' : 'No devices yet'}
        action={
          serverQuery ? (
            <Button onClick={clearFilters}>Clear search</Button>
          ) : canEdit ? (
            <ButtonLink to={paths.enroll} variant="primary" icon={<IconPlus />}>
              Enroll device
            </ButtonLink>
          ) : undefined
        }
      >
        {serverQuery ? undefined : 'No devices are enrolled yet.'}
      </EmptyState>
    ) : (
      <EmptyState
        icon={<IconDevices />}
        title="No devices match these filters"
        action={filtersActive ? <Button onClick={clearFilters}>Clear filters</Button> : undefined}
      >
        {status === 'attention' && check === 'failed'
          ? 'The configuration sync state could not be loaded, so devices that need attention can’t be found.'
          : status === 'attention' && check === 'pending'
            ? 'Still checking which devices need attention…'
            : undefined}
      </EmptyState>
    );

  const selection: TableProps<DeviceView>['selection'] = canSelect
    ? {
        selected,
        onToggle: (_k, d) => toggle(d.id),
        onToggleAll: (select) =>
          setSelected((prev) => {
            const next = new Set(prev);
            for (const d of pageRows) {
              if (select) next.add(d.id);
              else next.delete(d.id);
            }
            return next;
          }),
        rowLabel: (d) => `Select ${d.number}`,
        allLabel: 'Select all on this page',
      }
    : undefined;

  let body: ReactNode;
  if (loading && devices.length === 0) {
    body = (
      <Card aria-busy="true">
        <p className="v2-devices-loading" role="status">
          Loading devices…
        </p>
      </Card>
    );
  } else if (error && devices.length === 0) {
    body = null;
  } else if (view === 'table') {
    body = (
      <Card as="section" padding="none" clip aria-label="Device list" aria-busy={loading || undefined}>
        {bulkBar}
        {pageRows.length === 0 ? (
          <div className="v2-devices-empty">{empty}</div>
        ) : (
          <Table<DeviceView>
            aria-label="Devices"
            columns={columns}
            rows={pageRows}
            rowKey={(d) => d.id}
            edge={16}
            minWidth={860}
            selection={selection}
            sort={{ key: sort.key, dir: sort.dir, onSort }}
            onRowClick={activate}
          />
        )}
        {pager}
      </Card>
    );
  } else {
    body = (
      <>
        {bulkBar}
        {pageRows.length === 0 ? (
          <Card>{empty}</Card>
        ) : (
          <ul className="v2-devices-cards" aria-label="Devices" aria-busy={loading || undefined}>
            {pageRows.map((d) => {
              const s = statusOf(d);
              const on = selected.has(d.id);
              return (
                <li
                  key={d.id}
                  className={cx('v2-devices-card', on && 'is-selected')}
                  onClick={(e) => {
                    if (!(e.target as HTMLElement).closest(INTERACTIVE)) activate(d);
                  }}
                >
                  <div className="v2-devices-card__top">
                    {identity(d, 'card')}
                    {canSelect ? (
                      <Checkbox
                        size={17}
                        className="v2-devices-card__check"
                        aria-label={`Select ${d.number}`}
                        checked={on}
                        onChange={() => toggle(d.id)}
                      />
                    ) : null}
                    <PresenceStatus layout="inline" status={s.p} reason={s.reason} />
                    {hasRowActions ? rowActions(d, 'sm') : null}
                  </div>
                  <div className="v2-devices-card__config">{configCell(d, 'row')}</div>
                  <dl className="v2-devices-card__facts">
                    <div>
                      <dt>Android</dt>
                      <dd className="v2-mono">{orDash(d.androidVersion)}</dd>
                    </div>
                    <div>
                      <dt>Last seen</dt>
                      <dd>
                        <RelativeTime value={d.lastUpdate} />
                      </dd>
                    </div>
                  </dl>
                </li>
              );
            })}
          </ul>
        )}
        {pager}
      </>
    );
  }

  const issueEntries = ISSUE_ORDER.filter((k) => (issues[k] ?? 0) > 0);
  const edit = dialog && dialog.kind !== 'run' ? dialog : null;

  return (
    <>
      <PageHeader
        title="Devices"
        subtitle={subtitle}
        actions={
          canEdit ? (
            <ButtonLink to={paths.enroll} variant="primary" size="hero" icon={<IconPlus />}>
              Enroll device
            </ButtonLink>
          ) : undefined
        }
      />

      {error ? (
        <Callout tone="alert" role="alert">
          <span className="v2-devices-callout">
            {error}
            <Button size="sm" onClick={() => void refreshList()}>
              Try again
            </Button>
          </span>
        </Callout>
      ) : null}

      {total > devices.length && !loading ? (
        <Callout tone="info">
          Showing the first {devices.length} of {total} devices. Search to find the others.
        </Callout>
      ) : null}

      {attentionKnown && counts.attention > 0 ? (
        <Notice
          aria-label="Needs attention"
          icon={<IconWarning />}
          action={
            (issues.agentTooOld ?? 0) > 0 ? (
              <ButtonLink to={`${paths.settings}#agent-rollout`} strong>
                Roll out an agent update
              </ButtonLink>
            ) : undefined
          }
        >
          <span className="v2-devices-attn__title">{attentionText} need attention</span>
          {issueEntries.length > 0 ? (
            <span className="v2-devices-attn__chips">
              {issueEntries.map((k) => (
                <Chip key={k} tone={ISSUE_CHIP[k].tone} pressed={issue === k} onClick={() => pickIssue(k)}>
                  {ISSUE_CHIP[k].text(issues[k] ?? 0)}
                </Chip>
              ))}
            </span>
          ) : null}
        </Notice>
      ) : null}

      <div className="v2-devices-toolbar">
        <SearchInput
          className="v2-devices-search"
          aria-label="Search devices"
          placeholder="Search name, number, configuration"
          value={q}
          onChange={setQ}
        />
        <Segmented<StatusFilter>
          size="lg"
          aria-label="Status"
          value={status}
          onChange={(v) => {
            setStatus(v);
            setIssue(null);
          }}
          options={[
            { value: 'all', label: 'All', count: devices.length },
            { value: 'online', label: 'Online', count: counts.online },
            { value: 'attention', label: 'Attention', count: attentionKnown ? attentionText : '–' },
            { value: 'offline', label: 'Offline', count: counts.offline },
          ]}
        />
        {view === 'cards' ? (
          <LabelledSelect
            label="Sort"
            value={sort.key}
            onChange={(e) => {
              const k = e.target.value as SortKey;
              setSortChoice({ key: k, dir: SORT_DEFAULT_DIR[k] });
            }}
          >
            {(Object.keys(SORT_LABEL) as SortKey[]).map((k) => (
              <option key={k} value={k}>
                {SORT_LABEL[k]}
              </option>
            ))}
          </LabelledSelect>
        ) : null}
        <LabelledSelect label="Type" value={type} onChange={(e) => setType(e.target.value as 'all' | DeviceKind)}>
          <option value="all">All</option>
          {typeOptions.map((k) => (
            <option key={k} value={k}>
              {DEVICE_KIND_LABEL[k]}
            </option>
          ))}
        </LabelledSelect>
        <LabelledSelect label="Configuration" value={configFilter} onChange={(e) => setConfigFilter(e.target.value)}>
          <option value="all">All</option>
          {configOptions.list.map(([id, name]) => (
            <option key={id} value={id}>
              {name}
            </option>
          ))}
          {configOptions.unassigned ? <option value="none">Unassigned</option> : null}
        </LabelledSelect>
        <LabelledSelect label="Android" value={android} onChange={(e) => setAndroid(e.target.value)}>
          <option value="all">All</option>
          {androidOptions.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </LabelledSelect>
        {dupTotal > 0 ? (
          <button
            type="button"
            className={cx('v2-devices-dup', dupOnly && 'is-pressed')}
            aria-pressed={dupOnly}
            title="Devices that share a hardware ID with another row: likely the same physical device enrolled more than once"
            onClick={() => setDupOnly((v) => !v)}
          >
            Duplicates <span className="v2-mono">{dupTotal}</span>
          </button>
        ) : null}
        <span className="v2-devices-toolbar__spacer" />
        {!narrow ? (
          <Segmented<View>
            size="lg"
            aria-label="View"
            value={view}
            onChange={setView}
            options={[
              { value: 'table', label: 'Table', icon: <IconTable /> },
              { value: 'cards', label: 'Cards', icon: <IconCards /> },
            ]}
          />
        ) : null}
      </div>

      {body}

      {dialog?.kind === 'run' ? (
        <RunActionModal deviceIds={dialog.ids} devices={dialog.devices} onClose={() => setDialog(null)} onDone={clearSel} />
      ) : null}
      {edit?.kind === 'move' ? (
        <ChangeConfigurationModal
          ids={edit.ids}
          label={edit.label}
          configurations={allConfigs ?? []}
          configurationsError={allConfigsError}
          onClose={() => setDialog(null)}
          onDone={async () => {
            if (edit.fromSelection) clearSel();
            await refreshList();
          }}
        />
      ) : null}
      {edit?.kind === 'delete' ? (
        <DeleteDevicesModal
          ids={edit.ids}
          label={edit.label}
          onClose={() => setDialog(null)}
          onDone={async () => {
            setSelected((prev) => {
              if (edit.fromSelection) return new Set();
              const next = new Set(prev);
              for (const id of edit.ids) next.delete(id);
              return next;
            });
            await refreshList();
          }}
        />
      ) : null}
    </>
  );
}
