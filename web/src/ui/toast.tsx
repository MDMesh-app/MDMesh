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
import './Toast.css';

/** `warn`: it went through, but not as asked (a sync that waits for the device's next check-in). */
export type ToastKind = 'ok' | 'warn' | 'err';

interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  body: string;
}

export interface ToastApi {
  push: (kind: ToastKind, title: string, body: string) => void;
}

const Ctx = createContext<ToastApi | null>(null);

/** How long a success or warning toast stays; a failure stays until dismissed. */
const TOAST_MS = 5000;
/** After the pointer or focus leaves the stack, how long a toast that came due meanwhile stays. */
const RELEASE_MS = 2000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);
  // A dismissal that comes due after the provider is gone (or a test's page with it) does nothing. Pending timers
  // are not cancelled instead: StrictMode's trial unmount in development would cancel a toast pushed while mounting.
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const dismiss = useCallback((id: number) => {
    if (mounted.current) setToasts((t) => t.filter((x) => x.id !== id));
  }, []);

  // Pointer over the stack or focus in it holds every dismissal (2.2.1): one that comes due meanwhile waits until
  // the pointer and focus leave, then goes after a short grace.
  const held = useRef({ hover: false, focus: false });
  const due = useRef(new Set<number>());
  const release = useCallback(() => {
    if (held.current.hover || held.current.focus || !due.current.size) return;
    const ids = [...due.current];
    due.current.clear();
    window.setTimeout(() => {
      if (mounted.current) setToasts((t) => t.filter((x) => !ids.includes(x.id)));
    }, RELEASE_MS);
  }, []);

  const push = useCallback(
    (kind: ToastKind, title: string, body: string) => {
      if (!mounted.current) return;
      const id = ++seq.current;
      setToasts((t) => [...t, { id, kind, title, body }]);
      // A failure stays until it is dismissed: it may be the only place the reason is shown.
      if (kind === 'err') return;
      window.setTimeout(() => {
        if (!mounted.current) return;
        if (held.current.hover || held.current.focus) due.current.add(id);
        else dismiss(id);
      }, TOAST_MS);
    },
    [dismiss],
  );
  const api = useMemo(() => ({ push }), [push]);

  return (
    <Ctx.Provider value={api}>
      {children}
      <div
        className="toast-wrap"
        aria-live="polite"
        aria-atomic="false"
        onPointerEnter={() => {
          held.current.hover = true;
        }}
        onPointerLeave={() => {
          held.current.hover = false;
          release();
        }}
        onFocus={() => {
          held.current.focus = true;
        }}
        onBlur={(e) => {
          if (e.currentTarget.contains(e.relatedTarget as Node | null)) return;
          held.current.focus = false;
          release();
        }}
      >
        {toasts.map((t) => (
          // A failure is an alert (announced at once); the rest are polite status messages (4.1.3).
          <div key={t.id} className={`toast ${t.kind}`} role={t.kind === 'err' ? 'alert' : 'status'}>
            <span className="t-ic" aria-hidden="true">
              {t.kind === 'ok' ? <CheckMark /> : t.kind === 'warn' ? <BangMark /> : <CrossMark />}
            </span>
            <div className="t-main">
              <div className="t-title">{t.title}</div>
              {t.body && <div className="t-body">{t.body}</div>}
            </div>
            <button type="button" className="t-close" aria-label={`Dismiss: ${t.title}`} onClick={() => dismiss(t.id)}>
              <CrossMark />
            </button>
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}

function CheckMark() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

function CrossMark() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round">
      <path d="M6 6l12 12M18 6 6 18" />
    </svg>
  );
}

function BangMark() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round">
      <path d="M12 5v9M12 19.5v.01" />
    </svg>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useToast(): ToastApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}
