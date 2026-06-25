// Maps the server's device statusCode colour (green/red/yellow/brown/grey)
// onto our instrument status tones (ok/warn/alert/idle) + a human label.
// Brand amber is never used for status — only ok/warn/alert/idle.

export type StatusTone = 'ok' | 'warn' | 'alert' | 'idle';

interface StatusMeta {
  tone: StatusTone;
  label: string;
}

const MAP: Record<string, StatusMeta> = {
  green: { tone: 'ok', label: 'Online' },
  yellow: { tone: 'warn', label: 'Idle' },
  brown: { tone: 'warn', label: 'Stale' },
  red: { tone: 'alert', label: 'Offline' },
  grey: { tone: 'idle', label: 'Unknown' },
};

export function statusMeta(code?: string): StatusMeta {
  return MAP[code ?? 'grey'] ?? { tone: 'idle', label: code ?? 'Unknown' };
}

/** Online if last sync is within this window (ms). */
export const ONLINE_WINDOW_MS = 10 * 60 * 1000;

export function isOnline(lastUpdate?: number, now: number = Date.now()): boolean {
  if (!lastUpdate) return false;
  return now - lastUpdate <= ONLINE_WINDOW_MS;
}
