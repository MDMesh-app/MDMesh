// The Automation view: what the Policies server plugin does by itself. Automatic scans (a nightly sweep of every
// phone inside a window, and new phones at any time), auto-remove on the server, and each policy's host phone with
// the apps new on it. Needs the plugin; without it the view says so.
import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Callout,
  Card,
  CardHeader,
  EmptyState,
  FieldRow,
  IconClock,
  IconDevices,
  IconInfo,
  Link,
  RelativeTime,
  Select,
  Table,
  Toggle,
  usePermission,
  useToast,
  type Column,
} from '@mdmesh/console';
import { getAutomation, saveAutomation, type Automation, type PolicyRow } from './api';
import { enforcedList, type Fleet } from './fleet';
import { newOnHost } from './model';
import { errorText, plural } from './shared';

const HOURS = Array.from({ length: 24 }, (_, h) => h);
const hourLabel = (h: number) => `${String(h).padStart(2, '0')}:00`;

/** The browser's time zone, which the window is set in. */
function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

export function AutomationView({ fleet }: { fleet: Fleet }) {
  if (fleet.source === 'perPhone') {
    return (
      <Callout tone="info" icon={<IconInfo size={16} />}>
        Automation needs the Policies server plugin (plugins/fork/policies) on the server. Without it, scans and
        auto-remove run only from this page.
      </Callout>
    );
  }
  return (
    <div className="x-policies-stack">
      <ScansCard />
      <AutoRemoveCard fleet={fleet} />
      <HostsCard fleet={fleet} />
    </div>
  );
}

