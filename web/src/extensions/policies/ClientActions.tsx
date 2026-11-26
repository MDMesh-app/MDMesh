// Client actions: everything the console can do to a set of phones, in one panel, as an endpoint-security console's
// client actions and the device page's Remote actions card lay them out. Opened for the selected phones (Status),
// a group, a policy's phones, or the whole fleet. One phone goes through the device's own endpoint, several through
// core's bulk endpoint, which skips a phone whose agent can't do the command.
import { useMemo, useState, type ReactNode } from 'react';
import {
  Button,
  ButtonLink,
  Field,
  IconAlert,
  IconApps,
  IconDevices,
  IconDownload,
  IconExitKiosk,
  IconLock,
  IconPin,
  IconPower,
  IconRing,
  IconScreenMessage,
  IconStopRing,
  IconSync,
  IconTrash,
  Input,
  Modal,
  Select,
  Textarea,
  paths,
  useToast,
  type QueueCommandRequest,
} from '@mdmesh/console';
import {
  alert,
  COMMANDS,
  getActiveRollout,
  getAgentApk,
  lockscreenMessage,
  openApp,
  moveDevices,
  queue,
  SCAN,
  startAgentUpdate,
  setGroupMembers,
  syncNow,
} from './api';
import { runBulk } from './bulk';
import type { Fleet, FleetDevice } from './fleet';
import { BrandAssignForm, GroupAssignForm } from './AssignForms';
import { removable } from './model';
import { errorText, plural } from './shared';

type Step = 'list' | 'alert' | 'lockmsg' | 'group' | 'brand' | 'open' | 'policy' | 'reboot' | 'lock' | 'update';

const nameOf = (d: FleetDevice) => d.device.description || d.device.number;
const mayRemove = (d: FleetDevice) => {
  const mode = d.currentPolicy?.data.apps.mode;
  return (mode === 'remove' || mode === 'auto') && removable(d.violations ?? []).length > 0;
};

