import { createElement, useState } from 'react';
import { Link, NavLink } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { useMayManageDeployment } from '../auth/useMayManageDeployment';
import { useTheme } from './theme';
import { isApplyTerminal, type UpdateStatus } from '../api/updates';
import { useSupervisor } from '../data/useSupervisor';
import { applyPhaseLabel, blockedShort, missedSecurityShort, updateOffer } from '../data/updateStatus';
import { ExtensionBoundary } from '../ext/ExtensionBoundary';
import { useContributions } from '../ext/useContributions';
import { paths, UPDATES_HREF } from '../routes';
import * as DM from '@radix-ui/react-dropdown-menu';
import { usePrefetchRoute } from '../ext/usePrefetchRoute';
import { IconChevronDown, IconMoon, IconSignOut, IconSun } from './icons';
import { cx } from './cx';
import './Menu.css';
import './Sidebar.css';

/** MDMesh wordmark: ink square + "MDM" 600 + "esh" 400 secondary, at the card-title size. */
export function Wordmark({ size = 17, className }: { size?: 17 | 18; className?: string }) {
  return (
    <span className={cx('v2-wordmark', size === 18 && 'v2-wordmark--18', className)} role="img" aria-label="MDMesh">
      <span className="v2-wordmark__mark" aria-hidden="true" />
      <span aria-hidden="true">
        MDM<span className="v2-wordmark__esh">esh</span>
      </span>
    </span>
  );
}

/**
 * The sidebar's server-update card, above the account row: a status dot and "Server update 0.1.15", one short line,
 * and the action. When the update brings security fixes from releases this server skipped, the dot is amber, the
 * title "Update 0.1.15" and the line says which. The Overview's Server card says the same in the same words; both
 * lead to Settings → Updates. Hidden when there is nothing to say.
 */
function UpdateCard({ status, onNavigate }: { status: UpdateStatus | null; onNavigate?: () => void }) {
  // Only the admin who runs the install may update; anyone else is told so instead of how long it takes.
  const canUpdate = useMayManageDeployment();
  if (!status) return null;
  const apply = status.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');

  if (active && apply) {
    return (
      <Link to={UPDATES_HREF} className="v2-updcard" onClick={onNavigate}>
        <span className="v2-updcard__title">
          <span className="v2-updcard__dot is-warn" aria-hidden="true" />
          Updating to {apply.toVersion}
        </span>
        <span className="v2-updcard__text">{applyPhaseLabel(apply.phase)}</span>
        <span className="v2-updcard__action">View progress</span>
      </Link>
    );
  }
  if (failed && apply) {
    return (
      <a href="/recovery" className="v2-updcard">
        <span className="v2-updcard__title">
          <span className="v2-updcard__dot is-alert" aria-hidden="true" />
          {apply.phase === 'rolled_back' ? 'Update rolled back' : 'Update failed'}
        </span>
        {apply.error ? <span className="v2-updcard__text">{apply.error}</span> : null}
        <span className="v2-updcard__action">Open recovery</span>
      </a>
    );
  }
  const offer = updateOffer(status);
  if (!offer) return null;
  // Found but not installable here: quiet, unless it leaves security fixes missing (amber, and it says so).
  if (offer.kind === 'blocked') {
    return (
      <Link to={UPDATES_HREF} className="v2-updcard" onClick={onNavigate}>
        <span className="v2-updcard__title">
          <span className={cx('v2-updcard__dot', offer.missed.length > 0 ? 'is-warn' : 'is-idle')} aria-hidden="true" />
          {offer.latest} can&rsquo;t be installed
        </span>
        <span className="v2-updcard__text">{blockedShort(status, offer.missed)}</span>
        <span className="v2-updcard__action">See why</span>
      </Link>
    );
  }
  const how =
    status.applySupported === false
      ? 'Update from the server shell.'
      : canUpdate
        ? 'About 2 minutes to apply.'
        : 'An admin can apply it.';
  // The update skips over security releases (verified manifest only): amber, and the card says which. Who can apply
  // it, or how, still shows where it isn't this admin's one click.
  const missed = offer.missed;
  if (missed.length > 0) {
    return (
      <Link to={UPDATES_HREF} className="v2-updcard" onClick={onNavigate}>
        <span className="v2-updcard__title">
          <span className="v2-updcard__dot is-warn" aria-hidden="true" />
          Update {status.latest}
        </span>
        <span className="v2-updcard__text">
          {missedSecurityShort(missed)}
          {status.applySupported === false || !canUpdate ? ` ${how}` : null}
        </span>
        <span className="v2-updcard__action">Review update</span>
      </Link>
    );
  }
  return (
    <Link to={UPDATES_HREF} className="v2-updcard" onClick={onNavigate}>
      <span className="v2-updcard__title">
        <span className={cx('v2-updcard__dot', status.verified ? 'is-ok' : 'is-warn')} aria-hidden="true" />
        Server update {status.latest}
      </span>
      <span className="v2-updcard__text">
        {status.verified ? 'Verified release.' : 'Signature not verified.'} {how}
      </span>
      <span className="v2-updcard__action">Review update</span>
    </Link>
  );
}

