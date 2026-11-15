import {
  Children,
  Fragment,
  forwardRef,
  isValidElement,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type ChangeEvent,
  type FocusEventHandler,
  type KeyboardEventHandler,
  type MouseEventHandler,
  type Ref,
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

/** Height (the control scale): `sm` (PIN, compact rows) · `md` (default) · `lg` (sign-in, prominent forms). */
export type InputSize = 'sm' | 'md' | 'lg';
export type InputTone = 'default' | 'invalid' | 'warn';

function useFieldProps(props: { id?: string; 'aria-describedby'?: string; 'aria-invalid'?: unknown }) {
  const f = useFieldControl();
  return {
    id: props.id ?? f?.id,
    // Both: a caller's (or a Tooltip's, while it shows) and the field's help and error.
    'aria-describedby': [props['aria-describedby'], f?.describedBy].filter(Boolean).join(' ') || undefined,
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

/**
 * The change event a native select's `onChange` hands its caller: `target` and `currentTarget` carry `value` and
 * `name`; `preventDefault` and `stopPropagation` exist (and do nothing: there is no native event behind it).
 */
function changeEvent(value: string, name?: string): ChangeEvent<HTMLSelectElement> {
  const target = { value, name: name ?? '' } as HTMLSelectElement;
  let prevented = false;
  let stopped = false;
  return {
    type: 'change',
    target,
    currentTarget: target,
    preventDefault: () => {
      prevented = true;
    },
    isDefaultPrevented: () => prevented,
    stopPropagation: () => {
      stopped = true;
    },
    isPropagationStopped: () => stopped,
    persist: () => {},
  } as unknown as ChangeEvent<HTMLSelectElement>;
}

/**
 * What the Select's `ref` points at until 0.6.0 (the native select element before 0.5.0): a stand-in with the members extensions
 * used — `value`, `name`, `disabled`, `required`, `focus()`, `blur()` — typed as the old element so code compiles.
 * The rest of the element's API is gone. From 0.6.0 the ref is the trigger button; use `triggerRef` for it now.
 */
function selectHandle(
  trigger: () => HTMLButtonElement | null,
  state: () => { value: string; name?: string; disabled?: boolean; required?: boolean },
): HTMLSelectElement {
  return {
    get value() {
      return state().value;
    },
    get name() {
      return state().name ?? '';
    },
    get disabled() {
      return !!state().disabled;
    },
    get required() {
      return !!state().required;
    },
    type: 'select-one',
    focus: (options?: FocusOptions) => trigger()?.focus(options),
    blur: () => trigger()?.blur(),
  } as unknown as HTMLSelectElement;
}

export interface SelectProps
  extends Omit<
    SelectHTMLAttributes<HTMLSelectElement>,
    'size' | 'value' | 'defaultValue' | 'onChange' | 'multiple' | 'onFocus' | 'onBlur' | 'onKeyDown' | 'onClick'
  > {
  size?: SelectSize;
  tone?: InputTone;
  value?: string | number;
  defaultValue?: string | number;
  /** Called with a change-shaped event: read `e.target.value`, as with a native select. */
  onChange?: (e: ChangeEvent<HTMLSelectElement>) => void;
  /** Focus, blur, key and click handlers run on the trigger button. */
  onFocus?: FocusEventHandler<HTMLElement>;
  onBlur?: FocusEventHandler<HTMLElement>;
  onKeyDown?: KeyboardEventHandler<HTMLElement>;
  onClick?: MouseEventHandler<HTMLElement>;
  /** The trigger button (the `ref` is a select-like stand-in until 0.6.0, then the button). */
  triggerRef?: Ref<HTMLButtonElement>;
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
 * selected item checked). Takes the props of a single native select: `<option>` / `<optgroup>` children, `value` or
 * `defaultValue`, `onChange(e)` with `e.target.value`, `name`, `required`, `form`, `autoComplete`. Inside a form Radix
 * keeps a hidden native select in step, so the form submits the value and `required` with nothing chosen blocks
 * submission. Other props (handlers, `style`, `tabIndex`, `autoFocus`, `data-*`, `aria-*`) go to the trigger.
 * `multiple` is not supported.
 */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
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
    form,
    autoComplete,
    inlineLabel,
    id,
    triggerRef,
    'aria-label': ariaLabel,
    'aria-describedby': describedBy,
    'aria-invalid': ariaInvalid,
    ...rest
  },
  ref,
) {
  const f = useFieldProps({ id, 'aria-describedby': describedBy });
  const invalid = tone === 'invalid' || f.invalid;
  const entries = parseOptions(children);
  const flat = entries.flatMap((e) => ('group' in e ? e.options : [e]));
  if (import.meta.env.DEV && (rest as { multiple?: unknown }).multiple) {
    console.warn('Select: `multiple` is not supported (one value only).');
  }
  const { multiple: _multiple, ...triggerProps } = rest as typeof rest & { multiple?: unknown };

  // Uncontrolled: our own state, as a native select starts on its first option.
  const [own, setOwn] = useState(() => String(defaultValue ?? flat[0]?.value ?? ''));
  const current = value === undefined ? own : String(value);
  // '' (or a value no option has) is Radix's "nothing chosen": the trigger shows the placeholder, which reads as the
  // empty option ("All", "None") if there is one, else as the first option, like a native select would.
  const known = current !== '' && flat.some((o) => o.value === current);
  const placeholder = (flat.find((o) => o.value === '') ?? flat[0])?.label;

  const trigger = useRef<HTMLButtonElement | null>(null);
  const latest = useRef({ value: current, name, disabled, required });
  latest.current = { value: current, name, disabled, required };
  useImperativeHandle(ref, () => selectHandle(() => trigger.current, () => latest.current), []);
  const setTrigger = useCallback(
    (el: HTMLButtonElement | null) => {
      trigger.current = el;
      if (typeof triggerRef === 'function') triggerRef(el);
      else if (triggerRef) (triggerRef as { current: HTMLButtonElement | null }).current = el;
    },
    [triggerRef],
  );

  return (
    <RSelect.Root
      value={known ? toItem(current) : ''}
      onValueChange={(v) => {
        const next = fromItem(v);
        if (value === undefined) setOwn(next);
        onChange?.(changeEvent(next, name));
      }}
      disabled={disabled}
      required={required}
      name={name}
      form={form}
      autoComplete={autoComplete}
    >
      <RSelect.Trigger
        {...(triggerProps as Record<string, unknown>)}
        ref={setTrigger}
        id={f.id}
        data-value={current}
        aria-label={ariaLabel ?? inlineLabel}
        aria-describedby={f['aria-describedby']}
        aria-invalid={invalid || ariaInvalid || undefined}
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
          <RSelect.Value placeholder={placeholder} />
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
export const LabelledSelect = forwardRef<HTMLSelectElement, LabelledSelectProps>(function LabelledSelect(
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
