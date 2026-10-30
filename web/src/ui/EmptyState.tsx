import type { ReactNode } from 'react';
import { cx } from './cx';
import './EmptyState.css';

/**
 * Empty / zero-data state. The boards draw none, so it is assembled from board parts: a 40px surface-2 icon tile
 * (r10, like the app letter tiles), a 15/600 title, 13.5px text-2 body, and an optional action row.
 * Use inside a Card, or with `bare` inside a table cell / list.
 */
export function EmptyState({
  icon,
  title,
  children,
  action,
  bare,
  className,
}: {
  icon?: ReactNode;
  title: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  bare?: boolean;
  className?: string;
}) {
  return (
    <div className={cx('v2-empty', bare && 'v2-empty--bare', className)}>
      {icon ? (
        <span className="v2-empty__icon" aria-hidden="true">
          {icon}
        </span>
      ) : null}
      <p className="v2-empty__title">{title}</p>
      {children ? <p className="v2-empty__body">{children}</p> : null}
      {action ? <div className="v2-empty__action">{action}</div> : null}
    </div>
  );
}