/** The signed-in user's row: their login, and a menu with the theme switch and Sign out. */
function AccountMenu() {
  const { user, signOut } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const nextTheme = theme === 'dark' ? 'light' : 'dark';
  const name = user?.login || user?.name || '—';
  const [open, setOpen] = useState(false);
  return (
    <DM.Root modal={false} open={open} onOpenChange={setOpen}>
      <DM.Trigger
        className="v2-account"
        onClick={(e) => {
          // A click with no pointer behind it (a screen reader's activation) toggles, as on Menu.
          if (e.detail === 0) setOpen((o) => !o);
        }}
      >
        <span className="v2-account__avatar" aria-hidden="true">
          {name.slice(0, 1).toUpperCase()}
        </span>
        <span className="v2-account__name">
          <span className="v2-visually-hidden">Account: </span>
          {name}
        </span>
        <IconChevronDown />
      </DM.Trigger>
      <DM.Portal>
        <DM.Content className="v2-menu__popup" side="top" align="start" sideOffset={4} collisionPadding={8} loop>
          <DM.Item className="v2-menu__item" onSelect={toggleTheme}>
            <span className="v2-menu__icon" aria-hidden="true">
              {theme === 'dark' ? <IconSun /> : <IconMoon />}
            </span>
            Switch to {nextTheme} mode
          </DM.Item>
          <DM.Separator className="v2-menu__sep" />
          <DM.Item className="v2-menu__item" onSelect={() => void signOut()}>
            <span className="v2-menu__icon" aria-hidden="true">
              <IconSignOut />
            </span>
            Sign out
          </DM.Item>
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  );
}

export interface SidebarProps {
  /** Called when a link is followed (closes the mobile drawer). */
  onNavigate?: () => void;
  id?: string;
  className?: string;
}

/**
 * The left column: wordmark, main nav (--nav-item rows; hovering or focusing one starts loading its page), the
 * server-update card when there is one, and the account row (theme and sign out in its menu).
 * The nav is the registry's `nav.item` slot (core's items are ext/core/nav.ts), filtered by the user's role.
 */
export function Sidebar({ onNavigate, id, className }: SidebarProps) {
  const navItems = useContributions('nav.item');
  const prefetch = usePrefetchRoute();
  // The shell's shared status (at the fastest cadence any mounted consumer asks for: 30 min for the sidebar alone,
  // every minute in a versioned build because of ReloadPrompt, fast while an apply runs); hidden while the
  // supervisor is unreachable.
  const supervisor = useSupervisor();
  const update = supervisor.reachable ? supervisor.status : null;

  return (
    <nav id={id} aria-label="Main" className={cx('v2-sidebar', className)}>
      <Link to={paths.overview} className="v2-sidebar__brand" onClick={onNavigate}>
        <Wordmark />
      </Link>
      <div className="v2-sidebar__nav">
        {navItems.map((entry) => {
          const { to, label, icon, badge } = entry.contribution;
          return (
            <NavLink
              key={entry.key}
              to={to}
              end={to === paths.overview}
              className={({ isActive }) => cx('v2-navitem', isActive && 'is-active')}
              onClick={onNavigate}
              onPointerEnter={() => prefetch(to)}
              onFocus={() => prefetch(to)}
            >
              <ExtensionBoundary entry={entry} fallback="none">
                {createElement(icon)}
              </ExtensionBoundary>
              {badge ? (
                <>
                  <span className="v2-navitem__label">{label}</span>
                  <ExtensionBoundary entry={entry} fallback="none">
                    {createElement(badge)}
                  </ExtensionBoundary>
                </>
              ) : (
                label
              )}
            </NavLink>
          );
        })}
      </div>
      <div className="v2-sidebar__foot">
        <UpdateCard status={update} onNavigate={onNavigate} />
        <AccountMenu />
      </div>
    </nav>
  );
}
