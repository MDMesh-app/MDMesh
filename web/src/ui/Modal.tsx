import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import { IconButton } from './Button';
import { IconClose } from './icons';
import { cx } from './cx';
import './Modal.css';

export interface ModalProps {
  open: boolean;
  /** Called on Escape, the close button and (unless disabled) a backdrop click. */
  onClose: () => void;
  title: ReactNode;
  /** One line under the title; also the dialog's accessible description. */
  description?: ReactNode;
  children?: ReactNode;
  /** Buttons row, right-aligned (put the primary action last). */
  footer?: ReactNode;
  /** Width: `sm` 420 · `md` 540 (default) · `lg` 720 · `full` the whole viewport (full-screen QR code). */
  size?: 'sm' | 'md' | 'lg' | 'full';
  /** Element to focus on open (default: first focusable in the body, else the close button). */
  initialFocus?: RefObject<HTMLElement | null>;
  /** Keep the dialog open on backdrop clicks (forms with unsaved input). */
  dismissOnBackdrop?: boolean;
  /** `alertdialog` for destructive confirmations. */
  role?: 'dialog' | 'alertdialog';
  className?: string;
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Whether the body is a Tab stop of its own: while it scrolls and nothing in it is (a release's notes without a link),
 * so a keyboard can reach it and scroll it. Chromium does this for any such scroller; Safari doesn't. Kept up to date
 * as the body or its content resizes and as its content changes.
 */
function useScrollStop(body: RefObject<HTMLDivElement | null>, open: boolean): boolean {
  const [stop, setStop] = useState(false);
  useEffect(() => {
    const el = body.current;
    if (!open || !el) return;
    const update = () => {
      const scrolls = el.scrollHeight > el.clientHeight + 1;
      const tabbable = Array.from(el.querySelectorAll<HTMLElement>(FOCUSABLE)).some((x) => x.getClientRects().length > 0);
      // Kept while it has focus: dropping it then would drop focus out of the dialog.
      setStop((was) => (scrolls && !tabbable) || (was && document.activeElement === el));
    };
    const sizes = new ResizeObserver(update);
    const watch = () => {
      sizes.observe(el);
      for (const child of Array.from(el.children)) sizes.observe(child);
    };
    const changes = new MutationObserver(() => {
      watch();
      update();
    });
    watch();
    el.addEventListener('focusout', update); // a stop kept only for focus goes once focus leaves
    changes.observe(el, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden', 'disabled', 'tabindex', 'href'] });
    update();
    return () => {
      sizes.disconnect();
      changes.disconnect();
      el.removeEventListener('focusout', update);
      setStop(false);
    };
  }, [body, open]);
  return stop;
}

/**
 * Modal dialog on the native <dialog> + showModal(): the page behind is inert, Escape closes, Tab is kept inside,
 * and focus returns to the element that opened it. The boards draw no modal; it takes the card language
 * (white, 1px border, r16, 20×24 padding) with the float shadow over a navy scrim.
 */
export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  initialFocus,
  dismissOnBackdrop = true,
  role = 'dialog',
  className,
}: ModalProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const returnTo = useRef<HTMLElement | null>(null);
  const downOnBackdrop = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const openRef = useRef(open);
  openRef.current = open;
  const titleId = useId();
  const descId = useId();
  const scrollStop = useScrollStop(bodyRef, open && !!children);

  useEffect(() => {
    const dlg = ref.current;
    if (!dlg) return;
    if (open && !dlg.open) {
      returnTo.current = document.activeElement as HTMLElement | null;
      dlg.showModal();
      const target =
        initialFocus?.current ?? bodyRef.current?.querySelector<HTMLElement>(FOCUSABLE) ?? closeRef.current;
      target?.focus();
      const root = document.documentElement;
      const prev = root.style.overflow;
      root.style.overflow = 'hidden';
      return () => {
        root.style.overflow = prev;
        if (dlg.open) dlg.close();
        const back = returnTo.current;
        if (back && document.contains(back)) back.focus();
      };
    }
    if (!open && dlg.open) dlg.close();
    // initialFocus is read once on open
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // Tab stays inside the dialog (native modal dialogs also let focus reach the browser chrome).
  const onKeyDown = (e: KeyboardEvent<HTMLDialogElement>) => {
    if (e.key !== 'Tab' || !ref.current) return;
    const items = Array.from(ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => el.offsetParent !== null || el === document.activeElement,
    );
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  return (
    <dialog
      ref={ref}
      role={role === 'alertdialog' ? 'alertdialog' : undefined}
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      className={cx('v2-modal', `v2-modal--${size}`, className)}
      onCancel={(e) => {
        e.preventDefault();
        onCloseRef.current();
      }}
      onClose={() => {
        // The browser can close the dialog itself (a second Escape while onClose declined): keep `open` in step.
        // Ignore the close our own effect cleanup queued (StrictMode re-shows the dialog before it fires).
        if (openRef.current && !ref.current?.open) onCloseRef.current();
      }}
      onKeyDown={onKeyDown}
      onPointerDown={(e) => {
        downOnBackdrop.current = e.target === ref.current;
      }}
      onClick={(e) => {
        // Dismiss only when the press started and ended on the backdrop, not when a drag from the panel (selecting
        // text in an input) ends outside it.
        const fromBackdrop = downOnBackdrop.current;
        downOnBackdrop.current = false;
        if (dismissOnBackdrop && fromBackdrop && e.target === ref.current) onCloseRef.current();
      }}
    >
      {open ? (
        <div className="v2-modal__panel">
          <div className="v2-modal__head">
            <div className="v2-modal__titles">
              <h2 id={titleId} className="v2-modal__title">
                {title}
              </h2>
              {description ? (
                <p id={descId} className="v2-modal__desc">
                  {description}
                </p>
              ) : null}
            </div>
            <IconButton ref={closeRef} label="Close" size="sm" onClick={() => onCloseRef.current()}>
              <IconClose />
            </IconButton>
          </div>
          {children ? (
            <div
              ref={bodyRef}
              className="v2-modal__body"
              {...(scrollStop ? { tabIndex: 0, role: 'group', 'aria-labelledby': titleId } : {})}
            >
              {children}
            </div>
          ) : null}
          {footer ? <div className="v2-modal__foot">{footer}</div> : null}
        </div>
      ) : null}
    </dialog>
  );
}
