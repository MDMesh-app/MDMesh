import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './AuthContext';

/** Guards nested routes, redirecting unauthenticated users to /login. */
export function ProtectedRoute() {
  const { isAuthenticated, sessionExpired } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    const from = location.pathname + location.search + location.hash;
    return <Navigate to="/login" replace state={{ from, sessionExpired }} />;
  }
  return <Outlet />;
}
