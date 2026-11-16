import { useId, type HTMLAttributes, type ReactNode } from 'react';
import { cx } from './cx';
import { StepNumber } from './Steps';
import './Card.css';

/**
 * Two paddings: `default` 16 on every side, and `list` / `none` 0 for cards whose header and rows bring their own
 * (tables, lists). `rows` is 16 with 4 at the bottom, for a body of ruled rows that pad themselves.
 * Deprecated (kept for extensions): `roomy`, `compact`, `snug`, `rail`, `facts`, `side` render as `default`.
 */
export type CardPadding = 'default' | 'rows' | 'roomy' | 'compact' | 'snug' | 'list' | 'rail' | 'facts' | 'side' | 'none';
/** Border tone: `alert` (config didn't apply), `warn` (attention strip), `dashed` (Headwind-only), `selected` (ink). */
export type CardTone = 'default' | 'alert' | 'warn' | 'dashed' | 'selected';

export interface CardProps extends HTMLAttributes<HTMLElement> {
  as?: 'section' | 'div' | 'article' | 'aside' | 'li' | 'form';
  padding?: CardPadding;
  tone?: CardTone;
  /** @deprecated One card radius (8) now; kept so existing callers compile. */
  radius?: 'lg' | 'md';
  /** Clip children to the radius (tables). Keep off where focus rings sit at the edge. */
  clip?: boolean;
  /** Stack children with a gap from the spacing scale (px, rounded to 8, 12, 16 or 24). */
  gap?: number;
}

const PAD: Record<CardPadding, 'default' | 'rows' | 'none'> = {
  default: 'default',
  rows: 'rows',
  roomy: 'default',
  compact: 'default',
  snug: 'default',
  rail: 'default',
  facts: 'default',
  side: 'default',
  list: 'none',
  none: 'none',
};

/** The spacing step a `gap` in px stands for. */
function gapClass(gap: number) {
  const step = gap <= 8 ? 2 : gap <= 12 ? 3 : gap <= 16 ? 4 : 6;
  return `v2-card--gap-${step}`;
}

/** White card: 1px border, r8, flat (no shadow). */
export function Card({
  as: As = 'section',
  padding = 'default',
  tone = 'default',
  radius: _radius,
  clip,
  gap,
  className,
  children,
  ...rest
}: CardProps) {
  return (
    <As
      className={cx(
        'v2-card',
        `v2-card--pad-${PAD[padding]}`,
        tone !== 'default' && `v2-card--${tone}`,
        clip && 'v2-card--clip',
        gap !== undefined && gapClass(gap),
        className,
      )}
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
  /** Secondary line under the title ("What gets installed, and which apps the kiosk shows."). */
  subtitle?: ReactNode;
  /** Right side: links ("See all"), counts, buttons, a segmented filter. */
  actions?: ReactNode;
  /** Heading level (default h2). */
  level?: 2 | 3;
  /** Header padding (16, 8 below) for cards whose body is a list or table (`padding="list"`). */
  inset?: boolean;
  /** @deprecated One card-title size (16) now; kept so existing callers compile. */
  size?: 16 | 17 | 18;
  /** Step number before the title (numbered cards). */
  number?: number;
  /** Space under the header: 0, or 8 (6, 8) or 12 (10, 12). */
  spacing?: 0 | 6 | 8 | 10 | 12;
  className?: string;
}

/** Card heading row: 16/600 title, optional subtitle, actions on the right (quiet links, small buttons). */
export function CardHeader({
  title,
  titleId,
  subtitle,
  actions,
  level = 2,
  inset,
  size: _size,
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
        spacing > 0 && (spacing <= 8 ? 'v2-cardhead--sp8' : 'v2-cardhead--sp12'),
        number !== undefined && 'v2-cardhead--numbered',
        className,
      )}
    >
      {number !== undefined ? <StepNumber n={number} /> : null}
      <div className="v2-cardhead__text">
        <H id={titleId} className="v2-cardhead__title">
          {title}
        </H>
        {subtitle ? <span className="v2-cardhead__sub">{subtitle}</span> : null}
      </div>
      {actions ? <div className="v2-cardhead__actions">{actions}</div> : null}
    </div>
  );
}

/**
 * Attention / notice strip: tinted icon, text, action ("14 need attention", missing checksum). With a `title`, a
 * notice block instead: the title and its text as one block (the icon on the title's line), and the notice is
 * labelled by its title.
 */
export function Notice({
  tone = 'warn',
  icon,
  title,
  children,
  action,
  className,
  'aria-label': ariaLabel,
}: {
  tone?: 'warn' | 'alert' | 'ok' | 'idle';
  icon: ReactNode;
  /** A short title over the text ("Missing security fixes"). It names the notice unless `aria-label` is given. */
  title?: ReactNode;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
  'aria-label'?: string;
}) {
  const titleId = useId();
  return (
    <section
      aria-label={ariaLabel}
      aria-labelledby={title && !ariaLabel ? titleId : undefined}
      className={cx('v2-notice', `v2-notice--${tone}`, title ? 'v2-notice--titled' : null, className)}
    >
      <div className="v2-notice__grid">
        <span className="v2-notice__icon" aria-hidden="true">
          {icon}
        </span>
        {title ? (
          <div className="v2-notice__body">
            <span id={titleId} className="v2-notice__title">
              {title}
            </span>
            <div className="v2-notice__text">{children}</div>
          </div>
        ) : (
          <div className="v2-notice__body">{children}</div>
        )}
        {action ? <div className="v2-notice__action">{action}</div> : null}
      </div>
    </section>
  );
}

/** Inline callout (warn / info / alert / ok): control radius, UI text, leading icon. Red (`alert`) only for failures. */
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
