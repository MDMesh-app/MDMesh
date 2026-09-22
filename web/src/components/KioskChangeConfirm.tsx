import type { Configuration } from '../api/configurations';
import { KIOSK_AFFECTING_KEYS } from '../data/configFields';

function appsKey(c: Configuration): string {
  const apps = (c.applications ?? []) as { id: number; action?: number }[];
  return apps.map((a) => `${a.id}:${a.action ?? 1}`).sort().join(',');
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

export function KioskChangeConfirm({ count, keys, onCancel, onConfirm }: { count: number; keys: string[]; onCancel: () => void; onConfirm: () => void }) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Change kiosk on {count} device{count === 1 ? '' : 's'}?</h3>
        <p>This edit changes kiosk settings ({keys.join(', ')}). Every device assigned to this configuration will re-apply kiosk at its next check-in, usually within seconds. Turning kiosk off lifts it only on devices that entered kiosk through this configuration.</p>
        <div className="modal-actions">
          <button className="btn" onClick={onCancel}>Keep editing</button>
          <button className="btn btn-primary" onClick={onConfirm}>Save and apply</button>
        </div>
      </div>
    </div>
  );
}
