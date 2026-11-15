import {
  Children,
  Fragment,
  forwardRef,
  isValidElement,
  useEffect,
  useRef,
  type ChangeEvent,
  type InputHTMLAttributes,
  type ReactElement,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import * as RSelect from '@radix-ui/react-select';
import { cx } from './cx';
import { useFieldControl } from './Field';
import { IconCheck, IconChevronDown, IconSearch } from './icons';
import './Input.css';

/** Height: `sm` 28 (PIN, compact rows) · `md` 32 (default) · `lg` 36 (sign-in, prominent forms). */
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

/** Text input: r6, 1px 3:1 border, white, 14px text. Read-only renders on the page fill with the card border. */
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
  { tone = 'default', mono, className, rows = 3, ...rest },
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

/** Height: `xs` and `sm` 28 (rows, toolbars), `md` 32 (default), `lg` 36 (prominent forms). */
export type SelectSize = 'xs' | 'sm' | 'md' | 'lg';

/** Radix Select has no empty value; an `<option value="">` ("All", "None") travels under this one. */
const EMPTY = '\u0000empty';
const toItem = (v: string) => (v === '' ? EMPTY : v);
const fromItem = (v: string) => (v === EMPTY ? '' : v);

interface ParsedOption {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}
type ParsedEntry = ParsedOption | { group: ReactNode; options: ParsedOption[] };

/** The `<option>` and `<optgroup>` children a native select would take, as Radix items. */
function parseOptions(children: ReactNode): ParsedEntry[] {
  const out: ParsedEntry[] = [];
  const option = (el: ReactElement<{ value?: unknown; children?: ReactNode; disabled?: boolean }>): ParsedOption => ({
    value: el.props.value !== undefined ? String(el.props.value) : String(el.props.children ?? ''),
    label: el.props.children,
    disabled: el.props.disabled,
  });
  Children.forEach(children, (child) => {
    if (!isValidElement(child)) return;
    if (child.type === Fragment) {
      out.push(...parseOptions((child.props as { children?: ReactNode }).children));
    } else if (child.type === 'optgroup') {
      const g = child as ReactElement<{ label?: ReactNode; children?: ReactNode }>;
      out.push({
        group: g.props.label,
        options: parseOptions(g.props.children).flatMap((e) => ('group' in e ? e.options : [e])),
      });
    } else if (child.type === 'option') {
      out.push(option(child as ReactElement<{ value?: unknown; children?: ReactNode; disabled?: boolean }>));
    }
  });
  return out;
}

/** The change event a native select's `onChange` hands its caller: `e.target.value` is all callers read. */
function changeEvent(value: string, name?: string): ChangeEvent<HTMLSelectElement> {
  const target = { value, name: name ?? '' } as HTMLSelectElement;
  return { target, currentTarget: target } as ChangeEvent<HTMLSelectElement>;
}

export interface SelectProps
  extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size' | 'value' | 'defaultValue' | 'onChange'> {
  size?: SelectSize;
  tone?: InputTone;
  value?: string | number;
  defaultValue?: string | number;
  /** Called with a change-shaped event: read `e.target.value`, as with a native select. */
  onChange?: (e: ChangeEvent<HTMLSelectElement>) => void;
  /** Text before the value inside the box ("Type  All"); the toolbar filters use it. */
  inlineLabel?: string;
}

function Items({ entries }: { entries: ParsedEntry[] }) {
  return (
    <>
      {entries.map((e, i) =>
        'group' in e ? (
          <RSelect.Group key={`g${i}`}>
            <RSelect.Label className="v2-listbox__group">{e.group}</RSelect.Label>
            {e.options.map((o) => (
              <Item key={o.value} option={o} />
            ))}
          </RSelect.Group>
        ) : (
          <Item key={e.value} option={e} />
        ),
      )}
    </>
  );
}

