import { useId, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import {
  ACTION_TEMPLATES,
  CLEAR_PIN_LOCKOUT_AGENT,
  type CommandHistoryItem,
  type CommandTemplateExt,
} from '../api/commands';
import { DEVICE_RESET_LABEL, SOME_DEFERRED_LABEL, staleCause, summarizeStatus, type ConfigStatus } from '../api/configSync';
import type { DeviceEvent } from '../api/events';
import { DO_NOT_TRACK_HINT } from '../api/locationTracking';
import { paths } from '../routes';
import {
  Button,
  ButtonLink,
  Callout,
  Card,
  CardHeader,
  EmptyState,
  Field,
  IconAlert,
  IconCheckSmall,
  IconCrossSmall,
  IconDashSmall,
  IconDownload,
  IconEnterKiosk,
  IconExitKiosk,
  IconFactoryReset,
  IconHistory,
  IconKey,
  IconLock,
  IconPower,
  IconRing,
  IconScreenMessage,
  IconStopRing,
  IconSync,
  IconWarning,
  Input,
  Modal,
  Segmented,
  SkeletonRows,
  StatusPill,
  Table,
  Tooltip,
  cx,
  LinkButton,
  type Column,
} from '../ui';
import { capitalise, commandStatus, configNeedsPanel, fmtWhen, isoTime, shortRevision } from './deviceDetailFormat';
import type { DeviceView } from '../api/devices';
import { classifyOutcome, outcomeSummary, unsupportedOutcomeReason, type Outcome } from '../data/configOutcomes';
import { commandDetail, commandName, commandTime } from '../data/deviceActivity';
import { parseOutcomes } from '../api/configSync';
import { fmtDateTime } from '../ui/format';
import { versionAtLeast } from '../data/kiosk';

export const TEMPLATE: Record<string, CommandTemplateExt> = Object.fromEntries(ACTION_TEMPLATES.map((t) => [t.key, t]));

/* ===============================================================================================================
   Configuration status panel ("Configuration didn't fully apply")
   =============================================================================================================== */

type PanelTone = 'alert' | 'warn' | 'idle' | 'ok';

const OUTCOME_TONE: Record<Outcome['kind'], 'ok' | 'alert' | 'idle' | 'warn'> = {
  applied: 'ok',
  failed: 'alert',
  unsupported: 'idle',
  deferred: 'warn',
};

function OutcomeIcon({ kind }: { kind: Outcome['kind'] }) {
  if (kind === 'applied') return <IconCheckSmall size={12} />;
  if (kind === 'failed') return <IconCrossSmall size={12} />;
  return <IconDashSmall size={12} />;
}

function OutcomeGrid({ list }: { list: Outcome[] }) {
  return (
    <ul className="v2-device-cfg__outcomes">
      {list.map((o) => (
        <li key={o.key} className={`is-${OUTCOME_TONE[o.kind]}`}>
          <span className="v2-device-cfg__oicon" aria-hidden="true">
            <OutcomeIcon kind={o.kind} />
          </span>
          <span className="v2-device-cfg__okey" title={o.key}>
            {o.label}
            {o.reason && o.kind !== 'failed' ? <span className="v2-device-cfg__oreason">{o.reason}</span> : null}
          </span>
          {o.kind === 'failed' && o.reason ? (
            // A failed key shows why in the value column ("Needs device owner").
            <span className="v2-device-cfg__oval">
              <span className="v2-visually-hidden">Failed: </span>
              {capitalise(o.reason)}
            </span>
          ) : (
            <span className="v2-device-cfg__oval">{o.text}</span>
          )}
        </li>
      ))}
    </ul>
  );
}

export function ConfigPanel({
  status,
  configName,
  isDeviceOwner,
  androidSdkInt,
  busy,
  onRetry,
  now,
  events,
}: {
  status: ConfigStatus | null;
  configName: string | undefined;
  /** Telemetry security.isDeviceOwner (undefined when not reported). */
  isDeviceOwner: boolean | undefined;
  /** Telemetry hardware.sdkInt; used only to explain version-gated policy outcomes. */
  androidSdkInt: number | undefined;
  busy: boolean;
  /** Sends the configuration again ("Retry apply"); left out for a role that may not, which then sees no button. */
  onRetry?: () => void;
  now: number;
  /** The device's recent events: one may say why its configuration is out of date ([staleCause]). */
  events?: ReadonlyArray<DeviceEvent>;
}) {
  const titleId = useId();
  const verdict = summarizeStatus(status);
  const parsed = parseOutcomes(status?.lastCommand?.detail);
  const outcomes = useMemo(() => Object.entries(parsed?.outcomes ?? {}).map(([k, v]) => {
    const outcome = classifyOutcome(k, String(v));
    return outcome.kind === 'unsupported'
      ? { ...outcome, reason: unsupportedOutcomeReason(outcome.key, { androidSdkInt, isDeviceOwner }) }
      : outcome;
  }), [parsed, androidSdkInt, isDeviceOwner]);
  const cfgId = status?.configurationId ?? null;
  const name = configName ?? 'Its configuration';
  const strongName = <strong>{name}</strong>;

  if (!status || cfgId == null) {
    return (
      <PanelShell
        tone="idle"
        icon={<IconDashSmall size={14} />}
        titleId={titleId}
        title="No configuration"
        text="This device has no configuration, so nothing is enforced on it."
      />
    );
  }

  const failed = outcomes.filter((o) => o.kind === 'failed').length;
  const unsupported = outcomes.filter((o) => o.kind === 'unsupported').length;
  const full = configNeedsPanel(status, outcomes);

  // W6: the revisions are detail, not the headline: folded under "Revision", 7-character short forms.
  const appliedWhen =
    status.appliedAt && isoTime(status.appliedAt) ? (
      <time dateTime={isoTime(status.appliedAt)} title={fmtDateTime(status.appliedAt)}>
        {fmtWhen(status.appliedAt, now)}
      </time>
    ) : null;
  const rev = (r: string | null | undefined) => (
    <span className="v2-mono" title={r ?? undefined}>
      {shortRevision(r)}
    </span>
  );
  const sameRevision = !!status.appliedRevision && status.appliedRevision === status.currentRevision;
  const revisionLine = (
    <details className="v2-device-cfg__rev">
      <summary>Revision</summary>
      <p>
        {sameRevision ? (
          <>
            Revision {rev(status.currentRevision)}
            {appliedWhen ? <> · applied {appliedWhen}</> : null}
          </>
        ) : (
          <>
            Expected revision {rev(status.currentRevision)} ·{' '}
            {status.appliedRevision ? (
              <>
                device reports {rev(status.appliedRevision)}
                {appliedWhen ? <> ({appliedWhen})</> : null}
              </>
            ) : (
              'device reports none yet'
            )}
          </>
        )}
      </p>
    </details>
  );
  const edit = (
    <ButtonLink to={paths.configuration(cfgId)}>
      Edit {configName ?? 'configuration'}
    </ButtonLink>
  );

  if (!full) {
    // Clean "In sync": one compact panel, the per-key list folded away.
    return (
      <PanelShell
        tone="ok"
        icon={<IconCheckSmall size={14} />}
        titleId={titleId}
        title="Configuration in sync"
        text={<>{strongName} is applied on this device.</>}
      >
        {outcomes.length ? (
          <details className="v2-device-cfg__more">
            <summary>
              Show {outcomes.length} setting{outcomes.length === 1 ? '' : 's'}
            </summary>
            <OutcomeGrid list={outcomes} />
          </details>
        ) : null}
        <div className="v2-device-cfg__actions">
          {edit}
          {revisionLine}
        </div>
      </PanelShell>
    );
  }

  let tone: PanelTone = 'warn';
  let title = 'Configuration out of sync';
  let text: ReactNode = (
    <>
      The device runs an older revision of {strongName}. It applies the current one at its next check-in.
    </>
  );
  if (!status.supported) {
    title = 'Agent too old for configurations';
    text = (
      <>
        Update the agent (<Link to={`${paths.settings}#updates`}>Settings → Updates</Link>) to enable configuration
        enforcement.
      </>
    );
  } else if (verdict.label === 'Apply failed' || failed > 0) {
    tone = 'alert';
    title = outcomes.length && outcomes.some((o) => o.kind === 'applied') ? 'Configuration didn’t fully apply' : 'Configuration didn’t apply';
    text = outcomes.length ? (
      <>
        {strongName}: {outcomeSummary(outcomes)}
      </>
    ) : (
      <>
        {strongName} failed to apply{status.lastCommand?.detail ? `: ${status.lastCommand.detail}` : '.'}
      </>
    );
  } else if (verdict.label === 'Applying…') {
    tone = 'idle';
    title = 'Applying configuration';
    text = <>{strongName} is on its way to the device.</>;
  } else if (verdict.label === 'Not reported yet') {
    tone = 'idle';
    title = 'Configuration not reported yet';
    text = <>The device hasn’t reported which revision of {strongName} it runs.</>;
  } else if (verdict.label === DEVICE_RESET_LABEL) {
    title = 'Configuration out of date on the device';
    const cause = staleCause(events, status);
    text = cause ? (
      <>
        {cause} The device asked for {strongName} again; the server re-sends it.
      </>
    ) : (
      <>
        The device asked for {strongName} again; the server re-sends it. If the device turned Wi-Fi back on to stay
        reachable after Wi-Fi radio turned it off, Wi-Fi stays on until {strongName} changes.
      </>
    );
  } else if (verdict.label === SOME_DEFERRED_LABEL) {
    title = 'Some settings were deferred';
    text = (
      <>
        {strongName}: {outcomeSummary(outcomes)} Deferred settings are tried again at the next configuration change,
        reboot or agent update.
      </>
    );
  } else if (status.inSync && unsupported > 0) {
    tone = 'idle';
    title = 'Some settings aren’t supported';
    text = (
      <>
        {strongName}: {outcomeSummary(outcomes)}
      </>
    );
  }

  const retryable = status.supported && !status.inSync && (verdict.label === 'Apply failed' || verdict.label === 'Out of sync');
  const icon = tone === 'idle' ? <IconDashSmall size={14} /> : <IconWarning size={17} />;

  return (
    <PanelShell
      tone={tone}
      icon={icon}
      titleId={titleId}
      title={title}
      text={
        <>
          {text}
          {unsupported > 0 && isDeviceOwner === false ? (
            <> This device’s agent isn’t the device owner, and most of these settings need device-owner mode.</>
          ) : null}
        </>
      }
    >
      {outcomes.length ? <OutcomeGrid list={outcomes} /> : null}
      <div className="v2-device-cfg__actions">
        {retryable && onRetry ? (
          <Button variant="primary" icon={<IconSync />} disabled={busy} onClick={onRetry}>
            Retry apply
          </Button>
        ) : null}
        {edit}
        {revisionLine}
      </div>
    </PanelShell>
  );
}

function PanelShell({
  tone,
  icon,
  title,
  titleId,
  text,
  children,
}: {
  tone: PanelTone;
  icon: ReactNode;
  title: ReactNode;
  titleId: string;
  text: ReactNode;
  children?: ReactNode;
}) {
  return (
    <Card
      aria-labelledby={titleId}
      tone={tone === 'alert' ? 'alert' : tone === 'warn' ? 'warn' : 'default'}
      className={cx('v2-device-cfg', `is-${tone}`)}
    >
      <span className="v2-device-cfg__tile" aria-hidden="true">
        {icon}
      </span>
      <div className="v2-device-cfg__body">
        <div className="v2-device-cfg__intro">
          <h2 id={titleId} className="v2-device-cfg__title">
            {title}
          </h2>
          <p className="v2-device-cfg__text">{text}</p>
        </div>
        {children}
      </div>
    </Card>
  );
}

/* ===============================================================================================================
   Remote actions (Message / Kiosk & apps / Device / Power use / Destructive)
   =============================================================================================================== */

/** The Remote actions card's id: the header menu's "All actions" scrolls to it and focuses it. */
export const REMOTE_ACTIONS_ID = 'v2-device-remote-actions';

type ActionRunner = (t: CommandTemplateExt) => void;

interface Tile {
  key: string;
  label: string;
  icon: ReactNode;
  /** A template key, or a local action. */
  run: () => void;
  /** What it does, in a tooltip. */
  tip?: string;
  /** Default subtle; the row's main action is primary. */
  variant?: 'primary' | 'subtle';
  /** Unavailable for a reason the row's hint gives. */
  disabled?: boolean;
}

function ActionHead({ id, title, sub }: { id: string; title: string; sub: string }) {
  return (
    <div className="v2-device-actions__head">
      <span id={id} className="v2-device-actions__title">
        {title}
      </span>
      <span className="v2-device-actions__sub">{sub}</span>
    </div>
  );
}

function ActionRow({
  title,
  sub,
  tiles,
  busy,
  hint,
}: {
  title: string;
  sub: string;
  tiles: Tile[];
  busy: boolean;
  /** A note under the buttons (why one is unavailable); disabled buttons point at it. */
  hint?: ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <div className="v2-device-actions__row" role="group" aria-labelledby={id}>
      <ActionHead id={id} title={title} sub={sub} />
      <div className="v2-device-actions__tiles">
        {tiles.map((t) => (
          <Tooltip key={t.key} content={t.tip}>
            <Button
              variant={t.variant ?? 'subtle'}
              icon={t.icon}
              disabled={busy || t.disabled}
              aria-describedby={t.disabled && hint ? hintId : undefined}
              onClick={t.run}
            >
              {t.label}
            </Button>
          </Tooltip>
        ))}
        {hint ? (
          <p id={hintId} className="v2-device-actions__hint">
            {hint}
          </p>
        ) : null}
      </div>
    </div>
  );
}

/** The configured-kiosk action in Remote actions: absent (the hint says why), or offered, possibly unavailable. */
export interface KioskReturnOffer {
  /** Show "Return to configured kiosk". */
  offered: boolean;
  /** Offered but unavailable: the hint says why. */
  disabled?: boolean;
  /** Under the kiosk buttons: why there is no configured kiosk, or why it can't be used now. */
  hint?: ReactNode;
  onReturn: () => void;
}

export function RemoteActions({
  busy,
  onAction,
  onEnterKiosk,
  kioskReturn,
  onInstallConfigApps,
  powerMode,
  powerPending,
  configPowerMode,
  locationMode,
  locationPending,
  locationUnknown,
  doNotTrack,
  agentVersion,
}: {
  busy: boolean;
  onAction: ActionRunner;
  /** Opens the custom (one-time) kiosk dialog. */
  onEnterKiosk: () => void;
  kioskReturn: KioskReturnOffer;
  onInstallConfigApps: () => void;
  /** Selected connection mode ('alwaysOn' | 'adaptive'), from state or the request just sent. */
  powerMode: string | undefined;
  powerPending: boolean;
  /** The connection mode the configuration sets ('alwaysOn' | 'adaptive'), or undefined when it doesn't manage it. */
  configPowerMode: string | undefined;
  /** Selected location mode ('active' | 'passive'), when known. */
  locationMode: string | undefined;
  locationPending: boolean;
  /** The current location mode can't be told (a device-level change was sent before; the agent doesn't report it). */
  locationUnknown: boolean;
  doNotTrack: boolean | null;
  /** The device's agent version, when reported: Clear PIN lockout needs CLEAR_PIN_LOCKOUT_AGENT. */
  agentVersion?: string | null;
}) {
  const titleId = useId();
  // Unknown (not reported yet) stays available: the server holds the command from an agent that can't run it.
  const lockoutTooOld = versionAtLeast(agentVersion, CLEAR_PIN_LOCKOUT_AGENT) === false;
  const kioskHint =
    kioskReturn.hint || lockoutTooOld ? (
      <>
        {kioskReturn.hint}
        {kioskReturn.hint && lockoutTooOld ? ' ' : null}
        {lockoutTooOld ? `Clear PIN lockout needs agent ${CLEAR_PIN_LOCKOUT_AGENT} or later on the device.` : null}
      </>
    ) : undefined;
  const tile = (key: string, label: string, icon: ReactNode): Tile => ({
    key,
    label,
    icon,
    tip: TEMPLATE[key].description,
    run: () => onAction(TEMPLATE[key]),
  });
  const send = (key: string) => onAction(TEMPLATE[key]);

  return (
    <Card id={REMOTE_ACTIONS_ID} tabIndex={-1} aria-labelledby={titleId} className="v2-device-actions">
      <CardHeader title="Remote actions" titleId={titleId} className="v2-device-actions__h" />
      <ActionRow
        title="Message"
        sub="Get someone’s attention"
        busy={busy}
        tiles={[
          tile('alert', 'Send alert', <IconAlert />),
          tile('lockscreen-message', 'Lock-screen message', <IconScreenMessage />),
          tile('ring', 'Ring', <IconRing />),
          tile('ring-stop', 'Stop ringing', <IconStopRing />),
        ]}
      />
      <ActionRow
        title="Kiosk & apps"
        sub="What runs on screen"
        busy={busy}
        hint={kioskHint}
        tiles={[
          ...(kioskReturn.offered
            ? [
                {
                  key: 'kiosk-return',
                  label: 'Return to configured kiosk',
                  icon: <IconEnterKiosk />,
                  tip: 'Puts the device back into the kiosk its configuration defines.',
                  variant: 'primary' as const,
                  disabled: kioskReturn.disabled,
                  run: kioskReturn.onReturn,
                },
              ]
            : []),
          { key: 'kiosk-enter', label: 'Custom kiosk', icon: <IconEnterKiosk />, tip: TEMPLATE['kiosk-enter'].description, run: onEnterKiosk },
          tile('kiosk-exit', 'Exit kiosk', <IconExitKiosk />),
          { ...tile('kiosk-clear-lockout', 'Clear PIN lockout', <IconKey />), disabled: lockoutTooOld },
          {
            key: 'config-apps',
            label: 'Install configuration apps',
            icon: <IconDownload size={16} />,
            tip: 'Sends every app the configuration marks Install again. Apps added to it install at the next check-in.',
            run: onInstallConfigApps,
          },
        ]}
      />
      <ActionRow
        title="Device"
        sub="Interrupts the user"
        busy={busy}
        tiles={[tile('lock', 'Lock', <IconLock size={16} />), tile('reboot', 'Reboot', <IconPower />)]}
      />
      <div className="v2-device-actions__row" role="group" aria-labelledby={`${titleId}-pw`}>
        <ActionHead id={`${titleId}-pw`} title="Power use" sub="How often it reports" />
        {/* Two label / control pairs on one grid: both Segmented controls start at the same x (I14). */}
        <div className="v2-device-actions__power">
          <span className="v2-device-actions__seglabel" id={`${titleId}-conn`}>
            Connection
          </span>
          <span className="v2-device-actions__segctl">
            <Segmented<string>
              aria-labelledby={`${titleId}-conn`}
              aria-describedby={configPowerMode ? `${titleId}-connhint` : undefined}
              value={powerMode}
              disabled={busy}
              onChange={(v) => send(v === 'alwaysOn' ? 'power-always' : 'power-adaptive')}
              options={[
                { value: 'alwaysOn', label: 'Always-on' },
                { value: 'adaptive', label: 'Battery-saver' },
              ]}
            />
            {powerPending ? <span className="v2-device-actions__pending">Waiting for the device</span> : null}
          </span>
          {configPowerMode ? (
            <p id={`${titleId}-connhint`} className="v2-device-actions__hint v2-device-actions__seghint">
              This device’s configuration sets Connectivity: {configPowerMode === 'alwaysOn' ? 'Always-on' : 'Battery-saver'}. A
              change here lasts until the configuration changes, the device restarts or the agent updates; to keep a mode,
              set it in the configuration.
            </p>
          ) : null}
          <span className="v2-device-actions__seglabel" id={`${titleId}-loc`}>
            Location
          </span>
          <span className="v2-device-actions__segctl">
            <Segmented<string>
              aria-labelledby={`${titleId}-loc`}
              aria-describedby={doNotTrack || locationUnknown ? `${titleId}-lochint` : undefined}
              value={doNotTrack ? undefined : locationMode}
              disabled={busy || doNotTrack === true}
              onChange={(v) => send(v === 'active' ? 'location-active' : 'location-passive')}
              options={[
                { value: 'active', label: 'Accurate' },
                { value: 'passive', label: 'Battery-saver' },
              ]}
            />
            {locationPending && !doNotTrack ? <span className="v2-device-actions__pending">Sent</span> : null}
          </span>
          {doNotTrack ? (
            <p id={`${titleId}-lochint`} className="v2-device-actions__hint v2-device-actions__seghint">
              {DO_NOT_TRACK_HINT}
            </p>
          ) : locationUnknown ? (
            <p id={`${titleId}-lochint`} className="v2-device-actions__hint v2-device-actions__seghint">
              The device doesn’t report its location mode; the last change was sent from this console.
            </p>
          ) : null}
        </div>
      </div>
      {/* C4: a plain row under a hairline, red-text buttons; each opens a confirmation. */}
      <div className="v2-device-actions__row is-danger" role="group" aria-labelledby={`${titleId}-dz`}>
        <ActionHead id={`${titleId}-dz`} title="Destructive" sub="Asks you to confirm" />
        <div className="v2-device-actions__tiles">
          <Tooltip content={TEMPLATE['passcode-reset'].description}>
            <Button variant="danger" icon={<IconKey />} disabled={busy} onClick={() => send('passcode-reset')}>
              Reset passcode
            </Button>
          </Tooltip>
          <Tooltip content={TEMPLATE.wipe.description}>
            <Button variant="danger" icon={<IconFactoryReset />} disabled={busy} onClick={() => send('wipe')}>
              Factory reset
            </Button>
          </Tooltip>
        </div>
      </div>
    </Card>
  );
}

/* ===============================================================================================================
   Action dialog (parameters and confirmations)
   =============================================================================================================== */

/** "WH-SCAN-07 · Warehouse scanner": which device a dialog acts on. */
export function DialogTarget({ device }: { device: Pick<DeviceView, 'number' | 'description'> }) {
  return (
    <p className="v2-device-target">
      Device <strong>{device.number}</strong>
      {device.description ? ` · ${device.description}` : null}
    </p>
  );
}

/**
 * Asks for a command's inputs or confirmation. The confirm button says the verb ("Reboot", "Factory reset"), never
 * "Confirm": solid red for what can't be undone (the destructive group), the normal primary otherwise. Keep it
 * mounted with `open` (and its template from useStickyValue) so it animates closed.
 */
export function ActionDialog({
  open = true,
  template,
  device,
  busy,
  onCancel,
  onSend,
}: {
  open?: boolean;
  template: CommandTemplateExt;
  /** Named in the dialog, so a confirmation always says which device it goes to. */
  device: Pick<DeviceView, 'number' | 'description'>;
  busy: boolean;
  onCancel: () => void;
  onSend: (values: Record<string, string>) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [confirmText, setConfirmText] = useState('');
  const formId = useId();
  const t = template;
  const typeToConfirm = t.confirm === 'type-to-confirm';
  const canSend = typeToConfirm
    ? confirmText === 'WIPE'
    : !t.params?.some((p) => p.required && !values[p.key]);
  const hasInputs = (t.params?.length ?? 0) > 0 || typeToConfirm;
  const irreversible = t.group === 'destructive';
  const submit = () => {
    if (open && !busy && canSend) onSend(values);
  };

  return (
    <Modal
      open={open}
      size="sm"
      role={t.danger ? 'alertdialog' : 'dialog'}
      title={t.title ?? t.label}
      description={t.description}
      onClose={onCancel}
      dismissOnBackdrop={!hasInputs}
      footer={
        <>
          <Button size="lg" disabled={busy} onClick={onCancel}>
            Cancel
          </Button>
          <Button
            size="lg"
            variant={irreversible ? 'danger-primary' : 'primary'}
            disabled={busy || !canSend}
            onClick={hasInputs ? undefined : submit}
            form={hasInputs ? formId : undefined}
            type={hasInputs ? 'submit' : 'button'}
          >
            {t.danger ? t.label : 'Send'}
          </Button>
        </>
      }
    >
      <DialogTarget device={device} />
      {hasInputs ? (
        <form
          id={formId}
          className="v2-device-actionform"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          {t.params?.map((p) => (
            <Field key={p.key} label={p.label}>
              <Input
                type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                autoComplete={p.kind === 'password' ? 'new-password' : 'off'}
                placeholder={p.placeholder}
                required={p.required}
                value={values[p.key] ?? ''}
                onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
              />
            </Field>
          ))}
          {typeToConfirm ? (
            <Field
              label={
                <>
                  Type <strong>WIPE</strong> to confirm
                </>
              }
            >
              <Input value={confirmText} autoComplete="off" spellCheck={false} onChange={(e) => setConfirmText(e.target.value)} />
            </Field>
          ) : null}
        </form>
      ) : t.danger ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          This interrupts whoever is using the device.
        </Callout>
      ) : null}
    </Modal>
  );
}

/* ===============================================================================================================
   Commands: the full history on the Activity tab
   =============================================================================================================== */

const DETAIL_LIMIT = 160;

/** Long details clamped at 160 characters with show more / show less (classic CommandDetail). */
function CommandDetail({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  if (text.length <= DETAIL_LIMIT) return <>{text}</>;
  return (
    <>
      {open ? text : `${text.slice(0, DETAIL_LIMIT)}…`}{' '}
      <LinkButton tone="quiet" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        {open ? 'show less' : 'show more'}
      </LinkButton>
    </>
  );
}

export function CommandsCard({
  title,
  items,
  now,
  loaded,
}: {
  title: string;
  items: CommandHistoryItem[];
  now: number;
  loaded: boolean;
}) {
  const titleId = useId();
    const columns: Column<CommandHistoryItem>[] = [
    {
      key: 'command',
      header: 'Command',
      className: 'v2-device-cmds__cmd',
      cell: (c) => {
        const detail = commandDetail(c);
        return (
          <>
            <span className="v2-device-cmds__name" title={c.type}>
              {commandName(c.type)}
            </span>
            {detail ? (
              <>
                {' '}
                <span className="v2-device-cmds__detail">
                  <CommandDetail text={detail} />
                </span>
              </>
            ) : null}
          </>
        );
      },
    },
    {
      key: 'status',
      header: 'Status',
      shrink: true,
      cell: (c) => {
        const st = commandStatus(c.status);
        return <StatusPill status={st.kind} label={st.label} dot={false} />;
      },
    },
    {
      key: 'when',
      header: 'When',
      shrink: true,
      align: 'right',
      className: 'v2-device-cmds__when',
      cell: (c) => {
        const when = commandTime(c);
        return when && isoTime(when) ? (
          <time dateTime={isoTime(when)} title={fmtDateTime(when)}>
            {fmtWhen(when, now)}
          </time>
        ) : (
          '—'
        );
      },
    },
  ];
  return (
    <Card padding="list" clip aria-labelledby={titleId} className="v2-device-cmds">
      <CardHeader
        inset
        title={title}
        titleId={titleId}
        actions={
          items.length ? (
            <span className="v2-device-meta">
              {items.length} command{items.length === 1 ? '' : 's'}
            </span>
          ) : null
        }
      />
      {!loaded && !items.length ? (
        <SkeletonRows compact count={4} label={`Loading ${title.toLowerCase()}`} />
      ) : items.length ? (
        <Table<CommandHistoryItem> aria-label={title} rowPad={10} rows={items} rowKey={(c) => String(c.id)} columns={columns} />
      ) : (
        <div className="v2-device-cmds__empty">
          <EmptyState bare icon={<IconHistory />} title="No commands yet">
            Actions you send to this device show up here with their result.
          </EmptyState>
        </div>
      )}
    </Card>
  );
}
