import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './AuthContext';
import { paths } from '../routes';

/**
 * Guards nested routes: a signed-out user goes to /login with the return path in the query (the sign-in page accepts
 * only a same-origin relative path, auth/safeReturnPath) and `state.sessionExpired` for its notice. The pages are
 * keyed by the account: when another tab signs in as someone else they mount afresh, instead of showing what the
 * previous account loaded. A role refresh keeps the account, so its pages stay as they are.
 */
export function ProtectedRoute() {
  const { user, sessionExpired } = useAuth();
  const location = useLocation();

  if (!user) {
    const from = location.pathname + location.search + location.hash;
    const query = from === paths.overview ? '' : `?from=${encodeURIComponent(from)}`;
    return <Navigate to={`${paths.login}${query}`} replace state={{ sessionExpired }} />;
  }
  return <Outlet key={user.id} />;
}
