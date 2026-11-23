import type { HTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';
import './Badge.css';

/**
 * - `outline`: white with the control border, text-2 ("Kiosk" tag)
 * - `neutral`: surface-2 fill with the card border ("Built-in" with a lock, file-extension chips)
 * - `count`: white, card border, mono 11.5/500 (sidebar "Devices 248")
 * - `ok` / `warn` / `alert` / `idle`: soft fill + line + ink (checksum, command status, attention filters)
 * - `ink`: ink fill, on-ink text (current stepper step)
 * - `quiet`: no fill, card border, inherits colour ("Later")
 */
export type BadgeTone = 'outline' | 'neutral' | 'count' | 'ok' | 'warn' | 'alert' | 'idle' | 'ink' | 'quiet';

/**
 * - `xs` 0×6, 11px ("Later", "2× hardware ID")
 * - `sm` 0×8, 12px (card tags "Kiosk" / "Built-in", count badge)
 * - `md` 1×9, 12.5/500 (checksum, "Signature verified", command status); outline md = editor "Kiosk" tag (0×9, 400)
 * - `lg` 2×10, 13px (attention-strip filters, stepper)
 */
export type BadgeSize = 'xs' | 'sm' | 'md' | 'lg';

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  size?: BadgeSize;
  icon?: ReactNode;
  mono?: boolean;
  /** Squarer chip (r7) instead of a pill: file-extension chips. */
  square?: boolean;
}

export function Badge({ tone = 'outline', size = 'sm', icon, mono, square, className, children, ...rest }: BadgeProps) {
  return (
    <span
      className={cx(
        'v2-badge',
        `v2-badge--${tone}`,
        `v2-badge--${size}`,
        mono && 'v2-badge--mono',
        square && 'v2-badge--square',
        className,
      )}
      {...rest}
    >
      {icon ? <span className="v2-badge__icon">{icon}</span> : null}
      {children}
    </span>
  );
}
