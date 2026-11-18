import type { Key, ReactNode } from 'react';
import { Link } from 'react-router';
import { StackedBar } from './Bars';
import { Skeleton } from './Skeleton';
import { StatusDot } from './Status';
import { cx } from './cx';
import './Stats.css';

export interface StatNote {
  text: ReactNode;
  /** Ink colour: alert / warn / ok, or `muted` (secondary). */
  tone?: 'alert' | 'warn' | 'ok' | 'muted';
}

export interface StatCellProps {
  label: ReactNode;
  value: ReactNode;
  /** Denominator, shown secondary after the value ("/248"). */
  of?: ReactNode;
  /** Up to two footnotes ("1 failed", "12 applying"). */
  notes?: ReadonlyArray<StatNote>;
}

/** Overview stat cell: meta-size label, page-title-size tabular value, toned meta footnotes. Use inside <StatGrid>. */
export function StatCell({ label, value, of, notes = [] }: StatCellProps) {
  return (
    <div className="v2-stat">
      <dt className="v2-stat__label">{label}</dt>
      <dd className="v2-stat__value">
        {value}
        {of !== undefined && of !== null && of !== '' ? <span className="v2-stat__of">{of}</span> : null}
      </dd>
      {notes.map((n, i) => (
        <dd key={i} className={cx('v2-stat__note', `is-${n.tone ?? 'muted'}`)}>
          {n.text}
        </dd>
      ))}
    </div>
  );
}

/** The <dl> grid of stat cells: two columns with a rule between them, more rows as needed. */
export function StatGrid({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cx('v2-statgrid', className)}>{children}</dl>;
}

export interface FleetLegendItem {
  key: Key;
  label: ReactNode;
  count: number;
  tone: 'ok' | 'warn' | 'idle';
  /** Filtered Devices link. */
  to?: string;
}

export interface FleetSummaryProps {
  /** null while unknown (loading, or the load failed): no number is shown, never a 0. */
  total: number | null;
  /** "devices enrolled". */
  caption?: ReactNode;
  legend: ReadonlyArray<FleetLegendItem>;
  /** @deprecated The stat tiles are a card of their own beside this one now; still rendered under the legend. */
  stats?: ReactNode;
  /** Still loading: skeletons where the numbers go. */
  loading?: boolean;
  /** Id for the "Fleet" heading (the section uses aria-labelledby). */
  headingId?: string;
  className?: string;
}

/**
 * Overview fleet card: "Fleet" title, the total (28px tabular) and caption on one baseline, the fleet bar, a legend
 * with shape marks, counts and percentages. Loading shows skeletons at the same size; an unknown total shows "—".
 */
export function FleetSummary({
  total,
  caption = 'devices enrolled',
  legend,
  stats,
  loading,
  headingId = 'v2-fleet',
  className,
}: FleetSummaryProps) {
  const known = total !== null && !loading;
  const pct = (n: number) => (known && total > 0 ? `${Math.round((n / total) * 100)}%` : '');
  const label = legend.map((l) => `${l.count} ${typeof l.label === 'string' ? l.label.toLowerCase() : ''}`.trim()).join(', ');
  return (
    <section aria-labelledby={headingId} aria-busy={loading || undefined} className={cx('v2-fleet', className)}>
      <h2 id={headingId} className="v2-fleet__title">
        Fleet
      </h2>
      <div className="v2-fleet__head">
        {loading ? (
          <Skeleton width="120px" height="var(--lh-display)" />
        ) : (
          <>
            <span className="v2-fleet__total">{known ? total : '—'}</span>
            <span className="v2-fleet__caption">{caption}</span>
          </>
        )}
      </div>
      {known ? (
        <StackedBar variant="fleet" label={label} segments={legend.map((l) => ({ value: l.count, tone: l.tone }))} />
      ) : (
        <span className="v2-fleet__track" aria-hidden="true" />
      )}
      <div className="v2-fleet__legend">
        {legend.map((l) => {
          const body = (
            <>
              <StatusDot tone={l.tone} size={8} />
              <span className="v2-fleet__label">{l.label}</span>
              <span className="v2-fleet__count">{known ? l.count : '—'}</span>
              {known ? <span className="v2-fleet__pct">{pct(l.count)}</span> : null}
            </>
          );
          return l.to && known ? (
            <Link key={l.key} to={l.to} className="v2-fleet__item">
              {body}
            </Link>
          ) : (
            <span key={l.key} className="v2-fleet__item">
              {body}
            </span>
          );
        })}
      </div>
      {stats}
    </section>
  );
}
