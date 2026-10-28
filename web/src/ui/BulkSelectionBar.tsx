import type { ReactNode } from 'react';
import { Button } from './Button';
import { cx } from './cx';
import './BulkSelectionBar.css';

export interface BulkSelectionBarProps {
  /** Selected count; render the bar only when it is > 0. */
  count: number;
  /** Everything matching the filters; with `onSelectAll`, offers "Select all N" until all are selected. */
  total?: number;
  onSelectAll?: () => void;
  onClear: () => void;
  /** Action buttons (Run action, Change configuration, Delete): `Button` md, danger for Delete. */
  children: ReactNode;
  /**
   * `attached`: the first row inside the table card, rule underneath.
   * `standalone`: its own r14 strip above the card grid.
   */
  variant?: 'attached' | 'standalone';
  /** Noun after the count ("2 selected" by default; "2 devices selected"). */
  noun?: string;
  className?: string;
}

/** Selection bar: surface-2 strip, 10×16, "N selected" 600, actions, ghost Clear. */
export function BulkSelectionBar({
  count,
  total,
  onSelectAll,
  onClear,
  children,
  variant = 'attached',
  noun,
  className,
}: BulkSelectionBarProps) {
  return (
    <section aria-label="Selection" className={cx('v2-bulkbar', `v2-bulkbar--${variant}`, className)}>
      <span className="v2-bulkbar__count" aria-live="polite">
        {count} {noun ? `${noun} ` : ''}selected
        {onSelectAll && total !== undefined && count < total ? (
          <Button variant="ghost" size="sm" className="v2-bulkbar__all" onClick={onSelectAll}>
            Select all {total}
          </Button>
        ) : null}
      </span>
      {children}
      <Button variant="ghost" onClick={onClear}>
        Clear
      </Button>
    </section>
  );
}
