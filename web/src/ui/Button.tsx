import { forwardRef, type AnchorHTMLAttributes, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';
import { cx } from './cx';
import './Button.css';

/**
 * - `primary`: the one main action of a page, card or dialog (ink fill)
 * - `secondary`: every other action (bordered). `subtle` is the same on the lighter card border.
 * - `ghost`: quiet text action (toolbars, card headers)
 * - `danger`: a destructive action that opens a confirmation ("Delete"): red text, bordered
 * - `danger-primary`: the confirmation of an irreversible action ("Factory reset", "Delete configuration"): red fill
 */
export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'danger-primary' | 'subtle' | 'ghost';

/**
 * Three heights (--control-sm / -md / -lg, 32 / 36 / 40), UI-size text: `sm` row actions, card footers, chips; `md`
 * the default (toolbars, card headers, forms); `lg` the page's primary action, dialog footers, sign-in. In the phone
 * layout every control reaches the 44px touch target.
 *
 * Deprecated (the v0.4 board sizes, kept for extensions): `xs` = `sm`; `xl`, `hero` = `lg`; `auth` = `lg` stretched
 * to the container's width.
 */
export type ButtonSize = 'sm' | 'md' | 'lg' | 'xs' | 'xl' | 'hero' | 'auth';

const SIZE: Record<ButtonSize, 'sm' | 'md' | 'lg'> = { xs: 'sm', sm: 'sm', md: 'md', lg: 'lg', xl: 'lg', hero: 'lg', auth: 'lg' };

interface CommonProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Leading icon (an icon component element); its colour follows the variant. */
  icon?: ReactNode;
  /** Trailing icon, e.g. a chevron. */
  iconEnd?: ReactNode;
  /** @deprecated Every button label is 500 now; kept so existing callers compile. */
  strong?: boolean;
  /** Stretch to the container width. */
  block?: boolean;
}

function classes(
  { variant = 'secondary', size = 'md', block }: CommonProps,
  className?: string,
) {
  return cx('v2-btn', `v2-btn--${variant}`, `v2-btn--${SIZE[size]}`, (block || size === 'auth') && 'v2-btn--block', className);
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

/** Square, ghost (no border, a hover fill), at the control heights: `xs` (inside table rows), `sm`, `md` (default), `lg`. */
export type IconButtonSize = 'xs' | 'sm' | 'md' | 'lg';

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> {
  /** Accessible name; required because the button has no visible text. */
  label: string;
  size?: IconButtonSize;
  /** @deprecated Icon buttons are ghost buttons now; `tone` changes nothing. Kept so existing callers compile. */
  tone?: 'default' | 'strong';
  children: ReactNode;
}

/** Square icon-only button. Pass the icon as children. */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, size = 'md', className, type = 'button', children, ...props },
  ref,
) {
  // `tone` is deprecated and does nothing; taken out here so it never reaches the DOM.
  const { tone: _deprecated, ...rest } = props;
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      className={cx('v2-iconbtn', `v2-iconbtn--${size}`, className)}
      {...rest}
    >
      {children}
    </button>
  );
});

export interface LinkButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /**
   * `strong` and `quiet`: a quiet text action in a card header or under a list (secondary ink, 500, underlined on
   * hover: "See all", "View all", "Show details"); `inline` takes the surrounding text's font and colour and stays
   * underlined (a sentence's "try again").
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
