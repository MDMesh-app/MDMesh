import { useRef, type ReactNode } from 'react';
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
  /** @deprecated The bar floats at the bottom of the window for every list now; kept so callers compile. */
  variant?: 'attached' | 'standalone';
  /**
   * Keep it mounted and pass whether it shows: it then slides in and out, and nothing on the page moves. Without it,
   * render the bar only while something is selected (it still never pushes the page: it floats).
   */
  open?: boolean;
  /** Noun after the count ("2 selected" by default; "2 devices selected"). */
  noun?: string;
  className?: string;
}

/**
 * Selection bar: floats over the bottom of the page (it never pushes the list, so the row just clicked stays under
 * the pointer): "N selected", "Select all", the actions, Clear.
 */
export function BulkSelectionBar({
  count,
  total,
  onSelectAll,
  onClear,
  children,
  variant: _variant,
  open = true,
  noun,
  className,
}: BulkSelectionBarProps) {
  // While it slides out, it keeps saying what it said (the count has already gone to 0).
  const last = useRef(count);
  if (count > 0) last.current = count;
  const shown = open ? count : last.current;
  return (
    <section
      aria-label="Selection"
      className={cx('v2-bulkbar', className)}
      data-state={open ? 'open' : 'closed'}
      inert={!open || undefined}
      aria-hidden={!open || undefined}
    >
      <span className="v2-bulkbar__count" aria-live="polite">
        {shown} {noun ? `${noun} ` : ''}selected
        {onSelectAll && total !== undefined && shown < total ? (
          <Button variant="ghost" size="sm" className="v2-bulkbar__all" onClick={onSelectAll}>
            Select all {total}
          </Button>
        ) : null}
      </span>
      {children}
      <Button variant="ghost" size="sm" onClick={onClear}>
        Clear
      </Button>
    </section>
  );
}
