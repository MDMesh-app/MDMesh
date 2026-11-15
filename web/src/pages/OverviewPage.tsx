import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { forceSync } from '../api/commands';
import type { ConfigSyncSummary } from '../api/configSync';
import type { ConfigurationLookup, DeviceView } from '../api/devices';
import type { DeviceEvent } from '../api/events';
import type { ActiveRollout } from '../api/rollout';
import { isApplyTerminal, type UpdateStatus } from '../api/updates';
import { isOnline, notConnected } from '../data/presence';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { CoreOverviewProvider } from '../ext/core/overview';
import { OverviewRows, OverviewStats } from '../ext/hosts/OverviewCards';
import { usePermission } from '../ext/permissions';
import { paths } from '../routes';
import {
  ActivityList,
  ActivityRow,
  AttentionList,
  Button,
  ButtonLink,
  Card,
  CardHeader,
  CountMeta,
  DeviceIdentity,
  EmptyState,
  ErrorState,
  FleetSummary,
  Grid,
  GridCell,
  IconAlert,
  IconBattery,
  IconCheck,
  IconDownload,
  IconInfo,
  IconLock,
  IconNetwork,
  IconPlus,
  IconPower,
  IconTrash,
  PageHeader,
  RelativeTime,
  Segmented,
  StackedBar,
  StatCell,
  StatGrid,
  StatusDot,
  SkeletonFacts,
  SkeletonRows,
  deriveDeviceKind,
  useToast,
  type AttentionItem,
  type BarSegment,
  type StatNote,
  type StatusTone,
} from '../ui';
import { applyPhaseLabel, lastCheckError } from '../data/updateStatus';
import { EVENT_VERBS, parseCommandResult } from '../data/deviceActivity';
import type { SyncCheck } from '../data/fleetHealth';
import { wipedOnlyLine } from './configurations/sync';
import { bucketOf, reasonFor, topReason, type ConfigIssue } from './overview/attention';
import { useOverviewData, type ActivityItem, type OverviewData } from './overview/useOverviewData';
import './OverviewPage.css';

/** Rows in "Needs attention" and "Recent activity". */
const ATTENTION_ROWS = 6;
const ACTIVITY_ROWS = 8;
/** Settings sections the Overview links to (Settings side-nav anchors). */
const SETTINGS_UPDATES = `${paths.settings}#updates`;
const SETTINGS_ROLLOUT = `${paths.settings}#agent-rollout`;

const plural = (n: number, one: string, many: string) => (n === 1 ? one : many);
const Num = ({ children }: { children: ReactNode }) => <span className="v2-num">{children}</span>;

interface Counts {
  online: number;
  attention: number;
  offline: number;
  /** Not in the fleet bar: a wiped device no longer reports (#137). */
  wiped: number;
}

