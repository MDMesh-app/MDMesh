// The sign-in, set-password and change-password forms' answers to the server's v0.3.8 and v0.3.9 refusals: the sign-in
// limit (LoginThrottle) says so instead of "Wrong login or password." (the current-password check of a password change
// counts too), and a reset token the server no longer takes (it works once, for 24 hours) sends the flagged session
// back to sign in, which issues a fresh one.
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '../api/auth';
import { renderInConsole } from '../test/console';
import { LoginPage } from './LoginPage';
import { SetPasswordPage } from './SetPasswordPage';
import { ChangePasswordModal } from './settings/ChangePasswordModal';

const LOAD = { timeout: 3000 };

/** Every call answers `answer(path)`: an envelope, or an HTTP status with no body. */
function stubServer(answer: (path: string) => { status?: string; message?: string; data?: unknown } | number) {
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const a = answer(String(input));
    if (typeof a === 'number') return new Response(null, { status: a });
    return new Response(JSON.stringify({ status: 'OK', message: null, data: null, ...a }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

function signInWith(login: string, password: string) {
  fireEvent.change(screen.getByLabelText('Login'), { target: { value: login } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('Sign in', () => {
  it('says "Too many failed sign-ins" when the server limits sign-in (error.auth.too.many.attempts)', async () => {
    stubServer((p) => (p.includes('/public/auth/login') ? { status: 'ERROR', message: 'error.auth.too.many.attempts' } : {}));
    renderInConsole(<LoginPage />, { user: null, path: '/login' });

    signInWith('admin', 'guess');

    const alert = await screen.findByRole('alert', undefined, LOAD);
    expect(alert.textContent).toBe('Too many failed sign-ins. Wait a few minutes, then try again.');
  });

  it('says the same for an HTTP 429', async () => {
    stubServer((p) => (p.includes('/public/auth/login') ? 429 : {}));
    renderInConsole(<LoginPage />, { user: null, path: '/login' });

    signInWith('admin', 'guess');

    expect((await screen.findByRole('alert', undefined, LOAD)).textContent).toMatch(/^Too many failed sign-ins\./);
  });

  it('still says "Wrong login or password." for any other refusal', async () => {
    stubServer((p) => (p.includes('/public/auth/login') ? { status: 'ERROR', message: null as unknown as string } : {}));
    renderInConsole(<LoginPage />, { user: null, path: '/login' });

    signInWith('admin', 'wrong');

    expect((await screen.findByRole('alert', undefined, LOAD)).textContent).toBe('Wrong login or password.');
  });
});

describe('Set password', () => {
  const flagged: AuthUser = {
    id: 12,
    login: 'admin',
    passwordReset: true,
    passwordResetToken: 'token-1',
    userRole: { id: 2, permissions: [] },
  };

  function renderFlagged() {
    // The reset token lives in memory only (AuthContext), so the page is reached the way the console reaches it: a
    // sign-in that answers passwordReset with the token.
    renderInConsole(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/set-password" element={<SetPasswordPage />} />
      </Routes>,
      { user: null, path: '/login' },
    );
    signInWith('admin', 'admin');
  }

  it('sends a refused reset token back to sign in, which says the change expired', async () => {
    stubServer((p) =>
      p.includes('/public/auth/login')
        ? { data: flagged }
        : p.includes('/public/passwordReset/reset')
          ? { status: 'ERROR', message: 'error.user.not.found' }
          : {},
    );
    renderFlagged();
    await screen.findByLabelText('New password', undefined, LOAD);
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'long-enough-1' } });
    fireEvent.change(screen.getByLabelText('Type it again'), { target: { value: 'long-enough-1' } });
    fireEvent.click(screen.getByRole('button', { name: /Set password/ }));

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe('That password change expired. Sign in again to set your password.'),
    LOAD);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
  });
});

describe('Change password (Settings)', () => {
  it('says "Too many failed sign-ins" when the current-password check is limited', async () => {
    stubServer((p) => (p.includes('/private/users/current') ? { status: 'ERROR', message: 'error.auth.too.many.attempts' } : {}));
    renderInConsole(<ChangePasswordModal open user={{ id: 12, login: 'admin' }} onClose={() => {}} />, { path: '/settings' });

    fireEvent.change(await screen.findByLabelText('Current password', undefined, LOAD), { target: { value: 'guess-one' } });
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'long-enough-1' } });
    fireEvent.change(screen.getByLabelText('Type it again'), { target: { value: 'long-enough-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() =>
      expect(screen.getByText('Too many failed sign-ins. Wait a few minutes, then try again.')).toBeTruthy(),
    LOAD);
  });
});
