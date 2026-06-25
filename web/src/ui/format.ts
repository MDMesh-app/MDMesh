// Small display helpers. Render an em dash for absent values.

export const DASH = '—';

export function fmtDateTime(ms?: number): string {
  if (!ms) return DASH;
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return DASH;
  return d.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Compact relative "last seen" — e.g. "3m ago", "2h ago", "5d ago". */
export function fmtRelative(ms?: number, now: number = Date.now()): string {
  if (!ms) return DASH;
  const diff = now - ms;
  if (diff < 0) return 'just now';
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return 'just now';
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day}d ago`;
  const mo = Math.floor(day / 30);
  if (mo < 12) return `${mo}mo ago`;
  return `${Math.floor(mo / 12)}y ago`;
}

export function orDash(v?: string | number | null): string {
  if (v === undefined || v === null || v === '') return DASH;
  return String(v);
}
