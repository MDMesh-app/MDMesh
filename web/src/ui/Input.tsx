import {
  forwardRef,
  useEffect,
  useRef,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { cx } from './cx';
import { useFieldControl } from './Field';
import { IconSearch } from './icons';
import './Input.css';

/** `sm` 8×12 (PIN, compact rows) · `md` 9×12 (default) · `lg` 10×12 (sign-in, Enroll configuration). */
export type InputSize = 'sm' | 'md' | 'lg';
export type InputTone = 'default' | 'invalid' | 'warn';

function useFieldProps(props: { id?: string; 'aria-describedby'?: string; 'aria-invalid'?: unknown }) {
  const f = useFieldControl();
  return {
    id: props.id ?? f?.id,
    'aria-describedby': props['aria-describedby'] ?? f?.describedBy,
    invalid: f?.invalid ?? false,
  };
}

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  size?: InputSize;
  tone?: InputTone;
  /** Technical values (package names, versions, hashes, PIN). */
  mono?: boolean;
}

/** Text input: r10, 1px 3:1 border, white, 14px. Read-only renders on the page fill with the card border. */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { size = 'md', tone = 'default', mono, className, type = 'text', ...rest },
  ref,
) {
  const f = useFieldProps(rest);
  const invalid = tone === 'invalid' || f.invalid;
  return (
    <input
      ref={ref}
      type={type}
      {...rest}
      id={f.id}
      aria-describedby={f['aria-describedby']}
      aria-invalid={invalid || rest['aria-invalid'] || undefined}
      className={cx(
        'v2-input',
        `v2-input--${size}`,
        invalid && 'is-invalid',
        tone === 'warn' && 'is-warn',
        mono && 'v2-input--mono',
        className,
      )}
    />
  );
});

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  tone?: InputTone;
  mono?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { tone = 'default', mono, className, rows = 2, ...rest },
  ref,
) {
  const f = useFieldProps(rest);
  const invalid = tone === 'invalid' || f.invalid;
  return (
    <textarea
      ref={ref}
      rows={rows}
      {...rest}
      id={f.id}
      aria-describedby={f['aria-describedby']}
      aria-invalid={invalid || rest['aria-invalid'] || undefined}
      className={cx(
        'v2-input',
        'v2-input--md',
        'v2-textarea',
        invalid && 'is-invalid',
        tone === 'warn' && 'is-warn',
        mono && 'v2-input--mono',
        className,
      )}
    />
  );
});

/** `xs` 6×8 r9 (app rows) · `sm` 8×10 · `md` 9×12 · `lg` 10×12. */
export type SelectSize = 'xs' | 'sm' | 'md' | 'lg';

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  size?: SelectSize;
  tone?: InputTone;
}

/** Native select, styled like the input (the boards draw the platform arrow). Pass <option>s as children. */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { size = 'md', tone = 'default', className, children, ...rest },
  ref,
) {
  const f = useFieldProps(rest);
  const invalid = tone === 'invalid' || f.invalid;
  return (
    <select
      ref={ref}
      {...rest}
      id={f.id}
      aria-describedby={f['aria-describedby']}
      aria-invalid={invalid || rest['aria-invalid'] || undefined}
      className={cx('v2-input', 'v2-select', `v2-select--${size}`, invalid && 'is-invalid', className)}
    >
      {children}
    </select>
  );
});

export interface LabelledSelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  /** Shown inside the box before the value ("Type  All ▾"); also the select's accessible name ("Type", not
   *  "Type All": the wrapping label alone would read the selected option into the name). */
  label: string;
  children: ReactNode;
}

/** Toolbar filter with its label inside the box. */
export const LabelledSelect = forwardRef<HTMLSelectElement, LabelledSelectProps>(function LabelledSelect(
  { label, className, children, ...rest },
  ref,
) {
  return (
    <label className={cx('v2-lselect', className)}>
      {label}
      <select ref={ref} className="v2-lselect__select" aria-label={label} {...rest}>
        {children}
      </select>
    </label>
  );
});

export interface SearchInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'onChange' | 'size'> {
  value: string;
  onChange: (value: string) => void;
  /** Required: the field has no visible label. */
  'aria-label': string;
  /** Class for the outer box (set its flex basis / max width there). */
  className?: string;
}

/** Search box: leading 15px icon, 9px vertical padding, r10, focus ring on the box. */
export const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(function SearchInput(
  { value, onChange, className, ...rest },
  ref,
) {
  return (
    <label className={cx('v2-search', className)}>
      <IconSearch />
      <input
        ref={ref}
        type="search"
        className="v2-search__input"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        {...rest}
      />
    </label>
  );
});

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  /** Visible label next to the box. Without it, pass aria-label. */
  label?: ReactNode;
  indeterminate?: boolean;
  /** 16px (tables) or 17px (device cards). */
  size?: 16 | 17;
}

/** Native checkbox in ink (accent-color), 16px, with an optional inline label. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, indeterminate, size = 16, className, ...rest },
  forwarded,
) {
  const local = useRef<HTMLInputElement | null>(null);
  useEffect(() => {
    if (local.current) local.current.indeterminate = !!indeterminate;
  }, [indeterminate]);
  const box = (
    <input
      ref={(el) => {
        local.current = el;
        if (typeof forwarded === 'function') forwarded(el);
        else if (forwarded) forwarded.current = el;
      }}
      type="checkbox"
      className={cx('v2-check', size === 17 && 'v2-check--17', !label && className)}
      {...rest}
    />
  );
  if (!label) return box;
  return (
    <label className={cx('v2-checklabel', rest.disabled && 'is-disabled', className)}>
      {box}
      {label}
    </label>
  );
});
