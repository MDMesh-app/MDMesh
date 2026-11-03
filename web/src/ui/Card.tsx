import type { HTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';
import { StepNumber } from './Steps';
import './Card.css';

/**
 * Board paddings (top / sides / bottom):
 * - `default` 20 24 (most cards, 05 panels)
 * - `rows` 20 24 8: cards whose body is a list of ruled rows, the last rule-less row ending 8px above the edge
 *   (all six Settings sections, 07 Apps and the generated editor sections)
 * - `roomy` 20 24 24 (Enroll steps, Custom APK form)
 * - `compact` 18 24 (Overview Server and Agent versions)
 * - `snug` 16 24 18 (Overview By configuration)
 * - `rail` 20 20 16 (05 device rail) · `facts` 4 20 16 (04 rail under a header card) · `side` 18 20 (09 side cards)
 * - `list` / `none` 0: header and rows bring their own (Needs attention, Library table)
 */
export type CardPadding = 'default' | 'rows' | 'roomy' | 'compact' | 'snug' | 'list' | 'rail' | 'facts' | 'side' | 'none';
/** Border tone: `alert` (config didn't apply), `warn` (attention strip), `dashed` (Headwind-only), `selected` (ink). */
export type CardTone = 'default' | 'alert' | 'warn' | 'dashed' | 'selected';

export interface CardProps extends HTMLAttributes<HTMLElement> {
  as?: 'section' | 'div' | 'article' | 'aside' | 'li' | 'form';
  padding?: CardPadding;
  tone?: CardTone;
  /** r14 instead of r16 (attention strip, bulk bar style notices). */
  radius?: 'lg' | 'md';
  /** Clip children to the radius (tables). Keep off where focus rings sit at the edge. */
  clip?: boolean;
  /** Stack children with this gap (px). */
  gap?: number;
}

/** White card: 1px #E7E9EE, r16, flat (no shadow). */
export function Card({
  as: As = 'section',
  padding = 'default',
  tone = 'default',
  radius = 'lg',
  clip,
  gap,
  className,
  style,
  children,
  ...rest
}: CardProps) {
  return (
    <As
      className={cx(
        'v2-card',
        `v2-card--pad-${padding}`,
        tone !== 'default' && `v2-card--${tone}`,
        radius === 'md' && 'v2-card--r14',
        clip && 'v2-card--clip',
        className,
      )}
      style={gap !== undefined ? { gap, ...style } : style}
      {...rest}
    >
      {children}
    </As>
  );
}

export interface CardHeaderProps {
  title: ReactNode;
  /** Id for the heading, so the card can use aria-labelledby. */
  titleId?: string;
  /** 13px secondary line under the title ("What gets installed, and which apps the kiosk shows."). */
  subtitle?: ReactNode;
  /** Right side: links ("See all"), counts, buttons, a segmented filter. */
  actions?: ReactNode;
  /** Heading level (default h2). */
  level?: 2 | 3;
  /** `list` header padding 16 24 10 for cards whose body is a list or table. */
  inset?: boolean;
  /** Title size: 16 (default), 17 (numbered steps), 18 ("Add your own app"). */
  size?: 16 | 17 | 18;
  /** Step number in a 26px ink square before the title (numbered cards; pair with size 17). */
  number?: number;
  /** Space under the header (px): 12 (07 editor sections), 10 (12 Settings), 6 (01 By configuration), 0. */
  spacing?: 0 | 6 | 8 | 10 | 12;
  className?: string;
}

/** Card heading row: 16/600 title, optional subtitle, actions on the right. */
export function CardHeader({
  title,
  titleId,
  subtitle,
  actions,
  level = 2,
  inset,
  size = 16,
  spacing = 0,
  number,
  className,
}: CardHeaderProps) {
  const H = level === 3 ? 'h3' : 'h2';
  return (
    <div
      className={cx(
        'v2-cardhead',
        inset && 'v2-cardhead--inset',
        spacing > 0 && `v2-cardhead--sp${spacing}`,
        number !== undefined && 'v2-cardhead--numbered',
        className,
      )}
    >
      {number !== undefined ? <StepNumber n={number} /> : null}
      <div className="v2-cardhead__text">
        <H id={titleId} className={cx('v2-cardhead__title', size !== 16 && `v2-cardhead__title--${size}`)}>
          {title}
        </H>
        {subtitle ? <span className="v2-cardhead__sub">{subtitle}</span> : null}
      </div>
      {actions ? <div className="v2-cardhead__actions">{actions}</div> : null}
    </div>
  );
}

/** Attention / notice strip: tinted icon tile, text, action ("14 need attention", missing checksum). */
export function Notice({
  tone = 'warn',
  icon,
  children,
  action,
  className,
  'aria-label': ariaLabel,
}: {
  tone?: 'warn' | 'alert' | 'ok' | 'idle';
  icon: ReactNode;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
  'aria-label'?: string;
}) {
  return (
    <section aria-label={ariaLabel} className={cx('v2-notice', `v2-notice--${tone}`, className)}>
      <span className="v2-notice__icon" aria-hidden="true">
        {icon}
      </span>
      <div className="v2-notice__body">{children}</div>
      {action ? <div className="v2-notice__action">{action}</div> : null}
    </section>
  );
}

/** Inline callout (warn / info / alert / ok): r10, 10×12, 13px, leading icon. */
export function Callout({
  tone = 'info',
  icon,
  children,
  role,
  className,
}: {
  tone?: 'info' | 'warn' | 'alert' | 'ok';
  icon?: ReactNode;
  children: ReactNode;
  /** `status` for live notices such as "Your session expired". */
  role?: 'status' | 'alert';
  className?: string;
}) {
  return (
    <div role={role} className={cx('v2-callout', `v2-callout--${tone}`, className)}>
      {icon ? (
        <span className="v2-callout__icon" aria-hidden="true">
          {icon}
        </span>
      ) : null}
      <div className="v2-callout__body">{children}</div>
    </div>
  );
}
