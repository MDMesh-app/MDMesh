import { useId, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Button } from './Button';
import { cx } from './cx';
import './Pagination.css';

export interface PaginationProps {
  /** 1-based page. */
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  /** With both, a "Rows per page" select is shown (10 / 50 / 100). */
  pageSizeOptions?: ReadonlyArray<number>;
  onPageSizeChange?: (size: number) => void;
  /** Text after the count ("· needs attention first"). */
  suffix?: ReactNode;
  /**
   * `inset`: the footer row of a table card, 12×16 padding with a top rule.
   * `plain`: under a card grid, no padding or rule.
   */
  variant?: 'inset' | 'plain';
  /** Noun for the screen-reader label ("devices"). */
  noun?: string;
  className?: string;
}

/** "Showing 1–10 of 248", optional rows-per-page, Previous / Next. */
export function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
  pageSizeOptions,
  onPageSizeChange,
  suffix,
  variant = 'inset',
  noun = 'items',
  className,
}: PaginationProps) {
  const sizeId = useId();
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(total, page * pageSize);
  return (
    <nav aria-label={`Pages of ${noun}`} className={cx('v2-pager', `v2-pager--${variant}`, className)}>
      <span className="v2-pager__count" aria-live="polite">
        Showing{' '}
        <span className="v2-mono">
          {first}–{last}
        </span>{' '}
        of <span className="v2-mono">{total}</span>
        {suffix ? <> {suffix}</> : null}
      </span>
      {pageSizeOptions && onPageSizeChange ? (
        <label className="v2-pager__size" htmlFor={sizeId}>
          Rows per page
          <select
            id={sizeId}
            className="v2-pager__select"
            value={pageSize}
            onChange={(e) => onPageSizeChange(Number(e.target.value))}
          >
            {pageSizeOptions.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      <div className="v2-pager__buttons">
        <Button disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
          Previous
        </Button>
        <Button disabled={page >= pages} onClick={() => onPageChange(page + 1)}>
          Next
        </Button>
      </div>
    </nav>
  );
}

/** Centred "Show more" (F-Droid): secondary 9×18. */
export function ShowMore({
  onClick,
  busy,
  label = 'Show more',
  busyLabel = 'Loading…',
}: {
  onClick: () => void;
  busy?: boolean;
  label?: string;
  busyLabel?: string;
}) {
  return (
    <div className="v2-showmore">
      <Button size="xl" className="v2-showmore__btn" onClick={onClick} disabled={busy}>
        {busy ? busyLabel : label}
      </Button>
    </div>
  );
}

/** Card-header meta "Showing 6 of 14 · See all" (Needs attention): 13px secondary + a 600 link. */
export function CountMeta({ shown, total, to, linkLabel = 'See all' }: { shown: number; total: number; to?: string; linkLabel?: string }) {
  return (
    <>
      <span className="v2-countmeta">
        Showing {shown} of {total}
      </span>
      {to ? (
        <Link to={to} className="v2-countmeta__link">
          {linkLabel}
        </Link>
      ) : null}
    </>
  );
}
