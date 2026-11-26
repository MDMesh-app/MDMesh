// Status: every phone and whether it follows its group's policy, as an endpoint-security console's status page has
// it. A group tree on the left picks the phones shown; the table's selection (or, with none, every phone shown) is
// what the Actions menu acts on. A policy's Phones tab uses the same table without the tree.
import { useEffect, useMemo, useState, type Key } from 'react';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Eyebrow,
  IconApps,
  IconDevices,
  IconSearch,
  IconSliders,
  IconSync,
  IconTrash,
  LinkButton,
  Link,
  Menu,
  Modal,
  RelativeTime,
  SearchInput,
  Segmented,
  Table,
  paths,
  usePermission,
  useToast,
  type Column,
  type MenuEntry,
} from '@mdmesh/console';
import { moveDevices, updatePolicyData } from './api';
import { runBulk } from './bulk';
import type { Fleet, FleetDevice } from './fleet';
import { brandLabel, removable, type Verdict } from './model';
import { ClientActions } from './ClientActions';
import { removeApps } from './removal';
import { errorText, plural, SyncPill, VerdictPill } from './shared';

type Show = 'all' | Verdict;
/** The group tree's choice: every phone, phones in no group, or one group's id. */
type Scope = 'all' | 'none' | string;

const ORDER: Record<Verdict, number> = { attention: 0, unknown: 1, compliant: 2 };

const mayRemoveOn = (d: FleetDevice) => {
  const mode = d.currentPolicy?.data.apps.mode;
  return (mode === 'remove' || mode === 'auto') && removable(d.violations ?? []).length > 0;
};
const misplaced = (d: FleetDevice) => !!d.expectedPolicy && d.device.configurationId !== d.expectedPolicy.id;

