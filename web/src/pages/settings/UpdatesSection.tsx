import { useMemo, useState } from 'react';
import { applyUpdate, checkForUpdates, setAutoUpdate, type UpdateStatus } from '../../api/updates';
import { useMayManageDeployment } from '../../auth/useMayManageDeployment';
import {
  Badge,
  Button,
  ButtonAnchor,
  Callout,
  Card,
  CardHeader,
  CopyField,
  IconAlert,
  IconCheck,
  IconInfo,
  Modal,
  RelativeTime,
  Stepper,
  Toggle,
  cx,
  formatRelative,
  LinkButton,
} from '../../ui';
import { applyRunning, type SupervisorState } from '../../data/useSupervisor';
import { notesWeight, parseReleaseNotes } from '../../data/releaseNotes';
import { applyPhaseLabel, lastCheckError, safeReleaseUrl } from '../../data/updateStatus';

/** Apply phases in order (supervisor APPLY_PHASES before `done`). */
const PHASES = ['authorizing', 'backup', 'pull', 'recreate', 'healthcheck'] as const;
const PHASE_STEP: Record<(typeof PHASES)[number], string> = {
  authorizing: 'Authorize',
  backup: 'Back up',
  pull: 'Download',
  recreate: 'Restart',
  healthcheck: 'Health check',
};
/** Source installs (APPLY_SUPPORTED=0) update from the shell: the documented commands (README, classic Settings). */
const MANUAL_STEPS = [
  { label: 'Docker, installed from source', cmd: 'git pull && ./setup.sh' },
  { label: 'Native install', cmd: 'git pull && sudo ./setup.sh --native' },
] as const;

const DONE_NOTICE_MS = 24 * 60 * 60 * 1000;

function releasedOn(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return null;
  return new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/** "3 minutes ago", "yesterday", or a date, for use mid-sentence. */
function ago(ms: number): string {
  const r = formatRelative(ms, Date.now(), 'long');
  return r === 'Yesterday' ? 'yesterday' : r;
}

function channelLabel(ch: string | null | undefined): string | null {
  if (!ch) return null;
  return `${ch.charAt(0).toUpperCase()}${ch.slice(1)} channel`;
}

function ReleaseNotes({ status }: { status: UpdateStatus }) {
  const blocks = useMemo(() => parseReleaseNotes(status.release?.notes, status.latest), [status.release?.notes, status.latest]);
  const long = notesWeight(blocks) > 6;
  const [open, setOpen] = useState(false);
  if (blocks.length === 0) return null;
  const id = 'v2-settings-notes';
  return (
    <div className="v2-settings-notes-wrap">
      <div id={id} className={cx('v2-settings-notes', long && !open && 'is-clamped')}>
        {blocks.map((b, i) =>
          b.kind === 'list' ? (
            <ul key={i} className="v2-settings-notes__list">
              {b.items.map((it, j) => (
                <li key={j}>{it}</li>
              ))}
            </ul>
          ) : b.kind === 'heading' ? (
            <p key={i} className="v2-settings-notes__h">
              {b.text}
            </p>
          ) : (
            <p key={i} className="v2-settings-notes__p">
              {b.text}
            </p>
          ),
        )}
      </div>
      {long ? (
        <LinkButton
          aria-expanded={open}
          aria-controls={id}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? 'Show less' : 'Show all notes'}
        </LinkButton>
      ) : null}
    </div>
  );
}

