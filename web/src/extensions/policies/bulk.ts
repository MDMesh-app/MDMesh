// Bulk actions on many phones, in one place, so every screen sends them the same way.
//
// With the Policies server plugin and more than one phone, an action is ONE request (a job): the server queues every
// command at once and wakes the phones at a steady rate, and the page shows its progress. One phone, or no plugin,
// runs as before (per phone, or core's bulk endpoint), so nothing depends on the plugin being there.
import {
  createJob,
  queue,
  queueBulk,
  SCAN,
  syncApps,
  syncNow,
} from './api';
import type { Fleet, FleetDevice } from './fleet';
import { jobRequest, type BulkAction } from './jobs';
import { removeApps } from './removal';
import { plural } from './shared';

export type { BulkAction } from './jobs';

/**
 * Run `action` on `phones` and say what happened (the toast text). A job's progress then shows on the page
 * (fleet.trackJob); the per-phone way reports when it is done.
 */
export async function runBulk(fleet: Fleet, phones: readonly FleetDevice[], action: BulkAction, label: string): Promise<string> {
  if (!phones.length) return 'No phones to act on.';
  if (fleet.source === 'plugin' && phones.length > 1) {
    const req = jobRequest(action, phones, label);
    if (!req) return 'No extra apps to remove.';
    const job = await createJob(req);
    fleet.trackJob(job);
    return `Started on ${plural(job.phones, 'phone')}. The server wakes them a few at a time; progress shows at the top of the page.`;
  }
  return perPhone(phones, action);
}

/** The way without the plugin (or for one phone): what the screens did before jobs existed. */
async function perPhone(phones: readonly FleetDevice[], action: BulkAction): Promise<string> {
  const one = phones.length === 1 ? phones[0] : null;
  switch (action.kind) {
    case 'reapply': {
      let offline = 0;
      for (const d of phones) {
        const r = (await syncNow(d.device.number).catch(() => null)) as { connected?: boolean } | null;
        if (r?.connected === false) offline++;
      }
      return `${plural(phones.length, 'phone')} asked to check in and apply their policy.${offline ? ` ${offline} offline get it when they reconnect.` : ''}`;
    }
    case 'scan':
    case 'command': {
      const req = action.kind === 'scan' ? SCAN : action.req;
      const what = action.kind === 'scan' ? 'reports its apps' : action.what;
      if (one) {
        await queue(one.device.number, req);
        await syncNow(one.device.number).catch(() => undefined);
        return `${one.device.description || one.device.number} ${what} at its next sync.`;
      }
      const r = await queueBulk(phones.map((d) => d.device.id), req);
      const skipped = r.skipped.length ? ` ${plural(r.skipped.length, 'phone')} skipped: its agent can't do this.` : '';
      return `${plural(r.queued, 'phone')} queued.${skipped}`;
    }
    case 'install': {
      let n = 0;
      for (const d of phones) n += (await syncApps(d.device.number).catch(() => ({ queued: 0 }))).queued ?? 0;
      return `${plural(n, 'install')} queued on ${plural(phones.length, 'phone')}.`;
    }
    case 'remove': {
      let n = 0;
      for (const d of phones) n += await removeApps(d);
      return n ? `${plural(n, 'app')} will be uninstalled at the phones' next sync.` : 'No extra apps to remove.';
    }
  }
}