function Item({ option }: { option: ParsedOption }) {
  return (
    <RSelect.Item
      value={toItem(option.value)}
      data-value={option.value}
      disabled={option.disabled}
      className="v2-listbox__item"
    >
      <RSelect.ItemText>{option.label}</RSelect.ItemText>
      <RSelect.ItemIndicator className="v2-listbox__check">
        <IconCheck size={14} />
      </RSelect.ItemIndicator>
    </RSelect.Item>
  );
}

/**
 * Select on Radix: a button-like trigger at the input heights and a listbox popover (typeahead, arrow keys, the
 * selected item checked). Takes the props of a native select: `<option>` / `<optgroup>` children, `value`,
 * `onChange(e)` with `e.target.value`. The ref is the trigger button.
 */
export const Select = forwardRef<HTMLButtonElement, SelectProps>(function Select(
  {
    size = 'md',
    tone = 'default',
    className,
    children,
    value,
    defaultValue,
    onChange,
    disabled,
    required,
    name,
    inlineLabel,
    id,
    title,
    'aria-label': ariaLabel,
    'aria-labelledby': ariaLabelledBy,
    ...rest
  },
  ref,
) {
  const f = useFieldProps({ id, ...rest });
  const invalid = tone === 'invalid' || f.invalid;
  const entries = parseOptions(children);
  return (
    <RSelect.Root
      value={value === undefined ? undefined : toItem(String(value))}
      defaultValue={defaultValue === undefined ? undefined : toItem(String(defaultValue))}
      onValueChange={(v) => onChange?.(changeEvent(fromItem(v), name))}
      disabled={disabled}
      required={required}
      name={name}
    >
      <RSelect.Trigger
        ref={ref}
        id={f.id}
        data-value={value === undefined ? undefined : String(value)}
        title={title}
        aria-label={ariaLabel ?? inlineLabel}
        aria-labelledby={ariaLabelledBy}
        aria-describedby={f['aria-describedby']}
        aria-invalid={invalid || rest['aria-invalid'] || undefined}
        className={cx(
          'v2-input',
          'v2-select',
          `v2-select--${size === 'xs' ? 'sm' : size}`,
          invalid && 'is-invalid',
          tone === 'warn' && 'is-warn',
          className,
        )}
      >
        {inlineLabel ? (
          <span className="v2-select__label" aria-hidden="true">
            {inlineLabel}
          </span>
        ) : null}
        <span className="v2-select__value">
          <RSelect.Value />
        </span>
        <RSelect.Icon className="v2-select__chevron">
          <IconChevronDown />
        </RSelect.Icon>
      </RSelect.Trigger>
      <RSelect.Portal>
        <RSelect.Content className="v2-listbox" position="popper" sideOffset={4} collisionPadding={8}>
          <RSelect.Viewport className="v2-listbox__viewport">
            <Items entries={entries} />
          </RSelect.Viewport>
        </RSelect.Content>
      </RSelect.Portal>
    </RSelect.Root>
  );
});

export interface LabelledSelectProps extends Omit<SelectProps, 'inlineLabel' | 'size'> {
  /** Shown inside the box before the value ("Type  All"); also the select's accessible name ("Type", not
   *  "Type All", so the selected option isn't read into the name). */
  label: string;
  children: ReactNode;
}

/** Toolbar filter with its label inside the box: a `Select` at the 32px height. */
export const LabelledSelect = forwardRef<HTMLButtonElement, LabelledSelectProps>(function LabelledSelect(
  { label, className, children, ...rest },
  ref,
) {
  return (
    <Select ref={ref} inlineLabel={label} className={cx('v2-lselect', className)} {...rest}>
      {children}
    </Select>
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

/** Search box at the 32px height: leading icon, focus ring on the box. */
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
  /** @deprecated One size (16px) now; kept so existing callers compile. */
  size?: 16 | 17;
}

/** Native checkbox drawn in CSS (16px, r4, ink when checked, a dash when indeterminate), optional inline label. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, indeterminate, size: _size, className, ...rest },
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
      className={cx('v2-check', !label && className)}
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