function ScansCard() {
  const toast = useToast();
  const canEdit = usePermission({ permission: 'edit_devices' });
  const [stored, setStored] = useState<Automation | null>(null);
  const [draft, setDraft] = useState<Automation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const zone = browserZone();

  useEffect(() => {
    let on = true;
    getAutomation()
      .then((a) => {
        if (!on) return;
        setStored(a);
        setDraft(a);
      })
      .catch((e: unknown) => on && setError(errorText(e, 'Could not read the automation settings.')));
    return () => {
      on = false;
    };
  }, []);

  if (error) return <Callout tone="alert">{error}</Callout>;
  if (!draft || !stored) {
    return (
      <Card>
        <EmptyState icon={<IconClock />} title="Loading the automation settings…" bare />
      </Card>
    );
  }

  const changed =
    draft.automaticScans !== stored.automaticScans || draft.windowStart !== stored.windowStart || draft.windowEnd !== stored.windowEnd || zone !== stored.timeZone;

  async function save() {
    if (!draft) return;
    setBusy(true);
    try {
      const saved = await saveAutomation({ automaticScans: draft.automaticScans, windowStart: draft.windowStart, windowEnd: draft.windowEnd, timeZone: zone });
      setStored(saved);
      setDraft(saved);
      toast.push('ok', 'Automation saved', saved.automaticScans ? `Phones are scanned each night between ${hourLabel(saved.windowStart)} and ${hourLabel(saved.windowEnd)}.` : 'Automatic scans are off.');
    } catch (e) {
      toast.push('err', 'Could not save the automation', errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  const night = stored.lastSweep;
  return (
    <Card>
      <CardHeader
        title="Automatic scans"
        subtitle="Each night, inside the window, the server scans every phone once, a few a minute, in a fixed order, and skips phones scanned that day. New phones are scanned at any time. Scans keep Status and the extra apps up to date."
        level={3}
        actions={
          canEdit && changed ? (
            <Button variant="primary" size="sm" disabled={busy} onClick={() => void save()}>
              {busy ? 'Saving…' : 'Save'}
            </Button>
          ) : undefined
        }
      />
      <FieldRow label="Automatic scans" help={draft.automaticScans ? 'On.' : 'Off: phones are scanned only when you ask.'} divider>
        {({ labelId }) => (
          <Toggle
            aria-labelledby={labelId}
            checked={draft.automaticScans}
            disabled={!canEdit}
            onChange={(automaticScans) => setDraft({ ...draft, automaticScans })}
            showState
          />
        )}
      </FieldRow>
      <FieldRow
        label="Nightly window"
        help={`In ${zone}. Pick hours when the phones are on and charging. The window may cross midnight.`}
        divider
        disabled={!draft.automaticScans}
      >
        {() => (
          <span className="x-policies-inline">
            <Select aria-label="From" value={draft.windowStart} disabled={!canEdit || !draft.automaticScans} onChange={(e) => setDraft({ ...draft, windowStart: Number(e.target.value) })}>
              {HOURS.map((h) => (
                <option key={h} value={h}>
                  {hourLabel(h)}
                </option>
              ))}
            </Select>
            <span className="x-policies-help">to</span>
            <Select aria-label="To" value={draft.windowEnd} disabled={!canEdit || !draft.automaticScans} onChange={(e) => setDraft({ ...draft, windowEnd: Number(e.target.value) })}>
              {HOURS.map((h) => (
                <option key={h} value={h}>
                  {hourLabel(h)}
                </option>
              ))}
            </Select>
          </span>
        )}
      </FieldRow>
      {stored.timeZone !== zone ? (
        <p className="x-policies-help is-below">Saved in {stored.timeZone}; saving sets it to {zone}.</p>
      ) : null}
      {night ? (
        <p className="x-policies-help is-below">
          Night of {night.night}: {night.reached} of {plural(night.total, 'phone')} reached{night.finished ? ', finished' : ', in progress'}.
        </p>
      ) : stored.automaticScans ? (
        <p className="x-policies-help is-below">The first sweep runs in the next window.</p>
      ) : null}
    </Card>
  );
}

function AutoRemoveCard({ fleet }: { fleet: Fleet }) {
  const auto = (fleet.policies ?? []).filter((p) => p.data.apps.mode === 'auto');
  const columns: Column<PolicyRow>[] = [
    { key: 'name', header: 'Policy', cell: (p) => <span className="x-policies-strong">{p.name}</span> },
    {
      key: 'phones',
      header: 'Phones',
      align: 'right',
      mono: true,
      cell: (p) => (fleet.devices ?? []).filter((d) => d.device.configurationId === p.id).length,
    },
    {
      key: 'state',
      header: 'Removing',
      cell: (p) =>
        enforcedList(p) ? (
          <Badge tone="ok" size="sm">
            After each scan
          </Badge>
        ) : (
          <span className="x-policies-warn">{p.kiosk ? 'No app is allowed: left alone' : 'Kiosk is off: nothing is removed'}</span>
        ),
    },
  ];
  return (
    <Card padding="none" clip>
      <div className="x-policies-cardhead">
        <CardHeader
          title="Auto-remove"
          subtitle="On a kiosk policy set to Auto-remove, the server compares each new app scan with the allowed apps and uninstalls the extra ones (user-installed apps only), as one paced job. It acts only on scans made after it started checking, never on older ones."
          level={3}
        />
      </div>
      <Table<PolicyRow>
        aria-label="Policies set to auto-remove"
        columns={columns}
        rows={auto}
        rowKey={(p) => p.id}
        edge={20}
        empty={
          <EmptyState icon={<IconDevices />} title="No policy is set to Auto-remove" bare>
            Set it on a policy&rsquo;s Allowed apps tab, under Extra apps.
          </EmptyState>
        }
      />
    </Card>
  );
}

interface HostRow {
  policy: PolicyRow;
  host: string;
  name: string;
  scannedAt?: number;
  fresh: number;
  reviewed: boolean;
}

function HostsCard({ fleet }: { fleet: Fleet }) {
  const rows = useMemo<HostRow[]>(() => {
    const out: HostRow[] = [];
    for (const p of fleet.policies ?? []) {
      if (!p.data.host) continue;
      const d = (fleet.devices ?? []).find((x) => x.device.number === p.data.host);
      const apps = d?.apps?.apps ?? [];
      const allowed = new Set((p.allowed ?? []).map((a) => a.pkg));
      out.push({
        policy: p,
        host: p.data.host,
        name: d?.device.description || p.data.host,
        scannedAt: d?.apps?.scannedAt,
        fresh: newOnHost(apps, allowed, p.data.hostSeen).length,
        reviewed: !!p.data.hostSeen,
      });
    }
    return out;
  }, [fleet.policies, fleet.devices]);

  const columns: Column<HostRow>[] = [
    { key: 'policy', header: 'Policy', cell: (r) => <span className="x-policies-strong">{r.policy.name}</span> },
    { key: 'host', header: 'Host phone', cell: (r) => r.name },
    {
      key: 'scan',
      header: 'Last scan',
      cell: (r) => (r.scannedAt ? <RelativeTime value={r.scannedAt} /> : <span className="x-policies-muted">Never</span>),
    },
    {
      key: 'new',
      header: 'New apps on host',
      cell: (r) =>
        !r.reviewed ? (
          <span className="x-policies-muted">Not reviewed yet</span>
        ) : r.fresh ? (
          <Link className="x-policies-devlink" to={`/policies?policy=${r.policy.id}&tab=apps`}>
            {plural(r.fresh, 'new app')}
          </Link>
        ) : (
          <span className="x-policies-muted">None</span>
        ),
    },
  ];
  return (
    <Card padding="none" clip>
      <div className="x-policies-cardhead">
        <CardHeader
          title="Host phones"
          subtitle="Each policy's host is scanned with the others. Apps that appear on it after a review are listed here and on the policy's Allowed apps tab; they stay blocked until you tick them."
          level={3}
        />
      </div>
      <Table<HostRow>
        aria-label="Host phones"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.policy.id}
        edge={20}
        empty={
          <EmptyState icon={<IconDevices />} title="No policy has a host phone" bare>
            Pick one on a policy&rsquo;s Allowed apps tab.
          </EmptyState>
        }
      />
    </Card>
  );
}
