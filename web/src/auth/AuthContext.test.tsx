import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '../api/auth';
import { ROLES, stubApi } from '../test/console';
import { AuthProvider, useAuth } from './AuthContext';

const KEY = 'hmdm.admin.user';
const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
const stored = () => JSON.parse(localStorage.getItem(KEY) ?? 'null') as AuthUser | null;
const isCurrentUser = (path: string) => path.endsWith('/rest/private/users/current');
const currentUserCalls = (fetch: ReturnType<typeof stubApi>) => fetch.mock.calls.filter(([input]) => isCurrentUser(String(input)));

/** An Observer whose role an admin has since given `configurations`, as GET /private/users/current returns it. */
const promoted: AuthUser = {
  ...ROLES.observer,
  userRole: { id: 100, superAdmin: false, permissions: [{ name: 'applications' }, { name: 'files' }, { name: 'configurations' }] },
};

function signedInAs(user: AuthUser) {
  localStorage.setItem(KEY, JSON.stringify(user));
  return renderHook(() => useAuth(), { wrapper });
}

function setVisibility(state: DocumentVisibilityState) {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state);
  document.dispatchEvent(new Event('visibilitychange'));
}

function otherTabWrites(value: AuthUser | null) {
  if (value) localStorage.setItem(KEY, JSON.stringify(value));
  else localStorage.removeItem(KEY);
  window.dispatchEvent(new StorageEvent('storage', { key: KEY, newValue: value && JSON.stringify(value) }));
}

