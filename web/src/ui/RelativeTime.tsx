import { useEffect, useState } from 'react';
import { DASH, fmtDateTime } from './format';

export type RelativeStyle = 'short' | 'long';

/**
 * - `short` (tables and feeds): "20s ago" (with `seconds`) or "just now", "3m ago", "2h ago", "2d ago",
 *   "3mo ago", "1y ago"
 * - `long` (sentences): "just now", "3 minutes ago", "2 hours ago", "Yesterday", "3 days ago",
 *   then the date
 */
export function formatRelative(ms: number, now: number, style: RelativeStyle = 'short', seconds = false): string {
  const diff = Math.max(0, now - ms);
  const sec = Math.floor(diff / 1000);
  const min = Math.floor(sec / 60);
  const hr = Math.floor(min / 60);
  const day = Math.floor(hr / 24);
  if (style === 'short') {
    if (sec < 60) return seconds ? `${sec}s ago` : 'just now';
    if (min < 60) return `${min}m ago`;
    if (hr < 24) return `${hr}h ago`;
    if (day < 30) return `${day}d ago`;
    if (day < 365) return `${Math.floor(day / 30)}mo ago`;
    return `${Math.floor(day / 365)}y ago`;
  }
  const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? '' : 's'} ago`;
  if (sec < 60) return seconds && sec >= 5 ? plural(sec, 'second') : 'just now';
  if (min < 60) return plural(min, 'minute');
  if (hr < 24) return plural(hr, 'hour');
  if (day === 1) return 'Yesterday';
  if (day < 30) return `${day} days ago`;
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/** Re-render cadence: every second under a minute (when seconds show), 30s under an hour, else every 5 minutes. */
function nextTick(ms: number, now: number, seconds: boolean): number {
  const age = now - ms;
  if (age < 60_000) return seconds ? 1000 : 60_000 - age;
  if (age < 3_600_000) return 30_000;
  return 300_000;
}

export interface RelativeTimeProps {
  /** Epoch ms (or an ISO string). Missing renders an em dash. */
  value: number | string | null | undefined;
  style?: RelativeStyle;
  /** Count seconds under a minute ("Updated 20s ago"). */
  seconds?: boolean;
  /** Text before the time ("Updated ", "Checked "). */
  prefix?: string;
  className?: string;
}

/** A ticking <time> with the absolute date in its title. */
export function RelativeTime({ value, style = 'short', seconds = false, prefix = '', className }: RelativeTimeProps) {
  const ms = typeof value === 'string' ? Date.parse(value) : value ?? NaN;
  // A finite number can still be out of Date's range (toISOString would throw).
  const valid = Number.isFinite(ms) && !Number.isNaN(new Date(ms as number).getTime());
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!valid) return;
    const t = setTimeout(() => setNow(Date.now()), nextTick(ms as number, now, seconds));
    return () => clearTimeout(t);
  }, [ms, now, seconds, valid]);
  if (!valid) return <span className={className}>{DASH}</span>;
  return (
    <time dateTime={new Date(ms as number).toISOString()} title={fmtDateTime(ms as number)} className={className}>
      {prefix}
      {formatRelative(ms as number, now, style, seconds)}
    </time>
  );
}
