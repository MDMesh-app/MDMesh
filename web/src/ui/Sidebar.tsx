import { createElement } from 'react';
import { Link, NavLink } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { useMayManageDeployment } from '../auth/useMayManageDeployment';
import { useTheme } from './theme';
import { isApplyTerminal, type UpdateStatus } from '../api/updates';
import { useSupervisor } from '../data/useSupervisor';
import { applyPhaseLabel } from '../data/updateStatus';
import { ExtensionBoundary } from '../ext/ExtensionBoundary';
import { useContributions } from '../ext/useContributions';
import { paths } from '../routes';
import { IconButton } from './Button';
import { IconMoon, IconSignOut, IconSun } from './icons';
import { cx } from './cx';
import './Sidebar.css';

/** MDMesh wordmark: ink square + "MDM" 700 + "esh" 500 secondary (at 17px, auth cards at 18px). */
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

/** The sidebar's server-update card. Hidden when there is nothing to say. */
function UpdateCard({ status, onNavigate }: { status: UpdateStatus | null; onNavigate?: () => void }) {
  // v0.3.8: only the admin who runs the install may update; anyone else is told so instead of how long it takes.
  const canUpdate = useMayManageDeployment();
  if (!status) return null;
  const apply = status.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');
  const updatesHref = `${paths.settings}#updates`;

  if (active && apply) {
    return (
      <Link to={updatesHref} className="v2-updcard" onClick={onNavigate}>
        <span className="v2-updcard__title">
          <span className="v2-updcard__dot is-warn" aria-hidden="true" />
          Updating to <span className="v2-mono">{apply.toVersion}</span>
        </span>
        <span className="v2-updcard__text">{applyPhaseLabel(apply.phase)}</span>
        <span className="v2-updcard__link">View progress</span>
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
        <span className="v2-updcard__link">Open recovery</span>
      </a>
    );
  }
  if (!status.updateAvailable || !status.latest) return null;
  const how =
    status.applySupported === false
      ? 'Update from the server shell.'
      : canUpdate
        ? 'About 2 minutes.'
        : 'An admin who runs this install can apply it.';
  return (
    <Link to={updatesHref} className="v2-updcard" onClick={onNavigate}>
      <span className="v2-updcard__title">
        <span className={cx('v2-updcard__dot', status.verified ? 'is-ok' : 'is-warn')} aria-hidden="true" />
        Server update {status.latest}
      </span>
      <span className="v2-updcard__text">
        {status.verified ? 'Verified release.' : 'Signature not verified.'} {how}
      </span>
      <span className="v2-updcard__link">Review update</span>
    </Link>
  );
}

export interface SidebarProps {
  /** Called when a link is followed (closes the mobile drawer). */
  onNavigate?: () => void;
  id?: string;
  className?: string;
}

/**
 * Left column of every board: wordmark, main nav, update card, signed-in user with theme and sign-out.
 * The nav is the registry's `nav.item` slot (core's items are ext/core/nav.ts), filtered by the user's role.
 */
export function Sidebar({ onNavigate, id, className }: SidebarProps) {
  const { user, signOut } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const navItems = useContributions('nav.item');
  // The shell's shared status (at the fastest cadence any mounted consumer asks for: 30 min for the sidebar alone,
  // every minute in a versioned build because of ReloadPrompt, fast while an apply runs); hidden while the
  // supervisor is unreachable.
  const supervisor = useSupervisor();
  const update = supervisor.reachable ? supervisor.status : null;
  const nextTheme = theme === 'dark' ? 'light' : 'dark';

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
        <div className="v2-sidebar__user">
          <span className="v2-sidebar__login" title={user?.login || user?.name || undefined}>
            {user?.login || user?.name || '—'}
          </span>
          <IconButton size="lg" label={`Switch to ${nextTheme} mode`} onClick={toggleTheme}>
            {theme === 'dark' ? <IconSun /> : <IconMoon />}
          </IconButton>
          <IconButton size="lg" label="Sign out" onClick={() => void signOut()}>
            <IconSignOut />
          </IconButton>
        </div>
      </div>
    </nav>
  );
}
