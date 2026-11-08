import type { Key, ReactNode } from 'react';
import { Link } from 'react-router';
import { StackedBar } from './Bars';
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
  /** Denominator, shown 14px secondary after the value ("/248"). */
  of?: ReactNode;
  /** Up to two footnotes ("1 failed", "12 applying"). */
  notes?: ReadonlyArray<StatNote>;
}

/** Overview stat cell: 12.5 label, Mono 24/500 value, toned 12.5 footnotes, 16px left rule. Use inside <StatGrid>. */
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

/** The <dl> grid of stat cells: auto-fit columns of at least 130px, 16px gaps. */
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
  total: number;
  /** "devices enrolled". */
  caption?: ReactNode;
  legend: ReadonlyArray<FleetLegendItem>;
  /** Right column: a <StatGrid> of <StatCell>s. */
  stats?: ReactNode;
  /** Id for the "Fleet" heading (the section uses aria-labelledby). */
  headingId?: string;
  className?: string;
}

/**
 * Overview fleet card: "FLEET 248 devices enrolled", the 12px fleet bar, a legend with shape marks,
 * counts and percentages, and the stat cells on the right. Renders the card itself (20×24, r16).
 */
export function FleetSummary({ total, caption = 'devices enrolled', legend, stats, headingId = 'v2-fleet', className }: FleetSummaryProps) {
  const pct = (n: number) => (total > 0 ? `${Math.round((n / total) * 100)}%` : '0%');
  const label = legend.map((l) => `${l.count} ${typeof l.label === 'string' ? l.label.toLowerCase() : ''}`.trim()).join(', ');
  return (
    <section aria-labelledby={headingId} className={cx('v2-fleet', className)}>
      <div className="v2-fleet__main">
        <div className="v2-fleet__head">
          <h2 id={headingId} className="v2-eyebrow">
            Fleet
          </h2>
          <span className="v2-fleet__total">{total}</span>
          <span className="v2-fleet__caption">{caption}</span>
        </div>
        <StackedBar variant="fleet" label={label} segments={legend.map((l) => ({ value: l.count, tone: l.tone }))} />
        <div className="v2-fleet__legend">
          {legend.map((l) => {
            const body = (
              <>
                <StatusDot tone={l.tone} size={8} />
                <span className="v2-fleet__label">{l.label}</span>
                <span className="v2-fleet__count">{l.count}</span>
                <span className="v2-fleet__pct">{pct(l.count)}</span>
              </>
            );
            return l.to ? (
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
      </div>
      {stats}
    </section>
  );
}
