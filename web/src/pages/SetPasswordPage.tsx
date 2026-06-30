import { useState, type FormEvent } from 'react';
import { useNavigate, Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { submitForcedPasswordReset } from '../api/auth';
import { Wordmark } from '../ui/Wordmark';

/**
 * Forced first-login password change. Reached when login returns `passwordReset: true`
 * (the seeded admin). Uses the public reset endpoint (the authenticated path is blocked
 * by the server while the flag is set), then bounces back to /login for a fresh sign-in.
 */
export function SetPasswordPage() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const [pw, setPw] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Only valid for a flagged session carrying a reset token; otherwise send to login.
  if (!user?.passwordReset || !user.passwordResetToken) {
    return <Navigate to="/login" replace />;
  }
  const token = user.passwordResetToken;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (pw.length < 8) {
      setError('Use at least 8 characters.');
      return;
    }
    if (pw !== confirm) {
      setError('Passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      await submitForcedPasswordReset(token, pw);
      await signOut(); // clear the flagged session; require a fresh login with the new password
      navigate('/login', { replace: true, state: { passwordChanged: true } });
    } catch {
      setError('Could not set the password. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card route-enter" onSubmit={onSubmit}>
        <Wordmark />
        <div className="login-head">
          <h1>Set your password</h1>
          <p>Choose a new password to finish securing this account.</p>
        </div>

        <label className="field">
          <span className="label">New password</span>
          <input
            className="input"
            type="password"
            autoComplete="new-password"
            value={pw}
            onChange={(e) => setPw(e.target.value)}
            required
            autoFocus
          />
        </label>

        <label className="field">
          <span className="label">Confirm password</span>
          <input
            className="input"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            required
          />
        </label>

        {error && <div className="login-err">{error}</div>}

        <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
          {busy ? 'Saving…' : 'Set password'}
        </button>
      </form>
    </div>
  );
}