/** Re-render on an interval so online/attention/offline decay while the page is open (classic: 30 s). */
function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/** Overview. Classic: pages/DashboardPage.tsx. */
export function OverviewPage() {
  const data = useOverviewData();
  const now = useNow(30_000);
  // Enrolling writes to devices (ext/core/access.ts).
  const canEnroll = usePermission(CORE_ACTION_ACCESS.editDevices);
  const { devices, issues, syncCheck } = data;

  const counts = useMemo<Counts>(() => {
    const c: Counts = { online: 0, attention: 0, offline: 0, wiped: 0 };
    for (const d of devices) c[bucketOf(d, issues, now)]++;
    return c;
  }, [devices, issues, now]);

  // Attention or offline (a wiped device is neither): configuration problems first, then the longest silent (classic
  // order).
  const needsAttention = useMemo(
    () =>
      devices
        .filter((d) => {
          const b = bucketOf(d, issues, now);
          return b === 'attention' || b === 'offline';
        })
        .sort((a, b) => Number(!!issues[b.id]) - Number(!!issues[a.id]) || (a.lastUpdate ?? 0) - (b.lastUpdate ?? 0)),
    [devices, issues, now],
  );

  const showSubtitle = !data.loading && data.loadedAt !== null;
  // The first load failed: nothing is known, so the cards say so instead of showing an empty fleet.
  const unknown = data.loadedAt === null && !!data.error;
  const firstLoad = data.loading && data.loadedAt === null;

  // Core's cards, which the contributions in ext/core/overview.tsx pick out; extensions' cards are placed among them.
  const core = {
    sync: <SyncStat summary={data.summary} failed={data.summaryFailed} />,
    rollout: data.rollout ? <RolloutStat rollout={data.rollout} /> : null,
    needsAttention: (
      <NeedsAttention
        loading={firstLoad}
        unknown={unknown}
        rows={needsAttention}
        issues={issues}
        now={now}
        syncCheck={syncCheck}
        hasDevices={devices.length > 0}
        allWiped={devices.length > 0 && counts.wiped === devices.length}
      />
    ),
    byConfiguration: (
      <ByConfiguration
        devices={devices}
        configurations={data.configurations}
        summary={data.summary}
        loading={firstLoad}
        unknown={unknown}
      />
    ),
    recentActivity: <RecentActivity activity={unknown ? null : data.activity} appNames={data.appNames} unknown={unknown} />,
    server: data.update ? <ServerCard status={data.update} /> : null,
  };

  return (
    <CoreOverviewProvider parts={core}>
      <PageHeader
        title="Overview"
        subtitle={showSubtitle ? <Subtitle data={data} counts={counts} now={now} /> : null}
        actions={
          <>
            <UpdatedAt loadedAt={data.loadedAt} failed={!!data.error} />
            {canEnroll ? (
              <ButtonLink to={paths.enroll} variant="primary" size="lg" icon={<IconPlus />}>
                Enroll device
              </ButtonLink>
            ) : null}
          </>
        }
      />

      {data.error ? (
        <ErrorState
          thing="the fleet"
          detail={data.loadedAt !== null ? 'Showing the last data loaded.' : undefined}
          onRetry={data.refresh}
        />
      ) : null}

      {/* One 12-column grid for every row, so card edges line up down the page (ext/hosts/OverviewCards). */}
      <Grid>
        <GridCell span={8}>
          <FleetSummary
            headingId="v2-ov-fleet"
            loading={firstLoad}
            total={unknown ? null : data.total}
            caption={
              <>
                {plural(data.total, 'device enrolled', 'devices enrolled')}
                {data.total > devices.length && devices.length > 0 ? (
                  <>
                    {' '}
                    · status of the first <Num>{devices.length}</Num>
                  </>
                ) : null}
                {/* Beside the total, not in the bar and legend: a wiped device is enrolled but no longer reports. */}
                {counts.wiped > 0 ? (
                  <>
                    {' · '}
                    <Link to={`${paths.devices}?status=wiped`}>
                      <Num>{counts.wiped}</Num> wiped
                    </Link>
                  </>
                ) : null}
              </>
            }
            legend={[
              { key: 'online', label: 'Online', count: counts.online, tone: 'ok', to: `${paths.devices}?status=online` },
              {
                key: 'attention',
                label: 'Needs attention',
                count: counts.attention,
                tone: 'warn',
                to: `${paths.devices}?status=attention`,
              },
              { key: 'offline', label: 'Offline', count: counts.offline, tone: 'idle', to: `${paths.devices}?status=offline` },
            ]}
          />
        </GridCell>
        <GridCell span={4}>
          <Card aria-label="Fleet status" className="v2-ov-stats">
            <StatGrid>
              <OverviewStats />
            </StatGrid>
          </Card>
        </GridCell>
        <OverviewRows />
      </Grid>
    </CoreOverviewProvider>
  );
}

/* ---- header ------------------------------------------------------------------------------------------------------ */

