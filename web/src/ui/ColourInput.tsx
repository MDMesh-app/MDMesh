import { useId, useRef } from 'react';
import { Button } from './Button';
import { cx } from './cx';
import './ColourInput.css';

export interface ColourInputProps {
  /** "#RRGGBB", or null for "use the launcher default". */
  value: string | null;
  onChange: (value: string | null) => void;
  /** Id of the row label (FieldRow's labelId). The button's name is the label and then the value it shows. */
  'aria-labelledby'?: string;
  /** The field's name; the button is announced as "<label>: <value>". */
  'aria-label'?: string;
  /** Offer "Reset" back to the default (null). Default true. */
  allowReset?: boolean;
  defaultLabel?: string;
  disabled?: boolean;
  className?: string;
}

/**
 * Colour field (launcher colours): a 5/12/5/6 button with a 24px r6 swatch (15% ink outline) and the hex
 * in mono 13, opening the native colour picker. Null shows "Default" with a hatched swatch; Reset returns to it
 * ("Default" is a state of its own, not a colour).
 */
export function ColourInput({
  value,
  onChange,
  allowReset = true,
  defaultLabel = 'Default',
  disabled,
  className,
  'aria-labelledby': labelledBy,
  'aria-label': label,
}: ColourInputProps) {
  const picker = useRef<HTMLInputElement>(null);
  const valueId = useId();
  const hex = value && /^#[0-9a-f]{6}$/i.test(value) ? value : null;
  const shown = hex ? hex.toUpperCase() : defaultLabel;
  // The name carries the value too ("Background colour #15181D"), as a native colour or select field would.
  const name = labelledBy
    ? { 'aria-labelledby': `${labelledBy} ${valueId}` }
    : label
      ? { 'aria-label': `${label}: ${shown}` }
      : {};
  return (
    <span className={cx('v2-colour', className)}>
      <button
        type="button"
        className="v2-colour__btn"
        disabled={disabled}
        onClick={() => picker.current?.click()}
        {...name}
      >
        <span className={cx('v2-colour__swatch', !hex && 'is-default')} style={hex ? { background: hex } : undefined} aria-hidden="true" />
        <span id={valueId} className="v2-colour__hex">
          {shown}
        </span>
      </button>
      <input
        ref={picker}
        type="color"
        className="v2-colour__native"
        tabIndex={-1}
        aria-hidden="true"
        value={hex ?? '#ffffff'}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value.toUpperCase())}
      />
      {allowReset && hex ? (
        <Button variant="ghost" size="sm" disabled={disabled} onClick={() => onChange(null)}>
          Reset
        </Button>
      ) : null}
    </span>
  );
}
