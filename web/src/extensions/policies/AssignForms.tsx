// "Add to group" and "Set brand" for chosen phones: in Client actions (Policies page) and in a dialog opened from the
// Devices list's selection bar (index.ts sends the phones here through the address).
import { useMemo, useState } from 'react';
import { Button, Field, Input, Modal, Select, usePermission, useToast } from '@mdmesh/console';
import { moveDevices, createBrand, setDeviceBrands, setGroupMembers } from './api';
import type { Fleet } from './fleet';
import { brandSummary, groupableBrand } from './groups';
import { brandLabel, normalizeBrand } from './model';
import { errorText, plural } from './shared';

interface FormProps {
  fleet: Fleet;
  /** The phones, by device id. */
  ids: readonly number[];
  /** "Back" in Client actions, "Cancel" in the dialog. */
  cancelLabel: string;
  onCancel: () => void;
  onDone: () => void;
}

function Footer({ busy, cancelLabel, onCancel, action, disabled, onAction }: { busy: boolean; cancelLabel: string; onCancel: () => void; action: string; disabled?: boolean; onAction: () => void }) {
  return (
    <div className="x-policies-formfoot">
      <Button onClick={onCancel} disabled={busy}>
        {cancelLabel}
      </Button>
      <Button variant="primary" disabled={busy || disabled} onClick={onAction}>
        {busy ? 'Working…' : action}
      </Button>
    </div>
  );
}

/** Picks the phones into a group (whatever their brand), or takes them back to their brand's group. */
export function GroupAssignForm({ fleet, ids, cancelLabel, onCancel, onDone }: FormProps) {
  const toast = useToast();
  const canMove = usePermission({ permission: 'edit_devices' });
  const [groupId, setGroupId] = useState<string>(fleet.groups[0] ? String(fleet.groups[0].id) : '');
  const [alsoPolicy, setAlsoPolicy] = useState(true);
  const [busy, setBusy] = useState(false);
  const group = fleet.groups.find((g) => String(g.id) === groupId);
  const policy = group?.policyId != null ? fleet.policies?.find((p) => p.id === group.policyId) : undefined;
  const moves = canMove && !!policy && alsoPolicy;

  async function run() {
    setBusy(true);
    try {
      fleet.applyGroups(await setGroupMembers(ids, group ? group.id : null));
      if (moves && policy) {
        await moveDevices([...ids], policy.id);
        fleet.reload();
      }
      toast.push(
        'ok',
        group ? `Added to ${group.name}` : 'Back to their brand’s group',
        `${plural(ids.length, 'phone')}${moves && policy ? `, on ${policy.name}` : ''}`,
      );
      onDone();
    } catch (e) {
      toast.push('err', 'Could not change their group', errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="x-policies-stack">
      <p className="x-policies-help">
        Picked phones belong to the group whatever their brand, until you take them back to their brand&rsquo;s group.
      </p>
      <Field label="Group">
        <Select value={groupId} onChange={(e) => setGroupId(e.target.value)}>
          {fleet.groups.map((g) => (
            <option key={g.id} value={String(g.id)}>
              {g.name}
            </option>
          ))}
          <option value="">No group picked: back to their brand&rsquo;s group</option>
        </Select>
      </Field>
      {group && canMove ? (
        policy ? (
          <label className="x-policies-inline">
            <input type="checkbox" checked={alsoPolicy} onChange={(e) => setAlsoPolicy(e.target.checked)} />
            <span>Also put them on its policy now ({policy.name})</span>
          </label>
        ) : (
          <p className="x-policies-help">{group.name} has no policy yet: give it one on Groups, then Apply.</p>
        )
      ) : null}
      <Footer
        busy={busy}
        cancelLabel={cancelLabel}
        onCancel={onCancel}
        action={group ? `Add ${plural(ids.length, 'phone')}` : `Move ${plural(ids.length, 'phone')} back`}
        disabled={!ids.length}
        onAction={() => void run()}
      />
    </div>
  );
}

const NEW = '__new__';
const OWN = '__own__';

/** Sets a brand on the phones by hand (an existing one or a new one), or gives them back the brand they report. */
export function BrandAssignForm({ fleet, ids, cancelLabel, onCancel, onDone }: FormProps) {
  const toast = useToast();
  const brands = useMemo(
    () => brandSummary(fleet.devices ?? [], fleet.groups, fleet.customBrands).filter((b) => groupableBrand(b.brand)),
    [fleet.devices, fleet.groups, fleet.customBrands],
  );
  const [choice, setChoice] = useState<string>(brands[0]?.brand ?? NEW);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const newKey = normalizeBrand(name);
  const nameOk = choice !== NEW || (!!name.trim() && groupableBrand(newKey) && name.trim().length <= 40);

  async function run() {
    setBusy(true);
    try {
      let key: string | null = choice === OWN ? null : choice;
      if (choice === NEW) {
        fleet.applyGroups(await createBrand(name.trim()));
        key = newKey;
      }
      fleet.applyGroups(await setDeviceBrands(ids, key));
      toast.push('ok', key ? `Set to ${brandLabel(key)}` : 'Their own brand again', plural(ids.length, 'phone'));
      onDone();
    } catch (e) {
      toast.push('err', 'Could not set the brand', errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="x-policies-stack">
      <p className="x-policies-help">
        A brand set by hand wins over the one the phone reports, so the phone joins that brand&rsquo;s group.
      </p>
      <Field label="Brand">
        <Select value={choice} onChange={(e) => setChoice(e.target.value)}>
          {brands.map((b) => (
            <option key={b.brand} value={b.brand}>
              {brandLabel(b.brand)} · {plural(b.count, 'phone')}
            </option>
          ))}
          <option value={NEW}>New brand…</option>
          <option value={OWN}>The brand each phone reports</option>
        </Select>
      </Field>
      {choice === NEW ? (
        <Field label="New brand" error={name && !nameOk ? 'Up to 40 letters, digits and spaces' : undefined}>
          <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} placeholder="e.g. Nothing" autoFocus />
        </Field>
      ) : null}
      <Footer busy={busy} cancelLabel={cancelLabel} onCancel={onCancel} action={`Set on ${plural(ids.length, 'phone')}`} disabled={!ids.length || !nameOk} onAction={() => void run()} />
    </div>
  );
}

/** The Devices list's "Add to group…" / "Set brand…", opened on the Policies page with the phones it chose. */
export function AssignModal({ mode, ids, fleet, onClose }: { mode: 'group' | 'brand'; ids: readonly number[]; fleet: Fleet; onClose: () => void }) {
  const known = ids.filter((id) => (fleet.devices ?? []).some((d) => d.device.id === id));
  return (
    <Modal open onClose={onClose} size="sm" title={mode === 'group' ? `Add ${plural(known.length, 'phone')} to a group` : `Set the brand of ${plural(known.length, 'phone')}`}>
      {!fleet.devices ? (
        <p className="x-policies-help">Loading the phones…</p>
      ) : mode === 'group' && !fleet.groups.length ? (
        <p className="x-policies-help">There are no groups yet. Create one first.</p>
      ) : mode === 'group' ? (
        <GroupAssignForm fleet={fleet} ids={known} cancelLabel="Cancel" onCancel={onClose} onDone={onClose} />
      ) : (
        <BrandAssignForm fleet={fleet} ids={known} cancelLabel="Cancel" onCancel={onClose} onDone={onClose} />
      )}
    </Modal>
  );
}
