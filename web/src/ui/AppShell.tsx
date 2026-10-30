import { Suspense, useEffect, useRef, useState, type MouseEvent, type RefObject } from 'react';
import { Outlet, useLocation } from 'react-router';
import { ReloadPrompt } from './ReloadPrompt';
import { SupervisorProvider } from '../data/useSupervisor';
import { WhatsNewProvider } from '../whatsnew/WhatsNew';
import { useContributions } from '../ext/useContributions';
import { IconButton } from './Button';
import { IconMenu } from './icons';
import { PageErrorBoundary } from './PageErrorBoundary';
import { Sidebar } from './Sidebar';
import { MOBILE_QUERY, useMediaQuery } from './useMediaQuery';
import { cx } from './cx';
import './AppShell.css';

/** How long a #hash target is kept in view while the page above it is still loading. */
const HASH_SETTLE_MS = 4000;

function focusTarget(el: HTMLElement) {
  if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
  el.focus({ preventScroll: true });
}

/**
 * A navigation to `#id` (a deep link such as /settings#updates, or the same link followed again) scrolls the
 * element into view and focuses it once the page has rendered it. Sections above it grow as their data arrives, so
 * it is put back in view on every layout change until the user scrolls, types or clicks, or HASH_SETTLE_MS pass.
 */
function useHashTarget(main: RefObject<HTMLElement | null>) {
  const { hash, key } = useLocation();
  useEffect(() => {
    const id = decodeURIComponent(hash.replace(/^#/, ''));
    if (!id) return;
    const until = performance.now() + HASH_SETTLE_MS;
    let frame = 0;
    let target: HTMLElement | null = null;
    let observer: ResizeObserver | null = null;
    const stop = () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
      window.removeEventListener('wheel', stop);
      window.removeEventListener('touchmove', stop);
      window.removeEventListener('keydown', stop);
      window.removeEventListener('pointerdown', stop);
    };
    const find = () => {
      target = document.getElementById(id);
      if (!target) {
        if (performance.now() < until) frame = requestAnimationFrame(find);
        else stop();
        return;
      }
      target.scrollIntoView({ block: 'start' });
      focusTarget(target);
      if (main.current && typeof ResizeObserver !== 'undefined') {
        observer = new ResizeObserver(() => {
          if (performance.now() > until) stop();
          else target?.scrollIntoView({ block: 'start' });
        });
        observer.observe(main.current);
      }
    };
    window.addEventListener('wheel', stop, { passive: true });
    window.addEventListener('touchmove', stop, { passive: true });
    window.addEventListener('keydown', stop);
    window.addEventListener('pointerdown', stop);
    frame = requestAnimationFrame(find);
    return stop;
  }, [hash, key, main]);
}

/**
 * Signed-in layout for every page (a layout route: pages render into <Outlet/>). It owns the supervisor status
 * poll (SupervisorProvider), so the sidebar's update card, the Overview and Settings share one status, and the What's
 * new dialog (whatsnew/WhatsNew.tsx), which never shows over the signed-out pages.
 * Desktop: the board's flex row, sidebar `1 1 232px` beside a centred main column (max 1280, padding
 * clamp(20px, 3vw, 32px), 20px between sections), sidebar sticky at full height.
 * ≤ 860px: the v0.3 console's pattern, a 52px sticky top bar with a menu button and the page name, the sidebar
 * as an off-canvas drawer over a scrim (Escape, scrim click and navigation close it; focus moves in and back).
 */
export function AppShell() {
  const location = useLocation();
  const mobile = useMediaQuery(MOBILE_QUERY);
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  useHashTarget(mainRef);

  // The skip link moves focus to the page without adding a #v2-main history entry (Back would only undo it).
  const skip = (e: MouseEvent<HTMLAnchorElement>) => {
    const main = mainRef.current;
    if (!main) return;
    e.preventDefault();
    main.scrollIntoView({ block: 'start' });
    main.focus({ preventScroll: true });
  };

  // The mobile bar names the sidebar item the page belongs to.
  const section = useContributions('nav.item').find(({ contribution: { to } }) =>
    location.pathname === to || location.pathname.startsWith(`${to}/`),
  );
  const title = section?.contribution.label ?? 'MDMesh';

  // Navigation closes the drawer.
  useEffect(() => {
    setOpen(false);
  }, [location.pathname]);

  // Leaving the mobile layout closes it too.
  useEffect(() => {
    if (!mobile) setOpen(false);
  }, [mobile]);

  // Escape closes; focus moves into the drawer on open and back to the menu button on close.
  useEffect(() => {
    if (!open) return;
    drawerRef.current?.querySelector<HTMLElement>('a[href], button')?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    const menu = menuRef.current;
    return () => {
      document.removeEventListener('keydown', onKey);
      if (menu && drawerRef.current?.contains(document.activeElement)) menu.focus();
    };
  }, [open]);

  const hidden = mobile && !open;

  return (
    <SupervisorProvider>
      <WhatsNewProvider>
        <div className="v2-shell">
          <a href="#v2-main" className="v2-skip" inert={(mobile && open) || undefined} onClick={skip}>
            Skip to content
          </a>
          <div
            ref={drawerRef}
            className={cx('v2-shell__side', open && 'is-open')}
            inert={hidden || undefined}
            aria-hidden={hidden || undefined}
          >
            <Sidebar id="v2-sidebar" onNavigate={() => setOpen(false)} />
          </div>
          {mobile ? (
            <div className={cx('v2-scrim', open && 'is-open')} onClick={() => setOpen(false)} aria-hidden="true" />
          ) : null}
          <div className="v2-shell__mainwrap" inert={(mobile && open) || undefined}>
            {mobile ? (
              <div className="v2-mobilebar">
                <IconButton
                  ref={menuRef}
                  size="lg"
                  label={open ? 'Close navigation' : 'Open navigation'}
                  aria-expanded={open}
                  aria-controls="v2-sidebar"
                  onClick={() => setOpen((v) => !v)}
                >
                  <IconMenu />
                </IconButton>
                <span className="v2-mobilebar__title">{title}</span>
              </div>
            ) : null}
            <main ref={mainRef} id="v2-main" tabIndex={-1} className="v2-shell__main">
              <ReloadPrompt />
              {/* Pages are lazy chunks; a crash or a failed chunk replaces only the page, never the shell. */}
              <PageErrorBoundary key={location.pathname}>
                <Suspense fallback={null}>
                  <Outlet />
                </Suspense>
              </PageErrorBoundary>
            </main>
          </div>
        </div>
      </WhatsNewProvider>
    </SupervisorProvider>
  );
}