export function ClientActions({
  phones,
  fleet,
  scope,
  onClose,
}: {
  phones: FleetDevice[];
  fleet: Fleet;
  /** What the phones are, for the title ("2 selected phones", "Oppo family"). */
  scope: string;
  onClose: () => void;
}) {
  const toast = useToast();
  const [step, setStep] = useState<Step>('list');
  const [busy, setBusy] = useState(false);
  const ids = phones.map((d) => d.device.id);
  const one = phones.length === 1 ? phones[0] : null;

  async function act(label: string, fn: () => Promise<string>, close = true) {
    setBusy(true);
    try {
      toast.push('ok', label, await fn());
      fleet.refreshDevices(ids);
      if (close) onClose();
      else setStep('list');
    } catch (e) {
      toast.push('err', `${label} failed`, errorText(e, 'Try again.'));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Queue `req` on every phone and wake them. Many phones with the Policies server plugin: one job the server paces;
   * otherwise as before (runBulk).
   */
  const send = (label: string, req: QueueCommandRequest, what: string) =>
    act(label, async () => {
      if (one) {
        await queue(one.device.number, req);
        await syncNow(one.device.number).catch(() => undefined);
        return `${nameOf(one)} ${what} at its next sync.`;
      }
      return runBulk(fleet, phones, req.type === 'apps.scan' ? { kind: 'scan' } : { kind: 'command', req, what }, `${label} · ${scope}`);
    });

  const reapply = () => act('Policy reapplied', () => runBulk(fleet, phones, { kind: 'reapply' }, `Reapply policy · ${scope}`));

  const installApps = () => act('Policy apps queued', () => runBulk(fleet, phones, { kind: 'install' }, `Install policy apps · ${scope}`));

  const removeBlocked = () =>
    act('Removal queued', () => runBulk(fleet, phones.filter(mayRemove), { kind: 'remove' }, `Remove extra apps · ${scope}`));

  const removableCount = phones.filter(mayRemove).length;
  const byHand = phones.filter((d) => d.groupByHand);

  const forms: Partial<Record<Step, ReactNode>> = {
    alert: <AlertForm busy={busy} onBack={() => setStep('list')} onSend={(t, b) => send('Alert sent', alert(t, b), 'shows the alert')} />,
    lockmsg: (
      <LockMessageForm busy={busy} onBack={() => setStep('list')} onSend={(m) => send('Lock-screen message set', lockscreenMessage(m), m ? 'shows the message' : 'clears its message')} />
    ),
    reboot: (
      <Confirm
        busy={busy}
        text={`${plural(phones.length, 'phone')} restart${phones.length === 1 ? 's' : ''} at once and stay${phones.length === 1 ? 's' : ''} offline for a minute or two.`}
        action="Reboot"
        danger
        onBack={() => setStep('list')}
        onConfirm={() => send('Reboot queued', COMMANDS.reboot, 'restarts')}
      />
    ),
    lock: (
      <Confirm
        busy={busy}
        text={`The screen of ${plural(phones.length, 'phone')} locks at once. The user unlocks it as usual.`}
        action="Lock"
        onBack={() => setStep('list')}
        onConfirm={() => send('Lock queued', COMMANDS.lock, 'locks')}
      />
    ),
    group: <GroupAssignForm fleet={fleet} ids={ids} cancelLabel="Back" onCancel={() => setStep('list')} onDone={onClose} />,
    open: (
      <OpenAppForm
        phones={phones}
        busy={busy}
        onBack={() => setStep('list')}
        onOpen={(pkg, name) => send(`Open ${name}`, openApp(pkg), `opens ${name}`)}
      />
    ),
    brand: <BrandAssignForm fleet={fleet} ids={ids} cancelLabel="Back" onCancel={() => setStep('list')} onDone={onClose} />,
    policy: (
      <PolicyForm
        fleet={fleet}
        phones={phones}
        busy={busy}
        onBack={() => setStep('list')}
        onAssign={(pid, name) =>
          act('Policy assigned', async () => {
            await moveDevices(ids, pid);
            fleet.reload();
            return `${plural(phones.length, 'phone')} on ${name}. They apply it at their next sync.`;
          })
        }
      />
    ),
    update: (
      <UpdateForm
        phones={phones}
        busy={busy}
        onBack={() => setStep('list')}
        onStart={(apkStart) => act('Agent update started', apkStart)}
      />
    ),
  };

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={step === 'list' ? `Client actions · ${scope}` : `${TITLES[step]} · ${scope}`}
      description={step === 'list' ? `${plural(phones.length, 'phone')}. Phones that are offline get the action when they next check in.` : undefined}
      footer={step === 'list' ? <Button onClick={onClose}>Close</Button> : undefined}
    >
      {step !== 'list' ? (
        forms[step]
      ) : (
        <div className="x-policies-actions">
          <Row title="Policy" sub="What the phones follow">
            <Button icon={<IconSync />} disabled={busy} onClick={() => void reapply()}>
              Reapply policy
            </Button>
            <Button icon={<IconDevices />} disabled={busy} onClick={() => setStep('policy')}>
              Assign policy…
            </Button>
            <Button icon={<IconDevices />} disabled={busy || !fleet.groups.length || fleet.groupsAvailable !== true} onClick={() => setStep('group')}>
              Add to group…
            </Button>
            <Button icon={<IconDevices />} disabled={busy || fleet.groupsAvailable !== true} onClick={() => setStep('brand')}>
              Set brand…
            </Button>
          </Row>
          <Row title="Apps" sub="Inventory and app rules">
            <Button icon={<IconApps />} disabled={busy} onClick={() => setStep('open')}>
              Open app…
            </Button>
            <Button icon={<IconApps />} disabled={busy} onClick={() => void send('App scan queued', SCAN, 'reports its apps')}>
              Scan apps
            </Button>
            <Button icon={<IconDownload />} disabled={busy} onClick={() => void installApps()}>
              Install policy apps
            </Button>
            <Button icon={<IconTrash />} variant="danger" disabled={busy || removableCount === 0} onClick={() => void removeBlocked()}>
              Remove extra apps{removableCount ? ` (${removableCount})` : ''}
            </Button>
          </Row>
          <Row title="Agent" sub="The MDMesh app on the phone">
            <Button icon={<IconDownload />} disabled={busy} onClick={() => setStep('update')}>
              Update agent…
            </Button>
          </Row>
          <Row title="Message" sub="Get someone's attention">
            <Button icon={<IconAlert />} disabled={busy} onClick={() => setStep('alert')}>
              Send alert…
            </Button>
            <Button icon={<IconScreenMessage />} disabled={busy} onClick={() => setStep('lockmsg')}>
              Lock-screen message…
            </Button>
            <Button icon={<IconRing />} disabled={busy} onClick={() => void send('Ring queued', COMMANDS.ring, 'rings for 30 seconds')}>
              Ring
            </Button>
            <Button icon={<IconStopRing />} disabled={busy} onClick={() => void send('Stop ringing queued', COMMANDS.ringStop, 'stops ringing')}>
              Stop ringing
            </Button>
          </Row>
          <Row title="Device" sub="Interrupts the user">
            <Button icon={<IconLock />} disabled={busy} onClick={() => setStep('lock')}>
              Lock…
            </Button>
            <Button icon={<IconPower />} disabled={busy} onClick={() => setStep('reboot')}>
              Reboot…
            </Button>
            <Button icon={<IconExitKiosk />} disabled={busy} onClick={() => void send('Kiosk exit queued', COMMANDS.kioskExit, 'leaves kiosk mode')}>
              Exit kiosk
            </Button>
          </Row>
          <Row title="Power and location" sub="How often they report">
            <span className="x-policies-inline">
              <span className="x-policies-help">Connection</span>
              <Button size="sm" disabled={busy} onClick={() => void send('Always-on queued', COMMANDS.alwaysOn, 'keeps its connection up')}>
                Always-on
              </Button>
              <Button size="sm" disabled={busy} onClick={() => void send('Battery-saver queued', COMMANDS.batterySaver, 'saves battery')}>
                Battery-saver
              </Button>
            </span>
            <span className="x-policies-inline">
              <span className="x-policies-help">Location</span>
              <Button size="sm" icon={<IconPin size={14} />} disabled={busy} onClick={() => void send('Accurate location queued', COMMANDS.locationAccurate, 'takes a GPS fix each check-in')}>
                Accurate
              </Button>
              <Button size="sm" disabled={busy} onClick={() => void send('Battery-saver location queued', COMMANDS.locationSaver, 'reports its last known location')}>
                Battery-saver
              </Button>
            </span>
          </Row>
          {byHand.length ? (
            <Row title="Group" sub="Phones picked into a group">
              <Button
                disabled={busy}
                onClick={() =>
                  void act('Back to brand group', async () => {
                    fleet.applyGroups(await setGroupMembers(byHand.map((d) => d.device.id), null));
                    return `${plural(byHand.length, 'phone')} follow their brand's group again.`;
                  })
                }
              >
                Back to brand group ({byHand.length})
              </Button>
            </Row>
          ) : null}
          <p className="x-policies-help">
            Factory reset and passcode reset are sent one phone at a time, from the phone&rsquo;s own page.
            {one ? (
              <>
                {' '}
                <ButtonLink to={paths.device(one.device.number)} size="xs">
                  Open {nameOf(one)}
                </ButtonLink>
              </>
            ) : null}
          </p>
        </div>
      )}
    </Modal>
  );
}

const TITLES: Record<Step, string> = {
  list: 'Client actions',
  alert: 'Send alert',
  lockmsg: 'Lock-screen message',
  group: 'Add to group',
  open: 'Open app',
  brand: 'Set brand',
  policy: 'Assign policy',
  reboot: 'Reboot',
  lock: 'Lock',
  update: 'Update agent',
};

function Row({ title, sub, children }: { title: string; sub: string; children: ReactNode }) {
  return (
    <div className="x-policies-actrow" role="group" aria-label={title}>
      <div className="x-policies-actrow__head">
        <span className="x-policies-strong">{title}</span>
        <span className="x-policies-help">{sub}</span>
      </div>
      <div className="x-policies-actrow__tiles">{children}</div>
    </div>
  );
}

function FormFooter({ busy, onBack, action, danger, disabled, onAction }: { busy: boolean; onBack: () => void; action: string; danger?: boolean; disabled?: boolean; onAction: () => void }) {
  return (
    <div className="x-policies-formfoot">
      <Button onClick={onBack} disabled={busy}>
        Back
      </Button>
      <Button variant={danger ? 'danger' : 'primary'} disabled={busy || disabled} onClick={onAction}>
        {busy ? 'Working…' : action}
      </Button>
    </div>
  );
}

function Confirm({ busy, text, action, danger, onBack, onConfirm }: { busy: boolean; text: string; action: string; danger?: boolean; onBack: () => void; onConfirm: () => void }) {
  return (
    <div className="x-policies-stack">
      <p className="x-policies-help">{text}</p>
      <FormFooter busy={busy} onBack={onBack} action={action} danger={danger} onAction={onConfirm} />
    </div>
  );
}

function AlertForm({ busy, onBack, onSend }: { busy: boolean; onBack: () => void; onSend: (title: string, body: string) => void }) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  return (
    <div className="x-policies-stack">
      <Field label="Title">
        <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Message from IT" maxLength={80} />
      </Field>
      <Field label="Message">
        <Textarea value={body} onChange={(e) => setBody(e.target.value)} rows={3} maxLength={500} autoFocus />
      </Field>
      <FormFooter busy={busy} onBack={onBack} action="Send alert" disabled={!body.trim()} onAction={() => onSend(title.trim(), body.trim())} />
    </div>
  );
}

