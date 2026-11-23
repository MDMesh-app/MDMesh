import type { CSSProperties } from 'react';
import { cx } from './cx';
import './Bars.css';

/**
 * Fill colours: status dots, `idle` = offline grey, `pending` = not-reported grey (#D6DAE1), `neutral` = text grey,
 * `track` = empty (shows the track: rollout devices still waiting).
 */
export type BarTone = 'ok' | 'warn' | 'alert' | 'idle' | 'pending' | 'neutral' | 'ink' | 'track';

export interface BarSegment {
  value: number;
  tone: BarTone;
}

/**
 * - `fleet`: 12px tall, 3px gaps, 6px outer ends and 2px inner corners (Overview fleet bar)
 * - `config`: 6px tall, 2px gaps, r3 segments (per-configuration sync bars, rollout cohorts)
 */
export type StackedBarVariant = 'fleet' | 'config';

export interface StackedBarProps {
  segments: ReadonlyArray<BarSegment>;
  /** Accessible summary ("211 online, 14 need attention, 23 offline"). Omit only when the numbers are printed next to it. */
  label?: string;
  variant?: StackedBarVariant;
  /** Grey track behind the segments (configuration cards, rollout). */
  track?: boolean;
  className?: string;
}

/** Proportional segments. With `label` it is `role="img"`; without, it is hidden from assistive tech. */
export function StackedBar({ segments, label, variant = 'config', track, className }: StackedBarProps) {
  const shown = segments.filter((s) => s.value > 0);
  return (
    <div
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      className={cx('v2-stack', `v2-stack--${variant}`, track && 'v2-stack--track', className)}
    >
      {shown.map((s, i) => (
        <span key={i} className={cx('v2-stack__seg', `v2-bar--${s.tone}`)} style={{ flex: `${s.value} 1 0` }} />
      ))}
    </div>
  );
}

export interface ProgressBarProps {
  /** 0-100. */
  value: number;
  tone?: BarTone;
  /** Accessible name; renders role="progressbar" with aria-valuenow. Omit for decorative bars next to a number. */
  label?: string;
  /**
   * - `md`: 6px, r3, full width (Overview agent versions)
   * - `mini`: 40×6 r3 (Devices table battery) · `micro`: up to 48×4 r2 (device card battery)
   */
  size?: 'md' | 'mini' | 'micro';
  className?: string;
  style?: CSSProperties;
}

/** Single fill on a #EEF0F3 track. */
export function ProgressBar({ value, tone = 'ok', label, size = 'md', className, style }: ProgressBarProps) {
  const pct = Math.max(0, Math.min(100, value));
  return (
    <div
      role={label ? 'progressbar' : undefined}
      aria-label={label}
      aria-valuenow={label ? Math.round(pct) : undefined}
      aria-valuemin={label ? 0 : undefined}
      aria-valuemax={label ? 100 : undefined}
      aria-hidden={label ? undefined : true}
      className={cx('v2-progress', `v2-progress--${size}`, className)}
      style={style}
    >
      <span className={cx('v2-progress__fill', `v2-bar--${tone}`)} style={{ width: `${pct}%` }} />
    </div>
  );
}

/** Battery colour rule from the boards: < 15% alert, < 40% warn, otherwise neutral grey. */
// eslint-disable-next-line react-refresh/only-export-components
export function batteryTone(pct: number): BarTone {
  return pct < 15 ? 'alert' : pct < 40 ? 'warn' : 'neutral';
}
