import { describe, expect, it } from 'vitest';
import type { DeviceView } from '@mdmesh/console';
import type { Job } from './api';
import type { FleetDevice } from './fleet';
import { jobPercent, jobSummary } from './JobBar';
import { jobRequest, removalAlert } from './jobs';
import { parsePolicy } from './model';

const phone = (id: number, extras: string[], notify = true): FleetDevice =>
  ({
    device: { id, number: `P-${id}` } as DeviceView,
    violations: extras.map((pkg) => ({ pkg, label: pkg.split('.').pop()!, system: false, removing: pkg.endsWith('.busy') })),
    currentPolicy: { data: { ...parsePolicy(null), apps: { mode: 'remove', notify, exceptions: [] } } },
  }) as unknown as FleetDevice;

describe('job requests', () => {
  it('sends the phones by internal id, and a command without its capability (the server sets it)', () => {
    const phones = [phone(11, []), phone(12, [])];
    expect(jobRequest({ kind: 'scan' }, phones, 'Scan')).toEqual({ action: 'scan', label: 'Scan', deviceIds: [11, 12] });
    expect(
      jobRequest({ kind: 'command', req: { type: 'device.lock', requiresCapability: 'device.lock' }, what: 'locks' }, phones, 'Lock'),
    ).toEqual({ action: 'command', label: 'Lock', deviceIds: [11, 12], command: { type: 'device.lock', payload: undefined } });
  });

  it('removes only what may be removed, with the alert when the policy says so', () => {
    const req = jobRequest({ kind: 'remove' }, [phone(11, ['com.truecaller', 'com.game.busy']), phone(12, []), phone(13, ['com.x'], false)], 'Remove');
    expect(req).toEqual({
      action: 'remove',
      label: 'Remove',
      removals: [
        { deviceId: 11, packages: ['com.truecaller'], ...(() => { const a = removalAlert(['truecaller']); return { alertTitle: a.title, alertBody: a.body }; })() },
        { deviceId: 13, packages: ['com.x'] },
      ],
    });
    expect(jobRequest({ kind: 'remove' }, [phone(12, [])], 'Remove')).toBeNull();
  });
});

describe('job progress', () => {
  const job = (over: Partial<Job>): Job => ({
    id: 1, action: 'scan', createdAt: 0, cancelled: false, phones: 4, wake: {}, commands: {}, phase: 'sending', ...over,
  });

  it('counts finished commands, or for a reapply the phones past their wake', () => {
    expect(jobPercent(job({ commands: { done: 2, failed: 1, pending: 1 } }))).toBe(75);
    expect(jobPercent(job({ action: 'reapply', wake: { queued: 1, woken: 2, offline: 1 } }))).toBe(75);
  });

  it('says it in words', () => {
    expect(jobSummary(job({ wake: { queued: 1, woken: 2, offline: 1 }, commands: { done: 2, unsupported: 1 } }))).toBe(
      '1 waiting to be woken · 2 woken · 1 offline (get it when they connect) · 2 done · 1 failed',
    );
  });
});
