import { forwardRef, type AnchorHTMLAttributes, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';
import { cx } from './cx';
import './Button.css';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'subtle' | 'ghost';

/**
 * Sizes are the board button heights (14px text at line-height 1.5 unless noted). Pick by where the button sits:
 * - `xs`  6×12, 13px: in-row actions in dense tables (Library "Deploy", F-Droid card footer)
 * - `sm`  7×12, 13px: row actions in lists (Needs attention "Retry apply"), card footers, "Review update"
 * - `md`  7×12: toolbars and card headers (bulk bar, pagination, Settings "Check now", attention-strip action). Default.
 * - `lg`  8×14: section actions (editor Duplicate / Delete…, "Add apps", "Retry apply", remote-action tiles)
 * - `xl`  9×16: sticky-bar and settings primaries ("Save and apply", "Update server…", "Promote…"), device quick actions
 * - `hero` 10×16: page-header calls to action ("Enroll device", "New configuration", "Add to library")
 * - `auth` 11×16, 15px, full width: the submit button on the sign-in and set-password cards
 */
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'hero' | 'auth';

interface CommonProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Leading icon (an icon component element); its colour follows the variant. */
  icon?: ReactNode;
  /** Trailing icon, e.g. a chevron. */
  iconEnd?: ReactNode;
  /** 500 weight on a secondary button (boards: "Roll out agent…", "Review update", card "Edit"). */
  strong?: boolean;
  /** Stretch to the container width. */
  block?: boolean;
}

function classes(
  { variant = 'secondary', size = 'md', strong, block }: CommonProps,
  className?: string,
) {
  return cx(
    'v2-btn',
    `v2-btn--${variant}`,
    `v2-btn--${size}`,
    strong && 'v2-btn--strong',
    block && 'v2-btn--block',
    className,
  );
}

function Inner({ icon, iconEnd, children }: { icon?: ReactNode; iconEnd?: ReactNode; children?: ReactNode }) {
  return (
    <>
      {icon ? <span className="v2-btn__icon">{icon}</span> : null}
      {children}
      {iconEnd ? <span className="v2-btn__icon v2-btn__icon--end">{iconEnd}</span> : null}
    </>
  );
}

export type ButtonProps = CommonProps & ButtonHTMLAttributes<HTMLButtonElement>;

/** Text button. Defaults to `type="button"` so it never submits a form by accident. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant, size, icon, iconEnd, strong, block, className, children, type = 'button', ...rest },
  ref,
) {
  return (
    <button ref={ref} type={type} className={classes({ variant, size, strong, block }, className)} {...rest}>
      <Inner icon={icon} iconEnd={iconEnd}>{children}</Inner>
    </button>
  );
});

export type ButtonLinkProps = CommonProps & LinkProps;

/** A router link that looks like a button (e.g. "Enroll device" in a page header). */
export function ButtonLink({ variant, size, icon, iconEnd, strong, block, className, children, ...rest }: ButtonLinkProps) {
  return (
    <Link className={classes({ variant, size, strong, block }, className)} {...rest}>
      <Inner icon={icon} iconEnd={iconEnd}>{children}</Inner>
    </Link>
  );
}

export type ButtonAnchorProps = CommonProps & AnchorHTMLAttributes<HTMLAnchorElement>;

/** An external link styled as a button ("APKMirror ↗"). */
export function ButtonAnchor({ variant, size, icon, iconEnd, strong, block, className, children, ...rest }: ButtonAnchorProps) {
  return (
    <a className={classes({ variant, size, strong, block }, className)} {...rest}>
      <Inner icon={icon} iconEnd={iconEnd}>{children}</Inner>
    </a>
  );
}

export type IconButtonSize = 'sm' | 'md' | 'lg';

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> {
  /** Accessible name; required because the button has no visible text. */
  label: string;
  /** `sm` 30×30 r8 (cards, remove, rename) · `md` 32×32 r9 (table rows) · `lg` 34×34 r9 (sidebar). */
  size?: IconButtonSize;
  /** `strong` uses the control border (#D6DAE1) instead of the card border, for icon buttons beside inputs. */
  tone?: 'default' | 'strong';
  children: ReactNode;
}

/** Square icon-only button. Pass the icon as children. */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, size = 'md', tone = 'default', className, type = 'button', children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      className={cx('v2-iconbtn', `v2-iconbtn--${size}`, tone === 'strong' && 'v2-iconbtn--strong', className)}
      {...rest}
    >
      {children}
    </button>
  );
});

export interface LinkButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /**
   * `strong` 13px 600 ink (Settings "Retry", device page "View all"); `quiet` inherited size, 500, secondary ink
   * (device page "Show details"); `inline` takes the surrounding text's font and colour (a sentence's "try again").
   */
  tone?: 'strong' | 'quiet' | 'inline';
}

/** An in-page action that reads as an underlined link (a button: it does something, it does not go anywhere). */
export const LinkButton = forwardRef<HTMLButtonElement, LinkButtonProps>(function LinkButton(
  { tone = 'strong', className, type = 'button', ...rest },
  ref,
) {
  return <button ref={ref} type={type} className={cx('v2-linkbtn', `v2-linkbtn--${tone}`, className)} {...rest} />;
});
