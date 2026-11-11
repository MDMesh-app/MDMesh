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
  /** How many times this message came in a row (a repeated failure is one toast with a count). */
  count: number;
}

export interface ToastApi {
  push: (kind: ToastKind, title: string, body: string) => void;
}

const Ctx = createContext<ToastApi | null>(null);

/** How long a toast stays: a failure longer, since it may be the only place the reason is shown. */
const TOAST_MS: Record<ToastKind, number> = { ok: 5000, warn: 5000, err: 30_000 };
/** While the stack is held (pointer over it, focus in it), how often a toast that came due checks again. */
const HOLD_RECHECK_MS = 1000;
/** Toasts shown at once; older ones wait behind "N more". */
const VISIBLE = 3;

/**
 * Toasts, bottom right, above the configuration editor's save bar. Success and warning go after 5 s, a failure after
 * 30 s; the pointer over the stack or focus in it holds them (2.2.1), and each has a Dismiss button (Escape too).
 * Failures are announced from one assertive live region, the rest from one polite region (4.1.3; plain aria-live, so the
 * empty regions are no extra alert/status landmarks on every page); a
 * repeated message comes back as one toast with a count; three show at once.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);
  const wrapRef = useRef<HTMLElement>(null);
  // A dismissal that comes due after the provider is gone (or a test's page with it) does nothing. Pending timers
  // are not cancelled instead: StrictMode's trial unmount in development would cancel a toast pushed while mounting.
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Asked when a toast comes due, never remembered: a toast removed under the pointer or with focus fires no
  // pointerleave or blur, so stored flags could hold the stack for good.
  const held = useCallback(() => {
    const wrap = wrapRef.current;
    return !!wrap && (wrap.matches(':hover') || wrap.contains(document.activeElement));
  }, []);

  const expire = useCallback(
    (id: number, ms: number) => {
      window.setTimeout(() => {
        if (!mounted.current) return;
        if (held()) expire(id, HOLD_RECHECK_MS);
        else setToasts((t) => t.filter((x) => x.id !== id));
      }, ms);
    },
    [held],
  );

  // Where focus goes after a toast that had it is dismissed: the next toast's button, else where it came from.
  const cameFrom = useRef<HTMLElement | null>(null);
  const focusAfter = useRef<number | 'back' | null>(null);
  useEffect(() => {
    const onFocusIn = (e: FocusEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && el !== document.body && !wrapRef.current?.contains(el)) cameFrom.current = el;
    };
    document.addEventListener('focusin', onFocusIn);
    return () => document.removeEventListener('focusin', onFocusIn);
  }, []);
  useEffect(() => {
    const target = focusAfter.current;
    if (target === null) return;
    focusAfter.current = null;
    const wrap = wrapRef.current;
    const next = typeof target === 'number' ? wrap?.querySelector<HTMLElement>(`[data-toast="${target}"] .t-close`) : null;
    if (next) next.focus();
    else if (cameFrom.current && document.contains(cameFrom.current)) cameFrom.current.focus();
  }, [toasts]);

  const dismiss = useCallback((ids: number[]) => {
    if (!mounted.current) return;
    setToasts((all) => {
      const wrap = wrapRef.current;
      const focused = wrap?.contains(document.activeElement)
        ? Number(document.activeElement?.closest<HTMLElement>('[data-toast]')?.dataset.toast)
        : NaN;
      const left = all.filter((x) => !ids.includes(x.id));
      if (ids.includes(focused)) {
        const at = all.findIndex((x) => x.id === focused);
        const next = all.slice(at + 1).find((x) => !ids.includes(x.id)) ?? [...all.slice(0, at)].reverse().find((x) => !ids.includes(x.id));
        focusAfter.current = next ? next.id : 'back';
      } else if (wrap?.contains(document.activeElement)) {
        focusAfter.current = 'back';
      }
      return left;
    });
  }, []);

  const push = useCallback(
    (kind: ToastKind, title: string, body: string) => {
      if (!mounted.current) return;
      const id = ++seq.current;
      setToasts((t) => {
        const same = t.find((x) => x.kind === kind && x.title === title && x.body === body);
        // A repeat replaces the toast (a new node, so it is announced again) and counts.
        return [...t.filter((x) => x !== same), { id, kind, title, body, count: (same?.count ?? 0) + 1 }];
      });
      expire(id, TOAST_MS[kind]);
    },
    [expire],
  );
  const api = useMemo(() => ({ push }), [push]);

  const shown = toasts.slice(-VISIBLE);
  const more = toasts.length - shown.length;
  const render = (t: Toast) => (
    <div key={t.id} data-toast={t.id} className={`toast ${t.kind}`}>
      <span className="t-ic" aria-hidden="true">
        {t.kind === 'ok' ? <CheckMark /> : t.kind === 'warn' ? <BangMark /> : <CrossMark />}
      </span>
      <div className="t-main">
        <div className="t-title" id={`toast-${t.id}`}>
          {t.title}
          {t.count > 1 ? (
            <span className="t-count">
              <span aria-hidden="true"> ×{t.count}</span>
              <span className="v2-visually-hidden"> ({t.count} times)</span>
            </span>
          ) : null}
        </div>
        {t.body && <div className="t-body">{t.body}</div>}
      </div>
      <button type="button" className="t-close" aria-label="Dismiss" aria-describedby={`toast-${t.id}`} onClick={() => dismiss([t.id])}>
        <CrossMark />
      </button>
    </div>
  );

  return (
    <Ctx.Provider value={api}>
      {children}
      <section
        ref={wrapRef}
        className="toast-wrap"
        aria-label="Notifications"
        onKeyDown={(e) => {
          if (e.key !== 'Escape') return;
          const id = Number((e.target as HTMLElement).closest<HTMLElement>('[data-toast]')?.dataset.toast);
          if (!Number.isNaN(id)) {
            e.preventDefault();
            dismiss([id]);
          }
        }}
      >
        {more > 0 ? (
          <div className="toast-more">
            <span>{more} more</span>
            <button type="button" className="t-all" onClick={() => dismiss(toasts.map((t) => t.id))}>
              Dismiss all
            </button>
          </div>
        ) : null}
        {/* Two live regions that are always there (a region added with its content is often not read). */}
        <div aria-live="assertive" className="toast-region">
          {shown.filter((t) => t.kind === 'err').map(render)}
        </div>
        <div aria-live="polite" className="toast-region">
          {shown.filter((t) => t.kind !== 'err').map(render)}
        </div>
      </section>
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