function LockMessageForm({ busy, onBack, onSend }: { busy: boolean; onBack: () => void; onSend: (message: string) => void }) {
  const [message, setMessage] = useState('');
  return (
    <div className="x-policies-stack">
      <Field label="Message" help="Shown on the lock screen. Leave it empty to clear the message.">
        <Input value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Property of your company" maxLength={120} autoFocus />
      </Field>
      <FormFooter busy={busy} onBack={onBack} action={message.trim() ? 'Set message' : 'Clear message'} onAction={() => onSend(message.trim())} />
    </div>
  );
}

/**
 * Open an app on the phones now (agent command app.launch), as a phone link opens an app from a computer: nothing locks
 * it, the user leaves it when they want. The apps offered are their policies' allowed apps and the apps their last
 * scans found; only phones whose agent can open apps get it.
 */
function OpenAppForm({
  phones,
  busy,
  onBack,
  onOpen,
}: {
  phones: FleetDevice[];
  busy: boolean;
  onBack: () => void;
  onOpen: (pkg: string, name: string) => void;
}) {
  const apps = useMemo(() => {
    const names = new Map<string, string>();
    for (const d of phones) {
      for (const a of d.currentPolicy?.allowed ?? []) if (!names.has(a.pkg)) names.set(a.pkg, a.label || a.pkg);
      for (const a of d.apps?.apps ?? []) if (!names.has(a.pkg)) names.set(a.pkg, a.label || a.pkg);
    }
    return [...names.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [phones]);
  const [pkg, setPkg] = useState('');
  const able = phones.filter((d) => d.canLaunch).length;
  const name = apps.find(([p]) => p === pkg)?.[1] ?? pkg;
  return (
    <div className="x-policies-stack">
      <p className="x-policies-help">
        The app opens on the phone as if its icon were tapped; the user leaves it when they want. {plural(able, 'phone')} of {phones.length} can
        open apps now: the others are skipped until their agent can.
      </p>
      <Field label="App">
        <Select value={pkg} onChange={(e) => setPkg(e.target.value)}>
          <option value="">Choose an app…</option>
          {apps.map(([p, n]) => (
            <option key={p} value={p}>
              {n} ({p})
            </option>
          ))}
        </Select>
      </Field>
      <FormFooter busy={busy} onBack={onBack} action={`Open on ${plural(able, 'phone')}`} disabled={!pkg || able === 0} onAction={() => onOpen(pkg, name)} />
    </div>
  );
}

function PolicyForm({
  fleet,
  phones,
  busy,
  onBack,
  onAssign,
}: {
  fleet: Fleet;
  phones: FleetDevice[];
  busy: boolean;
  onBack: () => void;
  onAssign: (policyId: number, name: string) => void;
}) {
  const policies = fleet.policies ?? [];
  const [pid, setPid] = useState<number>(phones[0]?.expectedPolicy?.id ?? policies.find((p) => !p.builtIn)?.id ?? policies[0]?.id ?? 0);
  const name = policies.find((p) => p.id === pid)?.name ?? '';
  return (
    <div className="x-policies-stack">
      <p className="x-policies-help">
        Each phone switches to the policy and applies it at its next sync: its kiosk, apps and restrictions change to the
        policy&rsquo;s.
      </p>
      <Field label="Policy">
        <Select value={String(pid)} onChange={(e) => setPid(Number(e.target.value))}>
          {policies.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </Select>
      </Field>
      <FormFooter busy={busy} onBack={onBack} action={`Assign ${name}`} disabled={!pid} onAction={() => onAssign(pid, name)} />
    </div>
  );
}

/** Agent update: the newest verified agent through core's staged rollout, with these phones as its first stage. */
function UpdateForm({
  phones,
  busy,
  onBack,
  onStart,
}: {
  phones: FleetDevice[];
  busy: boolean;
  onBack: () => void;
  onStart: (run: () => Promise<string>) => void;
}) {
  return (
    <div className="x-policies-stack">
      <p className="x-policies-help">
        A staged update with {plural(phones.length, 'phone')} as its first stage. They install the newest verified agent at their next
        sync. To give it to more phones, start another update for them here, or promote it in Settings → Agent rollout.
      </p>
      <FormFooter
        busy={busy}
        onBack={onBack}
        action="Start update"
        onAction={() =>
          onStart(async () => {
            const active = await getActiveRollout().catch(() => null);
            if (active) throw new Error(`An agent update to ${active.targetVersion} is already running. Finish or stop it in Settings → Agent rollout first.`);
            const apk = await getAgentApk();
            if (!apk) throw new Error('No verified agent release is available on this server. Check Settings → Updates.');
            const r = await startAgentUpdate(apk, phones.map((d) => d.device.number));
            return `Agent ${r.targetVersion} goes to ${plural(phones.length, 'phone')} first.`;
          })
        }
      />
    </div>
  );
}