describe('AuthProvider: the role is refreshed from the server', () => {
  it('takes the current role when the console starts, in memory and in the stored copy', async () => {
    const fetch = stubApi((path) => (isCurrentUser(path) ? { ...promoted, authToken: 'secret-token' } : null));
    const { result } = signedInAs(ROLES.observer);
    await waitFor(() => expect(result.current.user?.userRole?.permissions).toContainEqual({ name: 'configurations' }));
    expect(stored()?.userRole?.permissions).toContainEqual({ name: 'configurations' });
    expect(currentUserCalls(fetch)).toHaveLength(1);
    expect((currentUserCalls(fetch)[0] as unknown[])[1]).toMatchObject({ method: 'GET', credentials: 'include' });
  });

  it('takes only the role from the answer: never its authToken, nothing else either', async () => {
    stubApi((path) => (isCurrentUser(path) ? { ...promoted, login: 'renamed', authToken: 'secret-token' } : null));
    const { result } = signedInAs(ROLES.observer);
    await waitFor(() => expect(result.current.user?.userRole?.permissions).toContainEqual({ name: 'configurations' }));
    expect(result.current.user).not.toHaveProperty('authToken');
    expect(result.current.user?.login).toBe(ROLES.observer.login);
    expect(localStorage.getItem(KEY)).not.toContain('secret-token');
  });

  it('takes whether the user reaches every device, which an admin can limit to some groups', async () => {
    stubApi((path) => (isCurrentUser(path) ? { ...ROLES.admin, allDevicesAvailable: false } : null));
    const { result } = signedInAs(ROLES.admin);
    await waitFor(() => expect(result.current.user?.allDevicesAvailable).toBe(false));
    expect(stored()?.allDevicesAvailable).toBe(false);
  });

  it('refreshes again when the tab becomes visible', async () => {
    let answer: AuthUser = ROLES.observer;
    const fetch = stubApi((path) => (isCurrentUser(path) ? answer : null));
    const { result } = signedInAs(ROLES.observer);
    await waitFor(() => expect(currentUserCalls(fetch)).toHaveLength(1));

    answer = promoted;
    act(() => setVisibility('hidden'));
    expect(currentUserCalls(fetch)).toHaveLength(1);
    act(() => setVisibility('visible'));
    await waitFor(() => expect(result.current.user?.userRole?.permissions).toContainEqual({ name: 'configurations' }));
    expect(currentUserCalls(fetch)).toHaveLength(2);
  });

  it('keeps the same user object when the role has not changed (no re-render, no new plugin request)', async () => {
    // The answer carries the full permission rows (UserRolePermission), sign-in only their names.
    const rows = (ROLES.observer.userRole?.permissions ?? []).map((p, i) => ({ id: i + 1, name: p.name, superAdmin: false }));
    const answer = { ...ROLES.observer, userRole: { ...ROLES.observer.userRole, name: 'Observer', permissions: rows } };
    const fetch = stubApi((path) => (isCurrentUser(path) ? answer : null));
    const { result } = signedInAs(ROLES.observer);
    const before = result.current.user;
    await waitFor(() => expect(currentUserCalls(fetch)).toHaveLength(1));
    await act(async () => {});
    expect(result.current.user).toBe(before);
  });

  it.each([
    ['the server cannot be reached', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['the server fails', async () => new Response('', { status: 500 })],
    ['the answer is an error envelope', async () => Response.json({ status: 'ERROR', message: 'x', data: null })],
    ['the answer is not a user', async () => Response.json({ status: 'OK', message: null, data: [] })],
  ])('keeps the current copy when %s', async (_, answer) => {
    const fetch = vi.fn((input: RequestInfo | URL) =>
      isCurrentUser(String(input)) ? answer() : Promise.resolve(Response.json({ status: 'OK', data: null })),
    );
    vi.stubGlobal('fetch', fetch);
    const { result } = signedInAs(ROLES.observer);
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    await act(async () => {});
    expect(result.current.user).toEqual(ROLES.observer);
    expect(stored()).toEqual(ROLES.observer);
  });

  it('ignores an answer for another user (that sign-in reaches this tab through storage)', async () => {
    stubApi((path) => (isCurrentUser(path) ? ROLES.admin : null));
    const { result } = signedInAs(ROLES.observer);
    await act(async () => {});
    expect(result.current.user).toEqual(ROLES.observer);
  });

  it('signs out through the session-expired path on a 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 401 })));
    const { result } = signedInAs(ROLES.observer);
    await waitFor(() => expect(result.current.user).toBeNull());
    expect(result.current.sessionExpired).toBe(true);
    expect(stored()).toBeNull();
  });

  it('does not ask while the account must set its password first (the server refuses that session)', async () => {
    const fetch = stubApi();
    signedInAs({ ...ROLES.admin, passwordReset: true });
    await act(async () => {});
    expect(currentUserCalls(fetch)).toHaveLength(0);
  });

  it('does not ask while the sign-in still owes the second step the customer requires (twoFactor)', async () => {
    const fetch = stubApi((path) => (path.endsWith('/public/auth/login') ? { ...ROLES.admin, twoFactor: true } : null));
    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.signIn('admin', 'password'));
    expect(result.current.user?.twoFactor).toBe(true);
    expect(currentUserCalls(fetch)).toHaveLength(0);
    // The flag describes that sign-in, not the account: the stored copy leaves it out.
    expect(stored()).not.toHaveProperty('twoFactor');
  });

  it('asks for a session whose stored copy says twoFactor (written by an older console)', async () => {
    const fetch = stubApi((path) => (isCurrentUser(path) ? ROLES.admin : null));
    const { result } = signedInAs({ ...ROLES.admin, twoFactor: true });
    await waitFor(() => expect(currentUserCalls(fetch)).toHaveLength(1));
    expect(result.current.user).not.toHaveProperty('twoFactor');
    expect(stored()).not.toHaveProperty('twoFactor');
  });

  it('makes no request signed out', async () => {
    const fetch = stubApi();
    renderHook(() => useAuth(), { wrapper });
    await act(async () => {});
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe('AuthProvider: other tabs', () => {
  it('signs out when another tab signs out', async () => {
    stubApi();
    const { result } = signedInAs(ROLES.admin);
    act(() => otherTabWrites(null));
    expect(result.current.user).toBeNull();
  });

  it('follows another tab signing in as someone else (they share the session cookie)', async () => {
    stubApi();
    const { result } = signedInAs(ROLES.admin);
    act(() => otherTabWrites(ROLES.observer));
    expect(result.current.user).toEqual(ROLES.observer);
  });

  it('follows a role refresh made in another tab', async () => {
    stubApi();
    const { result } = signedInAs(ROLES.observer);
    act(() => otherTabWrites(promoted));
    expect(result.current.user?.userRole?.permissions).toContainEqual({ name: 'configurations' });
  });

  it('ignores other keys', async () => {
    stubApi();
    const { result } = signedInAs(ROLES.admin);
    act(() => window.dispatchEvent(new StorageEvent('storage', { key: 'mdmesh-theme', newValue: 'dark' })));
    expect(result.current.user).toEqual(ROLES.admin);
  });
});

describe('AuthProvider: the stored copy holds no token', () => {
  it('keeps the sign-in answer’s authToken nowhere, and its passwordResetToken in memory only', async () => {
    stubApi((path) =>
      path.endsWith('/public/auth/login') ? { ...ROLES.admin, authToken: 'secret-token', passwordResetToken: 'reset-token' } : null,
    );
    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.signIn('admin', 'password'));
    expect(localStorage.getItem(KEY)).not.toMatch(/secret-token|reset-token|authToken|passwordResetToken/);
    // Nor does this tab keep the authToken in memory: the console never needs it.
    expect(result.current.user).not.toHaveProperty('authToken');
    // This tab keeps the reset token in memory for the set-password page.
    expect(result.current.user?.passwordResetToken).toBe('reset-token');
  });

  it('rewrites a copy stored by an older console without its authToken', () => {
    localStorage.setItem(KEY, JSON.stringify({ ...ROLES.admin, authToken: 'secret-token' }));
    stubApi();
    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(localStorage.getItem(KEY)).not.toContain('secret-token');
    expect(result.current.user).not.toHaveProperty('authToken');
  });
});

describe('AuthProvider: sign-out', () => {
  it('stays signed out when a role refresh answers while the sign-out is being rendered', async () => {
    let answer!: (user: AuthUser) => void;
    const pending = new Promise<AuthUser>((resolve) => (answer = resolve));
    const fetch = vi.fn(async (input: RequestInfo | URL) =>
      Response.json({ status: 'OK', message: null, data: isCurrentUser(String(input)) ? await pending : null }),
    );
    vi.stubGlobal('fetch', fetch);
    const { result } = signedInAs(ROLES.observer);
    await waitFor(() => expect(fetch).toHaveBeenCalled()); // the refresh is in flight
    await act(async () => {
      await result.current.signOut(); // React renders the sign-out after this block...
      answer(promoted); // ...and the refresh answers before that, with a changed role
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(result.current.user).toBeNull();
    expect(stored()).toBeNull();
  });
});
