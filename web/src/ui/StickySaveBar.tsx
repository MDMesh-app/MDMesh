import { useEffect, useRef, type ReactNode } from 'react';
import { Button } from './Button';
import { cx } from './cx';
import './StickySaveBar.css';

export interface StickySaveBarProps {
  /** Number of changed fields; the bar renders nothing at 0. */
  count: number;
  /** Context sentence ("Kiosk settings change on 2 devices. Their launcher restarts on the next sync."). */
  message?: ReactNode;
  onDiscard: () => void;
  onSave: () => void;
  saveLabel?: string;
  discardLabel?: string;
  /** Saving in progress: both buttons disabled, save label swapped. */
  busy?: boolean;
  busyLabel?: string;
  /** Block saving (e.g. assigned apps failed to load) while still showing the bar. */
  saveDisabled?: boolean;
  className?: string;
}

/**
 * Unsaved-changes bar: sticky 16px above the viewport bottom inside the content column, white,
 * #D6DAE1 border, r14, float shadow. `role="region"` named "Unsaved changes". While mounted it sets
 * scroll-padding-bottom so a focused field never hides under it (WCAG 2.4.11).
 */
export function StickySaveBar({
  count,
  message,
  onDiscard,
  onSave,
  saveLabel = 'Save and apply',
  discardLabel = 'Discard',
  busy,
  busyLabel = 'Saving…',
  saveDisabled,
  className,
}: StickySaveBarProps) {
  const ref = useRef<HTMLDivElement>(null);
  const visible = count > 0;

  useEffect(() => {
    if (!visible || !ref.current) return;
    const root = document.documentElement;
    const prev = root.style.scrollPaddingBottom;
    const apply = () => {
      const h = ref.current?.offsetHeight ?? 0;
      root.style.scrollPaddingBottom = `${h + 32}px`;
    };
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(ref.current);
    return () => {
      ro.disconnect();
      root.style.scrollPaddingBottom = prev;
    };
  }, [visible]);

  if (!visible) return null;
  return (
    <div ref={ref} role="region" aria-label="Unsaved changes" className={cx('v2-savebar', className)}>
      <span className="v2-savebar__count" aria-live="polite">
        <span className="v2-savebar__dot" aria-hidden="true" />
        {count === 1 ? '1 unsaved change' : `${count} unsaved changes`}
      </span>
      {message ? <span className="v2-savebar__msg">{message}</span> : <span className="v2-savebar__msg" />}
      <div className="v2-savebar__actions">
        <Button size="xl" onClick={onDiscard} disabled={busy}>
          {discardLabel}
        </Button>
        <Button size="xl" variant="primary" className="v2-savebar__save" onClick={onSave} disabled={busy || saveDisabled}>
          {busy ? busyLabel : saveLabel}
        </Button>
      </div>
    </div>
  );
}
