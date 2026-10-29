import { useEffect, useMemo, useRef, useState } from 'react';
import { searchDevices, type DeviceView } from '../../api/devices';
import {
  cancelRollout,
  createRollout,
  promoteRollout,
  type ActiveRollout,
  type RolloutCounts,
} from '../../api/rollout';
import type { UpdateStatus } from '../../api/updates';
import {
  Button,
  Callout,
  Card,
  CardHeader,
  Checkbox,
  IconAlert,
  Modal,
  RelativeTime,
  RolloutSteps,
  SearchInput,
  formatRelative,
  type RolloutStep,
} from '../../ui';
import { CORE_ACTION_ACCESS } from '../../ext/core/access';
import { usePermission } from '../../ext/permissions';
import { rolloutActive, type RolloutState } from './hooks';

const AGENT_PACKAGE = (import.meta.env.VITE_AGENT_PACKAGE as string) || 'com.mdmesh.agent';
/** Devices fetched per search in the canary picker (classic: one page of 200). */
const PICKER_PAGE = 200;

/** Server error keys (RolloutResource) in words. Anything else is shown as the server sent it. */
const ROLLOUT_ERRORS: Record<string, string> = {
  'error.rollout.active': 'Another rollout is already running. Refresh to see it.',
  'error.rollout.canary.empty': 'None of the chosen devices can take part. Pick devices you manage.',
  'error.rollout.sha256': 'This agent release has no complete SHA-256 checksum, so it can’t be rolled out.',
  'error.rollout.invalid': 'This agent release is incomplete. Check for updates, then try again.',
  'error.rollout.stage': 'The rollout has already moved on. The page now shows where it is.',
  'error.permission.denied': 'Managing rollouts needs permission to edit devices and access to every device.',
};
const rolloutError = (e: unknown) => {
  const m = (e as Error)?.message || 'Something went wrong.';
  return ROLLOUT_ERRORS[m] ?? m;
};

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const eligibleOf = (c: RolloutCounts) => Math.max(0, c.total - c.ineligible);
const cohortDone = (c: RolloutCounts) => eligibleOf(c) > 0 && c.pending === 0 && c.outstanding === 0;

/** "1 updated · 1 installing · 2 waiting to check in · 1 too old to update itself". */
function cohortNote(c: RolloutCounts): string {
  const parts = [`${c.updated} updated`];
  if (c.pending > 0) parts.push(`${c.pending} installing`);
  if (c.outstanding > 0) parts.push(`${c.outstanding} waiting to check in`);
  if (c.ineligible > 0) parts.push(`${c.ineligible} too old to update ${c.ineligible === 1 ? 'itself' : 'themselves'}`);
  return parts.join(' · ');
}

function promoteHint(c: RolloutCounts): string {
  const n = eligibleOf(c);
  if (n === 0) return 'None of the canary devices can install the agent on their own. Cancel and pick others.';
  if (cohortDone(c)) return n === 1 ? 'The canary device has updated.' : `All ${n} canary devices have updated.`;
  if (n === 1) return 'Available once the canary device has updated.';
  if (n === 2) return 'Available once both canary devices have updated.';
  return `Available once all ${n} canary devices have updated.`;
}

function steps(r: ActiveRollout): RolloutStep[] {
  const c = r.progress.canary;
  const f = r.progress.fleet;
  const canaryOk = cohortDone(c) || r.stage === 'fleet';
  const out: RolloutStep[] = [
    {
      key: 'canary',
      step: 'Step 1',
      name: 'Canary',
      state: canaryOk ? 'Done' : eligibleOf(c) === 0 ? 'Blocked' : 'In progress',
      stateTone: canaryOk ? 'ok' : 'warn',
      active: r.stage === 'canary',
      done: c.updated,
      busy: c.pending,
      total: eligibleOf(c),
      note: cohortNote(c),
    },
  ];
  if (r.stage === 'fleet' && f) {
    const fe = eligibleOf(f);
    const done = cohortDone(f) || (fe === 0 && f.total === 0);
    out.push({
      key: 'fleet',
      step: 'Step 2',
      name: 'Everyone else',
      state: done ? 'Done' : 'In progress',
      stateTone: done ? 'ok' : 'warn',
      active: true,
      done: f.updated,
      busy: f.pending,
      total: fe,
      note: f.total === 0 ? 'No other devices.' : cohortNote(f),
    });
  } else {
    out.push({
      key: 'fleet',
      step: 'Step 2',
      name: 'Everyone else',
      state: 'Waiting',
      stateTone: 'muted',
      done: 0,
      total: null,
      ofLabel: 'of the rest of the fleet',
      note: 'Starts when you promote the rollout.',
    });
  }
  return out;
}