/** "Most of the fleet is healthy. 14 devices need attention, mostly for an out-of-date agent." from real counts. */
function Subtitle({ data, counts, now }: { data: OverviewData; counts: Counts; now: number }) {
  const { devices, issues, syncCheck } = data;
  // Wiped devices are left out: they no longer report, so they are neither healthy nor offline.
  const shown = devices.length - counts.wiped;
  if (devices.length === 0) return <>No devices enrolled yet. Enroll one to see it here.</>;
  if (shown === 0) return <>Every enrolled device was wiped.</>;

  if (counts.attention === 0 && counts.offline === 0) {
    if (syncCheck === 'complete') return <>Every device is online and in sync.</>;
    if (syncCheck === 'pending') return <>Every device is online. Checking configuration sync…</>;
    if (syncCheck === 'failed') return <>Every device is online. Configuration sync could not be checked.</>;
    return <>Every device is online. Configuration sync was only partly checked.</>;
  }

  const lead = counts.online * 2 >= shown ? 'Most of the fleet is healthy. ' : '';
  if (counts.attention === 0) {
    return (
      <>
        {lead}
        <Num>{counts.offline}</Num> {plural(counts.offline, 'device is', 'devices are')} offline.
      </>
    );
  }
  const n = counts.attention;
  const reason = topReason(devices, issues, now);
  const why = reason ? `, ${reason.share === 'all' ? 'all' : 'mostly'} for ${reason.phrase}` : '';
  return (
    <>
      {lead}
      {syncCheck === 'partial' ? 'At least ' : null}
      <Num>{n}</Num> {plural(n, 'device needs', 'devices need')} attention{why}
      {counts.offline > 0 ? (
        <>
          , and <Num>{counts.offline}</Num> {plural(counts.offline, 'is', 'are')} offline
        </>
      ) : null}
      .
    </>
  );
}

/** "● Updated 20s ago": when the data last loaded (warn mark when the latest refresh failed). */
function UpdatedAt({ loadedAt, failed }: { loadedAt: number | null; failed: boolean }) {
  if (loadedAt === null) return null;
  return (
    <span className="v2-ov-updated">
      <StatusDot tone={failed ? 'warn' : 'ok'} size={7} />
      <RelativeTime value={loadedAt} seconds prefix="Updated " />
    </span>
  );
}

/* ---- stats beside the fleet bar ---------------------------------------------------------------------------------- */

function SyncStat({ summary, failed }: { summary: ConfigSyncSummary[] | null; failed: boolean }) {
  if (!summary) {
    return (
      <StatCell
        label="Configuration in sync"
        value="—"
        notes={[failed ? { text: 'Could not be checked', tone: 'alert' } : { text: 'Checking…' }]}
      />
    );
  }
  const t = summary.reduce(
    (a, s) => ({
      total: a.total + s.total,
      inSync: a.inSync + s.inSync,
      outOfSync: a.outOfSync + s.outOfSync,
      unsupported: a.unsupported + s.unsupported,
      neverSeen: a.neverSeen + s.neverSeen,
    }),
    { total: 0, inSync: 0, outOfSync: 0, unsupported: 0, neverSeen: 0 },
  );
  const notes: StatNote[] = [];
  if (t.outOfSync > 0) notes.push({ text: `${t.outOfSync} out of sync`, tone: 'warn' });
  if (t.unsupported > 0) notes.push({ text: `${t.unsupported} agent too old`, tone: 'warn' });
  if (t.neverSeen > 0) notes.push({ text: `${t.neverSeen} not reported` });
  if (t.total === 0) notes.push({ text: 'No device has a configuration' });
  else if (notes.length === 0) notes.push({ text: 'All in sync', tone: 'ok' });
  return <StatCell label="Configuration in sync" value={t.inSync} of={`/${t.total}`} notes={notes.slice(0, 2)} />;
}

