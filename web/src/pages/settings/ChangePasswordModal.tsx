import { useEffect, useId, useState, type FormEvent } from 'react';
import { changeOwnPassword, isTooManyAttempts, TOO_MANY_MESSAGE, type AuthUser } from '../../api/auth';
import { Button, Callout, Field, IconAlert, Modal, PasswordInput, RuleList, useToast, type Rule } from '../../ui';

const MIN_LENGTH = 8;

/**
 * Change your own password (new with the redesign: the v0.3 console had no such form). `PUT /private/users/current` checks
 * the current password server-side; the rules below are the ones the client can check (same minimum as the
 * set-password page).
 */
export function ChangePasswordModal({ open, user, onClose }: { open: boolean; user: AuthUser | null; onClose: () => void }) {
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [wrongCurrent, setWrongCurrent] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const rulesId = useId();
  const formId = useId();

  useEffect(() => {
    if (open) return;
    setCurrent('');
    setNext('');
    setAgain('');
    setWrongCurrent(false);
    setErr(null);
  }, [open]);

  const typed = next.length > 0;
  const rules: Rule[] = [
    { key: 'len', label: `At least ${MIN_LENGTH} characters`, met: typed ? next.length >= MIN_LENGTH : null },
    { key: 'diff', label: 'Different from your current password', met: typed && current ? next !== current : null },
    { key: 'match', label: 'Both new passwords match', met: again ? next === again : null },
  ];
  const valid = current.length > 0 && next.length >= MIN_LENGTH && next !== current && next === again;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid || busy) return;
    if (!user?.id || !user.login) {
      setErr('Your session is missing your account details. Sign out and in again, then retry.');
      return;
    }
    setBusy(true);
    setErr(null);
    setWrongCurrent(false);
    try {
      await changeOwnPassword({ id: user.id, login: user.login }, current, next);
      toast.push('ok', 'Password changed', 'Use the new password the next time you sign in.');
      onClose();
    } catch (ex) {
      const m = (ex as Error).message;
      if (m === 'error.password.wrong') setWrongCurrent(true);
      // The current-password check counts as a sign-in for the server's limit (LoginThrottle, v0.3.9).
      else if (isTooManyAttempts(ex)) setErr(TOO_MANY_MESSAGE);
      else if (m === 'error.password.empty') setErr('Enter a new password.');
      else if (m === 'error.permission.denied') setErr('The server refused the change. Sign out and in again, then retry.');
      else setErr(m || 'The password was not changed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={() => !busy && onClose()}
      dismissOnBackdrop={false}
      size="sm"
      title="Change password"
      description={user?.login ? <>For <span className="v2-mono">{user.login}</span>.</> : undefined}
      footer={
        <>
          <Button size="lg" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form={formId} variant="primary" size="lg" disabled={!valid || busy}>
            {busy ? 'Saving…' : 'Change password'}
          </Button>
        </>
      }
    >
      <form id={formId} className="v2-settings-pwform" onSubmit={(e) => void submit(e)} noValidate>
        {err ? (
          <Callout tone="alert" role="alert" icon={<IconAlert />}>
            {err}
          </Callout>
        ) : null}
        <Field label="Current password" error={wrongCurrent ? 'That isn’t your current password.' : undefined}>
          <PasswordInput
            size="md"
            autoComplete="current-password"
            toggleLabel="Show current password"
            value={current}
            onChange={(e) => {
              setCurrent(e.target.value);
              setWrongCurrent(false);
            }}
            required
          />
        </Field>
        <Field label="New password">
          <PasswordInput
            size="md"
            autoComplete="new-password"
            toggleLabel="Show new password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            aria-describedby={rulesId}
            required
          />
        </Field>
        <Field label="Type it again">
          <PasswordInput
            size="md"
            autoComplete="new-password"
            toggleLabel="Show the password typed again"
            value={again}
            onChange={(e) => setAgain(e.target.value)}
            aria-describedby={rulesId}
            required
          />
        </Field>
        <RuleList id={rulesId} rules={rules} />
      </form>
    </Modal>
  );
}
