import { Suspense, useEffect, useRef, useState, type MouseEvent, type RefObject } from 'react';
import { Outlet, useLocation } from 'react-router';
import { ReloadPrompt } from './ReloadPrompt';
import { SupervisorProvider } from '../data/useSupervisor';
import { WhatsNewProvider } from '../whatsnew/WhatsNew';
import { IconButton } from './Button';
import { IconMenu } from './icons';
import { PageErrorBoundary } from './PageErrorBoundary';
import { Sidebar, Wordmark } from './Sidebar';
import { Skeleton, SkeletonRows } from './Skeleton';
import { MOBILE_QUERY, useMediaQuery } from './useMediaQuery';
import { cx } from './cx';
import './AppShell.css';

/** What a page shows while its chunk downloads the first time (later visits keep the previous page meanwhile). */
function PageSkeleton() {
  return (
    <div className="v2-pageskel" role="status">
      <span className="v2-visually-hidden">Loading page…</span>
      <Skeleton width="180px" height="title" />
      <div className="v2-pageskel__card">
        <SkeletonRows count={6} />
      </div>
    </div>
  );
}

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
 * Desktop: a 220px sticky sidebar beside a left-anchored main column (up to 1440, 24px gutters, 16px between
 * sections). Each page fades in (opacity only) as it arrives; while a page's chunk downloads for the first time a
 * skeleton holds its place, and moving between pages keeps the previous one on screen until the next is ready.
 * ≤ 860px: a sticky top bar (--topbar-h) with the menu button and the wordmark (the page's own heading names it), the
 * sidebar as an off-canvas drawer over a scrim (Escape, scrim click and navigation close it; focus moves in and back).
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
      // An Escape a menu or dialog inside the drawer already used (it prevents the default) closes only that.
      if (e.key === 'Escape' && !e.defaultPrevented) setOpen(false);
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
                  size="md"
                  label={open ? 'Close navigation' : 'Open navigation'}
                  aria-expanded={open}
                  aria-controls="v2-sidebar"
                  onClick={() => setOpen((v) => !v)}
                >
                  <IconMenu />
                </IconButton>
                <Wordmark className="v2-mobilebar__brand" />
              </div>
            ) : null}
            <main ref={mainRef} id="v2-main" tabIndex={-1} className="v2-shell__main">
              <ReloadPrompt />
              {/* Pages are lazy chunks; a crash or a failed chunk replaces only the page, never the shell. The Suspense
                  boundary sits outside the per-page boundary so it survives navigation: a page still downloading
                  keeps the previous page on screen (navigations are transitions) instead of an empty frame. */}
              <Suspense fallback={<PageSkeleton />}>
                <PageErrorBoundary key={location.pathname}>
                  <div className="v2-route">
                    <Outlet />
                  </div>
                </PageErrorBoundary>
              </Suspense>
            </main>
          </div>
        </div>
      </WhatsNewProvider>
    </SupervisorProvider>
  );
}
