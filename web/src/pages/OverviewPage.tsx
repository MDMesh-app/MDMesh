import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { forceSync } from '../api/commands';
import type { ConfigSyncSummary } from '../api/configSync';
import type { ConfigurationLookup, DeviceView } from '../api/devices';
import type { DeviceEvent } from '../api/events';
import type { ActiveRollout } from '../api/rollout';
import { isApplyTerminal, type UpdateStatus } from '../api/updates';
import { isOnline } from '../data/presence';
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
  ButtonAnchor,
  ButtonLink,
  Callout,
  Card,
  CardHeader,
  CountMeta,
  DeviceIdentity,
  EmptyState,
  FleetSummary,
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
    let online = 0,
      attention = 0,
      offline = 0;
    for (const d of devices) {
      const b = bucketOf(d, issues, now);
      if (b === 'online') online++;
      else if (b === 'attention') attention++;
      else offline++;
    }
    return { online, attention, offline };
  }, [devices, issues, now]);

  // Not online: configuration problems first, then the longest silent (classic order).
  const needsAttention = useMemo(
    () =>
      devices
        .filter((d) => bucketOf(d, issues, now) !== 'online')
        .sort((a, b) => Number(!!issues[b.id]) - Number(!!issues[a.id]) || (a.lastUpdate ?? 0) - (b.lastUpdate ?? 0)),
    [devices, issues, now],
  );

  const showSubtitle = !data.loading && data.loadedAt !== null;

  // Core's cards, which the contributions in ext/core/overview.tsx pick out; extensions' cards are placed among them.
  const core = {
    sync: <SyncStat summary={data.summary} failed={data.summaryFailed} />,
    rollout: data.rollout ? <RolloutStat rollout={data.rollout} /> : null,
    needsAttention: (
      <NeedsAttention
        loading={data.loading}
        rows={needsAttention}
        issues={issues}
        now={now}
        syncCheck={syncCheck}
        hasDevices={devices.length > 0}
      />
    ),
    byConfiguration: (
      <ByConfiguration devices={devices} configurations={data.configurations} summary={data.summary} loading={data.loading} />
    ),
    recentActivity: <RecentActivity activity={data.activity} appNames={data.appNames} />,
    server: data.update ? <ServerCard status={data.update} /> : null,
  };

  return (
    <CoreOverviewProvider parts={core}>
      <PageHeader
        title="Overview"
        subtitle={showSubtitle ? <Subtitle data={data} counts={counts} now={now} /> : undefined}
        actions={
          <>
            <UpdatedAt loadedAt={data.loadedAt} failed={!!data.error} loading={data.loading} />
            {canEnroll ? (
              <ButtonLink to={paths.enroll} variant="primary" size="hero" icon={<IconPlus />}>
                Enroll device
              </ButtonLink>
            ) : null}
          </>
        }
      />

      {data.error ? (
        <Callout tone="alert" role="alert" icon={<IconAlert size={15} />} className="v2-ov-error">
          <span>
            {data.error}
            {data.loadedAt !== null ? ' Showing the last data loaded.' : ''}
          </span>
          <Button size="sm" onClick={data.refresh}>
            Retry
          </Button>
        </Callout>
      ) : null}

      <FleetSummary
        headingId="v2-ov-fleet"
        total={data.total}
        caption={
          <>
            {plural(data.total, 'device enrolled', 'devices enrolled')}
            {data.total > devices.length && devices.length > 0 ? (
              <>
                {' '}
                · status of the first <Num>{devices.length}</Num>
              </>
            ) : null}
          </>
        }
        legend={[
          { key: 'online', label: 'Online', count: counts.online, tone: 'ok', to: `${paths.devices}?status=online` },
          {
            key: 'attention',
            label: 'Need attention',
            count: counts.attention,
            tone: 'warn',
            to: `${paths.devices}?status=attention`,
          },
          { key: 'offline', label: 'Offline', count: counts.offline, tone: 'idle', to: `${paths.devices}?status=offline` },
        ]}
        stats={
          <StatGrid>
            <OverviewStats />
          </StatGrid>
        }
      />

      <OverviewRows />
    </CoreOverviewProvider>
  );
}

/* ---- header ------------------------------------------------------------------------------------------------------ */

