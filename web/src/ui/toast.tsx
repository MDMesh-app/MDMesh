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
import { useMediaQuery } from './useMediaQuery';
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
/** On a screen this narrow, two, so the stack leaves most of the focused field in view. */
const NARROW_QUERY = '(max-width: 360px)';
const VISIBLE_NARROW = 2;
/** A pointer that hovers (a mouse or trackpad, not a finger). */
const HOVER_QUERY = '(hover: hover)';
/** How long an announcement stays in its live region (it has been read by then; the toast stays on screen). */
const ANNOUNCE_MS = 7000;

/**
 * Toasts, bottom right, above the configuration editor's save bar. Success and warning go after 5 s, a failure after
 * 30 s; the pointer over the stack or focus in it holds them (2.2.1), and each has a Dismiss button (Escape too).
 * Each message is announced once as it arrives, failures from one assertive live region and the rest from one polite
 * region (4.1.3; plain aria-live, so the empty regions add no alert/status roles to every page); the stack itself is
 * not live. A repeated message shows as one toast with a count, announced again; three show at once (two at 360px or less), in
 * arrival order. A failure waiting behind "N more" gets its full time once it shows; a success or warning runs out on
 * schedule even out of view (it was announced; shown late it would be out of context). Only a hovering pointer holds.
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
  // The pointer holds only where there is one: a touch browser leaves :hover on whatever was last tapped, which would
  // hold the stack after a tap on Dismiss until the next tap elsewhere.
  const held = useCallback(() => {
    const wrap = wrapRef.current;
    if (!wrap) return false;
    const hovered = !!window.matchMedia?.(HOVER_QUERY).matches && wrap.matches(':hover');
    return hovered || wrap.contains(document.activeElement);
  }, []);

  // Each shown toast's running timer, by a token: a toast that goes out of view (behind "N more") drops its token, so
  // its timer does nothing, and it gets the full time again when it shows.
  const running = useRef(new Map<number, object>());
  const expire = useCallback(
    (id: number, ms: number, token: object) => {
      window.setTimeout(() => {
        if (!mounted.current || running.current.get(id) !== token) return;
        if (held()) expire(id, HOLD_RECHECK_MS, token);
        else setToasts((t) => t.filter((x) => x.id !== id));
      }, ms);
    },
    [held],
  );

  // Where focus goes after a toast that had it is dismissed. From the keyboard: the next toast's button, else where
  // it came from. From a pointer (the press focused the button, with no ring): straight back where it came from, or
  // nowhere; focus left in the stack would hold every toast with nothing on screen to show why.
  const cameFrom = useRef<HTMLElement | null>(null);
  const focusAfter = useRef<number | 'back' | 'back-quiet' | null>(null);
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
    else if (cameFrom.current && document.contains(cameFrom.current)) {
      // After a pointer dismiss the user is looking at the stack, not the field: don't scroll the page to it.
      if (target === 'back-quiet') cameFrom.current.focus({ preventScroll: true });
      else cameFrom.current.focus();
    }
    else if (wrap?.contains(document.activeElement)) (document.activeElement as HTMLElement).blur();
  }, [toasts]);

  const dismiss = useCallback((ids: number[], keyboard: boolean) => {
    if (!mounted.current) return;
    setToasts((all) => {
      const wrap = wrapRef.current;
      const focused = wrap?.contains(document.activeElement)
        ? Number(document.activeElement?.closest<HTMLElement>('[data-toast]')?.dataset.toast)
        : NaN;
      const left = all.filter((x) => !ids.includes(x.id));
      if (keyboard && ids.includes(focused)) {
        const at = all.findIndex((x) => x.id === focused);
        const next = all.slice(at + 1).find((x) => !ids.includes(x.id)) ?? [...all.slice(0, at)].reverse().find((x) => !ids.includes(x.id));
        focusAfter.current = next ? next.id : 'back';
      } else if (wrap?.contains(document.activeElement)) {
        focusAfter.current = keyboard ? 'back' : 'back-quiet';
      }
      return left;
    });
  }, []);

  // What the live regions say: each pushed message once, as it arrives. The stack itself is not live, so a toast that
  // moves into view from "N more" is not read again; a repeat is a new push and is.
  const [said, setSaid] = useState<Array<{ id: number; kind: ToastKind; text: string }>>([]);
  const push = useCallback(
    (kind: ToastKind, title: string, body: string) => {
      if (!mounted.current) return;
      const id = ++seq.current;
      setSaid((a) => [...a.slice(-4), { id, kind, text: body ? `${title}. ${body}` : title }]);
      window.setTimeout(() => {
        if (mounted.current) setSaid((a) => a.filter((x) => x.id !== id));
      }, ANNOUNCE_MS);
      setToasts((t) => {
        const same = t.find((x) => x.kind === kind && x.title === title && x.body === body);
        // The repeat of a toast whose Dismiss has keyboard focus takes the focus with it.
        const focusedToast = wrapRef.current?.contains(document.activeElement)
          ? Number(document.activeElement?.closest<HTMLElement>('[data-toast]')?.dataset.toast)
          : NaN;
        if (same && same.id === focusedToast) focusAfter.current = id;
        // A repeat replaces the toast at the newest end, with its count.
        return [...t.filter((x) => x !== same), { id, kind, title, body, count: (same?.count ?? 0) + 1 }];
      });
    },
    [],
  );
  const api = useMemo(() => ({ push }), [push]);

  const narrow = useMediaQuery(NARROW_QUERY);
  const cap = narrow ? VISIBLE_NARROW : VISIBLE;
  // The toast whose button has focus stays in view whatever arrives after it (else its button, and focus, would go).
  const [focusedId, setFocusedId] = useState<number | null>(null);
  const focusedToast = focusedId === null ? undefined : toasts.find((t) => t.id === focusedId);
  const shown =
    focusedToast && !toasts.slice(-cap).includes(focusedToast)
      ? [focusedToast, ...toasts.filter((t) => t !== focusedToast).slice(-(cap - 1))]
      : toasts.slice(-cap);
  // A failure's time runs only while it shows: one waiting behind "N more" can't expire unread. A success or warning
  // keeps its time out of view too, so it never shows long after the newer messages it came before.
  const timed = toasts.filter((t) => t.kind !== 'err' || shown.includes(t));
  const timedIds = timed.map((t) => `${t.id}:${t.kind}`).join(',');
  useEffect(() => {
    const ids = new Set(timed.map((t) => t.id));
    for (const id of [...running.current.keys()]) if (!ids.has(id)) running.current.delete(id);
    for (const t of timed) {
      if (running.current.has(t.id)) continue;
      const token = {};
      running.current.set(t.id, token);
      expire(t.id, TOAST_MS[t.kind], token);
    }
    // timedIds stands for `timed`, which is a new array every render
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timedIds, expire]);
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
      {/* detail 0: a click from the keyboard (Enter, Space), not a pointer. */}
      <button
        type="button"
        className="t-close"
        aria-label="Dismiss"
        aria-describedby={`toast-${t.id}`}
        onClick={(e) => dismiss([t.id], e.detail === 0)}
      >
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
        onFocus={(e) => {
          const id = Number((e.target as HTMLElement).closest<HTMLElement>('[data-toast]')?.dataset.toast);
          setFocusedId(Number.isNaN(id) ? null : id);
        }}
        onBlur={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocusedId(null);
        }}
        onKeyDown={(e) => {
          if (e.key !== 'Escape') return;
          const id = Number((e.target as HTMLElement).closest<HTMLElement>('[data-toast]')?.dataset.toast);
          if (!Number.isNaN(id)) {
            e.preventDefault();
            dismiss([id], true);
          }
        }}
      >
        {more > 0 ? (
          <div className="toast-more">
            <span>{more} more</span>
            <button type="button" className="t-all" onClick={(e) => dismiss(toasts.map((t) => t.id), e.detail === 0)}>
              Dismiss all
            </button>
          </div>
        ) : null}
        <div className="toast-region">{shown.map(render)}</div>
        {/* Two live regions that are always there (a region added with its content is often not read). */}
        <div aria-live="assertive" className="v2-visually-hidden">
          {said.filter((a) => a.kind === 'err').map((a) => (
            <p key={a.id}>{a.text}</p>
          ))}
        </div>
        <div aria-live="polite" className="v2-visually-hidden">
          {said.filter((a) => a.kind !== 'err').map((a) => (
            <p key={a.id}>{a.text}</p>
          ))}
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
