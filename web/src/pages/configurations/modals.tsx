import { useMemo, useState, type FormEvent } from 'react';
import {
  copyConfiguration,
  deleteConfiguration,
  getConfigurations,
  type Configuration,
} from '../../api/configurations';
import { appCategory, type AppCategory, type Application } from '../../api/applications';
import { CONFIG_FIELDS } from '../../data/configFields';
import { AppTile, Button, Checkbox, EmptyState, Field, Input, Modal, SearchInput, Segmented, useToast } from '../../ui';
import { plural } from './sync';
import './modals.css';

const errMessage = (e: unknown) => (e instanceof Error ? e.message : '');

/**
 * Copy a configuration on the server under a new name (classic CopyModal: "Copy" / "Use as template"). The server
 * copy (PUT /configurations/copy) takes every setting, the apps, configuration files and app settings of the saved
 * configuration; it is the one Duplicate path (list and editor). `onDone` gets the copy's id, found by its unique
 * name since the copy answers without one (null if it cannot be found).
 */
export function CopyConfigModal({
  source,
  note,
  onClose,
  onDone,
}: {
  source: Configuration | null;
  /** An extra line under the lead (the editor: unsaved changes are not copied). */
  note?: string;
  onClose: () => void;
  onDone: (name: string, id: number | null) => void;
}) {
  const [busy, setBusy] = useState(false);
  // Escape and the close button wait for a running copy (closing would hide its outcome).
  const close = () => {
    if (!busy) onClose();
  };
  return (
    <Modal open={source != null} onClose={close} title="Duplicate configuration" size="sm" dismissOnBackdrop={false}>
      {source ? (
        <CopyForm key={source.id} source={source} note={note} busy={busy} setBusy={setBusy} onClose={close} onDone={onDone} />
      ) : null}
    </Modal>
  );
}