/** Settings → Updates: check, the ready release, apply with live progress, automatic updates. */
export function UpdatesSection({
  id,
  sup,
  rolloutHref,
}: {
  id: string;
  sup: SupervisorState;
  /** In-page link to the agent rollout section. */
  rolloutHref: string;
}) {
  const { status: s, loaded, reachable } = sup;
  const [checking, setChecking] = useState(false);
  const [checkMsg, setCheckMsg] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [starting, setStarting] = useState(false);
  const [applyErr, setApplyErr] = useState<string | null>(null);
  const [autoSaving, setAutoSaving] = useState(false);
  const [autoErr, setAutoErr] = useState<string | null>(null);
  // The supervisor refuses checking, updating, rolling back and the switch to anyone but the admin who runs the install
  // (v0.3.8: a super-admin, or the org admin of a single-customer install; the server's deploymentAdmin). Anyone else
  // sees the status only.
  const canManage = useMayManageDeployment();

  const checkNow = async () => {
    setChecking(true);
    setCheckMsg(null);
    const x = await checkForUpdates();
    setChecking(false);
    if (x) sup.setStatus(x);
    else setCheckMsg('Could not check for updates. Try again in a minute.');
  };

  const applyNow = async () => {
    setStarting(true);
    setApplyErr(null);
    const r = await applyUpdate();
    setStarting(false);
    setConfirming(false);
    if (!r.ok) setApplyErr(r.error || 'The update did not start.');
    void sup.refresh(); // shows the progress at once and polls every 3 s while it runs
  };

  const toggleAuto = async (next: boolean) => {
    setAutoSaving(true);
    setAutoErr(null);
    const r = await setAutoUpdate(next);
    setAutoSaving(false);
    if (!r.ok) {
      setAutoErr(r.error ? `Could not save: ${r.error}` : 'Could not save.');
      return;
    }
    sup.patch({ auto: next });
  };

  const apply = s?.apply ?? null;
  const running = applyRunning(s);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');
  const justDone =
    !!apply && apply.phase === 'done' && !!apply.finishedAt && Date.now() - apply.finishedAt < DONE_NOTICE_MS;
  const canApply = s?.applySupported !== false;
  const lastError = lastCheckError(s);
  const releaseUrl = safeReleaseUrl(s?.release?.url);

  const header = (
    <CardHeader
      title="Updates"
      titleId={`${id}-title`}
      actions={
        !loaded ? null : s ? (
          <>
            <span className="v2-settings-meta">
              {s.checkedAt ? <RelativeTime value={s.checkedAt} style="long" prefix="Checked " /> : 'Not checked yet'}
            </span>
            {canManage ? (
              <Button onClick={() => void checkNow()} disabled={checking || running}>
                {checking ? 'Checking…' : 'Check now'}
              </Button>
            ) : null}
          </>
        ) : (
          <Button onClick={() => void sup.refresh()}>Try again</Button>
        )
      }
    />
  );

  return (
    <Card id={id} aria-labelledby={`${id}-title`} gap={14} className="v2-settings-card">
      {header}

      {!loaded ? <p className="v2-settings-muted">Asking the updater…</p> : null}

      {loaded && !s ? (
        <Callout tone="info" icon={<IconInfo />}>
          The updater isn&rsquo;t reachable, so this console can&rsquo;t check for or install updates. It runs next to
          the server (the <span className="v2-mono">supervisor</span> service) and serves{' '}
          <span className="v2-mono">/update</span>.
        </Callout>
      ) : null}

      {s && !reachable ? (
        <p className="v2-settings-muted" role="status">
          Can&rsquo;t reach the updater right now. Showing the last state it reported.
        </p>
      ) : null}

      {checkMsg ? (
        <Callout tone="alert" role="alert" icon={<IconAlert />}>
          {checkMsg}
        </Callout>
      ) : null}
      {s && lastError && !checkMsg ? (
        <Callout tone="warn" icon={<IconAlert />}>
          The last check failed: {lastError}
        </Callout>
      ) : null}
      {applyErr ? (
        <Callout tone="alert" role="alert" icon={<IconAlert />}>
          The update didn&rsquo;t start: {applyErr}
        </Callout>
      ) : null}

      {/* Apply in progress: live phase, polled every 3 s. */}
      {s && running && apply ? (
        <div className="v2-settings-upd v2-settings-upd--busy">
          <div className="v2-settings-upd__titleline">
            <span className="v2-settings-upd__title">
              Updating to MDMesh <span className="v2-mono">{apply.toVersion}</span>
            </span>
            {apply.trigger === 'auto' ? <Badge tone="neutral" size="md">Started automatically</Badge> : null}
          </div>
          {apply.phase === 'rollback' ? null : (
            <Stepper
              aria-label="Update progress"
              steps={PHASES.map((p) => PHASE_STEP[p])}
              current={Math.max(0, PHASES.indexOf(apply.phase as (typeof PHASES)[number]))}
            />
          )}
          <p className={cx('v2-settings-upd__line', apply.phase === 'rollback' && 'is-warn')} role="status" aria-live="polite">
            {applyPhaseLabel(apply.phase)}
            {apply.startedAt ? ` Started ${ago(apply.startedAt)}.` : null}
          </p>
          {apply.error ? <p className="v2-settings-upd__line is-warn">{apply.error}</p> : null}
          <p className="v2-settings-upd__help">
            The console may stop answering for a minute while the server restarts. This page keeps checking and
            catches up on its own.
          </p>
        </div>
      ) : null}

      {/* The last apply failed or rolled back. */}
      {s && failed && apply ? (
        <div className="v2-settings-upd v2-settings-upd--alert" role="alert">
          <span className="v2-settings-upd__title">
            The update to <span className="v2-mono">{apply.toVersion}</span>
            {apply.phase === 'rolled_back' ? ' was rolled back' : ' failed'}
          </span>
          <p className="v2-settings-upd__help">
            {apply.phase === 'rolled_back' ? (
              <>
                It didn&rsquo;t pass its checks, so the server went back to{' '}
                <span className="v2-mono">{apply.fromVersion}</span> and its database backup.
              </>
            ) : (
              <>It stopped and could not roll back on its own. Recovery can roll back to the backup or retry.</>
            )}
            {apply.finishedAt ? <> Finished {ago(apply.finishedAt)}.</> : null}
          </p>
          {apply.error ? <p className="v2-settings-upd__error v2-mono">{apply.error}</p> : null}
          <div className="v2-settings-upd__actions">
            <ButtonAnchor href="/recovery" size="lg" strong>
              Open recovery
            </ButtonAnchor>
          </div>
        </div>
      ) : null}

      {s && justDone && apply && !running ? (
        <Callout tone="ok" role="status" icon={<IconCheck />}>
          <span className="v2-settings-callout-row">
            <span>
              Updated to MDMesh <span className="v2-mono">{apply.toVersion}</span>{' '}
              {ago(apply.finishedAt as number)}. Reload to use the new console.
            </span>
            <Button size="sm" onClick={() => window.location.reload()}>
              Reload
            </Button>
          </span>
        </Callout>
      ) : null}

      {/* A release is ready. */}
      {s && s.updateAvailable && s.latest && !running ? (
        <div className="v2-settings-upd v2-settings-upd--ready">
          <div className="v2-settings-upd__titleline">
            <span className="v2-settings-upd__title">
              MDMesh <span className="v2-mono">{s.latest}</span> is ready
            </span>
            {s.verified ? (
              <Badge tone="ok" size="md" icon={<IconCheck size={12} />}>
                Signature verified
              </Badge>
            ) : (
              <Badge tone="warn" size="md" icon={<IconAlert size={12} />}>
                Signature not verified
              </Badge>
            )}
            {channelLabel(s.channel) || releasedOn(s.release?.publishedAt) ? (
              <span className="v2-settings-meta">
                {[channelLabel(s.channel), releasedOn(s.release?.publishedAt) && `released ${releasedOn(s.release?.publishedAt)}`]
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            ) : null}
          </div>

          <ReleaseNotes status={s} />

          {canApply ? (
            // Without the Update button, its help ("Takes about 2 minutes…") goes too, and a line says who can update;
            // the unverified warning stays.
            <div className="v2-settings-upd__actions">
              {canManage ? (
                <Button
                  variant="primary"
                  size="xl"
                  disabled={!s.verified || starting}
                  onClick={() => setConfirming(true)}
                >
                  Update server and console…
                </Button>
              ) : null}
              {releaseUrl ? (
                <a className="v2-settings-strong-link" href={releaseUrl} target="_blank" rel="noreferrer">
                  Full release notes ↗
                </a>
              ) : null}
              {!canManage ? (
                <span className="v2-settings-upd__help v2-settings-upd__sentence">
                  Only a super-admin, or the admin of a single-customer install, can update.
                </span>
              ) : null}
              {canManage || !s.verified ? (
                <span className={cx('v2-settings-upd__help', 'v2-settings-upd__sentence', !s.verified && 'is-alert')}>
                  {s.verified
                    ? 'Takes about 2 minutes. The database is backed up first, and devices keep working while the console restarts.'
                    : 'The release signature could not be verified, so it can’t be installed.'}
                </span>
              ) : null}
            </div>
          ) : (
            <div className="v2-settings-manual">
              <p className="v2-settings-upd__help">
                This server can&rsquo;t update itself: it was installed from source or with the native installer.
                Update it from the server&rsquo;s shell, in the MDMesh folder:
              </p>
              {MANUAL_STEPS.map((m) => (
                <div key={m.label} className="v2-settings-manual__row">
                  <span className="v2-settings-manual__label">{m.label}</span>
                  <CopyField value={m.cmd} aria-label={`${m.label} update command`} />
                </div>
              ))}
              {releaseUrl ? (
                <a className="v2-settings-strong-link" href={releaseUrl} target="_blank" rel="noreferrer">
                  Full release notes ↗
                </a>
              ) : null}
            </div>
          )}
          {s.apk?.available ? (
            <p className="v2-settings-upd__help">
              The release carries agent <span className="v2-mono">{s.apk.version}</span>.{' '}
              <a href={rolloutHref}>Roll it out to devices</a> after the server update.
            </p>
          ) : null}
        </div>
      ) : null}

      {/* Nothing to install. */}
      {s && !s.updateAvailable && !running ? (
        <div className="v2-settings-uptodate">
          <span className="v2-settings-uptodate__icon" aria-hidden="true">
            <IconCheck size={13} />
          </span>
          <span>
            MDMesh <span className="v2-mono">{s.current}</span> is the latest release
            {channelLabel(s.channel) ? ` on the ${channelLabel(s.channel)?.toLowerCase()}` : ''}.
          </span>
        </div>
      ) : null}
      {s && !s.updateAvailable && s.latest && !s.verified && s.latest !== s.current ? (
        <Callout tone="warn" icon={<IconAlert />}>
          MDMesh <span className="v2-mono">{s.latest}</span> was found, but its signature couldn&rsquo;t be verified, so it
          won&rsquo;t be offered or installed.
        </Callout>
      ) : null}

      {/* Automatic updates: only where the updater can apply (APPLY_SUPPORTED); stored in its auto.json. */}
      {s && canApply ? (
        <div className="v2-settings-auto">
          <div className="v2-settings-auto__text">
            <span id={`${id}-auto`} className="v2-settings-auto__label">
              Install updates automatically
            </span>
            <span id={`${id}-auto-help`} className="v2-settings-auto__help">
              Verified releases install within a few hours of release. Unverified releases never install on their
              own.
            </span>
          </div>
          {canManage ? (
            <Toggle
              aria-labelledby={`${id}-auto`}
              aria-describedby={`${id}-auto-help`}
              checked={!!s.auto}
              disabled={autoSaving}
              onChange={(v) => void toggleAuto(v)}
            />
          ) : (
            <span className="v2-settings-meta">{s.auto ? 'On' : 'Off'}</span>
          )}
          {s.auto && s.autoSkipped && s.autoSkipped === s.latest ? (
            <p className="v2-settings-auto__note">
              Automatic updates won&rsquo;t install <span className="v2-mono">{s.autoSkipped}</span> again, because its
              last update failed or was rolled back.{' '}
              {canManage ? 'Update installs it by hand; a newer release installs on its own.' : 'A newer release installs on its own.'}
            </p>
          ) : null}
          {autoErr ? (
            <p className="v2-settings-auto__note is-alert" role="alert">
              {autoErr}
            </p>
          ) : null}
        </div>
      ) : null}

      <Modal
        open={confirming}
        onClose={() => !starting && setConfirming(false)}
        role="alertdialog"
        size="sm"
        dismissOnBackdrop={!starting}
        title={`Update to MDMesh ${s?.latest ?? ''}?`}
        footer={
          <>
            <Button size="lg" onClick={() => setConfirming(false)} disabled={starting}>
              Cancel
            </Button>
            <Button variant="primary" size="lg" onClick={() => void applyNow()} disabled={starting}>
              {starting ? 'Starting…' : 'Update now'}
            </Button>
          </>
        }
      >
        <p className="v2-settings-modal-text">
          The server and console restart, which takes about 2 minutes. The database is backed up first, and the update
          rolls back on its own if it fails. Devices keep working and reconnect when the server is back.
        </p>
      </Modal>
    </Card>
  );
}
