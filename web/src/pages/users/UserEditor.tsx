import { useEffect, useId, useState } from 'react';
import {
  getUser,
  listAccessConfigurations,
  listGroups,
  managementError,
  saveUser,
  type AccessItem,
  type ManagedUser,
  type UserRole,
} from '../../api/users';
import { Button, Callout, Checkbox, Field, Input, Modal, PasswordInput, RuleList, Select, type Rule } from '../../ui';
import { MIN_LENGTH } from '../settings/ChangePasswordModal';

export function AccessPicker({
  label,
  all,
  onAll,
  items,
  selected,
  onSelect,
}: {
  label: string;
  all: boolean;
  onAll: (all: boolean) => void;
  items: AccessItem[];
  selected: AccessItem[];
  onSelect: (items: AccessItem[]) => void;
}) {
  // Keep a selected item even if a list no longer returns it; editing a name must not silently broaden access.
  const choices = [...items, ...selected.filter((s) => !items.some((i) => i.id === s.id))];
  return (
    <fieldset className="users-scope">
      <legend>{label}</legend>
      <label>
        <Checkbox checked={all} onChange={(e) => onAll(e.target.checked)} /> All {label.toLowerCase()}
      </label>
      {!all && (
        <div className="users-choices">
          {choices.map((item) => (
            <label key={item.id}>
              <Checkbox
                checked={selected.some((s) => s.id === item.id)}
                onChange={(e) =>
                  onSelect(e.target.checked ? [...selected, item] : selected.filter((s) => s.id !== item.id))
                }
              />
              {item.name}
            </label>
          ))}
          {!choices.length && <p>No {label.toLowerCase()} available.</p>}
          <small>With none selected, this account has access to none.</small>
        </div>
      )}
    </fieldset>
  );
}

export function UserEditor({
  target,
  roles,
  onClose,
  onSaved,
}: {
  target: ManagedUser | 'new';
  roles: UserRole[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [draft, setDraft] = useState<ManagedUser | null>(
    target === 'new'
      ? {
          login: '',
          name: '',
          email: '',
          userRole: { name: '', permissions: [] },
          allDevicesAvailable: true,
          allConfigAvailable: true,
          groups: [],
          configurations: [],
        }
      : null,
  );
  const [groups, setGroups] = useState<AccessItem[]>([]);
  const [configs, setConfigs] = useState<AccessItem[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const rulesId = useId();
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      target === 'new' ? Promise.resolve(null) : getUser(target.id!, controller.signal),
      listGroups(controller.signal),
      listAccessConfigurations(controller.signal),
    ])
      .then(([user, g, c]) => {
        if (controller.signal.aborted) return;
        if (user) setDraft({ ...user, groups: user.groups ?? [], configurations: user.configurations ?? [] });
        setGroups(g ?? []);
        setConfigs(c ?? []);
        setReady(true);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(managementError(e));
      });
    return () => controller.abort();
  }, [target]);
  const update = (patch: Partial<ManagedUser>) => setDraft((d) => d && { ...d, ...patch });
  // Change password's rules; an edit with both fields blank keeps the current password.
  const typed = password.length > 0 || confirmation.length > 0;
  const longEnough = password.length >= MIN_LENGTH;
  const matches = password === confirmation;
  const showRules = typed || target === 'new';
  const passwordValid = showRules ? longEnough && matches : true;
  const rules: Rule[] = [
    { key: 'len', label: `At least ${MIN_LENGTH} characters`, met: password ? longEnough : null },
    { key: 'match', label: 'Both passwords match', met: confirmation ? matches : null },
  ];
  const submit = async () => {
    if (!draft || busy) return;
    setError('');
    if (!passwordValid) {
      setError(longEnough ? 'The passwords do not match.' : `Use a password of at least ${MIN_LENGTH} characters.`);
      return;
    }
    setBusy(true);
    try {
      await saveUser(draft, password);
      onSaved();
    } catch (e) {
      setError(managementError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open
      title={target === 'new' ? 'New user' : `Edit ${target.login}`}
      onClose={() => {
        if (!busy) onClose();
      }}
      dismissOnBackdrop={false}
      description="Set the account’s role and the devices and configurations it can access."
      footer={
        <>
          <Button disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            type="submit"
            form="user-form"
            disabled={!ready || !draft?.userRole?.id || !passwordValid || busy}
          >
            {busy ? 'Saving…' : 'Save user'}
          </Button>
        </>
      }
    >
      {error && (
        <Callout tone="alert" role="alert">
          {error}
        </Callout>
      )}
      {!ready || !draft ? (
        <p role="status">{error ? 'Close this dialog and try again.' : 'Loading account and access options…'}</p>
      ) : (
        <form
          id="user-form"
          className="users-form"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <fieldset disabled={busy} className="users-fields">
            <Field label="Login">
              <Input
                value={draft.login}
                required
                maxLength={30}
                autoComplete="off"
                onChange={(e) => update({ login: e.target.value })}
              />
            </Field>
            <Field label="Name">
              <Input value={draft.name ?? ''} maxLength={50} onChange={(e) => update({ name: e.target.value })} />
            </Field>
            <Field label="Email">
              <Input
                type="email"
                maxLength={50}
                value={draft.email ?? ''}
                onChange={(e) => update({ email: e.target.value })}
              />
            </Field>
            <Field label="Role">
              <Select
                value={draft.userRole?.id ?? ''}
                required
                onChange={(e) => update({ userRole: roles.find((r) => r.id === Number(e.target.value))! })}
              >
                <option value="" disabled>
                  Select a role
                </option>
                {roles.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field
              label={target === 'new' ? 'Password' : 'New password'}
              help={
                target === 'new'
                  ? 'The server’s password reset policy applies at first sign-in.'
                  : 'Leave both password fields blank to keep the current password. Setting a password ends the account’s existing sessions.'
              }
            >
              <PasswordInput
                value={password}
                required={target === 'new'}
                autoComplete="new-password"
                onChange={(e) => setPassword(e.target.value)}
              />
            </Field>
            <Field label="Confirm password">
              <PasswordInput
                value={confirmation}
                required={!!password}
                autoComplete="new-password"
                toggleLabel="Show password confirmation"
                aria-describedby={showRules ? rulesId : undefined}
                onChange={(e) => setConfirmation(e.target.value)}
              />
            </Field>
            {showRules && <RuleList id={rulesId} rules={rules} />}
            <AccessPicker
              label="Device groups"
              all={draft.allDevicesAvailable}
              onAll={(allDevicesAvailable) => update({ allDevicesAvailable })}
              items={groups}
              selected={draft.groups}
              onSelect={(groups) => update({ groups })}
            />
            <AccessPicker
              label="Configurations"
              all={draft.allConfigAvailable}
              onAll={(allConfigAvailable) => update({ allConfigAvailable })}
              items={configs}
              selected={draft.configurations}
              onSelect={(configurations) => update({ configurations })}
            />
          </fieldset>
        </form>
      )}
    </Modal>
  );
}