function CopyForm({
  source,
  note,
  busy,
  setBusy,
  onClose,
  onDone,
}: {
  source: Configuration;
  note?: string;
  busy: boolean;
  setBusy: (b: boolean) => void;
  onClose: () => void;
  onDone: (name: string, id: number | null) => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(`${source.name} copy`);
  const [error, setError] = useState<string | null>(null);

  async function go(e: FormEvent) {
    e.preventDefault();
    const n = name.trim();
    if (!n || source.id == null) return;
    setBusy(true);
    setError(null);
    try {
      await copyConfiguration(source.id, n, source.description as string | undefined);
    } catch (err) {
      const msg = errMessage(err);
      const dup = /duplicate/i.test(msg);
      if (dup) setError('A configuration with that name exists.');
      toast.push('err', 'Copy failed', dup ? 'A configuration with that name exists.' : msg);
      setBusy(false);
      return;
    }
    toast.push('ok', 'Configuration copied', n);
    const id = await getConfigurations().then(
      (list) => list.find((x) => x.name === n)?.id ?? null,
      () => null,
    );
    setBusy(false);
    onDone(n, id);
  }

  return (
    <form className="v2-configs-modalform" onSubmit={(e) => void go(e)}>
      <p className="v2-configs-modalform__lead">
        Makes an editable copy of “{source.name}” with the same settings and apps. Devices stay on their current
        configuration.
      </p>
      {note ? <p className="v2-configs-modalform__lead">{note}</p> : null}
      <Field label="New name" error={error}>
        <Input value={name} onChange={(e) => setName(e.target.value)} autoFocus />
      </Field>
      <div className="v2-configs-modalform__actions">
        <Button size="lg" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button size="lg" variant="primary" type="submit" disabled={busy || !name.trim()}>
          {busy ? 'Copying…' : 'Duplicate'}
        </Button>
      </div>
    </form>
  );
}

/** Delete confirmation (classic: window.confirm). */
export function DeleteConfigModal({
  target,
  deviceCount,
  onClose,
  onDeleted,
}: {
  target: Configuration | null;
  /** Devices using it, from the sync summary (undefined = unknown). */
  deviceCount?: number;
  onClose: () => void;
  onDeleted: (c: Configuration) => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  async function go() {
    if (!target || target.id == null) return;
    setBusy(true);
    try {
      await deleteConfiguration(target.id);
      toast.push('ok', 'Configuration deleted', target.name);
      onDeleted(target);
    } catch (e) {
      const msg = errMessage(e);
      toast.push('err', 'Delete failed', /device/i.test(msg) ? 'Devices still use this configuration.' : msg);
      onClose();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={target != null}
      onClose={busy ? () => undefined : onClose}
      role="alertdialog"
      size="sm"
      title="Delete configuration?"
      description={target ? `Delete “${target.name}”? This cannot be undone.` : undefined}
      footer={
        <>
          <Button size="lg" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button size="lg" variant="danger" onClick={() => void go()} disabled={busy}>
            {busy ? 'Deleting…' : 'Delete'}
          </Button>
        </>
      }
    >
      {deviceCount ? (
        <p className="v2-configs-modalnote">
          {plural(deviceCount, 'device')} still {deviceCount === 1 ? 'uses' : 'use'} this configuration, so the server
          will refuse to delete it. Move {deviceCount === 1 ? 'it' : 'them'} to another configuration first.
        </p>
      ) : null}
    </Modal>
  );
}

/** "New configuration": blank, or based on one of the built-in templates (classic NewChooser). */
export function NewConfigChooser({
  open,
  templates,
  onClose,
  onPick,
}: {
  open: boolean;
  templates: Configuration[];
  onClose: () => void;
  onPick: (base: Configuration | null) => void;
}) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New configuration"
      description="Start from scratch, or base it on a built-in template."
      footer={
        <Button size="lg" onClick={onClose}>
          Cancel
        </Button>
      }
    >
      <ul className="v2-configs-chooser">
        <li>
          <button type="button" className="v2-configs-chooser__opt" onClick={() => onPick(null)}>
            <span className="v2-configs-chooser__name">Blank configuration</span>
            <span className="v2-configs-chooser__sub">Empty template: set everything yourself.</span>
          </button>
        </li>
        {templates.map((t) => (
          <li key={t.id}>
            <button type="button" className="v2-configs-chooser__opt" onClick={() => onPick(t)}>
              <span className="v2-configs-chooser__name">Based on “{t.name}”</span>
              <span className="v2-configs-chooser__sub">Copy this template’s settings and apps, then customise.</span>
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

const FIELD_LABEL: Record<string, string> = Object.fromEntries(CONFIG_FIELDS.map((f) => [f.key, f.label]));
FIELD_LABEL.applications = 'Apps';

/** Kiosk-change confirmation (classic KioskChangeConfirm). `count` null = unknown: still confirm, without a number. */
export function KioskConfirmModal({
  keys,
  count,
  onCancel,
  onConfirm,
}: {
  keys: string[] | null;
  count: number | null;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <Modal
      open={keys != null}
      onClose={onCancel}
      title={count == null ? 'Change kiosk on its devices?' : `Change kiosk on ${plural(count, 'device')}?`}
      footer={
        <>
          <Button size="lg" onClick={onCancel}>
            Keep editing
          </Button>
          <Button size="lg" variant="primary" onClick={onConfirm}>
            Save and apply
          </Button>
        </>
      }
    >
      <p className="v2-configs-modalnote">
        This edit changes kiosk settings ({(keys ?? []).map((k) => FIELD_LABEL[k] ?? k).join(', ')}). Every device
        assigned to this configuration will re-apply kiosk at its next check-in, usually within seconds. Turning kiosk
        off lifts it only on devices that entered kiosk through this configuration.
      </p>
    </Modal>
  );
}

const CAT_LABEL: Record<AppCategory, string> = { uploaded: 'Uploaded', system: 'System', web: 'Web apps' };
// Web apps are not offered: configurations assign installable apps only (the editor leaves them out of the library).
const CAT_ORDER: AppCategory[] = ['uploaded', 'system'];

/** Searchable, categorised, multi-select app picker (classic AppPicker). Defaults to "Uploaded". */
export function AppPickerModal({
  open,
  apps,
  excludeIds,
  onAdd,
  onClose,
}: {
  open: boolean;
  apps: Application[];
  excludeIds: Set<number>;
  onAdd: (apps: Application[]) => void;
  onClose: () => void;
}) {
  return (
    <Modal open={open} onClose={onClose} title="Add apps" size="lg">
      {open ? <PickerBody apps={apps} excludeIds={excludeIds} onAdd={onAdd} onClose={onClose} /> : null}
    </Modal>
  );
}

function PickerBody({
  apps,
  excludeIds,
  onAdd,
  onClose,
}: {
  apps: Application[];
  excludeIds: Set<number>;
  onAdd: (apps: Application[]) => void;
  onClose: () => void;
}) {
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const available = useMemo(() => apps.filter((a) => !excludeIds.has(a.id)), [apps, excludeIds]);
  const counts = useMemo(() => {
    const c: Record<AppCategory, number> = { uploaded: 0, system: 0, web: 0 };
    for (const a of available) c[appCategory(a)]++;
    return c;
  }, [available]);
  const tabs = CAT_ORDER.filter((c) => counts[c] > 0);
  const [cat, setCat] = useState<AppCategory>(counts.uploaded > 0 ? 'uploaded' : (tabs[0] ?? 'uploaded'));

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return available
      .filter((a) => appCategory(a) === cat)
      .filter((a) => !needle || `${a.name} ${a.pkg}`.toLowerCase().includes(needle))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [available, cat, q]);

  const toggle = (id: number) =>
    setPicked((p) => {
      const n = new Set(p);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });

  return (
    <div className="v2-configs-picker">
      <div className="v2-configs-picker__top">
        <SearchInput
          className="v2-configs-picker__search"
          value={q}
          onChange={setQ}
          placeholder="Search apps"
          aria-label="Search apps"
          autoFocus
        />
        {tabs.length > 0 ? (
          <Segmented
            aria-label="Category"
            value={cat}
            onChange={setCat}
            options={tabs.map((c) => ({ value: c, label: CAT_LABEL[c], count: counts[c] }))}
          />
        ) : null}
      </div>
      <div className="v2-configs-picker__list" role="group" aria-label="Apps">
        {shown.length === 0 ? (
          <EmptyState
            bare
            title={apps.length === 0 ? 'The app library is empty' : available.length === 0 ? 'Every app is already added' : 'No apps here'}
          />
        ) : (
          shown.map((a) => (
            <label key={a.id} className={picked.has(a.id) ? 'v2-configs-picker__row is-on' : 'v2-configs-picker__row'}>
              <Checkbox checked={picked.has(a.id)} onChange={() => toggle(a.id)} />
              <AppTile name={a.name} size={32} />
              <span className="v2-configs-picker__meta">
                <span className="v2-configs-picker__name">{a.name}</span>
                <span className="v2-configs-picker__pkg">{a.pkg}</span>
              </span>
              {a.version ? <span className="v2-configs-picker__ver">{a.version}</span> : null}
            </label>
          ))
        )}
      </div>
      <div className="v2-configs-modalform__actions">
        <Button size="lg" onClick={onClose}>
          Cancel
        </Button>
        <Button
          size="lg"
          variant="primary"
          disabled={picked.size === 0}
          onClick={() => {
            const chosen = apps.filter((a) => picked.has(a.id));
            if (chosen.length) onAdd(chosen);
            onClose();
          }}
        >
          {picked.size ? `Add ${picked.size}` : 'Add'}
        </Button>
      </div>
    </div>
  );
}
