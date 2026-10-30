import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import {
  fetchCurrentUser,
  login as apiLogin,
  logout as apiLogout,
  type AuthUser,
} from '../api/auth';
import { onSessionExpired } from '../api/client';

// Auth is session based on the server, but the SPA still needs to remember
// "am I logged in" across reloads. The session cookie is HttpOnly and not
// readable from JS, so we persist a lightweight copy of the user object in
// localStorage purely as a UI hint. If the cookie has actually expired, the
// next protected API call gets a 401/403: the API client reports it, we drop the
// stored user and flag sessionExpired, and ProtectedRoute redirects to login
// with a session-expired notice and the page to return to after signing in.
//
// The stored copy never carries a token. `passwordResetToken` (the one-time token a flagged account needs to set
// its password) stays in memory for this tab only; after a reload a flagged account simply signs in again to get
// a new one. `authToken` (the server's per-user secret that binds sessions and JWTs to the account) is never needed
// by the console at all, so it is dropped as soon as it arrives, from memory too. A copy stored by an older console
// (or seeded by the screenshot scripts) still has its reset token honoured once, then is rewritten without them.
//
// The role decides which pages the console shows (ext/permissions.ts), and the server applies a role change on the
// next request (AuthFilter reloads the user every time). So the role is refreshed from GET /private/users/current
// when the console starts and whenever the tab becomes visible again. Only `userRole`, `superAdmin`,
// `allDevicesAvailable` (whether the user is limited to some device groups) and `deploymentAdmin` (whether the user
// may manage updates, v0.3.8) are taken from that answer. A refresh
// that fails keeps the copy; a 401/403 signs out like any other call (onSessionExpired).
// Tabs share the session cookie, so a sign-out, a sign-in or a refresh in another tab, which rewrites the stored
// copy, reaches this one through the `storage` event.

const STORAGE_KEY = 'hmdm.admin.user';

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  signIn: (username: string, password: string) => Promise<AuthUser>;
  signOut: () => Promise<void>;
  /** True after a signed-in session was rejected by the server, until the next sign-in. */
  sessionExpired: boolean;
  /**
   * The server has accepted this session in this page load: a sign-in here, or a role refresh answered. A stored
   * sign-in alone is only a hint (the session may have expired, say across an update's restart).
   */
  sessionConfirmed: boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** Persist the UI hint, without the tokens, and without `twoFactor`, which describes one sign-in (see the refresh). */
function storeUser(u: AuthUser) {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { passwordResetToken, authToken, twoFactor, ...rest } = u as AuthUser & { authToken?: unknown };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(rest));
  } catch {
    /* storage may be unavailable; non-fatal */
  }
}

function loadStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as (AuthUser & { authToken?: unknown }) | null;
    if (!parsed || typeof parsed !== 'object') return null;
    // Scrub a copy an older console stored.
    if ('passwordResetToken' in parsed || 'authToken' in parsed || 'twoFactor' in parsed) storeUser(parsed);
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { authToken, twoFactor, ...u } = parsed;
    return u;
  } catch {
    return null;
  }
}

/** What the console decides on: the super-admin flag, the permission names, the device-group limit and whether the
 *  user may manage updates. */
const roleOf = (u: AuthUser) =>
  JSON.stringify([
    !!(u.superAdmin || u.userRole?.superAdmin),
    (u.userRole?.permissions ?? []).map((p) => p.name).sort(),
    u.allDevicesAvailable !== false,
    u.deploymentAdmin === true,
  ]);

/**
 * `current` with the role of `fresh` (the same object when the role is unchanged), or null when `fresh` is not a
 * user with a role, or is another user. The role is kept in the shape sign-in gives it (UserRoleView), as the
 * current-user answer carries the full permission rows.
 */
