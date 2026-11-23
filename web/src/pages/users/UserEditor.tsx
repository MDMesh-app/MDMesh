import { useEffect, useId, useState } from 'react';
import { fetchCurrentUser } from '../../api/auth';
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
import {
  Button,
  Callout,
  Checkbox,
  Field,
  Input,
  Modal,
  PasswordInput,
  RuleList,
  Select,
  Skeleton,
  type Rule,
} from '../../ui';
import { MIN_LENGTH } from '../settings/ChangePasswordModal';

export function AccessPicker({
  label,
  all,
  allLocked = false,
  allStored = false,
  onAll,
  items,
  selected,
  onSelect,
}: {
  label: string;
  all: boolean;
  /** The caller doesn't reach all of these themselves, so the server would refuse to grant it. */
  allLocked?: boolean;
  /** The account had "all" when the editor opened: keeping it is no grant, so it can be checked again. */
  allStored?: boolean;
  onAll: (all: boolean) => void;
  items: AccessItem[];
  selected: AccessItem[];
  onSelect: (items: AccessItem[]) => void;
}) {
  // Keep a selected item even if a list no longer returns it; editing a name must not silently broaden access.
  const choices = [...items, ...selected.filter((s) => !items.some((i) => i.id === s.id))];
  return (
    <fieldset className="v2-users-scope">
      <legend>{label}</legend>
      <Checkbox
        className="v2-users-choice"
        checked={all}
        // Never a checked box greyed out: a stored "all" can still be cleared, and checked again in the same edit
        disabled={allLocked && !all && !allStored}
        onChange={(e) => onAll(e.target.checked)}
        label={`All ${label.toLowerCase()}`}
      />
      {allLocked && !all && !allStored ? (
        <p className="v2-users-help">Your own access is limited, so you can give only what you have.</p>
      ) : null}
      {!all && (
        <>
          <div className="v2-users-choices">
            {choices.map((item) => (
              <Checkbox
                key={item.id}
                className="v2-users-choice"
                checked={selected.some((s) => s.id === item.id)}
                onChange={(e) =>
                  onSelect(e.target.checked ? [...selected, item] : selected.filter((s) => s.id !== item.id))
                }
                label={item.name}
              />
            ))}
          </div>
          <p className="v2-users-help">
            {choices.length ? 'With none selected, this account reaches none.' : `No ${label.toLowerCase()} to choose from.`}
          </p>
        </>
      )}
    </fieldset>
  );
}

/** Placeholder for the form while the account and its access options load. */
function FormSkeleton({ fields }: { fields: number }) {
  return (
    <div className="v2-users-loading" role="status">
      <span className="v2-visually-hidden">Loading the account and its access options</span>
      {Array.from({ length: fields }, (_, i) => (
        <div key={i} className="v2-users-loading__field" aria-hidden="true">
          <Skeleton width="25%" />
          <Skeleton height="control" />
        </div>
      ))}
    </div>
  );
}

export function UserEditor({
  open = true,
  target,
  roles,
  onClose,
  onSaved,
}: {
  /** False while it animates closed (the caller keeps it mounted). */
  open?: boolean;
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
          // Nothing until chosen: "all" is a deliberate choice, and one the server refuses a limited admin
          allDevicesAvailable: false,
          allConfigAvailable: false,
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
  // The caller's own password, for the changes the server asks it for (see needsCurrentPassword)
  const [currentPassword, setCurrentPassword] = useState('');
  const [askCurrent, setAskCurrent] = useState(false);
  const [storedEmail, setStoredEmail] = useState('');
  const [callerLimited, setCallerLimited] = useState({ devices: false, configs: false });
  const [storedAll, setStoredAll] = useState({ devices: false, configs: false });
  const rulesId = useId();
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      target === 'new' ? Promise.resolve(null) : getUser(target.id!, controller.signal),
      listGroups(controller.signal),
      listAccessConfigurations(controller.signal),
      // Only to grey out what the server would refuse: the editor opens without it, and the server still checks
      fetchCurrentUser(controller.signal).catch(() => null),
    ])
      .then(([user, g, c, caller]) => {
        if (controller.signal.aborted) return;
        // The caller's access as the server has it now, not as it was at sign-in. The lists hold only what they reach.
        const unlimited = !!(caller?.superAdmin || caller?.userRole?.superAdmin);
        setCallerLimited({
          devices: !unlimited && caller?.allDevicesAvailable === false,
          configs: !unlimited && caller?.allConfigAvailable === false,
        });
        if (user) {
          setDraft({ ...user, groups: user.groups ?? [], configurations: user.configurations ?? [] });
          setStoredEmail(user.email ?? '');
          setStoredAll({ devices: !!user.allDevicesAvailable, configs: !!user.allConfigAvailable });
        }
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
  // Creating an account takes the caller's own password, and so does changing another account's email (recovery links
  // go there) or password. askCurrent: the server asked for it anyway (the email changed meanwhile).
  const needsCurrentPassword =
    target === 'new' || typed || askCurrent || (draft?.email ?? '').trim() !== storedEmail.trim();
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
    if (needsCurrentPassword && !currentPassword) {
      setError('Enter your current password.');
      return;
    }
    setBusy(true);
    try {
      await saveUser(draft, password, needsCurrentPassword ? currentPassword : '');
      onSaved();
    } catch (e) {
      if (e instanceof Error && e.message === 'error.password.wrong' && !needsCurrentPassword) {
        setAskCurrent(true);
        setError('Enter your current password to save this change.');
      } else {
        setError(managementError(e));
      }
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={open}
      title={target === 'new' ? 'New user' : `Edit ${target.login}`}
      onClose={() => {
        if (!busy) onClose();
      }}
      dismissOnBackdrop={false}
      description="Set the account’s role and the devices and configurations it can access."
      footer={
        <>
          <Button size="lg" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button
            size="lg"
            variant="primary"
            type="submit"
            form="user-form"
            disabled={
              !ready || !draft?.userRole?.id || !passwordValid || (needsCurrentPassword && !currentPassword) || busy
            }
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
        error ? null : <FormSkeleton fields={4} />
      ) : (
        <form
          id="user-form"
          className="v2-users-form"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <fieldset disabled={busy} className="v2-users-fields">
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
            {needsCurrentPassword && (
              <Field
                label="Your current password"
                help={
                  target === 'new'
                    ? 'Needed to create an account.'
                    : 'Needed to change another account’s email or password.'
                }
              >
                <PasswordInput
                  value={currentPassword}
                  required
                  autoComplete="current-password"
                  toggleLabel="Show your current password"
                  onChange={(e) => setCurrentPassword(e.target.value)}
                />
              </Field>
            )}
            <AccessPicker
              label="Device groups"
              all={draft.allDevicesAvailable}
              allLocked={callerLimited.devices}
              allStored={storedAll.devices}
              onAll={(allDevicesAvailable) => update({ allDevicesAvailable })}
              items={groups}
              selected={draft.groups}
              onSelect={(groups) => update({ groups })}
            />
            <AccessPicker
              label="Configurations"
              all={draft.allConfigAvailable}
              allLocked={callerLimited.configs}
              allStored={storedAll.configs}
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
