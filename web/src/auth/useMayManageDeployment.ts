import { mayManageDeployment } from '../api/auth';
import { useAuth } from './AuthContext';

/**
 * Whether the signed-in user may apply, roll back, check for or schedule updates (see mayManageDeployment): the
 * server's `deploymentAdmin`, from the sign-in answer and from AuthContext's refresh of GET /private/users/current
 * (when the console starts and when the tab is shown again). The updater asks the server the same question and
 * refuses everyone else; the console only hides what would be refused.
 */
export function useMayManageDeployment(): boolean {
  const { user } = useAuth();
  return mayManageDeployment(user);
}
