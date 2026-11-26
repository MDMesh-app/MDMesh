// Bulk jobs in progress, above the Policies views: one row per job with its progress and a Cancel button. Shown only
// with the Policies server plugin, for jobs still working and for those that ended in the last 15 minutes.
import { useState } from 'react';
import { Button, Card, IconButton, IconClose, ProgressBar, RelativeTime, StatusPill, usePermission, useToast } from '@mdmesh/console';
import type { Job } from './api';
import type { Fleet } from './fleet';
import { errorText, plural } from './shared';

const RECENT_MS = 15 * 60 * 1000;
const FINAL = ['done', 'failed', 'unsupported', 'expired'];

const n = (m: Record<string, number>, k: string) => m[k] ?? 0;
const sum = (m: Record<string, number>, keys: readonly string[]) => keys.reduce((s, k) => s + n(m, k), 0);

/** How far a job is, 0–100: finished commands of all, or (no commands) phones past their wake of all. */
export function jobPercent(j: Job): number {
  const total = Object.values(j.commands).reduce((s, x) => s + x, 0);
  if (total > 0) return (sum(j.commands, FINAL) / total) * 100;
  return j.phones ? ((j.phones - n(j.wake, 'queued')) / j.phones) * 100 : 100;
}

/** The job's progress in words. */
export function jobSummary(j: Job): string {
  const parts: string[] = [];
  const queued = n(j.wake, 'queued');
  if (queued) parts.push(`${queued} waiting to be woken`);
  if (n(j.wake, 'woken')) parts.push(`${n(j.wake, 'woken')} woken`);
  if (n(j.wake, 'offline')) parts.push(`${n(j.wake, 'offline')} offline (get it when they connect)`);
  const done = n(j.commands, 'done');
  if (done) parts.push(`${done} done`);
  const failed = n(j.commands, 'failed') + n(j.commands, 'unsupported');
  if (failed) parts.push(`${failed} failed`);
  if (n(j.commands, 'expired')) parts.push(`${n(j.commands, 'expired')} not delivered`);
  return parts.join(' · ') || plural(j.phones, 'phone');
}

const PHASE: Record<Job['phase'], { status: 'applying' | 'applied' | 'notReported' | 'attention'; label: string }> = {
  sending: { status: 'applying', label: 'Sending' },
  running: { status: 'applying', label: 'Running' },
  waiting: { status: 'notReported', label: 'Waiting for offline phones' },
  done: { status: 'applied', label: 'Done' },
  cancelled: { status: 'attention', label: 'Cancelled' },
};

export function JobBar({ fleet }: { fleet: Fleet }) {
  const canAct = usePermission({ permission: 'edit_devices' });
  const toast = useToast();
  const [hidden, setHidden] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState<number | null>(null);
  const now = Date.now();
  const shown = fleet.jobs.filter(
    (j) => !hidden.has(j.id) && (j.phase === 'sending' || j.phase === 'running' || j.phase === 'waiting' || now - j.createdAt < RECENT_MS),
  );
  if (!shown.length) return null;

  async function cancel(j: Job) {
    setBusy(j.id);
    try {
      await fleet.cancelJob(j.id);
      toast.push('ok', 'Job cancelled', `${j.label || 'The job'}: phones not reached yet won't get it.`);
    } catch (e) {
      toast.push('err', 'Could not cancel the job', errorText(e, 'Try again.'));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card padding="none" clip>
      <ul className="x-policies-jobs" aria-label="Bulk jobs">
        {shown.map((j) => {
          const phase = PHASE[j.phase];
          const open = j.phase === 'sending' || j.phase === 'running' || j.phase === 'waiting';
          return (
            <li key={j.id} className="x-policies-job">
              <div className="x-policies-job__head">
                <span className="x-policies-strong">{j.label || j.action}</span>
                <StatusPill status={phase.status} label={phase.label} />
                <span className="x-policies-help">
                  {plural(j.phones, 'phone')} · started <RelativeTime value={j.createdAt} />
                </span>
                <span className="x-policies-toolbar__spacer" />
                {canAct && open ? (
                  <Button size="xs" disabled={busy === j.id} onClick={() => void cancel(j)}>
                    Cancel
                  </Button>
                ) : null}
                {!open ? (
                  <IconButton label="Hide this job" size="sm" onClick={() => setHidden((h) => new Set(h).add(j.id))}>
                    <IconClose />
                  </IconButton>
                ) : null}
              </div>
              <ProgressBar value={jobPercent(j)} tone={j.phase === 'cancelled' ? 'neutral' : 'ok'} size="md" label={`${j.label || j.action} progress`} />
              <span className="x-policies-help">{jobSummary(j)}</span>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