/** While an agent rollout runs: devices updated in its current stage (Settings → Agent rollout has the controls). */
function RolloutStat({ rollout }: { rollout: ActiveRollout }) {
  const fleet = rollout.stage === 'fleet' ? rollout.progress.fleet : null;
  const c = fleet ?? rollout.progress.canary;
  const notes: StatNote[] = [{ text: fleet ? 'Rolling out to the fleet' : 'Canary devices first' }];
  if (c.pending > 0) notes.push({ text: `${c.pending} installing`, tone: 'warn' });
  else if (c.outstanding > 0) notes.push({ text: `${c.outstanding} queued` });
  else if (c.ineligible > 0) notes.push({ text: `${c.ineligible} too old to update` });
  return (
    <StatCell
      label={
        <Link to={SETTINGS_ROLLOUT} className="v2-ov-statlink">
          Agent {rollout.targetVersion} rollout
        </Link>
      }
      value={c.updated}
      of={`/${Math.max(c.total - c.ineligible, 0)}`}
      notes={notes}
    />
  );
}

/* ---- Needs attention ---------------------------------------------------------------------------------------------- */

function NeedsAttention({
  loading,
  unknown,
  rows,
  issues,
  now,
  syncCheck,
  hasDevices,
  allWiped,
}: {
  loading: boolean;
  unknown?: boolean;
  rows: DeviceView[];
  issues: Record<number, ConfigIssue>;
  now: number;
  syncCheck: SyncCheck;
  hasDevices: boolean;
  /** Every enrolled device was wiped: none is online, so "All clear" would say the opposite. */
  allWiped: boolean;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  // Syncing and rolling out write to devices (ext/core/access.ts): a role without edit_devices opens the device.
  const canEdit = usePermission(CORE_ACTION_ACCESS.editDevices);

  async function syncNow(d: DeviceView) {
    setBusy((b) => ({ ...b, [d.number]: true }));
    try {
      const { connected } = await forceSync(d.number);
      if (connected === false) toast.push('warn', ...notConnected(d.number, 'sync'));
      else toast.push('ok', 'Sync requested', `${d.number} checks in now and picks up its configuration.`);
    } catch (e) {
      toast.push('err', 'Sync failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy((b) => ({ ...b, [d.number]: false }));
    }
  }

  const shown = rows.slice(0, ATTENTION_ROWS);
  const items: AttentionItem[] = shown.map((d) => {
    const issue = isOnline(d.lastUpdate, now) ? issues[d.id] : undefined;
    const r = reasonFor(issue);
    const to = paths.device(d.number);
    const name = d.description || d.number;
    let action: ReactNode;
    if (r.action === 'sync' && canEdit) {
      action = (
        <Button size="sm" disabled={!!busy[d.number]} aria-label={`Sync now: ${name}`} onClick={() => void syncNow(d)}>
          {busy[d.number] ? 'Syncing…' : 'Sync now'}
        </Button>
      );
    } else if (r.action === 'agent' && canEdit) {
      action = (
        <ButtonLink size="sm" to={SETTINGS_ROLLOUT} aria-label={`Update agent: ${name}`}>
          Update agent
        </ButtonLink>
      );
    }
    return {
      key: d.id,
      device: (
        <DeviceIdentity
          variant="attention"
          to={to}
          name={name}
          number={d.number}
          kind={deriveDeviceKind({ kioskMode: d.kioskMode, name: d.description })}
        />
      ),
      reason: r.text,
      tone: r.tone,
      when: <RelativeTime value={d.lastUpdate} />,
      action,
    };
  });

  let body: ReactNode;
  if (loading) body = <SkeletonRows count={5} label="Loading devices that need attention…" />;
  else if (unknown)
    body = (
      <EmptyState bare title="Not known">
        The devices couldn’t be loaded, so it isn’t known which need attention.
      </EmptyState>
    );
  else if (!hasDevices)
    body = (
      <EmptyState bare title="No devices yet">
        Devices that are offline or whose configuration didn’t apply show up here.
      </EmptyState>
    );
  else if (allWiped)
    body = (
      <EmptyState bare title="No active devices">
        Every enrolled device was wiped.
      </EmptyState>
    );
  else if (items.length === 0)
    body = (
      <EmptyState bare icon={<IconCheck />} title={syncCheck === 'complete' ? 'All clear' : 'All online'}>
        {syncCheck === 'complete'
          ? 'Every device is online and in sync.'
          : syncCheck === 'pending'
            ? 'Every device is online. Checking configuration sync…'
            : syncCheck === 'failed'
              ? 'Every device is online. Configuration sync could not be checked.'
              : 'Every device is online. Configuration sync was only partly checked.'}
      </EmptyState>
    );
  else body = <AttentionList aria-label="Devices that need attention" items={items} />;

  return (
    <Card padding="list" clip aria-labelledby="v2-ov-att" className="v2-ov-att">
      <CardHeader
        inset
        title="Needs attention"
        titleId="v2-ov-att"
        actions={
          !loading && rows.length > 0 ? (
            <CountMeta shown={shown.length} total={rows.length} to={`${paths.devices}?status=attention`} />
          ) : null
        }
      />
      {body}
    </Card>
  );
}

/* ---- By configuration ---------------------------------------------------------------------------------------------- */

interface ConfigRow {
  key: string;
  name: string;
  to?: string;
  count: number;
  segments: BarSegment[];
  note: string;
  tone: 'ok' | 'warn' | 'muted';
}

function ByConfiguration({
  devices,
  configurations,
  summary,
  loading,
  unknown,
}: {
  devices: DeviceView[];
  configurations: Record<string, ConfigurationLookup>;
  summary: ConfigSyncSummary[] | null;
  loading: boolean;
  unknown?: boolean;
}) {
  const rows = useMemo<ConfigRow[]>(() => {
    // Devices per configuration from the device list (the classic card); sync detail from the summary when loaded.
    const byId = new Map<number, number>();
    let none = 0;
    for (const d of devices) {
      if (d.configurationId == null || !configurations[String(d.configurationId)]) none++;
      else byId.set(d.configurationId, (byId.get(d.configurationId) ?? 0) + 1);
    }
    const sums = new Map<number, ConfigSyncSummary>((summary ?? []).map((s) => [s.configurationId, s]));
    const ids = new Set<number>(byId.keys());
    for (const s of sums.values()) if (s.total > 0 && configurations[String(s.configurationId)]) ids.add(s.configurationId);
    const out: ConfigRow[] = [...ids].map((id) => {
      const s = sums.get(id);
      const name = configurations[String(id)]?.name ?? `Configuration ${id}`;
      const to = paths.configuration(id);
      if (!s || s.total === 0) {
        return {
          key: String(id),
          name,
          to,
          count: byId.get(id) ?? 0,
          segments: [],
          note: wipedOnlyLine(s) ?? (summary ? 'Sync not reported' : 'Sync status unavailable'),
          tone: 'muted',
        };
      }
      const parts = [s.inSync === s.total ? 'All in sync' : `${s.inSync} in sync`];
      if (s.outOfSync) parts.push(`${s.outOfSync} out of sync`);
      if (s.unsupported) parts.push(`${s.unsupported} agent too old`);
      if (s.neverSeen) parts.push(`${s.neverSeen} not reported`);
      if (s.deferred) parts.push(`${s.deferred} deferred settings`);
      return {
        key: String(id),
        name,
        to,
        count: s.total,
        segments: [
          { value: s.inSync, tone: 'ok' },
          { value: s.outOfSync + s.unsupported, tone: 'warn' },
          { value: s.neverSeen, tone: 'pending' },
        ],
        note: parts.join(' · '),
        tone: s.outOfSync || s.unsupported || s.deferred ? 'warn' : s.inSync === s.total ? 'ok' : 'muted',
      };
    });
    out.sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
    if (none > 0) {
      out.push({
        key: 'none',
        name: 'No configuration',
        count: none,
        segments: [{ value: none, tone: 'pending' }],
        note: `${none} ${plural(none, 'device has', 'devices have')} nothing assigned`,
        tone: 'muted',
      });
    }
    return out;
  }, [devices, configurations, summary]);

  return (
    <Card aria-labelledby="v2-ov-cfg">
      <CardHeader
        spacing={8}
        title="By configuration"
        titleId="v2-ov-cfg"
        actions={<Link to={paths.configurations}>Manage</Link>}
      />
      {loading ? (
        <SkeletonFacts count={3} label="Loading configurations…" />
      ) : unknown ? (
        <EmptyState bare title="Not known">
          The devices couldn’t be loaded.
        </EmptyState>
      ) : rows.length === 0 ? (
        <EmptyState bare title="No devices yet">
          Devices are counted here by the configuration they use.
        </EmptyState>
      ) : (
        <ul className="v2-ov-cfgs">
          {rows.map((r) => (
            <li key={r.key} className="v2-ov-cfg">
              <div className="v2-ov-cfg__head">
                {r.to ? (
                  <Link to={r.to} className="v2-ov-cfg__name">
                    {r.name}
                  </Link>
                ) : (
                  <span className="v2-ov-cfg__name">{r.name}</span>
                )}
                <span className="v2-ov-cfg__count">{r.count}</span>
              </div>
              {r.segments.length > 0 ? <StackedBar segments={r.segments} /> : null}
              <span className={`v2-ov-cfg__note is-${r.tone}`}>{r.note}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/* ---- Recent activity ---------------------------------------------------------------------------------------------- */

type ActivityFilter = 'all' | 'commands' | 'events';

function eventView(
  ev: DeviceEvent,
  device: DeviceView,
  appNames: Record<string, string>,
): { icon: ReactNode; tone: StatusTone; what: string; detail?: string } {
  const what = EVENT_VERBS[ev.type] ?? ev.type;
  // Classic: the event detail, else the device description.
  const detail = ev.detail || device.description || undefined;
  switch (ev.type) {
    case 'enrolled':
      return { icon: <IconPlus size={15} />, tone: 'ok', what, detail };
    case 'appInstalled':
    case 'appUninstalled': {
      // Detail is the package name; show the library name when the app is in the library.
      const pkg = ev.detail?.trim() ?? '';
      const name = pkg ? appNames[pkg] : undefined;
      return {
        icon: ev.type === 'appInstalled' ? <IconDownload size={15} /> : <IconTrash size={15} />,
        tone: 'idle',
        what,
        detail: name ?? detail,
      };
    }
    case 'commandResult': {
      const r = parseCommandResult(ev.detail);
      if (!r) return { icon: <IconCheck size={15} />, tone: 'idle', what, detail };
      const s = r.status.toLowerCase();
      const tone: StatusTone = s === 'done' ? 'ok' : s === 'failed' ? 'alert' : s === 'expired' ? 'warn' : 'idle';
      return {
        icon: tone === 'alert' ? <IconAlert size={15} /> : <IconCheck size={15} />,
        tone,
        what,
        detail: `#${r.id} · ${r.status}`,
      };
    }
    case 'lowBattery':
      return { icon: <IconBattery size={15} />, tone: 'warn', what, detail };
    case 'connectivityChange':
      return { icon: <IconNetwork size={15} />, tone: 'idle', what, detail };
    case 'boot':
      return { icon: <IconPower size={15} />, tone: 'idle', what, detail };
    case 'kioskPinLockout':
      return { icon: <IconLock size={15} />, tone: 'alert', what, detail };
    case 'kioskCrashLoop':
      return { icon: <IconAlert size={15} />, tone: 'warn', what, detail };
    default:
      return { icon: <IconInfo size={15} />, tone: 'idle', what, detail };
  }
}

const FILTERS: ReadonlyArray<{ value: ActivityFilter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'commands', label: 'Commands' },
  { value: 'events', label: 'Device events' },
];

function RecentActivity({
  activity,
  appNames,
  unknown,
}: {
  activity: ActivityItem[] | null;
  appNames: Record<string, string>;
  unknown?: boolean;
}) {
  const [filter, setFilter] = useState<ActivityFilter>('all');
  const rows = (activity ?? [])
    .filter((a) =>
      filter === 'all' ? true : filter === 'commands' ? a.ev.type === 'commandResult' : a.ev.type !== 'commandResult',
    )
    .slice(0, ACTIVITY_ROWS);

  let body: ReactNode;
  if (unknown)
    body = (
      <EmptyState bare title="Not known">
        The devices couldn’t be loaded, so their recent activity isn’t known.
      </EmptyState>
    );
  else if (activity === null) body = <SkeletonRows count={6} compact label="Loading activity…" />;
  else if (rows.length === 0)
    body = (
      <EmptyState bare title={filter === 'commands' ? 'No command results' : 'No recent events'}>
        {filter === 'commands'
          ? 'Results of commands run by the most recently seen devices show up here.'
          : 'Events from the most recently seen devices show up here.'}
      </EmptyState>
    );
  else
    body = (
      <ActivityList aria-label="Recent activity">
        {rows.map((a) => {
          const v = eventView(a.ev, a.device, appNames);
          return (
            <ActivityRow
              key={a.key}
              icon={v.icon}
              tone={v.tone}
              who={a.device.number}
              whoTo={paths.device(a.device.number)}
              what={v.what}
              detail={v.detail}
              when={<RelativeTime value={a.ev.ts} />}
            />
          );
        })}
      </ActivityList>
    );

  return (
    <Card padding="list" clip aria-labelledby="v2-ov-act">
      <CardHeader
        inset
        title="Recent activity"
        titleId="v2-ov-act"
        actions={<Segmented size="sm" aria-label="Filter activity" options={FILTERS} value={filter} onChange={setFilter} />}
      />
      {body}
    </Card>
  );
}

/* ---- Server ------------------------------------------------------------------------------------------------------- */

/**
 * The server's version and update state as facts. The sidebar's update item is the one call to action for a new
 * release, so this card states it without a second button; a running or failed update links to its progress.
 */
function ServerCard({ status }: { status: UpdateStatus }) {
  const apply = status.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');

  let line: ReactNode;
  if (active && apply) {
    line = (
      <span className="v2-ov-srv__line is-warn">
        <StatusDot tone="warn" />
        Updating to {apply.toVersion}: {applyPhaseLabel(apply.phase)}
        <Link to={SETTINGS_UPDATES} className="v2-ov-srv__link">
          View progress
        </Link>
      </span>
    );
  } else if (failed && apply) {
    line = (
      <span className="v2-ov-srv__line is-alert">
        <StatusDot tone="alert" />
        {apply.phase === 'rolled_back' ? 'Update rolled back' : 'Update failed'}
        {/* /recovery is served outside the SPA. */}
        <a href="/recovery" className="v2-ov-srv__link">
          Open recovery
        </a>
      </span>
    );
  } else if (status.updateAvailable && status.latest) {
    line = (
      <span className="v2-ov-srv__line">
        {status.latest} available{status.verified ? ', signature verified' : ', signature not verified'}
      </span>
    );
  } else if (lastCheckError(status)) {
    line = <span className="v2-ov-srv__line is-warn">The last update check failed.</span>;
  } else {
    line = (
      <span className="v2-ov-srv__line">
        Up to date
        {status.checkedAt ? (
          <>
            {' · '}
            <RelativeTime value={status.checkedAt} style="long" prefix="checked " />
          </>
        ) : null}
      </span>
    );
  }

  return (
    <Card gap={8} aria-labelledby="v2-ov-srv">
      <CardHeader title="Server" titleId="v2-ov-srv" />
      <div className="v2-ov-srv__run">
        <StatusDot tone="ok" size={8} />
        <span>
          Running <span className="v2-num">{status.current}</span>
          {status.channel ? <span className="v2-secondary"> · {status.channel} channel</span> : null}
        </span>
      </div>
      {line}
    </Card>
  );
}
