import type { CSSProperties } from 'react';
import { cx } from './cx';
import './Skeleton.css';

/**
 * Placeholder for content on its way, at the size the content will take, so nothing moves when it lands. It stays
 * invisible for the first 150ms (most loads finish sooner), then fades in with a slow sheen; reduced motion keeps
 * it still. Hidden from assistive tech: pair it with a `role="status"` label where the wait matters.
 */
export function Skeleton({
  width,
  height = 'line',
  radius = 'sm',
  className,
}: {
  /** CSS width (default 100%). */
  width?: string;
  /** `line` one UI text line, `title` a card-title line, `control` the control height, `row` the table row, or a CSS length. */
  height?: 'line' | 'title' | 'control' | 'row' | string;
  radius?: 'sm' | 'full';
  className?: string;
}) {
  const named = height === 'line' || height === 'title' || height === 'control' || height === 'row';
  const style: CSSProperties = { width, ...(named ? {} : { height }) };
  return (
    <span
      aria-hidden="true"
      className={cx('v2-skel', named && `v2-skel--${height}`, radius === 'full' && 'v2-skel--full', className)}
      style={style}
    />
  );
}

/** `count` table-row placeholders (--row, or --row-compact) with a name-like and a meta-like bar, inside a list card. */
export function SkeletonRows({ count = 5, compact, label }: { count?: number; compact?: boolean; label?: string }) {
  return (
    <div className={cx('v2-skelrows', compact && 'v2-skelrows--compact')} role={label ? 'status' : undefined}>
      {label ? <span className="v2-visually-hidden">{label}</span> : null}
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="v2-skelrows__row" aria-hidden="true">
          <Skeleton width={`${28 + ((i * 17) % 20)}%`} />
          <Skeleton width="16%" />
          <Skeleton width="10%" />
        </div>
      ))}
    </div>
  );
}

/** Label / value placeholders matching a KeyValueList of `count` rows. */
export function SkeletonFacts({ count = 4, label }: { count?: number; label?: string }) {
  return (
    <div className="v2-skelfacts" role={label ? 'status' : undefined}>
      {label ? <span className="v2-visually-hidden">{label}</span> : null}
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="v2-skelfacts__row" aria-hidden="true">
          <Skeleton width={`${30 + ((i * 13) % 18)}%`} />
          <Skeleton width={`${18 + ((i * 11) % 16)}%`} />
        </div>
      ))}
    </div>
  );
}
