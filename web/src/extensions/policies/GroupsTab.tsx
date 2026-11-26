// Groups tab: device groups (whole brands plus phones picked by hand), the policy each one gets, and Apply, which
// puts a group's phones on its policy. Groups come first and policies are given to them, so a group may have none
// yet. Brands are listed below: what phones report, set by hand, or made here.
import { useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Callout,
  Card,
  CardHeader,
  Checkbox,
  EmptyState,
  Field,
  IconDevices,
  IconPencil,
  IconPlus,
  IconSliders,
  IconTrash,
  IconWarning,
  Input,
  Menu,
  Modal,
  SearchInput,
  Select,
  Table,
  useSearchParams,
  useToast,
  usePermission,
  type Column,
} from '@mdmesh/console';
import { createBrand, deleteBrand, deleteGroup, moveDevices, saveGroup, type GroupsState } from './api';
import { AssignModal } from './AssignForms';
import type { Fleet, FleetDevice } from './fleet';
import { brandSummary, groupableBrand, offPolicy, type BrandSummary } from './groups';
import { brandLabel, normalizeBrand, SUGGESTED_GROUPS, type DeviceGroup } from './model';
import { BrandChips, errorText, plural } from './shared';

interface Row {
  group: DeviceGroup;
  phones: FleetDevice[];
  /** Its phones not on its policy yet. */
  off: FleetDevice[];
}

interface Draft {
  id?: number;
  name: string;
  policyId: number | null;
  brands: string[];
  devices: number[];
}

/** The ids the Devices list sent (?assign=1,2,3), valid ones only. */
function assignedIds(raw: string | null): number[] {
  if (!raw) return [];
  return [
    ...new Set(
      raw
        .split(',')
        .map((s) => Number(s))
        .filter((n) => Number.isInteger(n) && n > 0),
    ),
  ].slice(0, 10000);
}

