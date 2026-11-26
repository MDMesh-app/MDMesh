// "New policy": a name, a policy to start from (copied, so it starts with working settings), and the groups it gets.
// No phone moves when it's made: its groups' phones go to it when someone presses Apply.
import { useState } from 'react';
import { Button, Checkbox, Field, Input, Link, Modal, Select, paths, useToast } from '@mdmesh/console';
import { copyConfig, setGroupsPolicy } from './api';
import type { Fleet } from './fleet';
import { errorText, plural } from './shared';

export function NewPolicyModal({ fleet, onClose, onCreated }: { fleet: Fleet; onClose: () => void; onCreated: (id: number) => void }) {
  const toast = useToast();
  const policies = fleet.policies ?? [];
  const start = policies.find((p) => !p.builtIn) ?? policies[0];
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [source, setSource] = useState<number | null>(start?.id ?? null);
  const [groups, setGroups] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const trimmed = name.trim();
  const taken = policies.some((p) => p.name.trim().toLowerCase() === trimmed.toLowerCase());
  const nameOf = (id: number | null) => (id == null ? null : (policies.find((p) => p.id === id)?.name ?? `#${id}`));
  const phonesOf = (groupId: number) => (fleet.devices ?? []).filter((d) => d.group?.id === groupId).length;

  async function create() {
    if (source == null) return;
    setBusy(true);
    try {
      const id = await copyConfig(source, trimmed, description.trim() || undefined);
      if (groups.length) fleet.applyGroups(await setGroupsPolicy(id, groups));
      await fleet.reloadPolicies();
      toast.push(
        'ok',
        'Policy created',
        groups.length ? `${trimmed} · ${plural(groups.length, 'group')}. Press Apply on Groups to move their phones.` : trimmed,
      );
      onCreated(id);
    } catch (e) {
      toast.push('err', 'Could not create the policy', errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="md"
      title="New policy"
      description="It starts as a copy of the policy you pick, then you change what it allows. Give it its groups now or later."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !trimmed || taken || source == null} onClick={() => void create()}>
            {busy ? 'Creating…' : 'Create policy'}
          </Button>
        </>
      }
    >
      <div className="x-policies-stack">
        <Field label="Name" error={taken ? 'A policy has this name already' : undefined}>
          <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="e.g. Delivery phones" autoFocus />
        </Field>
        <Field label="Description (optional)">
          <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={200} />
        </Field>
        <Field label="Based on">
          <Select value={source == null ? '' : String(source)} onChange={(e) => setSource(Number(e.target.value))}>
            {policies.map((p) => (
              <option key={p.id} value={String(p.id)}>
                {p.name}
                {p.builtIn ? ' (built-in)' : ''}
              </option>
            ))}
          </Select>
        </Field>
        <fieldset className="x-policies-fieldset">
          <legend>Groups</legend>
          {fleet.groupsAvailable !== true ? (
            <p className="x-policies-help">Groups need the Policies server plugin.</p>
          ) : fleet.groups.length ? (
            <div className="x-policies-picker" role="group" aria-label="Groups">
              {fleet.groups.map((g) => (
                <Checkbox
                  key={g.id}
                  checked={groups.includes(g.id)}
                  onChange={(e) => setGroups((prev) => (e.target.checked ? [...prev, g.id] : prev.filter((x) => x !== g.id)))}
                  label={
                    <>
                      <span className="x-policies-strong">{g.name}</span>{' '}
                      <span className="x-policies-muted">
                        · {plural(phonesOf(g.id), 'phone')} · {nameOf(g.policyId) ? `now on ${nameOf(g.policyId)}` : 'no policy yet'}
                      </span>
                    </>
                  }
                />
              ))}
            </div>
          ) : (
            <p className="x-policies-help">No groups yet: make them on Groups, then give them this policy.</p>
          )}
          <p className="x-policies-help">A group has one policy: a group picked here leaves the one it has. No phone moves until you press Apply.</p>
        </fieldset>
        <p className="x-policies-help">
          Want a blank configuration instead? <Link to={paths.configuration('new')}>Open the configuration editor</Link>.
        </p>
      </div>
    </Modal>
  );
}
