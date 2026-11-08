import { useRef, useState, type FormEvent, type MouseEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { submitForcedPasswordReset } from '../api/auth';
import { ApiError } from '../api/client';
import { paths } from '../routes';
import { AuthFooter, AuthLayout, Button, Callout, Field, IconAlert, Input, RuleList, type Rule } from '../ui';
import { safeReturnPath } from '../auth/safeReturnPath';
import { isUnreachable, UNREACHABLE } from './authCommon';

const MIN_LENGTH = 8;
const RULES_ID = 'v2-setpw-rules';

/**
 * Forced password change, without the shell. Reached when sign-in returns `passwordReset: true` (the
 * seeded admin, or an account an admin flagged). Same as the classic `pages/SetPasswordPage.tsx`: the public reset
 * endpoint with the session's reset token (api/auth.ts hashes the password exactly like sign-in), then sign out
 * and sign in again with the new password. The server checks no password rules, so the two client rules (at least
 * 8 characters, both entries match) are the only ones, as in classic.
 */
export function SetPasswordPage() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const from = safeReturnPath(params.get('from'), '');
  const loginPath = from ? `${paths.login}?from=${encodeURIComponent(from)}` : paths.login;

  const [pw, setPw] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Set while this page signs the flagged session out on purpose: the guard below must not redirect then, or its
  // state-less <Navigate> replaces ours and the "Password updated" notice is lost (the classic page has that bug).
  const leaving = useRef(false);

  // Only for a flagged session carrying a reset token; anyone else signs in.
  if (!user?.passwordReset || !user.passwordResetToken) {
    return leaving.current ? null : <Navigate to={loginPath} replace />;
  }
  const token = user.passwordResetToken;

  const longEnough = pw.length >= MIN_LENGTH;
  const matches = pw === confirm;
  const valid = longEnough && matches;
  const rules: Rule[] = [
    { key: 'length', label: `At least ${MIN_LENGTH} characters`, met: pw === '' ? null : longEnough },
    { key: 'match', label: 'Both passwords match', met: confirm === '' ? null : matches },
  ];

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    if (!longEnough) {
      setError(`Use at least ${MIN_LENGTH} characters.`);
      return;
    }
    if (!matches) {
      setError('Passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      await submitForcedPasswordReset(token, pw);
      leaving.current = true;
      await signOut(); // clear the flagged session; sign in again with the new password
      navigate(loginPath, { replace: true, state: { passwordChanged: true } });
    } catch (err) {
      if (err instanceof ApiError && err.message === 'error.user.not.found') {
        // The server refused the reset token: it works once, for 24 hours (v0.3.8). Signing in again issues a fresh one.
        leaving.current = true;
        await signOut();
        navigate(loginPath, { replace: true, state: { resetExpired: true } });
        return;
      }
      setError(isUnreachable(err) ? UNREACHABLE : 'Could not set the password. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  // Leaving drops the flagged session too, so the sign-in page starts clean.
  async function backToSignIn(e: MouseEvent<HTMLAnchorElement>) {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return; // new tab: plain link
    e.preventDefault();
    leaving.current = true;
    await signOut();
    navigate(loginPath, { replace: true });
  }

  return (
    <AuthLayout
      title="Choose a new password"
      subtitle={
        <>
          You’re signed in as <span className="v2-login-account">{user.login}</span> with a temporary password.
          Replace it before you continue.
        </>
      }
      onSubmit={onSubmit}
      footer={
        <AuthFooter>
          <Link to={loginPath} replace onClick={(e) => void backToSignIn(e)}>
            Back to sign in
          </Link>
        </AuthFooter>
      }
    >
      <Field label="New password">
        <Input
          size="lg"
          type="password"
          name="new-password"
          autoComplete="new-password"
          autoFocus
          required
          aria-describedby={RULES_ID}
          value={pw}
          onChange={(e) => setPw(e.target.value)}
        />
      </Field>

      <Field label="Type it again">
        <Input
          size="lg"
          type="password"
          name="confirm-password"
          autoComplete="new-password"
          required
          aria-describedby={RULES_ID}
          tone={confirm !== '' && !matches ? 'invalid' : 'default'}
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
      </Field>

      <RuleList id={RULES_ID} rules={rules} />

      {error ? (
        <Callout tone="alert" icon={<IconAlert size={16} />} role="alert">
          {error}
        </Callout>
      ) : null}

      <div className="v2-login-submit">
        <Button type="submit" variant="primary" size="auth" disabled={busy || !valid}>
          {busy ? 'Saving…' : 'Set password'}
        </Button>
        <p className="v2-login-submit__note">You’ll sign in once more with the new password.</p>
      </div>
    </AuthLayout>
  );
}