export function GroupsTab({ fleet, onActions }: { fleet: Fleet; onActions: (phones: FleetDevice[], scope: string) => void }) {
  const toast = useToast();
  const canMove = usePermission({ permission: 'edit_devices' });
  const [params, setParams] = useSearchParams();
  const { policies, devices, groups } = fleet;
  const [editing, setEditing] = useState<Draft | null>(null);
  const [applying, setApplying] = useState<Row | null>(null);
  const [deleting, setDeleting] = useState<DeviceGroup | null>(null);
  const [newBrand, setNewBrand] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const editable = useMemo(() => (policies ?? []).filter((p) => !p.builtIn), [policies]);
  const policyName = (id: number | null) => (id == null ? null : (policies?.find((p) => p.id === id)?.name ?? `#${id}`));

  const rows: Row[] = useMemo(
    () =>
      groups.map((group) => {
        const phones = (devices ?? []).filter((d) => d.group?.id === group.id);
        return { group, phones, off: offPolicy(group, phones) };
      }),
    [groups, devices],
  );
  const brands = useMemo(() => brandSummary(devices ?? [], groups, fleet.customBrands), [devices, groups, fleet.customBrands]);
  const ungrouped = brands.filter((b) => !b.group && b.count > 0 && groupableBrand(b.brand));

  // The Devices list's selection bar sends its phones here: ?assign=<ids>&do=group|brand.
  const assign = assignedIds(params.get('assign'));
  const assignMode = params.get('do') === 'brand' ? 'brand' : 'group';
  const closeAssign = () => {
    const p = new URLSearchParams(params);
    p.delete('assign');
    p.delete('do');
    setParams(p, { replace: true });
  };

  /** Run a change that answers with the new groups state. */
  async function change(run: () => Promise<GroupsState>, done: string, detail = ''): Promise<boolean> {
    setBusy(true);
    try {
      fleet.applyGroups(await run());
      toast.push('ok', done, detail);
      return true;
    } catch (e) {
      toast.push('err', 'Could not save', errorText(e, 'Try again.'));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function addSuggested() {
    const present = new Set(ungrouped.map((b) => b.brand));
    const names = new Set(groups.map((g) => g.name.toLowerCase()));
    const todo = SUGGESTED_GROUPS.map((s) => ({
      name: s.name,
      brands: s.brands.filter((b) => present.has(b)),
    })).filter((s) => s.brands.length && !names.has(s.name.toLowerCase()));
    if (!todo.length) {
      toast.push('warn', 'Nothing to suggest', 'Every brand with phones is already in a group.');
      return;
    }
    await change(
      async () => {
        let state: GroupsState | null = null;
        for (const s of todo)
          state = await saveGroup({
            name: s.name,
            configurationId: null,
            brands: s.brands,
            devices: [],
          });
        return state as GroupsState;
      },
      'Suggested groups added',
      `${plural(todo.length, 'group')}, no policy yet`,
    );
  }

  const columns: Column<Row>[] = [
    {
      key: 'name',
      header: 'Group',
      cell: (r) => <span className="x-policies-strong">{r.group.name}</span>,
    },
    {
      key: 'covers',
      header: 'Covers',
      cell: (r) => (
        <span className="x-policies-cell">
          <BrandChips brands={r.group.brands} empty="No brands" />
          {r.group.devices.length ? <span className="x-policies-muted">+ {plural(r.group.devices.length, 'picked phone')}</span> : null}
        </span>
      ),
    },
    {
      key: 'phones',
      header: 'Phones',
      mono: true,
      align: 'right',
      cell: (r) => r.phones.length,
    },
    {
      key: 'policy',
      header: 'Policy',
      cell: (r) => policyName(r.group.policyId) ?? <Badge tone="warn">No policy yet</Badge>,
    },
    {
      key: 'onPolicy',
      header: 'On its policy',
      cell: (r) =>
        r.group.policyId == null || r.phones.length === 0 ? (
          <span className="x-policies-muted">—</span>
        ) : r.off.length === 0 ? (
          <Badge tone="ok">All {r.phones.length}</Badge>
        ) : (
          <span className="x-policies-inline">
            <Badge tone="warn">
              {r.phones.length - r.off.length} of {r.phones.length}
            </Badge>
            {canMove ? (
              <Button size="xs" variant="primary" onClick={() => setApplying(r)}>
                Apply
              </Button>
            ) : null}
          </span>
        ),
    },
    {
      key: 'actions',
      header: 'Actions',
      headerHidden: true,
      shrink: true,
      cell: (r) => (
        <Menu
          label={`More for ${r.group.name}`}
          iconSize="sm"
          items={[
            {
              key: 'edit',
              label: 'Edit group…',
              textValue: 'Edit group',
              icon: <IconPencil />,
              onSelect: () =>
                setEditing({
                  id: r.group.id,
                  name: r.group.name,
                  policyId: r.group.policyId,
                  brands: r.group.brands,
                  devices: r.group.devices,
                }),
            },
            ...(canMove && r.off.length
              ? [
                  {
                    key: 'apply',
                    label: 'Apply its policy…',
                    textValue: 'Apply its policy',
                    icon: <IconDevices />,
                    onSelect: () => setApplying(r),
                  },
                ]
              : []),
            ...(canMove
              ? [
                  {
                    key: 'actions',
                    label: 'Client actions…',
                    textValue: 'Client actions',
                    icon: <IconSliders />,
                    disabled: !r.phones.length,
                    onSelect: () => onActions(r.phones, r.group.name),
                  },
                ]
              : []),
            'separator' as const,
            {
              key: 'delete',
              label: 'Delete group…',
              textValue: 'Delete group',
              icon: <IconTrash />,
              danger: true,
              onSelect: () => setDeleting(r.group),
            },
          ]}
        />
      ),
    },
  ];

  const brandColumns: Column<BrandSummary>[] = [
    {
      key: 'brand',
      header: 'Brand',
      cell: (b) => (
        <span className="x-policies-inline">
          <span className="x-policies-strong">{brandLabel(b.brand)}</span>
          {b.custom ? <Badge>Made here</Badge> : null}
        </span>
      ),
    },
    {
      key: 'phones',
      header: 'Phones',
      mono: true,
      align: 'right',
      cell: (b) => b.count,
    },
    {
      key: 'group',
      header: 'Group',
      cell: (b) =>
        b.group ? (
          b.group.name
        ) : groupableBrand(b.brand) ? (
          <Badge tone="warn">No group</Badge>
        ) : (
          <span className="x-policies-muted">Set a brand on these phones</span>
        ),
    },
    {
      key: 'policy',
      header: 'Policy',
      cell: (b) =>
        b.group ? (policyName(b.group.policyId) ?? <span className="x-policies-muted">None yet</span>) : <span className="x-policies-muted">—</span>,
    },
    {
      key: 'actions',
      header: 'Actions',
      headerHidden: true,
      shrink: true,
      cell: (b) =>
        b.custom && b.count === 0 && !b.group ? (
          <Button size="xs" disabled={busy} onClick={() => void change(() => deleteBrand(b.brand), 'Brand deleted', brandLabel(b.brand))}>
            Delete
          </Button>
        ) : null,
    },
  ];

  if (fleet.groupsAvailable === false) {
    return (
      <Callout tone="warn" icon={<IconWarning size={16} />}>
        Groups need the Policies server plugin, and it isn&rsquo;t answering. Check that it is installed and on for this account.
      </Callout>
    );
  }

  return (
    <div className="x-policies-stack">
      <div className="x-policies-toolbar">
        <p className="x-policies-help">Enroll phones, put them in groups (by brand, or picked one by one), then give each group a policy and Apply.</p>
        <span className="x-policies-toolbar__spacer" />
        <span className="x-policies-inline">
          {ungrouped.length ? (
            <Button disabled={busy || !devices} onClick={() => void addSuggested()}>
              Add suggested groups
            </Button>
          ) : null}
          <Button
            variant="primary"
            icon={<IconPlus />}
            disabled={busy || !devices}
            onClick={() => setEditing({ name: '', policyId: null, brands: [], devices: [] })}
          >
            New group
          </Button>
        </span>
      </div>

      <Card padding="none" clip>
        <div className="x-policies-cardhead">
          <CardHeader title="Groups" subtitle="A group's phones should be on its policy. A brand, and a picked phone, belong to one group." level={3} />
        </div>
        <Table<Row>
          aria-label="Device groups"
          columns={columns}
          rows={rows}
          rowKey={(r) => String(r.group.id)}
          edge={20}
          empty={
            <EmptyState icon={<IconDevices />} title={devices ? 'No groups yet' : 'Loading…'} bare>
              {devices ? 'Make a group of brands or of picked phones, or add the suggested ones (by phone family).' : null}
            </EmptyState>
          }
        />
      </Card>

      <Card padding="none" clip>
        <div className="x-policies-cardhead">
          <CardHeader
            title="Brands"
            subtitle={
              ungrouped.length
                ? `${plural(ungrouped.length, 'brand')} with phones in no group. Phones of a brand added later join its group by themselves.`
                : 'Phones join their brand’s group by themselves, new ones too. Set a brand on phones from the Devices list or Client actions.'
            }
            level={3}
            actions={
              <Button size="sm" icon={<IconPlus />} disabled={busy} onClick={() => setNewBrand('')}>
                New brand
              </Button>
            }
          />
        </div>
        <Table<BrandSummary>
          aria-label="Brands"
          columns={brandColumns}
          rows={brands}
          rowKey={(b) => b.brand}
          edge={20}
          empty={<EmptyState icon={<IconDevices />} title={devices ? 'No phones yet' : 'Loading…'} bare />}
        />
      </Card>

      {editing ? (
        <GroupModal
          draft={editing}
          fleet={fleet}
          brands={brands}
          policies={editable.map((p) => ({ id: p.id, name: p.name }))}
          busy={busy}
          onClose={() => setEditing(null)}
          onSave={async (d) => {
            const ok = await change(
              () =>
                saveGroup(
                  {
                    name: d.name,
                    configurationId: d.policyId,
                    brands: d.brands,
                    devices: d.devices,
                  },
                  d.id,
                ),
              d.id ? 'Group saved' : 'Group created',
              d.name,
            );
            if (ok) setEditing(null);
          }}
        />
      ) : null}

      <Modal
        open={newBrand !== null}
        onClose={() => setNewBrand(null)}
        size="sm"
        title="New brand"
        description="For phones whose brand isn't reported or is wrong. Set it on them from the Devices list (Set brand), then a group can cover it."
        footer={
          <>
            <Button onClick={() => setNewBrand(null)}>Cancel</Button>
            <Button
              variant="primary"
              disabled={busy || !newBrand?.trim() || !groupableBrand(normalizeBrand(newBrand))}
              onClick={async () => {
                if (newBrand && (await change(() => createBrand(newBrand.trim()), 'Brand created', newBrand.trim()))) setNewBrand(null);
              }}
            >
              Create brand
            </Button>
          </>
        }
      >
        <Field label="Name">
          <Input value={newBrand ?? ''} onChange={(e) => setNewBrand(e.target.value)} maxLength={40} placeholder="e.g. Nothing" autoFocus />
        </Field>
      </Modal>

      <Modal
        open={!!applying}
        onClose={() => setApplying(null)}
        title={applying ? `Put ${applying.group.name} on ${policyName(applying.group.policyId)}?` : ''}
        description="Each phone switches to that policy and applies it at its next sync: its kiosk, apps and restrictions change to the policy's. Phones already on it are left as they are."
        footer={
          <>
            <Button onClick={() => setApplying(null)}>Cancel</Button>
            <Button
              variant="primary"
              disabled={busy || !applying?.off.length}
              onClick={async () => {
                const r = applying;
                if (!r || r.group.policyId == null) return;
                setBusy(true);
                try {
                  await moveDevices(
                    r.off.map((d) => d.device.id),
                    r.group.policyId,
                  );
                  toast.push('ok', 'Policy applied', `${plural(r.off.length, 'phone')} on ${policyName(r.group.policyId)}`);
                  setApplying(null);
                  fleet.reload();
                } catch (e) {
                  toast.push('err', 'Could not move the phones', errorText(e, 'Try again.'));
                } finally {
                  setBusy(false);
                }
              }}
            >
              Apply to {applying ? plural(applying.off.length, 'phone') : ''}
            </Button>
          </>
        }
      >
        {applying ? (
          <ul className="x-policies-list">
            {applying.off.map((d) => (
              <li key={d.device.id}>
                <span className="x-policies-strong">{d.device.description || d.device.number}</span>
                <span className="x-policies-muted">from {d.currentPolicy?.name ?? 'no policy'}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </Modal>

      <Modal
        open={!!deleting}
        onClose={() => setDeleting(null)}
        role="alertdialog"
        title={deleting ? `Delete ${deleting.name}?` : ''}
        description="Its brands and picked phones leave it, so its phones show as in no group. No phone is moved and no setting changes on a phone."
        footer={
          <>
            <Button onClick={() => setDeleting(null)}>Cancel</Button>
            <Button
              variant="danger"
              disabled={busy}
              onClick={async () => {
                if (deleting && (await change(() => deleteGroup(deleting.id), 'Group deleted', deleting.name))) setDeleting(null);
              }}
            >
              Delete group
            </Button>
          </>
        }
      />

      {assign.length ? <AssignModal mode={assignMode} ids={assign} fleet={fleet} onClose={closeAssign} /> : null}
    </div>
  );
}

function GroupModal({
  draft,
  fleet,
  brands,
  policies,
  busy,
  onClose,
  onSave,
}: {
  draft: Draft;
  fleet: Fleet;
  brands: BrandSummary[];
  policies: { id: number; name: string }[];
  busy: boolean;
  onClose: () => void;
  onSave: (d: Draft) => void;
}) {
  const [name, setName] = useState(draft.name);
  const [policyId, setPolicyId] = useState<number | null>(draft.policyId);
  const [picked, setPicked] = useState<string[]>(draft.brands);
  const [phones, setPhones] = useState<Set<number>>(() => new Set(draft.devices));
  const [extra, setExtra] = useState('');
  const [q, setQ] = useState('');
  const all = fleet.devices ?? [];

  const list = useMemo(() => {
    const seen = new Set(brands.map((b) => b.brand));
    const extras = picked.filter((b) => !seen.has(b)).map((brand) => ({ brand, count: 0, custom: false }) as BrandSummary);
    return [...brands.filter((b) => groupableBrand(b.brand)), ...extras];
  }, [brands, picked]);
  const otherGroup = (g?: DeviceGroup) => (g && g.id !== draft.id ? g : undefined);
  const brandsLeaving = picked.filter((b) => otherGroup(list.find((x) => x.brand === b)?.group));

  /** Whether a phone ends up in this group: picked here, or its brand is covered and it isn't picked elsewhere. */
  const member = (d: FleetDevice) => phones.has(d.device.id) || (picked.includes(d.brand) && !(d.groupByHand && otherGroup(d.group)));
  const byBrand = all.filter((d) => !phones.has(d.device.id) && member(d)).length;
  const phonesLeaving = all.filter((d) => phones.has(d.device.id) && d.groupByHand && otherGroup(d.group)).length;

  const needle = q.trim().toLowerCase();
  const shown = all.filter(
    (d) =>
      !needle ||
      (d.device.description ?? '').toLowerCase().includes(needle) ||
      d.device.number.toLowerCase().includes(needle) ||
      brandLabel(d.brand).toLowerCase().includes(needle),
  );

  const toggleBrand = (b: string, on: boolean) => setPicked((prev) => (on ? [...prev, b] : prev.filter((x) => x !== b)));
  const togglePhone = (id: number, on: boolean) =>
    setPhones((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  const addExtra = () => {
    const b = normalizeBrand(extra);
    if (groupableBrand(b) && !picked.includes(b)) setPicked((prev) => [...prev, b]);
    setExtra('');
  };
  const nameOk = !!name.trim() && name.trim().length <= 100;

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={draft.id ? `Edit ${draft.name}` : 'New group'}
      description="A group covers whole brands (new phones of them join it by themselves) and phones you pick one by one."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || !nameOk}
            onClick={() =>
              onSave({
                id: draft.id,
                name: name.trim(),
                policyId,
                brands: picked,
                devices: [...phones],
              })
            }
          >
            {draft.id ? 'Save group' : 'Create group'}
          </Button>
        </>
      }
    >
      <div className="x-policies-stack">
        <div className="x-policies-addrow">
          <Field label="Name">
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="e.g. Oppo family" autoFocus={!draft.id} />
          </Field>
          <Field label="Policy">
            <Select value={policyId == null ? '' : String(policyId)} onChange={(e) => setPolicyId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">No policy yet</option>
              {policies.map((p) => (
                <option key={p.id} value={String(p.id)}>
                  {p.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <p className="x-policies-help">
          {policyId == null
            ? 'Give it a policy now or later. Phones change policy only when you press Apply.'
            : 'Its phones should be on this policy. They change policy only when you press Apply.'}
        </p>

        <fieldset className="x-policies-fieldset">
          <legend>Brands</legend>
          <div className="x-policies-checks">
            {list.map((b) => {
              const other = otherGroup(b.group);
              return (
                <Checkbox
                  key={b.brand}
                  checked={picked.includes(b.brand)}
                  onChange={(e) => toggleBrand(b.brand, e.target.checked)}
                  label={
                    <>
                      {brandLabel(b.brand)}{' '}
                      <span className="x-policies-muted">
                        · {plural(b.count, 'phone')}
                        {other ? ` · in ${other.name}` : ''}
                      </span>
                    </>
                  }
                />
              );
            })}
          </div>
          <div className="x-policies-addrow">
            <Input
              value={extra}
              onChange={(e) => setExtra(e.target.value)}
              placeholder="Another brand (e.g. nothing)"
              aria-label="Another brand"
              onKeyDown={(e) => e.key === 'Enter' && addExtra()}
            />
            <Button size="sm" disabled={!extra.trim()} onClick={addExtra}>
              Add brand
            </Button>
          </div>
          {brandsLeaving.length ? (
            <p className="x-policies-help">
              {brandsLeaving.map(brandLabel).join(', ')} {brandsLeaving.length === 1 ? 'leaves its' : 'leave their'} current group.
            </p>
          ) : null}
        </fieldset>

        <fieldset className="x-policies-fieldset">
          <legend>Phones picked one by one</legend>
          <SearchInput value={q} onChange={setQ} placeholder="Search phones by name, number or brand" aria-label="Search phones" />
          <div className="x-policies-picker" role="group" aria-label="Phones">
            {shown.length ? (
              shown.map((d) => {
                const other = otherGroup(d.group);
                const viaBrand = !phones.has(d.device.id) && member(d);
                return (
                  <Checkbox
                    key={d.device.id}
                    checked={phones.has(d.device.id)}
                    onChange={(e) => togglePhone(d.device.id, e.target.checked)}
                    label={
                      <>
                        <span className="x-policies-strong">{d.device.description || d.device.number}</span>{' '}
                        <span className="x-policies-muted">
                          · {brandLabel(d.brand)}
                          {viaBrand ? ' · in by its brand' : other ? ` · in ${other.name}` : ''}
                        </span>
                      </>
                    }
                  />
                );
              })
            ) : (
              <p className="x-policies-help">{all.length ? 'No phone matches.' : 'No phones yet.'}</p>
            )}
          </div>
          {phonesLeaving ? (
            <p className="x-policies-help">
              {plural(phonesLeaving, 'picked phone')} leave
              {phonesLeaving === 1 ? 's' : ''} another group.
            </p>
          ) : null}
        </fieldset>

        <p className="x-policies-help x-policies-strong">
          {plural(byBrand + phones.size, 'phone')} in this group: {byBrand} by brand, {phones.size} picked.
        </p>
      </div>
    </Modal>
  );
}
