// The job request for a bulk action (the Policies server plugin's jobs), and the alert text a removal sends. Pure: no
// network, so bulk.ts and the auto-remove (removal.ts) share it without importing each other.
import type { QueueCommandRequest } from '@mdmesh/console';
import type { JobRequest } from './api';
import type { FleetDevice } from './fleet';
import { removable } from './model';

export type BulkAction =
  | { kind: 'reapply' }
  | { kind: 'scan' }
  | { kind: 'command'; req: QueueCommandRequest; what: string }
  | { kind: 'install' }
  | { kind: 'remove' };

/** The text the alert says when apps are removed from a phone (as removeApps sends it). */
export function removalAlert(labels: readonly string[]): { title: string; body: string } {
  return {
    title: 'App removed by your administrator',
    body: `${labels.join(', ')} ${labels.length === 1 ? 'is' : 'are'} not allowed on this phone.`,
  };
}

/** The job request for an action, or null when there is nothing to send (no extra apps to remove). */
export function jobRequest(action: BulkAction, phones: readonly FleetDevice[], label: string): JobRequest | null {
  const deviceIds = phones.map((d) => d.device.id);
  switch (action.kind) {
    case 'reapply':
    case 'scan':
    case 'install':
      return { action: action.kind, label, deviceIds };
    case 'command':
      return { action: 'command', label, deviceIds, command: { type: action.req.type, payload: action.req.payload } };
    case 'remove': {
      const removals = phones.flatMap((d) => {
        const todo = removable(d.violations ?? []);
        if (!todo.length) return [];
        const note = d.currentPolicy?.data.apps.notify ? removalAlert(todo.map((v) => v.label)) : null;
        return [{ deviceId: d.device.id, packages: todo.map((v) => v.pkg), ...(note ? { alertTitle: note.title, alertBody: note.body } : {}) }];
      });
      return removals.length ? { action: 'remove', label, removals } : null;
    }
  }
}