/* ---- canary picker ---------------------------------------------------------------------------------------- */

function CanaryPicker({
  open,
  version,
  busy,
  error,
  onCancel,
  onStart,
}: {
  open: boolean;
  version: string;
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onStart: (numbers: string[]) => void;
}) {
  const [query, setQuery] = useState('');
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [selected, setSelected] = useState<Map<string, DeviceView>>(new Map());
  const seq = useRef(0);

  useEffect(() => {
    if (!open) {
      setQuery('');
      setSelected(new Map());
      return;
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const id = ++seq.current;
    const t = setTimeout(
      () => {
        setLoading(true);
        setLoadErr(null);
        searchDevices({ value: query.trim(), pageSize: PICKER_PAGE })
          .then((r) => {
            if (id !== seq.current) return;
            setDevices(r.devices?.items ?? []);
            setTotal(r.devices?.totalItemsCount ?? 0);
          })
          .catch((e) => {
            if (id === seq.current) setLoadErr((e as Error).message || 'Could not load devices.');
          })
          .finally(() => {
            if (id === seq.current) setLoading(false);
          });
      },
      query ? 250 : 0,
    );
    return () => clearTimeout(t);
  }, [open, query]);

  const toggle = (d: DeviceView) =>
    setSelected((s) => {
      const next = new Map(s);
      if (next.has(d.number)) next.delete(d.number);
      else next.set(d.number, d);
      return next;
    });

  const n = selected.size;
  return (
    <Modal
      open={open}
      onClose={() => !busy && onCancel()}
      dismissOnBackdrop={false}
      size="md"
      title="Choose canary devices"
      description={
        <>
          They get agent <span className="v2-mono">{version}</span> first. Once they&rsquo;ve updated, you promote it
          to everyone else.
        </>
      }
      footer={
        <>
          <span className="v2-settings-picker__count" aria-live="polite">
            {plural(n, 'device', 'devices')} selected
          </span>
          <Button size="lg" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button variant="primary" size="lg" onClick={() => onStart(Array.from(selected.keys()))} disabled={busy || n === 0}>
            {busy ? 'Starting…' : `Start canary (${n})`}
          </Button>
        </>
      }
    >
      <div className="v2-settings-picker">
        <SearchInput
          value={query}
          onChange={setQuery}
          aria-label="Search devices"
          placeholder="Search by number or description"
        />
        {error ? (
          <Callout tone="alert" role="alert" icon={<IconAlert />}>
            {error}
          </Callout>
        ) : null}
        {loadErr ? (
          <Callout tone="alert" role="alert" icon={<IconAlert />}>
            Could not load devices: {loadErr}
          </Callout>
        ) : null}
        <ul className="v2-settings-picker__list" aria-label="Devices" aria-busy={loading || undefined}>
          {devices.map((d) => (
            <li key={d.id}>
              <Checkbox
                checked={selected.has(d.number)}
                onChange={() => toggle(d)}
                label={
                  <span className="v2-settings-picker__dev">
                    <span className="v2-mono">{d.number}</span>
                    {d.description ? <span className="v2-settings-picker__desc">{d.description}</span> : null}
                    {d.lastUpdate ? (
                      <RelativeTime value={d.lastUpdate} prefix="seen " className="v2-settings-picker__seen" />
                    ) : null}
                  </span>
                }
              />
            </li>
          ))}
        </ul>
        {!loading && !loadErr && devices.length === 0 ? (
          <p className="v2-settings-muted">{query ? 'No devices match.' : 'No devices are enrolled yet.'}</p>
        ) : null}
        {loading && devices.length === 0 ? <p className="v2-settings-muted">Loading devices…</p> : null}
        {total > devices.length ? (
          <p className="v2-settings-muted">
            Showing {devices.length} of {total} devices. Search to find others.
          </p>
        ) : null}
      </div>
    </Modal>
  );
}

/* ---- section ---------------------------------------------------------------------------------------------- */

type Confirm = 'promote' | 'cancel' | null;

/** Settings → Agent rollout: canary devices first, then promote to everyone else; finish or cancel. */
export function RolloutSection({
  id,
  ro,
  status,
  statusLoaded,
}: {
  id: string;
  ro: RolloutState;
  status: UpdateStatus | null;
  statusLoaded: boolean;
}) {
  const { rollout, loaded, loadError } = ro;
  const active = rolloutActive(rollout) ? rollout : null;
  const apk = status?.apk ?? null;
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [picking, setPicking] = useState(false);
  const [pickErr, setPickErr] = useState<string | null>(null);
  const items = useMemo(() => (active ? steps(active) : []), [active]);
  // Starting, promoting and stopping a rollout queue commands on the whole fleet (ext/core/access.ts): a role without
  // edit_devices, or a user limited to some device groups, sees the rollout without its buttons.
  const canEdit = usePermission(CORE_ACTION_ACCESS.rollout);

  const start = async (numbers: string[]) => {
    if (!apk) return;
    setBusy(true);
    setPickErr(null);
    try {
      const created = await createRollout({
        targetVersion: apk.version,
        packageName: AGENT_PACKAGE,
        apkVersionCode: apk.versionCode,
        apkSha256: apk.sha256,
        canaryDeviceNumbers: numbers,
      });
      ro.set(created);
      setPicking(false);
      setErr(null);
      void ro.refresh();
    } catch (e) {
      setPickErr(rolloutError(e));
    } finally {
      setBusy(false);
    }
  };

  const promote = async () => {
    if (!active) return;
    setBusy(true);
    setErr(null);
    try {
      ro.set(await promoteRollout(active.id));
      void ro.refresh();
    } catch (e) {
      setErr(rolloutError(e));
      void ro.refresh();
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const stop = async () => {
    if (!active) return;
    setBusy(true);
    setErr(null);
    try {
      await cancelRollout(active.id);
      ro.set(null);
      void ro.refresh();
    } catch (e) {
      setErr(rolloutError(e));
      void ro.refresh(); // show what the server has now, as a failed promote does
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const titleId = `${id}-title`;
  const fleet = active?.stage === 'fleet';
  const remaining = active
    ? (() => {
        const c = fleet ? active.progress.fleet : active.progress.canary;
        return c ? c.pending + c.outstanding : 0;
      })()
    : 0;

  return (
    <Card id={id} aria-labelledby={titleId} gap={14} className="v2-settings-card">
      {active ? (
        <CardHeader
          title="Agent rollout"
          titleId={titleId}
          subtitle={
            <>
              Updating the agent to <span className="v2-mono">{active.targetVersion}</span>
              {fleet
                ? ' on every device.'
                : ` on ${plural(eligibleOf(active.progress.canary), 'canary device', 'canary devices')} first, then the rest of the fleet.`}{' '}
              Started {formatRelative(active.createdAt, Date.now(), 'long').replace(/^Y/, 'y')}.
            </>
          }
          actions={
            !canEdit ? undefined : fleet ? (
              <Button onClick={() => setConfirm('cancel')} disabled={busy}>
                Finish rollout
              </Button>
            ) : (
              <Button variant="danger" onClick={() => setConfirm('cancel')} disabled={busy}>
                Cancel rollout
              </Button>
            )
          }
        />
      ) : (
        <CardHeader title="Agent rollout" titleId={titleId} />
      )}

      {loadError ? (
        <Callout tone="warn" icon={<IconAlert />}>
          Could not refresh the rollout: {rolloutError({ message: loadError })}
        </Callout>
      ) : null}
      {err ? (
        <Callout tone="alert" role="alert" icon={<IconAlert />}>
          {err}
        </Callout>
      ) : null}

      {active ? (
        <>
          <RolloutSteps steps={items} />
          {!fleet ? (
            <div className="v2-settings-promote">
              {canEdit ? (
                <Button
                  variant="primary"
                  size="xl"
                  disabled={busy || !cohortDone(active.progress.canary)}
                  onClick={() => setConfirm('promote')}
                >
                  Promote to everyone else
                </Button>
              ) : null}
              <span className="v2-settings-promote__hint">{promoteHint(active.progress.canary)}</span>
            </div>
          ) : (
            <p className="v2-settings-promote__hint">
              {remaining === 0
                ? 'Every device that can update has the new agent. Finish the rollout to close it.'
                : 'Devices that are offline get the update when they next check in. Finish the rollout once you’re done.'}
            </p>
          )}
        </>
      ) : !loaded || (!statusLoaded && !loadError) ? (
        <p className="v2-settings-muted">Checking for a rollout…</p>
      ) : apk?.available ? (
        <div className="v2-settings-start">
          <div className="v2-settings-start__text">
            <span className="v2-settings-start__label">
              Agent <span className="v2-mono">{apk.version}</span> is ready to roll out
            </span>
            <span className="v2-settings-start__help">
              Pick a few canary devices to update first. Once they&rsquo;ve updated, promote it to everyone else.
            </span>
          </div>
          {canEdit ? (
            <Button
              size="lg"
              strong
              onClick={() => {
                setPickErr(null);
                setPicking(true);
              }}
            >
              Roll out agent…
            </Button>
          ) : null}
        </div>
      ) : (
        <p className="v2-settings-muted">
          No rollout is running. Each verified server release brings a new agent; once the updater has downloaded it,
          you can roll it out to devices here.
        </p>
      )}

      {apk ? (
        <CanaryPicker
          open={picking}
          version={apk.version}
          busy={busy}
          error={pickErr}
          onCancel={() => setPicking(false)}
          onStart={(nums) => void start(nums)}
        />
      ) : null}

      <Modal
        open={confirm === 'promote'}
        onClose={() => !busy && setConfirm(null)}
        size="sm"
        title="Promote to everyone else?"
        footer={
          <>
            <Button size="lg" onClick={() => setConfirm(null)} disabled={busy}>
              Not yet
            </Button>
            <Button variant="primary" size="lg" onClick={() => void promote()} disabled={busy}>
              {busy ? 'Promoting…' : 'Promote'}
            </Button>
          </>
        }
      >
        <p className="v2-settings-modal-text">
          Agent <span className="v2-mono">{active?.targetVersion}</span> is sent to every other device that can install
          it. Devices that are offline get it when they next check in.
        </p>
      </Modal>

      <Modal
        open={confirm === 'cancel'}
        onClose={() => !busy && setConfirm(null)}
        role="alertdialog"
        size="sm"
        title={fleet ? 'Finish this rollout?' : 'Cancel this rollout?'}
        footer={
          <>
            <Button size="lg" onClick={() => setConfirm(null)} disabled={busy}>
              Keep it running
            </Button>
            <Button variant={fleet ? 'primary' : 'danger'} size="lg" onClick={() => void stop()} disabled={busy}>
              {busy ? 'Stopping…' : fleet ? 'Finish rollout' : 'Cancel rollout'}
            </Button>
          </>
        }
      >
        <p className="v2-settings-modal-text">
          {remaining > 0
            ? `${plural(remaining, 'device hasn’t', 'devices haven’t')} updated yet and stop being offered agent ${active?.targetVersion ?? ''}. `
            : ''}
          Installs already under way finish on their own, and devices that updated keep the new agent.
        </p>
      </Modal>
    </Card>
  );
}
