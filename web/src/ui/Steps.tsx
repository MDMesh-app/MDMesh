import type { Key, ReactNode } from 'react';
import { StackedBar } from './Bars';
import { cx } from './cx';
import './Steps.css';

/* ------------------------------------------------------------------------------------------------------------
   Stepper ("✓ File · 2 Check details · 3 Add")
   ------------------------------------------------------------------------------------------------------------ */

export interface StepperProps {
  steps: ReadonlyArray<ReactNode>;
  /** 0-based index of the current step; earlier steps show as done. */
  current: number;
  'aria-label'?: string;
  className?: string;
}

/** Pill steps: done = ok-soft "✓ label", current = ink with `aria-current="step"`, later = surface-2 "N label". */
export function Stepper({ steps, current, className, 'aria-label': ariaLabel = 'Progress' }: StepperProps) {
  return (
    <ol aria-label={ariaLabel} className={cx('v2-stepper', className)}>
      {steps.map((s, i) => {
        const state = i < current ? 'done' : i === current ? 'current' : 'todo';
        return (
          <li key={i} aria-current={state === 'current' ? 'step' : undefined} className={cx('v2-stepper__step', `is-${state}`)}>
            {state === 'done' ? <span className="v2-visually-hidden">Done: </span> : null}
            {state === 'done' ? '✓ ' : `${i + 1} `}
            {s}
          </li>
        );
      })}
    </ol>
  );
}

/* ------------------------------------------------------------------------------------------------------------
   Rollout step cards ("Step 1 Canary · In progress · 1 of 2 updated")
   ------------------------------------------------------------------------------------------------------------ */

export interface RolloutStep {
  key: Key;
  /** "Step 1". */
  step: ReactNode;
  /** "Canary", "Everyone else". */
  name: ReactNode;
  /** "In progress", "Waiting", "Done". */
  state: ReactNode;
  stateTone?: 'warn' | 'ok' | 'muted';
  /** The current step: white with an ink border; others sit on the sunken fill. */
  active?: boolean;
  /** Counts: done (green), busy (amber), waiting (track). */
  done: number;
  busy?: number;
  total: number | null;
  /** "of 2 updated"; defaults to `of ${total} updated`, or "of the rest of the fleet" when total is null. */
  ofLabel?: ReactNode;
  /** "PHN-0027 updated · PHN-0032 installing". */
  note?: ReactNode;
}

/** Grid of rollout step cards (auto-fit ≥ 260px, 12px gaps). */
export function RolloutSteps({ steps, className }: { steps: ReadonlyArray<RolloutStep>; className?: string }) {
  return (
    <ol className={cx('v2-rollout', className)}>
      {steps.map((s) => {
        const busy = s.busy ?? 0;
        const wait = s.total === null ? 1 : Math.max(0, s.total - s.done - busy);
        const of = s.ofLabel ?? (s.total === null ? 'of the rest of the fleet' : `of ${s.total} updated`);
        return (
          <li key={s.key} className={cx('v2-rollout__step', s.active && 'is-active')}>
            <div className="v2-rollout__head">
              <span className="v2-eyebrow">{s.step}</span>
              <span className="v2-rollout__name">{s.name}</span>
              <span className={cx('v2-rollout__state', `is-${s.stateTone ?? 'muted'}`)}>{s.state}</span>
            </div>
            <div className="v2-rollout__count">
              <span className="v2-rollout__done">{s.done}</span>
              <span className="v2-rollout__of">{of}</span>
            </div>
            <StackedBar
              track
              segments={[
                { value: s.done, tone: 'ok' },
                { value: busy, tone: 'warn' },
                { value: wait, tone: 'track' },
              ]}
            />
            {s.note ? <span className="v2-rollout__note">{s.note}</span> : null}
          </li>
        );
      })}
    </ol>
  );
}

/* ------------------------------------------------------------------------------------------------------------
   Numbered instructions and step numbers
   ------------------------------------------------------------------------------------------------------------ */

/** "1 Factory-reset the device…": 22px outlined circles, 12/600, 12px gap, 10px between steps. */
export function NumberedSteps({ steps, className }: { steps: ReadonlyArray<ReactNode>; className?: string }) {
  return (
    <ol className={cx('v2-numsteps', className)}>
      {steps.map((s, i) => (
        <li key={i}>
          <span className="v2-numsteps__n" aria-hidden="true">
            {i + 1}
          </span>
          <span>{s}</span>
        </li>
      ))}
    </ol>
  );
}

/** The 26px ink square with a step number, used before a card title ("1 Choose what the device gets"). */
export function StepNumber({ n }: { n: number }) {
  return (
    <span className="v2-stepnum" aria-hidden="true">
      {n}
    </span>
  );
}
