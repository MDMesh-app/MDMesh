import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router';
import { cx } from './cx';
import './PageHeader.css';

export interface PageHeaderProps {
  title: ReactNode;
  /** One secondary line under the title; reserve its line while it loads (pass a stable sentence or `null`). */
  subtitle?: ReactNode;
  /** Right side: buttons, an "Updated 20s ago" status. Wraps under the title when narrow. */
  actions?: ReactNode;
  /** Chips beside the title ("Kiosk"). */
  titleAdornment?: ReactNode;
  /** Breadcrumb above the header. */
  breadcrumb?: ReactNode;
  /** Sets document.title to "<docTitle> · MDMesh". Defaults to the title when it is a string. */
  docTitle?: string;
  /** @deprecated The subtitle is capped at 80 characters for every page; kept so existing callers compile. */
  subtitleMaxCh?: number;
  /** @deprecated One gap (8) between actions now; kept so existing callers compile. */
  actionsGap?: 8 | 12;
  className?: string;
}

/**
 * Page title block: h1 22/600, one secondary subtitle line (its height reserved, so data arriving never moves the
 * page), actions at the right, centred on the title block.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
  titleAdornment,
  breadcrumb,
  docTitle,
  subtitleMaxCh: _subtitleMaxCh,
  actionsGap: _actionsGap,
  className,
}: PageHeaderProps) {
  const doc = docTitle ?? (typeof title === 'string' ? title : undefined);
  useEffect(() => {
    if (!doc) return;
    const prev = document.title;
    document.title = `${doc} · MDMesh`;
    return () => {
      document.title = prev;
    };
  }, [doc]);

  return (
    <>
      {breadcrumb}
      <header className={cx('v2-pagehead', className)}>
        <div className="v2-pagehead__text">
          {titleAdornment ? (
            <div className="v2-pagehead__titleline">
              <h1 className="v2-pagehead__title">{title}</h1>
              {titleAdornment}
            </div>
          ) : (
            <h1 className="v2-pagehead__title">{title}</h1>
          )}
          {subtitle !== undefined ? <p className="v2-pagehead__sub">{subtitle}</p> : null}
        </div>
        {actions ? (
          <div className="v2-pagehead__actions">{actions}</div>
        ) : null}
      </header>
    </>
  );
}

export interface Crumb {
  label: ReactNode;
  /** Router path; the last crumb (current page) has none. */
  to?: string;
  mono?: boolean;
}

/** "Devices / KSK-0151": 13px secondary links (underlined on hover), the current page in text colour. */
export function Breadcrumb({ items, className }: { items: ReadonlyArray<Crumb>; className?: string }) {
  return (
    <nav aria-label="Breadcrumb" className={cx('v2-crumbs', className)}>
      <ol>
        {items.map((c, i) => {
          const last = i === items.length - 1;
          return (
            <li key={i}>
              {c.to && !last ? (
                <Link to={c.to} className={cx(c.mono && 'v2-mono')}>
                  {c.label}
                </Link>
              ) : (
                <span aria-current={last ? 'page' : undefined} className={cx('v2-crumbs__current', c.mono && 'v2-mono')}>
                  {c.label}
                </span>
              )}
              {!last ? (
                <span aria-hidden="true" className="v2-crumbs__sep">
                  /
                </span>
              ) : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
