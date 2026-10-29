// Device presence: online when its last check-in is recent.

/** Online if the last check-in is within this window (ms). */
export const ONLINE_WINDOW_MS = 10 * 60 * 1000;

export function isOnline(lastUpdate?: number, now: number = Date.now()): boolean {
  if (!lastUpdate) return false;
  return now - lastUpdate <= ONLINE_WINDOW_MS;
}
