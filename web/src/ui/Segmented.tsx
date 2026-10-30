import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { cx } from './cx';
import './Segmented.css';

export type SegmentValue = string | number | boolean | null;

export interface SegmentOption<V extends SegmentValue> {
  value: V;
  label: ReactNode;
  /** Leading icon (lg size: the Devices Table/Cards switch). */
  icon?: ReactNode;
  /** Trailing mono count, e.g. "All 248". */
  count?: ReactNode;
  disabled?: boolean;
  /** Accessible name when the label is not plain text. */
  ariaLabel?: string;
}

/**
 * - `sm`: 4×10 options, r7, 12.5px, r10 track (Overview "Recent activity" filter)
 * - `md`: 5×12 options, r8, 13px, r10 track (tri-states, Settings Theme, QR/Token, Upload/Link). Default.
 * - `lg`: 6×12 options, r9, 13.5px, r12 track, mono counts (Devices status filter and view switch, Configurations "Show")
 */
export type SegmentedSize = 'sm' | 'md' | 'lg';

export interface SegmentedProps<V extends SegmentValue> {
  options: ReadonlyArray<SegmentOption<V>>;
  /** Selected value. `undefined` (or a value no option has) selects nothing, e.g. an unset enum. */
  value: V | undefined;
  onChange: (value: V) => void;
  size?: SegmentedSize;
  /** Name the group with one of these two. */
  'aria-label'?: string;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  disabled?: boolean;
  /** Wrap options onto several lines when narrow (boards: true for filters). */
  wrap?: boolean;
  className?: string;
  id?: string;
}

/**
 * Single-choice segmented control: `role="radiogroup"` with roving tabindex. Arrow keys move and select (radio
 * behaviour); Home/End jump to the ends. Selected = raised white segment + 600 ink, shape and weight only, so a
 * forced-colors rule adds an outline (colour alone never marks the choice).
 */
export function Segmented<V extends SegmentValue>({
  options,
  value,
  onChange,
  size = 'md',
  disabled,
  wrap = true,
  className,
  id,
  ...aria
}: SegmentedProps<V>) {
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const selectedIndex = options.findIndex((o) => Object.is(o.value, value));
  const enabled = (i: number) => !disabled && !options[i]?.disabled;
  const firstEnabled = options.findIndex((_, i) => enabled(i));
  const tabStop = selectedIndex >= 0 && enabled(selectedIndex) ? selectedIndex : firstEnabled;

  const move = (from: number, step: 1 | -1) => {
    const n = options.length;
    for (let k = 1; k <= n; k++) {
      const i = (from + step * k + n) % n;
      if (enabled(i)) return i;
    }
    return from;
  };

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    let next = -1;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = move(i, 1);
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = move(i, -1);
    else if (e.key === 'Home') next = move(-1 + options.length, 1);
    else if (e.key === 'End') next = move(0, -1);
    if (next < 0) return;
    e.preventDefault();
    refs.current[next]?.focus();
    // Always report the focused option: comparing with the last render's selection drifts when the parent's
    // state lands asynchronously (e.g. the theme switch runs inside a view transition). Parents ignore repeats.
    onChange(options[next].value);
  };

  return (
    <div
      role="radiogroup"
      id={id}
      aria-disabled={disabled || undefined}
      className={cx('v2-seg', `v2-seg--${size}`, wrap && 'v2-seg--wrap', className)}
      {...aria}
    >
      {options.map((o, i) => {
        const on = i === selectedIndex;
        return (
          <button
            key={i}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            role="radio"
            aria-checked={on}
            aria-label={o.ariaLabel}
            tabIndex={i === tabStop ? 0 : -1}
            disabled={!enabled(i)}
            className={cx('v2-seg__opt', on && 'is-selected')}
            onClick={() => {
              if (!on) onChange(o.value);
            }}
            onKeyDown={(e) => onKeyDown(e, i)}
          >
            {o.icon ? <span className="v2-seg__icon">{o.icon}</span> : null}
            {o.count !== undefined && o.count !== null ? (
              // One inline run, so label and count are a text space apart (boards), not the option's 6px icon gap.
              <span>
                {o.label} <span className="v2-seg__count">{o.count}</span>
              </span>
            ) : (
              o.label
            )}
          </button>
        );
      })}
    </div>
  );
}

/* ---------------------------------------------------------------------------------------------------------------
   Tri-state and two-state policy controls (configuration editor). Values follow web/src/data/configFields.ts:
   a tri field is boolean | null (null = Not managed); a flag field is boolean | null where null ≡ false.
   --------------------------------------------------------------------------------------------------------------- */

export interface PolicySegment {
  value: boolean | null;
  label: string;
}

/** Not managed / Allow / Block, true = allowed (configFields ALLOW_BLOCK). */
export const ALLOW_BLOCK_SEGMENTS: ReadonlyArray<PolicySegment> = [
  { value: null, label: 'Not managed' },
  { value: true, label: 'Allow' },
  { value: false, label: 'Block' },
];

export interface TriStateProps {
  /** Current value; `undefined` selects nothing. */
  value: boolean | null | undefined;
  onChange: (value: boolean | null) => void;
  /** Segments in display order. Pass the field's own (`field.segments`) so inverted policies keep their mapping. */
  segments?: ReadonlyArray<PolicySegment>;
  size?: SegmentedSize;
  disabled?: boolean;
  'aria-label'?: string;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  className?: string;
}

/** "Not managed / Allow / Block" (or any three boolean|null segments). */
export function TriState({ segments = ALLOW_BLOCK_SEGMENTS, ...rest }: TriStateProps) {
  return <Segmented<boolean | null> options={segments} {...rest} />;
}

export interface TwoStateProps {
  /** Stored value; `null` is shown as `false` (flag fields: null ≡ false). */
  value: boolean | null | undefined;
  /** Receives the chosen boolean. Callers that must keep a stored null (configFields `flagOffValue`) map it there. */
  onChange: (value: boolean) => void;
  /** Labels as [label for true, label for false], shown in that order. Default Allow / Block. */
  labels?: [string, string];
  /** Show the `false` option first (e.g. kioskExit: "Hidden gesture" (off) before "Visible button" (on)). */
  falseFirst?: boolean;
  size?: SegmentedSize;
  disabled?: boolean;
  'aria-label'?: string;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  className?: string;
}

/** Two-option variant for flag fields (Allow / Block, Show / Hide, …). */
export function TwoState({ value, onChange, labels = ['Allow', 'Block'], falseFirst, ...rest }: TwoStateProps) {
  const on: SegmentOption<boolean> = { value: true, label: labels[0] };
  const off: SegmentOption<boolean> = { value: false, label: labels[1] };
  const shown = value === undefined ? undefined : value === true;
  return <Segmented<boolean> options={falseFirst ? [off, on] : [on, off]} value={shown} onChange={onChange} {...rest} />;
}
