import { createElement, useState } from 'react';
import { Link, NavLink } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from './theme';
import { isApplyTerminal, type UpdateStatus } from '../api/updates';
import { useSupervisor } from '../data/useSupervisor';
import { applyPhaseLabel } from '../data/updateStatus';
import { ExtensionBoundary } from '../ext/ExtensionBoundary';
import { useContributions } from '../ext/useContributions';
import { paths } from '../routes';
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
 * The sidebar's one-line server-update item, the console's single announcement of a server update (Settings →
 * Updates has the details). Hidden when there is nothing to say.
 */
function UpdateItem({ status, onNavigate }: { status: UpdateStatus | null; onNavigate?: () => void }) {
  if (!status) return null;
  const apply = status.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');
  const updatesHref = `${paths.settings}#updates`;

  if (active && apply) {
    return (
      <Link to={updatesHref} className="v2-upditem" onClick={onNavigate}>
        <span className="v2-upditem__dot is-warn" aria-hidden="true" />
        <span className="v2-upditem__text">
          Updating to {apply.toVersion}
          <span className="v2-visually-hidden">: {applyPhaseLabel(apply.phase)}</span>
        </span>
        <span className="v2-upditem__go">View</span>
      </Link>
    );
  }
  if (failed && apply) {
    return (
      <a href="/recovery" className="v2-upditem">
        <span className="v2-upditem__dot is-alert" aria-hidden="true" />
        <span className="v2-upditem__text">{apply.phase === 'rolled_back' ? 'Update rolled back' : 'Update failed'}</span>
        <span className="v2-upditem__go">Recover</span>
      </a>
    );
  }
  if (!status.updateAvailable || !status.latest) return null;
  return (
    <Link to={updatesHref} className="v2-upditem" onClick={onNavigate}>
      <span className={cx('v2-upditem__dot', status.verified ? 'is-ok' : 'is-warn')} aria-hidden="true" />
      <span className="v2-upditem__text">
        <span className="v2-visually-hidden">Server</span> {status.latest} available
        {status.verified ? null : <span className="v2-visually-hidden"> (signature not verified)</span>}
      </span>
      <span className="v2-upditem__go">Review</span>
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
        aria-label={`Account: ${name}`}
        onClick={(e) => {
          if (e.detail === 0) setOpen(true);
        }}
      >
        <span className="v2-account__avatar" aria-hidden="true">
          {name.slice(0, 1).toUpperCase()}
        </span>
        <span className="v2-account__name">{name}</span>
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
 * The left column: wordmark, main nav (30px items; hovering or focusing one starts loading its page), the one-line
 * server-update item, and the account row (theme and sign out in its menu).
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
        <UpdateItem status={update} onNavigate={onNavigate} />
        <AccountMenu />
      </div>
    </nav>
  );
}
