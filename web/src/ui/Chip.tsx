import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { cx } from './cx';
import './Badge.css';

export interface ChipProps {
  children: ReactNode;
  tone?: 'ok' | 'warn' | 'alert' | 'idle' | 'outline';
  /** Router link ("1 config failed" → filtered Devices). */
  to?: string;
  /** Button behaviour (toggle a filter); pair with `pressed`. */
  onClick?: () => void;
  /** Toggle state for filter chips (aria-pressed). */
  pressed?: boolean;
  icon?: ReactNode;
  className?: string;
}

/**
 * Clickable pill: the Devices attention-strip filters ("1 config failed", "9 agent out of date",
 * "4 battery low"): 2×10, r999, soft fill + line + ink, 13px. A link with `to`, otherwise a button.
 */
export function Chip({ children, tone = 'warn', to, onClick, pressed, icon, className }: ChipProps) {
  const cls = cx('v2-badge', `v2-badge--${tone}`, 'v2-badge--lg', 'v2-chip', pressed && 'is-pressed', className);
  const body = (
    <>
      {icon ? <span className="v2-badge__icon">{icon}</span> : null}
      {children}
    </>
  );
  if (to) {
    return (
      <Link to={to} className={cls}>
        {body}
      </Link>
    );
  }
  return (
    <button type="button" className={cls} onClick={onClick} aria-pressed={pressed}>
      {body}
    </button>
  );
}
