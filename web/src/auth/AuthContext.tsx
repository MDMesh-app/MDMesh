import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
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

const STORAGE_KEY = 'hmdm.admin.user';

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  signIn: (username: string, password: string) => Promise<AuthUser>;
  signOut: () => Promise<void>;
  /** True after a signed-in session was rejected by the server, until the next sign-in. */
  sessionExpired: boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function loadStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(loadStoredUser);
  const [sessionExpired, setSessionExpired] = useState(false);

  useEffect(() => onSessionExpired(() => {
    if (!user) return; // e.g. polls still in flight after an explicit sign-out
    setUser(null);
    setSessionExpired(true);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* non-fatal */
    }
  }), [user]);

  const signIn = useCallback(async (username: string, password: string) => {
    const u = await apiLogin(username, password);
    setUser(u);
    setSessionExpired(false);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(u));
    } catch {
      /* storage may be unavailable; non-fatal */
    }
    return u;
  }, []);

  const signOut = useCallback(async () => {
    await apiLogout();
    setUser(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* non-fatal */
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: user !== null, signIn, signOut, sessionExpired }),
    [user, signIn, signOut, sessionExpired],
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
