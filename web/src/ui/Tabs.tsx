import { useId, useLayoutEffect, useRef, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import { Link, NavLink } from 'react-router';
import { Badge } from './Badge';
import { cx } from './cx';
import { useOverflowFade } from './useOverflowFade';
import './Tabs.css';

export interface TabItem<K extends string> {
  key: K;
  label: ReactNode;
  icon?: ReactNode;
  /** Mono count after the label ("Activity 12", "Library 8"). */
  count?: ReactNode;
  disabled?: boolean;
}

/**
 * - `tab` (default): 36px underline tabs, page-level navigation (device sections, app sources)
 * - `md`: 32px, inside cards and dialogs (QR code / Token, Deploy). `lg` is deprecated and renders as `tab`.
 */
export type TabsSize = 'tab' | 'lg' | 'md';

/** How the user changed tab: a click (or Enter or Space), or the arrow keys, Home and End. */
export type TabChange = 'click' | 'keyboard';

export interface TabsProps<K extends string> {
  items: ReadonlyArray<TabItem<K>>;
  value: K;
  /** `via` lets a caller treat the keys differently: a URL tab replaces its history entry for them, say. */
  onChange: (key: K, via: TabChange) => void;
  'aria-label': string;
  size?: TabsSize;
  /** Prefix for tab/panel ids; pass the same to <TabPanel idBase>. Defaults to a generated id. */
  idBase?: string;
  className?: string;
}

/** Ids that link a tab to its panel. */
export function tabIds(idBase: string, key: string) {
  return { tab: `${idBase}-tab-${key}`, panel: `${idBase}-panel-${key}` };
}

/**
 * Keeps the list's 2px ink indicator under its selected tab: the indicator is the list's ::after, moved and scaled
 * with transforms only (--tab-x, --tab-w), so it slides between tabs. It appears in place on first paint and
 * follows resizes; it hides while nothing is selected.
 */
function useIndicator(list: RefObject<HTMLElement | null>) {
  // The placement, kept for the observer and run again after every render (the selected tab may have changed).
  const place = useRef<() => void>(() => {});
  useLayoutEffect(() => {
    const el = list.current;
    if (!el) return;
    let frame = 0;
    place.current = () => {
      const on = el.querySelector<HTMLElement>('.v2-tabs__tab.is-selected');
      if (!on) {
        el.removeAttribute('data-indicator');
        return;
      }
      el.style.setProperty('--tab-x', `${on.offsetLeft}px`);
      el.style.setProperty('--tab-w', `${on.offsetWidth}`);
      // The first placement doesn't animate; later ones slide.
      if (!el.hasAttribute('data-indicator')) {
        el.setAttribute('data-indicator', 'placed');
        frame = requestAnimationFrame(() => el.setAttribute('data-indicator', 'ready'));
      }
    };
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => place.current());
    ro?.observe(el);
    return () => {
      ro?.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [list]);
  useLayoutEffect(() => place.current());
}

const sizeClass = (size: TabsSize) => `v2-tabs--${size === 'lg' ? 'tab' : size}`;

/**
 * Underline tabs: navigation between the views of one thing (device sections, app sources). Selected = ink text
 * and a 2px ink indicator that slides; filters and view modes use Segmented instead.
 * `role="tablist"` / `role="tab"` with `aria-selected` and `aria-controls`; arrow keys move and activate.
 */
export function Tabs<K extends string>({ items, value, onChange, idBase, size = 'tab', className, ...aria }: TabsProps<K>) {
  const generated = useId();
  const base = idBase ?? generated;
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const list = useRef<HTMLDivElement>(null);
  useIndicator(list);
  useOverflowFade(list);
  const current = items.findIndex((t) => t.key === value);
  // With none selected (the device page while a tab waits), the first enabled tab keeps the strip in the Tab order.
  const focusable = current >= 0 ? current : items.findIndex((t) => !t.disabled);

  const step = (from: number, dir: 1 | -1) => {
    const n = items.length;
    for (let k = 1; k <= n; k++) {
      const i = (from + dir * k + n) % n;
      if (!items[i].disabled) return i;
    }
    return from;
  };

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    let next = -1;
    if (e.key === 'ArrowRight') next = step(i, 1);
    else if (e.key === 'ArrowLeft') next = step(i, -1);
    else if (e.key === 'Home') next = step(items.length - 1, 1);
    else if (e.key === 'End') next = step(0, -1);
    if (next < 0) return;
    e.preventDefault();
    refs.current[next]?.focus();
    onChange(items[next].key, 'keyboard');
  };

  return (
    <div ref={list} role="tablist" className={cx('v2-tabs', sizeClass(size), className)} {...aria}>
      {items.map((t, i) => {
        const on = i === current;
        const ids = tabIds(base, t.key);
        return (
          <button
            key={t.key}
            ref={(el) => {
              refs.current[i] = el;
            }}
            id={ids.tab}
            type="button"
            role="tab"
            aria-selected={on}
            aria-controls={on ? ids.panel : undefined}
            tabIndex={i === focusable ? 0 : -1}
            disabled={t.disabled}
            className={cx('v2-tabs__tab', on && 'is-selected')}
            onClick={() => onChange(t.key, 'click')}
            onKeyDown={(e) => onKeyDown(e, i)}
          >
            {t.icon ? <span className="v2-tabs__icon">{t.icon}</span> : null}
            {t.label}
            {t.count !== undefined && t.count !== null && t.count !== '' ? (
              <span className="v2-tabs__count">{t.count}</span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

export interface TabPanelProps {
  idBase: string;
  tabKey: string;
  children: ReactNode;
  className?: string;
}

/** The panel for one tab; render only the active one. Its content fades in when the tab changes. */
export function TabPanel({ idBase, tabKey, children, className }: TabPanelProps) {
  const ids = tabIds(idBase, tabKey);
  return (
    <div role="tabpanel" id={ids.panel} aria-labelledby={ids.tab} tabIndex={0} className={cx('v2-tabpanel', className)}>
      {children}
    </div>
  );
}

export interface LinkTabItem {
  key: string;
  to: string;
  label: ReactNode;
  icon?: ReactNode;
  count?: ReactNode;
  /** Current item. Omit to let the router decide by pathname (pass it for `?source=` style links). */
  active?: boolean;
  /** Shown but not a link, with an optional chip ("Later"). */
  disabled?: boolean;
  badge?: ReactNode;
}

/**
 * Tabs that are links (app sources: a `<nav>` with `aria-current="page"`). Same look and sizes as `Tabs`;
 * navigation is ordinary links, so Tab moves between them.
 */
export function LinkTabs({
  items,
  size = 'tab',
  className,
  'aria-label': ariaLabel,
}: {
  items: ReadonlyArray<LinkTabItem>;
  size?: TabsSize;
  'aria-label': string;
  className?: string;
}) {
  const list = useRef<HTMLElement>(null);
  useIndicator(list);
  useOverflowFade(list);
  const inner = (t: LinkTabItem) => (
    <>
      {t.icon ? <span className="v2-tabs__icon">{t.icon}</span> : null}
      {t.label}
      {t.count !== undefined && t.count !== null && t.count !== '' ? <span className="v2-tabs__count">{t.count}</span> : null}
      {t.badge ? (
        <Badge tone="quiet" size="xs">
          {t.badge}
        </Badge>
      ) : null}
    </>
  );
  return (
    <nav ref={list} aria-label={ariaLabel} className={cx('v2-tabs', sizeClass(size), className)}>
      {items.map((t) =>
        t.disabled ? (
          <span key={t.key} aria-disabled="true" className="v2-tabs__tab is-disabled">
            {inner(t)}
          </span>
        ) : t.active !== undefined ? (
          // Caller decides (query-string tabs share a pathname, so NavLink would mark them all).
          <Link
            key={t.key}
            to={t.to}
            aria-current={t.active ? 'page' : undefined}
            className={cx('v2-tabs__tab', t.active && 'is-selected')}
          >
            {inner(t)}
          </Link>
        ) : (
          <NavLink key={t.key} to={t.to} end className={({ isActive }) => cx('v2-tabs__tab', isActive && 'is-selected')}>
            {inner(t)}
          </NavLink>
        ),
      )}
    </nav>
  );
}