function withRole(current: AuthUser, fresh: AuthUser | null): AuthUser | null {
  if (!fresh || typeof fresh !== 'object' || fresh.id !== current.id) return null;
  const role = fresh.userRole;
  if (!role || typeof role !== 'object' || !Array.isArray(role.permissions)) return null;
  const next: AuthUser = {
    ...current,
    superAdmin: !!fresh.superAdmin,
    allDevicesAvailable: fresh.allDevicesAvailable !== false,
    // Absent (a server before 0.3.8) counts as no: the console only hides what the updater would refuse.
    deploymentAdmin: fresh.deploymentAdmin === true,
    userRole: {
      id: role.id,
      name: role.name,
      superAdmin: !!role.superAdmin,
      permissions: role.permissions.map((p) => ({ name: p.name })),
    },
  };
  return roleOf(next) === roleOf(current) ? current : next;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(loadStoredUser);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [sessionConfirmed, setSessionConfirmed] = useState(false);
  // The user as of the last change, ahead of the render that shows it: a role refresh that answers in between (say,
  // while a sign-out is being rendered) must see the change, or it would sign the old user back in.
  const userRef = useRef(user);
  const replaceUser = useCallback((next: AuthUser | null) => {
    userRef.current = next;
    setUser(next);
  }, []);

  // Two sessions the server refuses everything but their own next step, so a refresh would only sign them out: one
  // whose account must set its password (passwordReset), and one that has not passed the customer's second sign-in
  // step (twoFactor, set on the sign-in answer). twoFactor is kept in memory only, so it means "this sign-in still
  // owes the second step": the console has no such step yet, and once it has one, passing it must clear the flag.
  // A reload or another tab then lacks the flag and refreshes, which drops a session still owing the step (as its
  // first page request would); that step should then keep such a sign-in out of storage until it passes.
  const refreshId = user && !user.passwordReset && !user.twoFactor ? user.id : null;
  useEffect(() => {
    if (refreshId === null) return;
    let live = true;
    const refresh = () => {
      fetchCurrentUser().then(
        (fresh) => {
          const current = userRef.current;
          if (!live || !current || current.id !== refreshId) return; // signed out or switched meanwhile
          setSessionConfirmed(true);
          // Another user's answer means another tab signed in; its stored copy arrives through `storage`.
          const next = withRole(current, fresh);
          if (!next || next === current) return;
          replaceUser(next);
          storeUser(next);
        },
        () => {
          /* keep the copy; a 401/403 has already signed out through onSessionExpired */
        },
      );
    };
    const onVisible = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    refresh();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      live = false;
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refreshId, replaceUser]);

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key !== STORAGE_KEY && e.key !== null) return; // null: the other tab cleared all of storage
      const next = loadStoredUser();
      const prev = userRef.current;
      if (!next) {
        replaceUser(null);
        setSessionConfirmed(false);
        return;
      }
      // The same account keeps what only this tab holds (a reset token); the same values keep the object.
      const merged = prev && prev.id === next.id ? { ...prev, ...next } : next;
      if (!prev || JSON.stringify(merged) !== JSON.stringify(prev)) replaceUser(merged);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [replaceUser]);

  useEffect(() => onSessionExpired(() => {
    if (!userRef.current) return; // e.g. polls still in flight after an explicit sign-out
    replaceUser(null);
    setSessionExpired(true);
    setSessionConfirmed(false);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* non-fatal */
    }
  }), [replaceUser]);

  const signIn = useCallback(async (username: string, password: string) => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { authToken, ...u } = (await apiLogin(username, password)) as AuthUser & { authToken?: unknown };
    replaceUser(u);
    setSessionExpired(false);
    setSessionConfirmed(true);
    storeUser(u);
    return u;
  }, [replaceUser]);

  const signOut = useCallback(async () => {
    await apiLogout();
    replaceUser(null);
    setSessionConfirmed(false);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* non-fatal */
    }
  }, [replaceUser]);

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: user !== null, signIn, signOut, sessionExpired, sessionConfirmed }),
    [user, signIn, signOut, sessionExpired, sessionConfirmed],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
