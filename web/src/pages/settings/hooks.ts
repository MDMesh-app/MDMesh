// Settings data hooks: the active rollout and the server connection. The supervisor status hook is shared
// (web/src/data/useSupervisor.ts).
import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchAuthOptions } from '../../api/auth';
import { getActiveRollout, type ActiveRollout } from '../../api/rollout';
import { rolloutActive } from '../../data/rollout';

export { rolloutActive };

export interface RolloutState {
  rollout: ActiveRollout | null;
  loaded: boolean;
  /** Why the last refresh failed (the previous rollout stays on screen). */
  loadError: string | null;
  refresh: () => Promise<void>;
  set: (r: ActiveRollout | null) => void;
}

/** Poll cadence while a rollout runs (classic RolloutPanel). */
const ROLLOUT_POLL_MS = 10000;

/**
 * The active agent rollout, polled every 10 s while one runs (classic RolloutPanel). Every request is numbered, and
 * `set` (the answer of a promote, cancel or start) counts as the newest: a poll still in flight when it lands is
 * older than that action and is dropped, so it can't put the previous stage back on screen.
 */
export function useActiveRollout(): RolloutState {
  const [rollout, setRollout] = useState<ActiveRollout | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const on = useRef(true);
  const last = useRef<ActiveRollout | null>(null);
  const seq = useRef(0);
  const refreshRef = useRef<() => Promise<void>>(async () => {});

  const arm = useCallback(() => {
    clearTimeout(timer.current);
    if (on.current && rolloutActive(last.current)) {
      timer.current = setTimeout(() => void refreshRef.current(), ROLLOUT_POLL_MS);
    }
  }, []);

  const refresh = useCallback(async () => {
    clearTimeout(timer.current);
    const id = ++seq.current;
    try {
      const r = await getActiveRollout();
      if (!on.current || id !== seq.current) return;
      last.current = r;
      setRollout(r);
      setLoadError(null);
    } catch (e) {
      if (!on.current || id !== seq.current) return;
      setLoadError((e as Error).message || 'Could not load the rollout.');
    }
    setLoaded(true);
    arm();
  }, [arm]);
  refreshRef.current = refresh;

  useEffect(() => {
    on.current = true;
    void refresh();
    return () => {
      on.current = false;
      seq.current++;
      clearTimeout(timer.current);
    };
  }, [refresh]);

  const set = useCallback(
    (r: ActiveRollout | null) => {
      seq.current++; // anything in flight was sent before this action's answer
      last.current = r;
      setRollout(r);
      arm();
    },
    [arm],
  );

  return { rollout, loaded, loadError, refresh, set };
}

export type Connection = { state: 'checking' } | { state: 'ok'; ms: number } | { state: 'down' };

/** Server reachability, timed: the public auth-options call (cheap, no session needed). */
export function useConnection(): { conn: Connection; recheck: () => void } {
  const [conn, setConn] = useState<Connection>({ state: 'checking' });
  const seq = useRef(0);
  const recheck = useCallback(() => {
    const id = ++seq.current;
    setConn({ state: 'checking' });
    const t0 = performance.now();
    fetchAuthOptions()
      .then(() => {
        if (id === seq.current) setConn({ state: 'ok', ms: Math.max(1, Math.round(performance.now() - t0)) });
      })
      .catch(() => {
        if (id === seq.current) setConn({ state: 'down' });
      });
  }, []);
  useEffect(() => {
    recheck();
    return () => {
      seq.current++;
    };
  }, [recheck]);
  return { conn, recheck };
}
