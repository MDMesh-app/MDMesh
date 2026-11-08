import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router';
import { cx } from './cx';
import './PageHeader.css';

export interface PageHeaderProps {
  title: ReactNode;
  /** 15px text-2 line under the title. */
  subtitle?: ReactNode;
  /** Right side: buttons, an "Updated 20s ago" status. Wraps under the title when narrow. */
  actions?: ReactNode;
  /** Chips beside the title ("Kiosk"). */
  titleAdornment?: ReactNode;
  /** Breadcrumb above the header. */
  breadcrumb?: ReactNode;
  /** Sets document.title to "<docTitle> · MDMesh". Defaults to the title when it is a string. */
  docTitle?: string;
  /** Cap the subtitle measure (Configurations: 72ch). */
  subtitleMaxCh?: number;
  /** Gap between actions: 12 (status + button, default) or 8 (a button group). */
  actionsGap?: 8 | 12;
  className?: string;
}

/** Page title block (every board): h1 28/600/-0.025em/1.15, subtitle 15px text-2, actions bottom-aligned right. */
export function PageHeader({
  title,
  subtitle,
  actions,
  titleAdornment,
  breadcrumb,
  docTitle,
  subtitleMaxCh,
  actionsGap = 12,
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
          {subtitle ? (
            <p className="v2-pagehead__sub" style={subtitleMaxCh ? { maxWidth: `${subtitleMaxCh}ch` } : undefined}>
              {subtitle}
            </p>
          ) : null}
        </div>
        {actions ? (
          <div className={cx('v2-pagehead__actions', actionsGap === 8 && 'v2-pagehead__actions--tight')}>{actions}</div>
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

/** "Devices / KSK-0151": 13px secondary, underlined links, current page in text colour. */
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
