import { createContext, useContext, useId, type ReactNode } from 'react';
import { cx } from './cx';
import './Field.css';

interface FieldCtx {
  id: string;
  describedBy?: string;
  invalid: boolean;
}

const Ctx = createContext<FieldCtx | null>(null);

/** Inside a <Field>, the control's id / aria-describedby / aria-invalid. Input, Select and Textarea use it. */
// eslint-disable-next-line react-refresh/only-export-components
export function useFieldControl() {
  return useContext(Ctx);
}

export interface FieldProps {
  label: ReactNode;
  /** Help under the control (13px secondary). */
  help?: ReactNode;
  /** Tone of the help line: `ok` for "✓ Worked out from the uploaded file". */
  helpTone?: 'default' | 'ok';
  /** Error under the control; also sets aria-invalid on the control. */
  error?: ReactNode;
  /** `sm` = 13px label (Enroll Wi-Fi fields). */
  size?: 'md' | 'sm';
  /** Explicit id for the control (otherwise generated). */
  id?: string;
  className?: string;
  children: ReactNode;
}

/**
 * Stacked form field: label (14/500) over the control, help / error below, 6px gaps.
 * The child control picks up id, aria-describedby and aria-invalid from context.
 */
export function Field({ label, help, helpTone = 'default', error, size = 'md', id, className, children }: FieldProps) {
  const auto = useId();
  const controlId = id ?? `f${auto}`;
  const helpId = help ? `${controlId}-help` : undefined;
  const errorId = error ? `${controlId}-err` : undefined;
  const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={cx('v2-field', size === 'sm' && 'v2-field--sm', className)}>
      <label className="v2-field__label" htmlFor={controlId}>
        {label}
      </label>
      <Ctx.Provider value={{ id: controlId, describedBy, invalid: !!error }}>{children}</Ctx.Provider>
      {help ? (
        <span id={helpId} className={cx('v2-field__help', helpTone === 'ok' && 'is-ok')}>
          {help}
        </span>
      ) : null}
      {error ? (
        <span id={errorId} className="v2-field__error">
          {error}
        </span>
      ) : null}
    </div>
  );
}

export interface FieldRowProps {
  label: ReactNode;
  help?: ReactNode;
  /** Chips after the label ("Changed", "Enforced"). */
  adornment?: ReactNode;
  /**
   * The control, right-aligned. A function receives the label/help ids so it can set
   * `aria-labelledby` / `aria-describedby` on a Toggle, TriState or Select.
   */
  children: ReactNode | ((ids: { labelId: string; helpId?: string }) => ReactNode);
  /** Full-width content under the row (a warning callout). */
  below?: ReactNode;
  /** Top rule between rows (default true; the card's first row usually keeps it, as on the boards). */
  divider?: boolean;
  /**
   * Disable the row (kiosk-only settings while kiosk mode is off). The control sits in a disabled <fieldset>, so
   * every input, select and button in it is really disabled (the dimmed text is then exempt from contrast rules).
   */
  disabled?: boolean;
  /** Left column basis: `md` 260px (editor), `lg` 300px (Settings). */
  labelWidth?: 'md' | 'lg';
  id?: string;
  className?: string;
}

/**
 * Setting row (configuration editor and Settings): label + help on the left, control pushed right, wraps under
 * the label when narrow. 13px vertical padding, 10px/24px gaps, 1px #EEF0F3 rule on top.
 */
export function FieldRow({
  label,
  help,
  adornment,
  children,
  below,
  divider = true,
  disabled,
  labelWidth = 'md',
  id,
  className,
}: FieldRowProps) {
  const auto = useId();
  const labelId = id ?? `r${auto}`;
  const helpId = help ? `${labelId}-help` : undefined;
  return (
    <div className={cx('v2-fieldrow', divider && 'v2-fieldrow--divider', disabled && 'is-disabled', className)}>
      <div className={cx('v2-fieldrow__text', labelWidth === 'lg' && 'v2-fieldrow__text--lg')}>
        <div className="v2-fieldrow__labelline">
          <span id={labelId} className="v2-fieldrow__label">
            {label}
          </span>
          {adornment}
        </div>
        {help ? (
          <span id={helpId} className="v2-fieldrow__help">
            {help}
          </span>
        ) : null}
      </div>
      <fieldset className="v2-fieldrow__control" disabled={disabled}>
        {typeof children === 'function' ? children({ labelId, helpId }) : children}
      </fieldset>
      {below ? <div className="v2-fieldrow__below">{below}</div> : null}
    </div>
  );
}

/** "Changed" chip: 6px ink dot + 11.5/500 on surface-2. */
export function ChangedChip({ label = 'Changed' }: { label?: string }) {
  return (
    <span className="v2-changed">
      <span className="v2-changed__dot" aria-hidden="true" />
      {label}
    </span>
  );
}

/** Small uppercase heading inside a card (12/600, 0.06em, secondary) with optional inline help. */
export function Eyebrow({
  children,
  help,
  as: As = 'span',
  id,
  className,
}: {
  children: ReactNode;
  help?: ReactNode;
  as?: 'span' | 'h2' | 'h3' | 'h4';
  id?: string;
  className?: string;
}) {
  if (!help) {
    return (
      <As id={id} className={cx('v2-eyebrow', className)}>
        {children}
      </As>
    );
  }
  return (
    <div className={cx('v2-eyebrowline', className)}>
      <As id={id} className="v2-eyebrow">
        {children}
      </As>
      <span className="v2-eyebrowline__help">{help}</span>
    </div>
  );
}
