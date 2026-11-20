import { useCallback, useEffect, useId, useInsertionEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import * as D from '@radix-ui/react-dialog';
import { IconButton } from './Button';
import { IconClose } from './icons';
import { cx } from './cx';
import { FOCUSABLE, focusPlace, placeOf, type FocusPlace } from './focusPlace';
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
  /** Width: `sm` 400 · `md` 520 (default) · `lg` 720 · `full` the whole viewport (full-screen QR code). */
  size?: 'sm' | 'md' | 'lg' | 'full';
  /** Element to focus on open (default: first focusable in the body, else the close button). */
  initialFocus?: RefObject<HTMLElement | null>;
  /** Keep the dialog open on backdrop clicks (forms with unsaved input). */
  dismissOnBackdrop?: boolean;
  /** `alertdialog` for destructive confirmations: a click outside never dismisses it. */
  role?: 'dialog' | 'alertdialog';
  className?: string;
}

/**
 * Whether the body is a Tab stop of its own: while it scrolls and nothing in it is (a release's notes without a link),
 * so a keyboard can reach it and scroll it. Chromium does this for any such scroller; Safari doesn't. Kept up to date
 * as the body or its content resizes and as its content changes.
 */
function useScrollStop(el: HTMLDivElement | null, open: boolean): boolean {
  const [stop, setStop] = useState(false);
  useEffect(() => {
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
  }, [el, open]);
  return stop;
}

/**
 * Modal dialog on Radix Dialog: the page behind is inert and doesn't scroll, Escape closes, Tab stays inside, and
 * focus returns to the element that opened it. Opens with a short scale-and-fade (180ms) and closes with the same
 * reversed (120ms) while `open` goes false; reduced motion makes both instant. r12 panel on the --elev-3 shadow over a
 * navy scrim; a header (title, description, close) and footer that stay put while a tall body scrolls.
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
  const closeRef = useRef<HTMLButtonElement>(null);
  // The body as state, not only a ref: the portal mounts it after this component, and the scroll stop watches it.
  const [body, setBody] = useState<HTMLDivElement | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const returnTo = useRef<FocusPlace | null>(null);
  // The opener, taken as `open` turns true, before the content mounts: a field in the body with `autoFocus` takes
  // focus before Radix's onOpenAutoFocus runs, so focus then is already inside the dialog.
  const wasOpen = useRef(false);
  useInsertionEffect(() => {
    if (open && !wasOpen.current) {
      returnTo.current = document.activeElement instanceof HTMLElement ? placeOf(document.activeElement) : null;
    }
    wasOpen.current = open;
  }, [open]);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const scrollStop = useScrollStop(body, open && !!children);
  // A stable ref callback (an inline one runs again on every render, with null and then the element).
  const bodyCallback = useCallback((el: HTMLDivElement | null) => {
    bodyRef.current = el;
    setBody(el);
  }, []);
  const alert = role === 'alertdialog';
  const titleId = useId();

  return (
    <D.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) onCloseRef.current();
      }}
    >
      <D.Portal>
        <D.Overlay className="v2-modal__scrim" />
        <D.Content
          role={alert ? 'alertdialog' : 'dialog'}
          {...(description ? {} : { 'aria-describedby': undefined })}
          className={cx('v2-modal', `v2-modal--${size}`, className)}
          onOpenAutoFocus={(e) => {
            // The caller's target, else the first control in the body, else the close button: never the close button
            // ahead of the content (Radix's default is the first focusable in the dialog, which is the close button).
            e.preventDefault();
            // Fallback for an opener not seen as `open` turned true; never an element inside this dialog.
            const active = document.activeElement;
            if (!returnTo.current && active instanceof HTMLElement && !(e.currentTarget as Node).contains(active)) {
              returnTo.current = placeOf(active);
            }
            // The first control that is drawn (one in a collapsed or hidden part has no boxes), else the first not
            // inside a `hidden` part (where nothing has boxes yet).
            const controls = Array.from(bodyRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
            const target =
              initialFocus?.current ??
              controls.find((x) => x.getClientRects().length > 0) ??
              controls.find((x) => !x.closest('[hidden]')) ??
              closeRef.current;
            target?.focus();
          }}
          onCloseAutoFocus={(e) => {
            // Back to the element that opened the dialog (there is no Radix trigger to return to), or, when what
            // the dialog did removed it with its list item (a Delete), to the item's neighbour (ui/focusPlace).
            e.preventDefault();
            if (returnTo.current) focusPlace(returnTo.current);
          }}
          onPointerDownOutside={(e) => {
            if (alert || !dismissOnBackdrop) e.preventDefault();
          }}
          onInteractOutside={(e) => {
            if (alert || !dismissOnBackdrop) e.preventDefault();
          }}
        >
          <div className="v2-modal__panel">
            <div className="v2-modal__head">
              <div className="v2-modal__titles">
                <D.Title className="v2-modal__title">
                  <span id={titleId}>{title}</span>
                </D.Title>
                {description ? <D.Description className="v2-modal__desc">{description}</D.Description> : null}
              </div>
              <IconButton ref={closeRef} label="Close" size="sm" onClick={() => onCloseRef.current()}>
                <IconClose />
              </IconButton>
            </div>
            {children ? (
              <div
                ref={bodyCallback}
                className="v2-modal__body"
                {...(scrollStop ? { tabIndex: 0, role: 'group', 'aria-labelledby': titleId } : {})}
              >
                {children}
              </div>
            ) : null}
            {footer ? <div className="v2-modal__foot">{footer}</div> : null}
          </div>
        </D.Content>
      </D.Portal>
    </D.Root>
  );
}
