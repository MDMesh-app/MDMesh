// A policy's Groups tab: the groups given this policy, adding more (a group has one policy, so it leaves its old one),
// taking one off, and Apply for the phones not on it yet.
import { useMemo, useState } from 'react';
import { Badge, Button, Card, CardHeader, Checkbox, EmptyState, IconDevices, Modal, Table, usePermission, useToast, type Column } from '@mdmesh/console';
import { moveDevices, setGroupsPolicy } from './api';
import type { Fleet } from './fleet';
import { offPolicy } from './groups';
import type { DeviceGroup } from './model';
import { BrandChips, errorText, plural } from './shared';

export function PolicyGroupsCard({
  fleet,
  policyId,
  policyName,
  readOnly,
  onManageGroups,
}: {
  fleet: Fleet;
  policyId: number;
  policyName: string;
  /** A built-in configuration: it can't be given groups. */
  readOnly: boolean;
  onManageGroups: () => void;
}) {
  const toast = useToast();
  const canMove = usePermission({ permission: 'edit_devices' });
  const [adding, setAdding] = useState(false);
  const [picked, setPicked] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const mine = useMemo(() => fleet.groups.filter((g) => g.policyId === policyId), [fleet.groups, policyId]);
  const others = fleet.groups.filter((g) => g.policyId !== policyId);
  const phonesOf = (g: DeviceGroup) => (fleet.devices ?? []).filter((d) => d.group?.id === g.id);
  const off = mine.flatMap((g) => offPolicy(g, fleet.devices ?? []));
  const nameOf = (id: number | null) => (id == null ? null : (fleet.policies?.find((p) => p.id === id)?.name ?? `#${id}`));

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(true);
    try {
      await fn();
      return true;
    } catch (e) {
      toast.push('err', `${label} failed`, errorText(e, 'Try again.'));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const columns: Column<DeviceGroup>[] = [
    {
      key: 'name',
      header: 'Group',
      cell: (g) => <span className="x-policies-strong">{g.name}</span>,
    },
    {
      key: 'covers',
      header: 'Covers',
      cell: (g) => (
        <span className="x-policies-cell">
          <BrandChips brands={g.brands} empty="No brands" />
          {g.devices.length ? <span className="x-policies-muted">+ {plural(g.devices.length, 'picked phone')}</span> : null}
        </span>
      ),
    },
    {
      key: 'phones',
      header: 'On this policy',
      cell: (g) => {
        const all = phonesOf(g);
        const n = all.length - offPolicy(g, all).length;
        return all.length ? (
          <Badge tone={n === all.length ? 'ok' : 'warn'}>{`${n} of ${all.length}`}</Badge>
        ) : (
          <span className="x-policies-muted">No phones</span>
        );
      },
    },
    {
      key: 'actions',
      header: 'Actions',
      headerHidden: true,
      shrink: true,
      cell: (g) =>
        readOnly || fleet.groupsAvailable !== true ? null : (
          <Button
            size="xs"
            disabled={busy}
            onClick={() =>
              void run('Taking the group off', async () => {
                fleet.applyGroups(await setGroupsPolicy(null, [g.id]));
                toast.push('ok', `${g.name} has no policy now`, 'No phone was moved.');
              })
            }
          >
            Take off
          </Button>
        ),
    },
  ];

  return (
    <Card padding="none" clip>
      <div className="x-policies-cardhead">
        <CardHeader
          title="Groups"
          subtitle="Groups whose phones should be on this policy. They change policy when you press Apply."
          level={3}
          actions={
            <span className="x-policies-inline">
              {canMove && off.length ? (
                <Button
                  size="sm"
                  variant="primary"
                  disabled={busy}
                  onClick={() =>
                    void run('Apply', async () => {
                      await moveDevices(
                        off.map((d) => d.device.id),
                        policyId,
                      );
                      toast.push('ok', 'Policy applied', `${plural(off.length, 'phone')} on ${policyName}`);
                      fleet.reload();
                    })
                  }
                >
                  Apply to {plural(off.length, 'phone')}
                </Button>
              ) : null}
              {!readOnly && fleet.groupsAvailable === true ? (
                <Button size="sm" disabled={busy || !others.length} onClick={() => (setPicked([]), setAdding(true))}>
                  Add groups…
                </Button>
              ) : null}
              <Button size="sm" onClick={onManageGroups}>
                Manage groups
              </Button>
            </span>
          }
        />
      </div>
      <Table<DeviceGroup>
        aria-label="Groups on this policy"
        columns={columns}
        rows={mine}
        rowKey={(g) => String(g.id)}
        edge={20}
        empty={<EmptyState icon={<IconDevices />} title={readOnly ? 'Built-in configurations take no groups' : 'No group has this policy yet'} bare />}
      />

      <Modal
        open={adding}
        onClose={() => setAdding(false)}
        size="sm"
        title={`Give groups to ${policyName}`}
        description="A group has one policy, so a group that has another one leaves it. No phone is moved until you press Apply."
        footer={
          <>
            <Button onClick={() => setAdding(false)}>Cancel</Button>
            <Button
              variant="primary"
              disabled={busy || !picked.length}
              onClick={async () => {
                const ok = await run('Giving the groups', async () => {
                  fleet.applyGroups(await setGroupsPolicy(policyId, picked));
                  toast.push('ok', `${plural(picked.length, 'group')} on ${policyName}`, 'Press Apply to move their phones.');
                });
                if (ok) setAdding(false);
              }}
            >
              Give {plural(picked.length, 'group')}
            </Button>
          </>
        }
      >
        <div className="x-policies-picker" role="group" aria-label="Groups">
          {others.map((g) => (
            <Checkbox
              key={g.id}
              checked={picked.includes(g.id)}
              onChange={(e) => setPicked((prev) => (e.target.checked ? [...prev, g.id] : prev.filter((x) => x !== g.id)))}
              label={
                <>
                  <span className="x-policies-strong">{g.name}</span>{' '}
                  <span className="x-policies-muted">
                    · {plural(phonesOf(g).length, 'phone')} · {nameOf(g.policyId) ? `now on ${nameOf(g.policyId)}` : 'no policy yet'}
                  </span>
                </>
              }
            />
          ))}
        </div>
      </Modal>
    </Card>
  );
}
