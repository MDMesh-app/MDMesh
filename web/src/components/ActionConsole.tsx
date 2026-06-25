import { useCallback, useEffect, useState } from 'react';
import {
  ACTION_TEMPLATES, type CommandTemplateExt, queueCommand, getDeviceState,
  listCommandHistory, forceSync, type DeviceState, type CommandHistoryItem,
} from '../api/commands';
import { useToast } from '../ui/toast';
import { KioskEnterModal } from './KioskEnterModal';

type Device = { number: string };

const GROUPS: Array<{ id: 'safe' | 'disruptive' | 'destructive'; title: string }> = [
  { id: 'safe', title: 'Actions' },
  { id: 'disruptive', title: 'Disruptive' },
  { id: 'destructive', title: 'Destructive' },
];

export function ActionConsole({ device }: { device: Device }) {
  const toast = useToast();
  const [state, setState] = useState<DeviceState | null>(null);
  const [history, setHistory] = useState<CommandHistoryItem[]>([]);
  const [active, setActive] = useState<CommandTemplateExt | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);
  const [kioskOpen, setKioskOpen] = useState(false);

  const refresh = useCallback(async () => {
    setState(await getDeviceState(device.number).catch(() => null));
    setHistory(await listCommandHistory(device.number).catch(() => []));
  }, [device.number]);

  useEffect(() => {
    void refresh();
    const t = setInterval(refresh, 5000); // light UI poll of server-side state
    return () => clearInterval(t);
  }, [refresh]);

  function start(t: CommandTemplateExt) {
    setActive(t);
    setValues({});
    setConfirmText('');
  }

  async function send(t: CommandTemplateExt, values: Record<string, string>) {
    setBusy(true);
    try {
      const req = t.build ? t.build(values) : t.request;
      const res = await queueCommand(device.number, req);
      const id = (res?.id as number | string | undefined) ?? '';
      toast.push('ok', `${t.label} queued`, id ? `Command ${id}` : '');
      await forceSync(device.number).catch(() => undefined); // nudge (no-op until MQTT lands)
      await refresh();
    } catch (e) {
      toast.push('err', `${t.label} failed`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  const needsModal = (t: CommandTemplateExt) =>
    (t.params && t.params.length > 0) || !!t.confirm;

  async function onClick(t: CommandTemplateExt) {
    if (t.key === 'kiosk-enter') { setKioskOpen(true); return; }
    if (needsModal(t)) { start(t); return; }
    await send(t, {});
  }

  async function confirmAndSend() {
    if (!active) return;
    const t = active;
    setActive(null);
    await send(t, values);
  }

  const canSend =
    !active ? false
    : active.confirm === 'type-to-confirm' ? confirmText === 'WIPE'
    : active.params?.some((p) => p.required && !values[p.key]) ? false
    : true;

  return (
    <div className="panel">
      <div className="panel-head">
        <h2 className="panel-title">Device control</h2>
        <button
          className="btn"
          disabled={busy}
          onClick={() => { void forceSync(device.number).then(refresh).catch(() => undefined); }}
        >
          Sync now
        </button>
      </div>

      <DeviceStatePanel state={state} />

      {GROUPS.map((g) => (
        <section key={g.id} className="action-group">
          <h3 className="action-group-title">{g.title}</h3>
          <div className="action-grid">
            {ACTION_TEMPLATES.filter((t) => (t.group ?? 'safe') === g.id).map((t) => (
              <button
                key={t.key}
                className={`btn ${t.danger ? 'btn-danger' : ''}`}
                disabled={busy}
                title={t.description}
                onClick={() => { void onClick(t); }}
              >
                {t.label}
              </button>
            ))}
          </div>
        </section>
      ))}

      <CommandTimeline items={history} />

      {kioskOpen && (
        <KioskEnterModal
          device={device}
          onClose={() => setKioskOpen(false)}
          onQueued={() => { void refresh(); }}
        />
      )}

      {active && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h3>{active.label}</h3>
            <p className="muted">{active.description}</p>
            {active.params?.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                <input
                  type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                  placeholder={p.placeholder}
                  value={values[p.key] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                />
              </label>
            ))}
            {active.confirm === 'type-to-confirm' && (
              <label className="field">
                <span>Type <strong>WIPE</strong> to confirm</span>
                <input value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
              </label>
            )}
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setActive(null)}>Cancel</button>
              <button
                className={`btn ${active.danger ? 'btn-danger' : 'btn-primary'}`}
                disabled={busy || !canSend}
                onClick={() => { void confirmAndSend(); }}
              >
                {active.danger ? 'Confirm' : 'Send'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function powerLabel(mode?: string | null): string {
  if (mode === 'alwaysOn') return 'Always-on';
  if (mode === 'adaptive') return 'Battery-saver';
  return '—';
}

function DeviceStatePanel({ state }: { state: DeviceState | null }) {
  if (!state) return <p className="muted">No state reported yet.</p>;
  const seen = state.updatedAt ? new Date(state.updatedAt).toLocaleTimeString() : '—';
  return (
    <dl className="state-grid">
      <div><dt>Battery</dt><dd>{state.battery < 0 ? '—' : `${state.battery}%`}{state.charging ? ' ⚡' : ''}</dd></div>
      <div><dt>Screen</dt><dd>{state.locked ? 'Locked' : 'Unlocked'}</dd></div>
      <div><dt>Kiosk</dt><dd>{state.kioskActive ? 'Active' : 'Off'}</dd></div>
      <div><dt>Android</dt><dd>{state.androidRelease || '—'}</dd></div>
      <div><dt>Agent</dt><dd>{state.agentVersion || '—'}</dd></div>
      <div><dt>Connectivity</dt><dd>{powerLabel(state.powerMode)}</dd></div>
      <div><dt>State as of</dt><dd>{seen}</dd></div>
    </dl>
  );
}

function CommandTimeline({ items }: { items: CommandHistoryItem[] }) {
  if (!items.length) return null;
  return (
    <section className="timeline">
      <h3 className="action-group-title">Recent commands</h3>
      <ul>
        {items.map((c) => (
          <li key={String(c.id)} className={`timeline-item status-${c.status}`}>
            <span className="t-type">{c.type}</span>
            <span className={`t-status status-${c.status}`}>{c.status}</span>
            {c.detail && <CommandDetail text={c.detail} />}
          </li>
        ))}
      </ul>
    </section>
  );
}

const DETAIL_LIMIT = 160;

function CommandDetail({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  if (text.length <= DETAIL_LIMIT) return <span className="t-detail">{text}</span>;
  return (
    <span className={`t-detail ${open ? 'open' : 'clamped'}`}>
      {open ? text : `${text.slice(0, DETAIL_LIMIT)}…`}
      <button type="button" className="t-more" onClick={() => setOpen((v) => !v)}>
        {open ? 'show less' : 'show more'}
      </button>
    </span>
  );
}
