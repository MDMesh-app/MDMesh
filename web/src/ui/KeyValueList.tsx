import type { Key, ReactNode } from 'react';
import { cx } from './cx';
import './KeyValueList.css';

export interface KeyValueItem {
  key?: Key;
  label: ReactNode;
  value: ReactNode;
  /** A value people copy (IPs, serials, hashes): IBM Plex Mono at the meta size. */
  mono?: boolean;
}

/**
 * - `rail`: dense rows, value right-aligned (device facts, telemetry)
 * - `rows`: --row-compact rows with a top rule, value at the right (Settings Account / Server)
 * - `facts`: label/value grid in a sunken box (Enroll summary)
 */
export type KeyValueVariant = 'rail' | 'rows' | 'facts';

export function KeyValueList({
  items,
  variant = 'rail',
  columns = 1,
  className,
}: {
  items: ReadonlyArray<KeyValueItem>;
  variant?: KeyValueVariant;
  /** `rail` and `rows`: flow the rows into two columns where the container is wide enough (≥ 560px). */
  columns?: 1 | 2;
  className?: string;
}) {
  if (variant === 'facts') {
    return (
      <dl className={cx('v2-kv', 'v2-kv--facts', className)}>
        {items.map((it, i) => (
          <div key={it.key ?? i} className="v2-kv__pair">
            <dt>{it.label}</dt>
            <dd className={cx(it.mono && 'is-mono')}>{it.value}</dd>
          </div>
        ))}
      </dl>
    );
  }
  return (
    <dl className={cx('v2-kv', `v2-kv--${variant}`, columns === 2 && 'v2-kv--2col', className)}>
      {items.map((it, i) => (
        <div key={it.key ?? i} className="v2-kv__row">
          <dt>{it.label}</dt>
          <dd className={cx(it.mono && 'is-mono')}>{it.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** A titled group of rail rows: a quiet 11px caps label, then the list (Status / Location / Hardware…). */
export function KeyValueGroup({
  title,
  items,
  last,
  children,
}: {
  title: ReactNode;
  items: ReadonlyArray<KeyValueItem>;
  /** Drop the bottom rule on the final group. */
  last?: boolean;
  /** Extra content under the list (e.g. "Open location in Maps ↗"). */
  children?: ReactNode;
}) {
  return (
    <section className={cx('v2-kvgroup', last && 'is-last')}>
      <h2 className="v2-eyebrow">{title}</h2>
      <KeyValueList items={items} variant="rail" />
      {children}
    </section>
  );
}

export interface MetaCell {
  key?: Key;
  label: ReactNode;
  value: ReactNode;
  mono?: boolean;
}

/**
 * Card footer cells separated by 1px rules on the sunken fill (device card "Android / Battery /
 * Last seen", the configuration card "Main app / Apps / Devices").
 * `boxed` = rounded box with its own border inside the card body; otherwise a full-width strip with a
 * top rule.
 */
export function MetaGrid({
  cells,
  columns,
  boxed,
  className,
}: {
  cells: ReadonlyArray<MetaCell>;
  /** grid-template-columns; default equal columns. */
  columns?: string;
  boxed?: boolean;
  className?: string;
}) {
  return (
    <dl
      className={cx('v2-metagrid', boxed ? 'v2-metagrid--boxed' : 'v2-metagrid--strip', className)}
      style={{ gridTemplateColumns: columns ?? `repeat(${cells.length}, minmax(0, 1fr))` }}
    >
      {cells.map((c, i) => (
        <div key={c.key ?? i} className="v2-metagrid__cell">
          <dt>{c.label}</dt>
          <dd className={cx(c.mono && 'is-mono')}>{c.value}</dd>
        </div>
      ))}
    </dl>
  );
}
