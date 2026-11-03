import { useEffect, useState, type CSSProperties, type MouseEvent, type ReactNode } from 'react';
import { useLocation } from 'react-router';
import { cx } from './cx';
import './SectionNav.css';

export interface SectionNavItem {
  /** Id of the section element the link scrolls to. */
  id: string;
  label: ReactNode;
  /** Right-aligned 12px meta: a count ("3"), "2 changed", "1 ready", "Running". */
  meta?: ReactNode;
  metaTone?: 'default' | 'ok' | 'warn' | 'alert';
}

export interface SectionNavProps {
  items: ReadonlyArray<SectionNavItem>;
  'aria-label': string;
  /** Controlled active id; omit to let the built-in scroll-spy track the section in view. */
  activeId?: string;
  /** Distance from the viewport top at which a section counts as current (px). */
  spyOffset?: number;
  className?: string;
}

/**
 * Tracks which section's top has passed `offset`; the last one wins, and the final one when a scrolling page is
 * scrolled to its end. A page too short to scroll never jumps to the final section.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useScrollSpy(ids: ReadonlyArray<string>, offset = 120): string | undefined {
  const [active, setActive] = useState<string | undefined>(ids[0]);
  const key = ids.join('|');
  useEffect(() => {
    let frame = 0;
    const update = () => {
      frame = 0;
      let current = ids[0];
      for (const id of ids) {
        const el = document.getElementById(id);
        if (el && el.getBoundingClientRect().top - offset <= 0) current = id;
      }
      const doc = document.documentElement;
      const scrolls = doc.scrollHeight > window.innerHeight + 2;
      if (scrolls && window.scrollY > 0 && window.innerHeight + window.scrollY >= doc.scrollHeight - 2 && ids.length) {
        current = ids[ids.length - 1];
      }
      setActive(current);
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, offset]);
  return active;
}

/** Keys that scroll the page by hand (they end a pin). */
const SCROLL_KEYS = ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '];

/**
 * The section the user jumped to (a side-nav click, or a link to the page's #section) stays current until they
 * scroll by hand. The scroll-spy alone would mark the last section whenever the page is scrolled to its end, which
 * is where a jump to one of the lower sections lands on a tall screen.
 */
function usePinnedSection(ids: ReadonlyArray<string>): [string | null, (id: string | null) => void] {
  const { hash, key } = useLocation();
  const [pinned, setPinned] = useState<string | null>(null);
  const idsKey = ids.join('|');
  useEffect(() => {
    const id = decodeURIComponent(hash.replace(/^#/, ''));
    if (id && ids.includes(id)) setPinned(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hash, key, idsKey]);
  useEffect(() => {
    if (!pinned) return;
    const clear = () => setPinned(null);
    const onKey = (e: KeyboardEvent) => {
      if (SCROLL_KEYS.includes(e.key)) clear();
    };
    window.addEventListener('wheel', clear, { passive: true });
    window.addEventListener('touchmove', clear, { passive: true });
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('wheel', clear);
      window.removeEventListener('touchmove', clear);
      window.removeEventListener('keydown', onKey);
    };
  }, [pinned]);
  return [pinned, setPinned];
}

/**
 * Section side-nav (configuration editor, Settings): 8×12 items, r10; the current one raised on the page with
 * 600 ink and `aria-current="location"`. Real `href="#id"` anchors. Narrow screens: a scrollable chip row.
 * The current item follows the scroll position, except that a section jumped to stays current until the user
 * scrolls by hand.
 */
export function SectionNav({ items, activeId, spyOffset, className, ...aria }: SectionNavProps) {
  const ids = items.map((i) => i.id);
  const spied = useScrollSpy(ids, spyOffset);
  const [pinned, setPinned] = usePinnedSection(ids);
  const active = activeId ?? pinned ?? spied;

  const go = (e: MouseEvent<HTMLAnchorElement>, id: string) => {
    const el = document.getElementById(id);
    if (!el) return;
    e.preventDefault();
    setPinned(id);
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
    history.replaceState(history.state, '', `#${id}`);
    // Move focus to the section so keyboard users continue from there.
    if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
    el.focus({ preventScroll: true });
  };

  return (
    <nav className={cx('v2-secnav', className)} {...aria}>
      {items.map((it) => {
        const on = it.id === active;
        return (
          <a
            key={it.id}
            href={`#${it.id}`}
            aria-current={on ? 'location' : undefined}
            className={cx('v2-secnav__item', on && 'is-current')}
            onClick={(e) => go(e, it.id)}
          >
            <span className="v2-secnav__label">{it.label}</span>
            {it.meta !== undefined && it.meta !== null && it.meta !== '' ? (
              <span className={cx('v2-secnav__meta', it.metaTone && it.metaTone !== 'default' && `is-${it.metaTone}`)}>
                {it.meta}
              </span>
            ) : null}
          </a>
        );
      })}
    </nav>
  );
}

/**
 * Two-column layout for SectionNav pages: nav (180-240px) beside the content, 20/24px gaps; stacks under 900px.
 * `contentMax` caps the content column (Settings: 880).
 */
export function SectionLayout({
  nav,
  children,
  contentMax,
  contentBasis = 600,
  className,
}: {
  nav: ReactNode;
  children: ReactNode;
  contentMax?: number;
  contentBasis?: number;
  className?: string;
}) {
  const style = { '--v2-sec-max': contentMax ? `${contentMax}px` : 'none', '--v2-sec-basis': `${contentBasis}px` } as CSSProperties;
  return (
    <div className={cx('v2-seclayout', className)} style={style}>
      <div className="v2-seclayout__nav">{nav}</div>
      <div className="v2-seclayout__content">{children}</div>
    </div>
  );
}
