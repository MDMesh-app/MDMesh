// The Overview's Server card: the running version, and an update on offer, running or failed.
import type { ReactNode } from 'react';
import { isApplyTerminal, type UpdateStatus } from '../../api/updates';
import {
  applyPhaseLabel,
  blockedShort,
  lastCheckError,
  missedSecurityShort,
  unverifiedRelease,
  upToDate,
  updateOffer,
} from '../../data/updateStatus';
import { UPDATES_HREF } from '../../routes';
import { ButtonAnchor, ButtonLink, Card, CardHeader, cx, IconCheck, IconShield, RelativeTime, StatusDot } from '../../ui';

/**
 * The server's version, and an update as an inset callout with its action: "0.1.15 is available", its signature and
 * channel, "Review update" (the sidebar's card says the same and links to the same place). A running or failed
 * update shows its progress or the way to recovery instead.
 */
export function ServerCard({ status }: { status: UpdateStatus }) {
  const apply = status.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');
  const channel = status.channel ? ` · ${status.channel} channel` : '';

  const offer = updateOffer(status);
  let panel: ReactNode = null;
  if (active && apply) {
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">Updating to {apply.toVersion}</span>
        <span className="v2-ov-srv__line is-warn">
          <StatusDot tone="warn" />
          {applyPhaseLabel(apply.phase)}
        </span>
        <ButtonLink size="sm" to={UPDATES_HREF}>
          View progress
        </ButtonLink>
      </div>
    );
  } else if (failed && apply) {
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">{apply.phase === 'rolled_back' ? 'Update rolled back' : 'Update failed'}</span>
        {apply.error ? <span className="v2-ov-srv__line is-alert">{apply.error}</span> : null}
        {/* /recovery is served outside the SPA. */}
        <ButtonAnchor size="sm" href="/recovery">
          Open recovery
        </ButtonAnchor>
      </div>
    );
  } else if (offer?.kind === 'blocked') {
    // Found but not installable here (the sidebar's card says the same): amber only when it leaves security fixes missing.
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">{offer.latest} can&rsquo;t be installed here</span>
        <span className={cx('v2-ov-srv__line v2-ov-srv__line--long', offer.missed.length > 0 && 'is-warn')}>
          {offer.missed.length > 0 ? (
            <span className="v2-ov-srv__icon" aria-hidden="true">
              <IconShield size={14} />
            </span>
          ) : null}
          <span>{blockedShort(status, offer.missed)}</span>
        </span>
        <ButtonLink size="sm" to={UPDATES_HREF}>
          See why
        </ButtonLink>
      </div>
    );
  } else if (offer) {
    // An update that skips security releases (verified manifest only) says so in amber, as the sidebar card does.
    const missed = offer.missed;
    panel = (
      <div className="v2-ov-srv__panel">
        <span className="v2-ov-srv__title">{offer.latest} is available</span>
        {missed.length > 0 ? (
          <span className="v2-ov-srv__line v2-ov-srv__line--long is-warn">
            <span className="v2-ov-srv__icon" aria-hidden="true">
              <IconShield size={14} />
            </span>
            <span>{missedSecurityShort(missed)}</span>
          </span>
        ) : status.verified ? (
          <span className="v2-ov-srv__line is-ok">
            <IconCheck size={14} />
            Signature verified{channel}
          </span>
        ) : (
          <span className="v2-ov-srv__line is-warn">
            <StatusDot tone="warn" />
            Signature not verified{channel}
          </span>
        )}
        <ButtonLink size="sm" to={UPDATES_HREF}>
          Review update
        </ButtonLink>
      </div>
    );
  }

  return (
    <Card gap={12} aria-labelledby="v2-ov-srv">
      <CardHeader title="Server" titleId="v2-ov-srv" />
      <div className="v2-ov-srv__run">
        <StatusDot tone="ok" size={8} />
        <span>Running {status.current}</span>
      </div>
      {panel ??
        // A newer release that didn't verify comes with the check's error too: the more specific line first.
        (unverifiedRelease(status) ? (
          <span className="v2-ov-srv__meta">
            {status.latest} was found, but its signature couldn&rsquo;t be verified.
          </span>
        ) : lastCheckError(status) ? (
          <span className="v2-ov-srv__meta">The last update check failed.</span>
        ) : upToDate(status) ? (
          <span className="v2-ov-srv__meta">
            Up to date{channel}
            {status.checkedAt ? (
              <>
                {' · '}
                <RelativeTime value={status.checkedAt} style="long" prefix="checked " />
              </>
            ) : null}
          </span>
        ) : null)}
    </Card>
  );
}
