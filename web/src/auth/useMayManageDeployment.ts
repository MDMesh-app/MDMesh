import { useEffect, useState } from 'react';
import { fetchCurrentUser, mayManageDeployment } from '../api/auth';
import { useAuth } from './AuthContext';

// One question per signed-in user and page load: the user stored at sign-in may predate the server's answer (a
// sign-in on an older server), so the server is asked once and the answer kept.
const asked = new Map<number, Promise<boolean>>();

/** Whether the signed-in user may apply, roll back or schedule updates (see mayManageDeployment). */
export function useMayManageDeployment(): boolean {
  const { user } = useAuth();
  const [fresh, setFresh] = useState<boolean | null>(null);
  const id = user?.id;
  useEffect(() => {
    if (id == null) return;
    let on = true;
    let p = asked.get(id);
    if (!p) {
      p = fetchCurrentUser().then((u) => mayManageDeployment(u)).catch(() => {
        asked.delete(id);
        return false;
      });
      asked.set(id, p);
    }
    void p.then((v) => on && setFresh(v));
    return () => {
      on = false;
    };
  }, [id]);
  return fresh ?? mayManageDeployment(user);
}
