import type { Configuration } from '../api/configurations';
import { KIOSK_AFFECTING_KEYS } from './configFields';

function appsKey(c: Configuration): string {
  const apps = (c.applications ?? []) as { id: number; action?: number; showIcon?: boolean }[];
  return apps.map((a) => `${a.id}:${a.action ?? 1}:${a.showIcon === false ? 0 : 1}`).sort().join(',');
}

/**
 * Keys whose change will re-enter/exit kiosk on the configuration's devices. Pure.
 * `before` must be the loaded baseline INCLUDING its `applications` — never the
 * list-endpoint row, which omits `applications` entirely and would make every
 * save look like an apps change.
 */
export function kioskAffectingChanges(before: Configuration, after: Configuration): string[] {
  const changed: string[] = [];
  for (const k of KIOSK_AFFECTING_KEYS) {
    if (k === 'applications') { if (appsKey(before) !== appsKey(after)) changed.push(k); continue; }
    if ((before[k] ?? null) !== (after[k] ?? null)) changed.push(k);
  }
  // Only kiosk-relevant when kiosk is/was on.
  return before.kioskMode || after.kioskMode ? changed : [];
}
