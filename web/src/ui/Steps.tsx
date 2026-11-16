import type { Key, ReactNode } from 'react';
import { StackedBar } from './Bars';
import { IconCheckSmall } from './icons';
import { cx } from './cx';
import './Steps.css';

/* ------------------------------------------------------------------------------------------------------------
   One step language for the console: a quiet numbered mark (a hairline circle with the number) before the
   step's label. Current = ink ring and ink number, done = green tick, later = muted. Used by the inline progress
   stepper (Custom APK, server update), the rollout cards, numbered instructions and numbered card headers (Enroll).
   ------------------------------------------------------------------------------------------------------------ */

type MarkState = 'todo' | 'current' | 'done' | 'plain';

function StepMark({ n, state = 'plain' }: { n: number; state?: MarkState }) {
  return (
    <span className={cx('v2-stepmark', `is-${state}`)} aria-hidden="true">
      {state === 'done' ? <IconCheckSmall /> : n}
    </span>
  );
}

/* ------------------------------------------------------------------------------------------------------------
   Stepper ("✓ File — 2 Check details — 3 Add")
   ------------------------------------------------------------------------------------------------------------ */

export interface StepperProps {
  steps: ReadonlyArray<ReactNode>;
  /** 0-based index of the current step; earlier steps show as done. */
  current: number;
  'aria-label'?: string;
  className?: string;
}

/** Inline progress: a numbered mark and label per step, joined by a hairline; the current one has `aria-current="step"`. */
export function Stepper({ steps, current, className, 'aria-label': ariaLabel = 'Progress' }: StepperProps) {
  return (
    <ol aria-label={ariaLabel} className={cx('v2-stepper', className)}>
      {steps.map((s, i) => {
        const state = i < current ? 'done' : i === current ? 'current' : 'todo';
        return (
          <li key={i} aria-current={state === 'current' ? 'step' : undefined} className={cx('v2-stepper__step', `is-${state}`)}>
            <StepMark n={i + 1} state={state} />
            <span className="v2-stepper__label">
              {state === 'done' ? <span className="v2-visually-hidden">Done: </span> : null}
              {s}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

/* ------------------------------------------------------------------------------------------------------------
   Rollout step cards ("1 Canary · In progress · 1 of 2 updated")
   ------------------------------------------------------------------------------------------------------------ */

export interface RolloutStep {
  key: Key;
  /** "Step 1": read by screen readers; the card shows the number as a step mark. */
  step: ReactNode;
  /** "Canary", "Everyone else". */
  name: ReactNode;
  /** "In progress", "Waiting", "Done". */
  state: ReactNode;
  stateTone?: 'warn' | 'ok' | 'muted';
  /** The current step: on the card surface with a stronger edge and an ink mark; others sit on the sunken fill. */
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

/** Grid of rollout step cards (auto-fit ≥ 260px). */
export function RolloutSteps({ steps, className }: { steps: ReadonlyArray<RolloutStep>; className?: string }) {
  return (
    <ol className={cx('v2-rollout', className)}>
      {steps.map((s, i) => {
        const busy = s.busy ?? 0;
        const wait = s.total === null ? 1 : Math.max(0, s.total - s.done - busy);
        const of = s.ofLabel ?? (s.total === null ? 'of the rest of the fleet' : `of ${s.total} updated`);
        const mark: MarkState = s.stateTone === 'ok' ? 'done' : s.active ? 'current' : 'todo';
        return (
          <li key={s.key} className={cx('v2-rollout__step', s.active && 'is-active')}>
            <div className="v2-rollout__head">
              <StepMark n={i + 1} state={mark} />
              <span className="v2-rollout__name">
                <span className="v2-visually-hidden">{s.step}: </span>
                {s.name}
              </span>
              <span className={cx('v2-rollout__state', `is-${s.stateTone ?? 'muted'}`)}>{s.state}</span>
            </div>
            <div className="v2-rollout__count">
              <span className="v2-rollout__done v2-num">{s.done}</span>
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

/** "1 Factory-reset the device…": a step mark per line. */
export function NumberedSteps({ steps, className }: { steps: ReadonlyArray<ReactNode>; className?: string }) {
  return (
    <ol className={cx('v2-numsteps', className)}>
      {steps.map((s, i) => (
        <li key={i}>
          <StepMark n={i + 1} />
          <span className="v2-numsteps__text">{s}</span>
        </li>
      ))}
    </ol>
  );
}

/** The step mark before a numbered card title ("1 Choose what the device gets"). */
export function StepNumber({ n }: { n: number }) {
  return <StepMark n={n} />;
}