/** Every phone (the Status view), or `policyId`'s phones only (a policy's Phones tab, without the group tree). */
export function StatusView({ fleet, policyId }: { fleet: Fleet; policyId?: number }) {
  const toast = useToast();
  const canAct = usePermission({ permission: 'edit_devices' });
  const tree = policyId == null;
  const [scope, setScope] = useState<Scope>('all');
  const [show, setShow] = useState<Show>('all');
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<ReadonlySet<Key>>(new Set());
  const [open, setOpen] = useState<FleetDevice | null>(null);
  const [acting, setActing] = useState<{ phones: FleetDevice[]; scope: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const all = useMemo(
    () => (policyId == null ? (fleet.devices ?? null) : (fleet.devices?.filter((d) => d.device.configurationId === policyId) ?? null)),
    [fleet.devices, policyId],
  );
  const inScope = useMemo(
    () => (all ?? []).filter((d) => (scope === 'all' ? true : scope === 'none' ? !d.group : String(d.group?.id) === scope)),
    [all, scope],
  );
  const counts = useMemo(
    () => ({
      all: inScope.length,
      attention: inScope.filter((d) => d.compliance.verdict === 'attention').length,
      compliant: inScope.filter((d) => d.compliance.verdict === 'compliant').length,
      unknown: inScope.filter((d) => d.compliance.verdict === 'unknown').length,
    }),
    [inScope],
  );
  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return inScope
      .filter((d) => show === 'all' || d.compliance.verdict === show)
      .filter((d) => !needle || `${d.device.number} ${d.device.description ?? ''} ${brandLabel(d.brand)}`.toLowerCase().includes(needle))
      .sort((a, b) => ORDER[a.compliance.verdict] - ORDER[b.compliance.verdict] || (a.device.description ?? '').localeCompare(b.device.description ?? ''));
  }, [inScope, show, q]);

  // A selection holds only phones still shown: changing the group, filter or search drops the others.
  useEffect(() => {
    setSelected((s) => {
      const ids = new Set(shown.map((d) => d.device.id));
      const next = new Set([...s].filter((k) => ids.has(k as number)));
      return next.size === s.size ? s : next;
    });
  }, [shown]);

  /** What an action acts on: the selection, or every phone shown when nothing is selected. */
  const targets = selected.size ? shown.filter((d) => selected.has(d.device.id)) : shown;
  const scopeText = selected.size ? `${selected.size} selected` : `all ${shown.length} shown`;
  /** The group tree's choice in words, for the Client actions title. */
  const scopeName =
    policyId != null
      ? (fleet.policies?.find((p) => p.id === policyId)?.name ?? 'this policy')
      : scope === 'all'
        ? 'All phones'
        : scope === 'none'
          ? 'No group'
          : (fleet.groups.find((g) => String(g.id) === scope)?.name ?? 'Group');

  async function run(label: string, fn: () => Promise<string>, ids: number[]) {
    setBusy(true);
    try {
      const text = await fn();
      toast.push('ok', label, text);
      fleet.refreshDevices(ids);
    } catch (e) {
      toast.push('err', `${label} failed`, errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  const ids = (list: FleetDevice[]) => list.map((d) => d.device.id);

  /** What a menu action acts on, for the job's label. */
  const labelFor = (list: FleetDevice[]) =>
    list.length === 1 ? list[0].device.description || list[0].device.number : `${plural(list.length, 'phone')} · ${scopeName}`;

  const reapply = (list: FleetDevice[]) =>
    void run('Policy reapplied', () => runBulk(fleet, list, { kind: 'reapply' }, `Reapply policy · ${labelFor(list)}`), ids(list));

  const scan = (list: FleetDevice[]) =>
    void run('App scan queued', () => runBulk(fleet, list, { kind: 'scan' }, `Scan apps · ${labelFor(list)}`), ids(list));

  const moveToGroupPolicy = (list: FleetDevice[]) =>
    void run(
      'Phones moved',
      async () => {
        const byPolicy = new Map<number, number[]>();
        for (const d of list.filter(misplaced)) byPolicy.set(d.expectedPolicy!.id, [...(byPolicy.get(d.expectedPolicy!.id) ?? []), d.device.id]);
        for (const [pid, devs] of byPolicy) await moveDevices(devs, pid);
        fleet.reload();
        return `${plural(list.filter(misplaced).length, 'phone')} moved to their group's policy.`;
      },
      ids(list),
    );

  const remove = (list: FleetDevice[]) =>
    void run(
      'Removal queued',
      () => runBulk(fleet, list.filter(mayRemoveOn), { kind: 'remove' }, `Remove extra apps · ${labelFor(list)}`),
      ids(list),
    );

  /** The Actions menu for `list` (a row's phone, or the selection). */
  const actions = (list: FleetDevice[]): MenuEntry[] => {
    const items: MenuEntry[] = [
      { key: 'reapply', label: 'Reapply policy', icon: <IconSync />, disabled: !list.length, onSelect: () => reapply(list) },
      { key: 'scan', label: 'Scan apps', icon: <IconApps />, disabled: !list.length, onSelect: () => scan(list) },
      'separator',
      {
        key: 'move',
        label: "Move to group's policy",
        icon: <IconDevices />,
        disabled: !list.some(misplaced),
        onSelect: () => moveToGroupPolicy(list),
      },
    ];
    if (list.some(mayRemoveOn)) {
      items.push({ key: 'remove', label: 'Remove extra apps', icon: <IconTrash />, danger: true, onSelect: () => remove(list) });
    }
    items.push('separator', {
      key: 'all',
      label: 'All actions…',
      textValue: 'All actions',
      icon: <IconSliders />,
      onSelect: () => setActing({ phones: list, scope: list.length === 1 ? list[0].device.description || list[0].device.number : plural(list.length, 'phone') }),
    });
    return items;
  };

  const columns: Column<FleetDevice>[] = [
    {
      key: 'device',
      header: 'Phone',
      cell: (d) => (
        <span className="x-policies-cell">
          <Link to={paths.device(d.device.number)} className="x-policies-devlink">
            {d.device.description || d.device.number}
          </Link>
          <span className="x-policies-muted">
            <span className="x-policies-mono">{d.device.number}</span> · {brandLabel(d.brand)}
            {d.brandByHand ? ' (set by hand)' : d.brandGuessed ? ' (from name)' : ''}
          </span>
          {tree ? <span className="x-policies-muted">{d.group ? `${d.group.name}${d.groupByHand ? ' (picked)' : ''}` : 'No group'}</span> : null}
        </span>
      ),
    },
    {
      key: 'policy',
      header: 'Policy',
      cell: (d) => (
        <span className="x-policies-cell">
          <span>{d.currentPolicy?.name ?? 'None'}</span>
          {misplaced(d) ? <span className="x-policies-warn">Should be {d.expectedPolicy!.name}</span> : null}
        </span>
      ),
    },
    { key: 'sync', header: 'Policy status', cell: (d) => (d.loaded ? <SyncPill state={d.sync} /> : <span className="x-policies-muted">…</span>) },
    {
      key: 'seen',
      header: 'Last connected',
      cell: (d) => (d.device.lastUpdate ? <RelativeTime value={d.device.lastUpdate} /> : <span className="x-policies-muted">Never</span>),
    },
    {
      key: 'apps',
      header: 'Extra apps',
      cell: (d) =>
        d.violations === null ? (
          <span className="x-policies-muted">{d.apps?.scanPending ? 'Scan queued' : 'Not scanned'}</span>
        ) : d.violations.length === 0 ? (
          <span className="x-policies-muted">None</span>
        ) : (
          <Button size="xs" variant="danger" onClick={() => setOpen(d)}>
            {plural(d.violations.length, 'app')}
          </Button>
        ),
    },
    {
      key: 'verdict',
      header: 'Status',
      cell: (d) => (
        <span className="x-policies-cell" title={d.compliance.reasons.join('\n') || undefined}>
          <VerdictPill verdict={d.compliance.verdict} />
          {d.compliance.reasons[0] ? (
            <span className="x-policies-muted">
              {d.compliance.reasons[0]}
              {d.compliance.reasons.length > 1 ? ` +${d.compliance.reasons.length - 1}` : ''}
            </span>
          ) : null}
        </span>
      ),
    },
    ...(canAct
      ? [
          {
            key: 'actions',
            header: 'Actions',
            headerHidden: true,
            shrink: true,
            cell: (d: FleetDevice) => (
              <Menu label={`Actions for ${d.device.description || d.device.number}`} iconSize="sm" items={actions([d])} />
            ),
          },
        ]
      : []),
  ];

  const table = (
    <div className="x-policies-main">
      <div className="x-policies-toolbar">
        <SearchInput className="x-policies-toolbar__search" value={q} onChange={setQ} placeholder="Search phones" aria-label="Search phones" />
        <Segmented<Show>
          aria-label="Show"
          value={show}
          onChange={setShow}
          options={[
            { value: 'all', label: 'All', count: counts.all },
            { value: 'attention', label: 'Needs attention', count: counts.attention },
            { value: 'compliant', label: 'Compliant', count: counts.compliant },
            { value: 'unknown', label: 'Checking', count: counts.unknown },
          ]}
        />
        <span className="x-policies-toolbar__spacer" />
        {canAct ? (
          <span className="x-policies-inline">
            {selected.size ? (
              <LinkButton tone="quiet" onClick={() => setSelected(new Set())}>
                Clear selection
              </LinkButton>
            ) : null}
            <Button
              variant={selected.size ? 'primary' : 'secondary'}
              icon={<IconSliders />}
              disabled={busy || !shown.length}
              onClick={() => setActing({ phones: targets, scope: selected.size ? `${selected.size} selected` : scopeName })}
            >
              Client actions · {scopeText}
            </Button>
          </span>
        ) : null}
      </div>

      <Card padding="none" clip>
        <Table<FleetDevice>
          aria-label="Phone status"
          columns={columns}
          rows={shown}
          rowKey={(d) => d.device.id}
          minWidth={820}
          selection={
            canAct
              ? {
                  selected,
                  onToggle: (key) =>
                    setSelected((s) => {
                      const n = new Set(s);
                      if (n.has(key)) n.delete(key);
                      else n.add(key);
                      return n;
                    }),
                  onToggleAll: (on) => setSelected(on ? new Set(shown.map((d) => d.device.id)) : new Set()),
                  rowLabel: (d) => d.device.description || d.device.number,
                  allLabel: 'Select every phone shown',
                }
              : undefined
          }
          empty={
            all === null ? (
              <EmptyState icon={<IconDevices />} title="Loading phones…" bare />
            ) : (
              <EmptyState icon={<IconSearch />} title="No phones match" bare>
                Try another group, filter or search.
              </EmptyState>
            )
          }
        />
      </Card>
    </div>
  );

  return (
    <>
      {tree ? (
        <div className="x-policies-cols">
          <GroupTree fleet={fleet} scope={scope} onScope={setScope} />
          {table}
        </div>
      ) : (
        table
      )}

      <ViolationsModal
        device={open}
        canAct={canAct}
        busy={busy}
        onClose={() => setOpen(null)}
        onKeep={(d, pkg) =>
          void run(
            'Exception added',
            async () => {
              const policy = d.currentPolicy;
              if (!policy) throw new Error('This phone is on no policy.');
              await updatePolicyData(policy.id, (data) => ({
                ...data,
                apps: {
                  ...data.apps,
                  exceptions: data.apps.exceptions.some((e) => e.device === d.device.number && e.pkg === pkg)
                    ? data.apps.exceptions
                    : [...data.apps.exceptions, { pkg, device: d.device.number }],
                },
              }));
              await fleet.reloadPolicies();
              setOpen(null);
              return `This phone may keep ${pkg}. It still can't be opened while kiosk is on unless it is allowed.`;
            },
            [d.device.id],
          )
        }
        onRemove={(d, pkgs) =>
          void run(
            'Removal queued',
            async () => {
              const n = await removeApps(d, pkgs);
              setOpen(null);
              return `${plural(n, 'app')} will be uninstalled at the phone's next sync.`;
            },
            [d.device.id],
          )
        }
      />

      {acting ? <ClientActions phones={acting.phones} scope={acting.scope} fleet={fleet} onClose={() => setActing(null)} /> : null}
    </>
  );
}

/** The group tree: every phone, each group with its policy, and phones in no group. */
function GroupTree({ fleet, scope, onScope }: { fleet: Fleet; scope: Scope; onScope: (s: Scope) => void }) {
  const devices = fleet.devices ?? [];
  const policyName = (id: number) => fleet.policies?.find((p) => p.id === id)?.name ?? '—';
  const node = (key: Scope, name: string, meta: string, list: FleetDevice[]) => {
    const attention = list.filter((d) => d.compliance.verdict === 'attention').length;
    const on = scope === key;
    return (
      <li key={key}>
        <button type="button" className={`x-policies-railitem${on ? ' is-selected' : ''}`} aria-current={on ? 'true' : undefined} onClick={() => onScope(key)}>
          <span className="x-policies-railitem__name">{name}</span>
          <span className="x-policies-railitem__meta">{meta}</span>
          {attention ? (
            <Badge tone="warn" size="sm" className="x-policies-railitem__badge" title={`${attention} need attention`}>
              {attention}
            </Badge>
          ) : null}
        </button>
      </li>
    );
  };
  const ungrouped = devices.filter((d) => !d.group);
  return (
    <Card as="aside" padding="rail" className="x-policies-rail x-policies-rail--narrow" aria-label="Groups">
      <div className="x-policies-railgroup">
        <Eyebrow as="h3">Phones</Eyebrow>
        <ul className="x-policies-raillist">{node('all', 'All phones', plural(devices.length, 'phone'), devices)}</ul>
      </div>
      <div className="x-policies-railgroup">
        <Eyebrow as="h3">Groups</Eyebrow>
        <ul className="x-policies-raillist">
          {fleet.groups.map((g) => {
            const list = devices.filter((d) => d.group?.id === g.id);
            return node(String(g.id), g.name, `${plural(list.length, 'phone')} · ${g.policyId == null ? 'no policy yet' : policyName(g.policyId)}`, list);
          })}
          {node('none', 'No group', plural(ungrouped.length, 'phone'), ungrouped)}
        </ul>
      </div>
    </Card>
  );
}

function ViolationsModal({
  device,
  canAct,
  busy,
  onClose,
  onKeep,
  onRemove,
}: {
  device: FleetDevice | null;
  canAct: boolean;
  busy: boolean;
  onClose: () => void;
  onKeep: (d: FleetDevice, pkg: string) => void;
  onRemove: (d: FleetDevice, pkgs?: string[]) => void;
}) {
  const v = device?.violations ?? [];
  const mode = device?.currentPolicy?.data.apps.mode;
  const mayRemove = canAct && (mode === 'remove' || mode === 'auto');
  const n = removable(v).length;
  return (
    <Modal
      open={!!device}
      onClose={onClose}
      size="md"
      title={device ? `Extra apps on ${device.device.description || device.device.number}` : ''}
      description={
        device?.apps?.scannedAt ? (
          <>
            Installed apps outside the allowed list of {device.currentPolicy?.name}, from the app scan{' '}
            <RelativeTime value={device.apps.scannedAt} style="long" />. Kiosk already stops them opening.
          </>
        ) : undefined
      }
      footer={
        <>
          <Button onClick={onClose}>Close</Button>
          {mayRemove && device && n > 0 ? (
            <Button variant="danger" icon={<IconTrash />} disabled={busy} onClick={() => onRemove(device)}>
              Remove {plural(n, 'app')}
            </Button>
          ) : null}
        </>
      }
    >
      <ul className="x-policies-list">
        {v.map((x) => (
          <li key={x.pkg}>
            <span className="x-policies-cell">
              <span className="x-policies-strong">{x.label}</span>
              <span className="x-policies-muted x-policies-mono">{x.pkg}</span>
            </span>
            {x.removing ? (
              <Badge tone="idle">Removing</Badge>
            ) : canAct && device ? (
              <span className="x-policies-inline">
                <Button size="xs" disabled={busy} onClick={() => onKeep(device, x.pkg)}>
                  Let it keep
                </Button>
                {mayRemove ? (
                  <Button size="xs" variant="danger" disabled={busy} onClick={() => onRemove(device, [x.pkg])}>
                    Remove
                  </Button>
                ) : null}
              </span>
            ) : null}
          </li>
        ))}
      </ul>
      {mode === 'report' ? (
        <p className="x-policies-help">This policy only reports extra apps. Set it to one-click or auto-remove to remove them.</p>
      ) : null}
    </Modal>
  );
}
