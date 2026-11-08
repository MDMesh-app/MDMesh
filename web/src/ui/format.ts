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

export function orDash(v?: string | number | null): string {
  if (v === undefined || v === null || v === '') return DASH;
  return String(v);
}
