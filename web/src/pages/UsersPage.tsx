import { Navigate } from 'react-router';
import { paths } from '../routes';

/** Preserve links shared before user administration moved into Settings. */
export function UsersPage() {
  return <Navigate to={`${paths.settings}#users`} replace />;
}