/** "Most of the fleet is healthy. 14 devices need attention, mostly for an out-of-date agent." from real counts. */
function Subtitle({ data, counts, now }: { data: OverviewData; counts: Counts; now: number }) {
  const { devices, issues, syncCheck } = data;
  const shown = devices.length;
  if (shown === 0) return <>No devices enrolled yet. Enroll one to see it here.</>;

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
function UpdatedAt({ loadedAt, failed, loading }: { loadedAt: number | null; failed: boolean; loading: boolean }) {
  if (loadedAt === null) return loading ? <span className="v2-ov-updated">Loading…</span> : null;
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
        label="Config in sync"
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
  return <StatCell label="Config in sync" value={t.inSync} of={`/${t.total}`} notes={notes.slice(0, 2)} />;
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
          Agent <span className="v2-mono">{rollout.targetVersion}</span> rollout
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
  rows,
  issues,
  now,
  syncCheck,
  hasDevices,
}: {
  loading: boolean;
  rows: DeviceView[];
  issues: Record<number, ConfigIssue>;
  now: number;
  syncCheck: SyncCheck;
  hasDevices: boolean;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  // Syncing and rolling out write to devices (ext/core/access.ts): a role without edit_devices opens the device.
  const canEdit = usePermission(CORE_ACTION_ACCESS.editDevices);

  async function syncNow(d: DeviceView) {
    setBusy((b) => ({ ...b, [d.number]: true }));
    try {
      await forceSync(d.number);
      toast.push('ok', 'Sync requested', `${d.number} checks in now and picks up its configuration.`);
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
    } else {
      action = (
        <ButtonLink size="sm" to={to} aria-label={`Open device: ${name}`}>
          Open device
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
  if (loading) body = <p className="v2-ov-loading">Loading…</p>;
  else if (!hasDevices)
    body = (
      <EmptyState bare title="No devices yet">
        Devices that are offline or whose configuration didn’t apply show up here.
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
          !loading && rows.length > 0 ? <CountMeta shown={shown.length} total={rows.length} to={paths.devices} /> : null
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
}: {
  devices: DeviceView[];
  configurations: Record<string, ConfigurationLookup>;
  summary: ConfigSyncSummary[] | null;
  loading: boolean;
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
      if (!s) {
        return {
          key: String(id),
          name,
          to,
          count: byId.get(id) ?? 0,
          segments: [],
          note: summary ? 'Sync not reported' : 'Sync status unavailable',
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
    <Card padding="snug" aria-labelledby="v2-ov-cfg">
      <CardHeader
        spacing={6}
        title="By configuration"
        titleId="v2-ov-cfg"
        actions={<Link to={paths.configurations}>Manage</Link>}
      />
      {loading ? (
        <p className="v2-ov-loading v2-ov-loading--flush">Loading…</p>
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

function RecentActivity({ activity, appNames }: { activity: ActivityItem[] | null; appNames: Record<string, string> }) {
  const [filter, setFilter] = useState<ActivityFilter>('all');
  const rows = (activity ?? [])
    .filter((a) =>
      filter === 'all' ? true : filter === 'commands' ? a.ev.type === 'commandResult' : a.ev.type !== 'commandResult',
    )
    .slice(0, ACTIVITY_ROWS);

  let body: ReactNode;
  if (activity === null) body = <p className="v2-ov-loading">Loading activity…</p>;
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

function ServerCard({ status }: { status: UpdateStatus }) {
  const apply = status.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');
  const channel = status.channel ? ` · ${status.channel} channel` : '';

  let panel: ReactNode = null;
  if (active && apply) {
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">
          Updating to <span className="v2-mono">{apply.toVersion}</span>
        </span>
        <span className="v2-ov-srv__line is-warn">
          <StatusDot tone="warn" />
          {applyPhaseLabel(apply.phase)}
        </span>
        <ButtonLink size="sm" strong to={SETTINGS_UPDATES}>
          View progress
        </ButtonLink>
      </div>
    );
  } else if (failed && apply) {
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">{apply.phase === 'rolled_back' ? 'Update rolled back' : 'Update failed'}</span>
        {apply.error ? <span className="v2-ov-srv__line is-alert">{apply.error}</span> : null}
        {/* /recovery is served outside the SPA. */}
        <ButtonAnchor size="sm" strong href="/recovery">
          Open recovery
        </ButtonAnchor>
      </div>
    );
  } else if (status.updateAvailable && status.latest) {
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">
          <span className="v2-mono">{status.latest}</span> is available
        </span>
        {status.verified ? (
          <span className="v2-ov-srv__line is-ok">
            <IconCheck />
            Signature verified{channel}
          </span>
        ) : (
          <span className="v2-ov-srv__line is-warn">
            <StatusDot tone="warn" />
            Signature not verified{channel}
          </span>
        )}
        <ButtonLink size="sm" strong to={SETTINGS_UPDATES}>
          Review update
        </ButtonLink>
      </div>
    );
  }

  return (
    <Card padding="compact" gap={12} aria-labelledby="v2-ov-srv">
      <CardHeader title="Server" titleId="v2-ov-srv" />
      <div className="v2-ov-srv__run">
        <StatusDot tone="ok" size={8} />
        <span>
          Running <span className="v2-mono">{status.current}</span>
        </span>
      </div>
      {panel ??
        (lastCheckError(status) ? (
          <span className="v2-ov-srv__meta">The last update check failed.</span>
        ) : (
          <span className="v2-ov-srv__meta">
            Up to date
            {status.checkedAt ? (
              <>
                {' · '}
                <RelativeTime value={status.checkedAt} style="long" prefix="checked " />
              </>
            ) : null}
          </span>
        ))}
    </Card>
  );
}
