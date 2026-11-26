// Removing extra apps (installed, outside the allowed list): one click (removeApps) and the policies' auto-remove
// (useAutoRemove). Only user-installed apps are ever removed: findExtras leaves system apps out.
import { useEffect, useRef } from 'react';
import { usePermission, useToast } from '@mdmesh/console';
import { alert, queue, syncNow, uninstall } from './api';
import type { Fleet, FleetDevice } from './fleet';
import { removalAlert } from './jobs';
import { removable } from './model';
import { plural } from './shared';

/** Queue the removal of a phone's removable extra apps (all, or `only`), alert its user when the policy says so. */
export async function removeApps(d: FleetDevice, only?: readonly string[]): Promise<number> {
  const todo = removable(d.violations ?? []).filter((v) => !only || only.includes(v.pkg));
  if (!todo.length) return 0;
  for (const v of todo) await queue(d.device.number, uninstall(v.pkg));
  if (d.currentPolicy?.data.apps.notify) {
    const note = removalAlert(todo.map((v) => v.label));
    await queue(d.device.number, alert(note.title, note.body)).catch(() => undefined);
  }
  await syncNow(d.device.number).catch(() => undefined);
  return todo.length;
}

/**
 * Auto-remove without the Policies server plugin: on a policy set to it, extra apps are removed as soon as the
 * Policies page sees them, whichever view is open. One request per phone and app for the page's lifetime, so a slow
 * phone doesn't get the same uninstall twice.
 */
export function useAutoRemove(fleet: Fleet): void {
  const canAct = usePermission({ permission: 'edit_devices' });
  const toast = useToast();
  const asked = useRef(new Set<string>());
  const { devices, refreshDevices, source } = fleet;

  useEffect(() => {
    // With the Policies server plugin the server removes extra apps itself after each scan (AutomationService), so
    // the page doesn't: it would send the same uninstalls twice.
    if (!canAct || !devices || source !== 'perPhone') return;
    const key = (d: FleetDevice, pkg: string) => `${d.device.id}:${pkg}`;
    const due = devices.filter(
      (d) => d.currentPolicy?.data.apps.mode === 'auto' && removable(d.violations ?? []).some((v) => !asked.current.has(key(d, v.pkg))),
    );
    if (!due.length) return;
    void (async () => {
      let total = 0;
      for (const d of due) {
        const pkgs = removable(d.violations ?? []).map((v) => v.pkg).filter((p) => !asked.current.has(key(d, p)));
        pkgs.forEach((p) => asked.current.add(key(d, p)));
        total += await removeApps(d, pkgs).catch(() => 0);
      }
      if (total) {
        toast.push('ok', 'Extra apps removed automatically', `${plural(total, 'app')} on ${plural(due.length, 'phone')}`);
        refreshDevices(due.map((d) => d.device.id));
      }
    })();
  }, [devices, canAct, toast, refreshDevices, source]);
}
