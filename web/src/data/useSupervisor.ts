// Supervisor (decoupled updater) status, one source for the whole signed-in console: the sidebar's update card,
// the Overview server card and Settings → Updates read the same state, so a "Check now", an "Update now" or an
// automatic update seen on one of them shows on the others at once.
import {
  createContext,
  createElement,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { getUpdateStatus, isApplyTerminal, type UpdateStatus } from '../api/updates';

/** Poll cadence while an apply runs (as the the v0.3 console's UpdateBanner). */
const APPLY_POLL_MS = 3000;
/** Idle cadence when only the sidebar card is showing (the v0.3 console's UpdateBanner). */
const DEFAULT_IDLE_MS = 30 * 60 * 1000;

export const applyRunning = (s: UpdateStatus | null | undefined): boolean =>
  !!s?.apply && !isApplyTerminal(s.apply.phase);

export interface SupervisorState {
  /** Last good status (kept while the supervisor is briefly unreachable during an apply). */
  status: UpdateStatus | null;
  /** The first request has finished (either way). */
  loaded: boolean;
  /** The last request reached the supervisor. */
  reachable: boolean;
  /** Re-fetch now (and re-arm the poll: fast while an apply runs). */
  refresh: () => Promise<void>;
  /** Replace the status with one a POST returned (/update/check). */
  setStatus: (s: UpdateStatus) => void;
  /** Patch the status locally (the auto toggle). */
  patch: (p: Partial<UpdateStatus>) => void;
}

interface SupervisorCtx extends SupervisorState {
  /** A consumer asks for an idle cadence; returns the release. The provider polls at the fastest one asked for. */
  demand: (idlePollMs: number) => () => void;
}

const Ctx = createContext<SupervisorCtx | null>(null);

/**
 * Owns the `/update/status` poll (served at the origin, up even while the Java server restarts): fast while an
 * apply runs, otherwise at the fastest idle cadence a mounted consumer asked for (30 min with just the sidebar,
 * 5 min while Settings or the Overview is open).
 */
export function SupervisorProvider({ children }: { children: ReactNode }) {
  const [status, setStatusState] = useState<UpdateStatus | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [reachable, setReachable] = useState(true);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const on = useRef(true);
  const last = useRef<UpdateStatus | null>(null);
  const loadedRef = useRef(false);
  // Each fetch gets a number; an answer older than a status set since (a POST's, or a newer fetch's) is dropped.
  const seq = useRef(0);
  const demands = useRef<number[]>([]);
  const refreshRef = useRef<() => Promise<void>>(async () => {});

  const arm = useCallback(() => {
    clearTimeout(timer.current);
    if (!on.current) return;
    const idle = Math.min(DEFAULT_IDLE_MS, ...demands.current);
    // While an apply runs the supervisor can drop a request or two (Caddy restarting): keep polling fast.
    timer.current = setTimeout(() => void refreshRef.current(), applyRunning(last.current) ? APPLY_POLL_MS : idle);
  }, []);

  const refresh = useCallback(async () => {
    clearTimeout(timer.current);
    const id = ++seq.current;
    const x = await getUpdateStatus();
    if (!on.current || id !== seq.current) return;
    if (x) {
      last.current = x;
      setStatusState(x);
    }
    setReachable(!!x);
    setLoaded(true);
    loadedRef.current = true;
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

  const setStatus = useCallback(
    (s: UpdateStatus) => {
      seq.current++; // a fetch still in flight is older than this
      last.current = s;
      setStatusState(s);
      setReachable(true);
      setLoaded(true);
      loadedRef.current = true;
      arm();
    },
    [arm],
  );
  const patch = useCallback((p: Partial<UpdateStatus>) => {
    seq.current++;
    const next = last.current ? { ...last.current, ...p } : last.current;
    last.current = next;
    setStatusState(next);
    arm(); // a dropped fetch would otherwise leave no poll armed
  }, [arm]);

  const demand = useCallback(
    (ms: number) => {
      const before = Math.min(DEFAULT_IDLE_MS, ...demands.current);
      demands.current = [...demands.current, ms];
      // A faster cadence starts now, not when the slower timer fires.
      if (ms < before && loadedRef.current) arm();
      return () => {
        const i = demands.current.indexOf(ms);
        if (i >= 0) demands.current = [...demands.current.slice(0, i), ...demands.current.slice(i + 1)];
      };
    },
    [arm],
  );

  const value = useMemo<SupervisorCtx>(
    () => ({ status, loaded, reachable, refresh, setStatus, patch, demand }),
    [status, loaded, reachable, refresh, setStatus, patch, demand],
  );
  return createElement(Ctx.Provider, { value }, children);
}

/**
 * The shared supervisor status (needs SupervisorProvider, which AppShell mounts). `idlePollMs` is how fresh this
 * consumer wants the idle status: the provider polls at the fastest cadence any mounted consumer asks for.
 */
export function useSupervisor(idlePollMs = DEFAULT_IDLE_MS): SupervisorState {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useSupervisor must be used within SupervisorProvider');
  const { demand, status, loaded, reachable, refresh, setStatus, patch } = ctx;
  useEffect(() => demand(idlePollMs), [demand, idlePollMs]);
  return { status, loaded, reachable, refresh, setStatus, patch };
}
