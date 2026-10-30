import { forwardRef, useState, type ReactNode } from 'react';
import { Input, type InputProps } from './Input';
import { IconCheckSmall, IconCrossSmall, IconDashSmall, IconEye, IconEyeOff } from './icons';
import { cx } from './cx';
import './Password.css';

export type PasswordInputProps = Omit<InputProps, 'type'> & {
  /**
   * The show/hide button's name. It stays the same while the button is `aria-pressed` (the pressed state says
   * whether the password shows), so give each field on a form its own: "Show new password", "Show current password".
   */
  toggleLabel?: string;
};

/**
 * Password field with a show/hide button: the input (10×12 at `lg`) and a 42px-wide button with the
 * control border, 6px apart. Works inside <Field> like any Input. The button is `aria-pressed`; its tooltip says
 * what a click does next.
 */
export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(function PasswordInput(
  { size = 'lg', className, toggleLabel = 'Show password', ...rest },
  ref,
) {
  const [shown, setShown] = useState(false);
  return (
    <span className={cx('v2-password', className)}>
      <Input ref={ref} type={shown ? 'text' : 'password'} size={size} spellCheck={false} {...rest} />
      <button
        type="button"
        className="v2-password__toggle"
        aria-label={toggleLabel}
        aria-pressed={shown}
        title={shown ? 'Hide password' : 'Show password'}
        disabled={rest.disabled}
        onClick={() => setShown((v) => !v)}
      >
        {shown ? <IconEyeOff size={16} /> : <IconEye size={16} />}
      </button>
    </span>
  );
});

export interface Rule {
  key: string;
  label: ReactNode;
  /** true met, false not met, null not checked yet (nothing typed). */
  met: boolean | null;
}

/**
 * Live password rules: 18px round tiles with ✓ (ok-soft / ok-ink) or ✗ (alert-soft / alert-ink),
 * 13.5px text in the same ink; neutral dash until there is input. `aria-live="polite"`; point the inputs'
 * aria-describedby at its id.
 */
export function RuleList({ rules, id, className }: { rules: ReadonlyArray<Rule>; id?: string; className?: string }) {
  return (
    <ul id={id} aria-live="polite" className={cx('v2-rules', className)}>
      {rules.map((r) => {
        const state = r.met === null ? 'idle' : r.met ? 'ok' : 'alert';
        return (
          <li key={r.key} className={`is-${state}`}>
            <span className="v2-rules__tile" aria-hidden="true">
              {state === 'ok' ? <IconCheckSmall /> : state === 'alert' ? <IconCrossSmall size={10} strokeWidth={2} /> : <IconDashSmall size={10} />}
            </span>
            <span className="v2-visually-hidden">{state === 'ok' ? 'Met: ' : state === 'alert' ? 'Not met: ' : ''}</span>
            {r.label}
          </li>
        );
      })}
    </ul>
  );
}
