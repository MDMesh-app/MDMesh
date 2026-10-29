import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cx } from './cx';
import './Toggle.css';

export interface ToggleProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onChange' | 'role' | 'aria-checked' | 'value'> {
  checked: boolean;
  onChange: (checked: boolean) => void;
  /** Show the "On" / "Off" word left of the track (editor and Settings rows). Off for bare switches (Enroll). */
  showState?: boolean;
  /** Words for the state label. */
  stateLabels?: [on: string, off: string];
}

/**
 * Switch: 40×22 track (r11, 3px padding) with a 16px knob. `role="switch"` + `aria-checked`; name it with
 * `aria-labelledby` (the row label's id) or `aria-label`. The off track carries a 1px ring because the board's
 * #D6DAE1 track alone is 1.40:1 on white, under the 3:1 a control needs.
 */
export const Toggle = forwardRef<HTMLButtonElement, ToggleProps>(function Toggle(
  { checked, onChange, showState = true, stateLabels = ['On', 'Off'], className, disabled, onClick, ...rest },
  ref,
) {
  if (import.meta.env.DEV && !rest['aria-label'] && !rest['aria-labelledby']) {
    console.warn('Toggle: give the switch a name with aria-label or aria-labelledby.');
  }
  return (
    <button
      ref={ref}
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      className={cx('v2-toggle', checked && 'is-on', !showState && 'v2-toggle--bare', className)}
      onClick={(e) => {
        onClick?.(e);
        if (!e.defaultPrevented) onChange(!checked);
      }}
      {...rest}
    >
      {showState ? (
        // aria-hidden: aria-checked already states it, and the word would otherwise become (or repeat) the name.
        <span className="v2-toggle__state" aria-hidden="true">
          {checked ? stateLabels[0] : stateLabels[1]}
        </span>
      ) : null}
      <span className="v2-toggle__track" aria-hidden="true">
        <span className="v2-toggle__knob" />
      </span>
    </button>
  );
});
