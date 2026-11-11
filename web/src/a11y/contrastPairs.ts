// Every foreground/background token pair the console paints, with the contrast it must reach (owner's standard:
// WCAG 2.2 AA with 4.5:1 for all normal text, 3:1 for large text and for non-text UI per 1.4.11).
// tokenContrast.test.ts fails when a pair drops below its minimum, in either theme. A new token, or an existing
// one used on a new background, gets its row here.

export type PairKind =
  /** Normal-size text: 4.5:1. */
  | 'text'
  /** Non-text UI that carries meaning (control borders, focus ring, toggles, status marks, chart fills): 3:1. */
  | 'ui'
  /** Disabled controls: exempt under WCAG, listed for the record only (no minimum). */
  | 'exempt';

export interface ContrastPair {
  /** Where it shows. */
  element: string;
  fg: string;
  bg: string;
  kind: PairKind;
  /** The opaque surface a translucent `bg` sits on (default --surface). */
  base?: string;
}

export const MIN: Record<PairKind, number> = { text: 4.5, ui: 3, exempt: 0 };

/** Neutral grounds text can sit on: page, card, tracks, sunken panels, selected rows, hover fills, raised segments. */
const NEUTRAL = ['app', 'surface', 'surface-2', 'surface-sunken', 'row-selected', 'hover', 'seg-selected'];

const grid = (element: string, fgs: string[], bgs: string[], kind: PairKind): ContrastPair[] =>
  fgs.flatMap((fg) => bgs.map((bg) => ({ element, fg, bg, kind })));

export const PAIRS: ContrastPair[] = [
  // ---- text on neutral grounds ----
  ...grid('Body, label and help text', ['text', 'text-2', 'secondary', 'ink'], NEUTRAL, 'text'),
  ...grid('Status text (Status.tsx stext, Field ok/error)', ['ok-ink', 'warn-ink', 'alert-ink', 'idle-ink'], NEUTRAL, 'text'),
  { element: 'Placeholder text', fg: 'secondary', bg: 'surface', kind: 'text' },
  { element: 'Placeholder in a read-only/disabled input', fg: 'secondary', bg: 'app', kind: 'text' },
  // ---- tone text on its own soft fill (Badge, StatusPill, Callout, Toast icon) ----
  { element: 'OK badge / pill / callout', fg: 'ok-ink', bg: 'ok-soft', kind: 'text' },
  { element: 'OK success panel', fg: 'ok-ink', bg: 'ok-wash', kind: 'text' },
  { element: 'Text on success panel', fg: 'text-2', bg: 'ok-wash', kind: 'text' },
  { element: 'Warning badge / pill / callout', fg: 'warn-ink', bg: 'warn-soft', kind: 'text' },
  { element: 'Alert badge / pill / callout', fg: 'alert-ink', bg: 'alert-soft', kind: 'text' },
  { element: 'Danger menu item / button hover', fg: 'alert-ink', bg: 'alert-soft', kind: 'text' },
  { element: 'Idle badge / pill', fg: 'idle-ink', bg: 'idle-soft', kind: 'text' },
  { element: 'Body text in a warning callout', fg: 'text', bg: 'warn-soft', kind: 'text' },
  { element: 'Body text in an alert callout', fg: 'text', bg: 'alert-soft', kind: 'text' },
  { element: 'Body text in an OK callout', fg: 'text', bg: 'ok-soft', kind: 'text' },
  // ---- inverted ----
  { element: 'Primary button / ink badge', fg: 'on-ink', bg: 'ink', kind: 'text' },
  { element: 'Primary button hover', fg: 'on-ink', bg: 'ink-hover', kind: 'text' },
  // ---- non-text: control boundaries ----
  ...grid('Text input / select border', ['input-border'], ['surface', 'app', 'surface-2'], 'ui'),
  ...grid('Secondary / icon / danger button border', ['control-border'], ['surface', 'app', 'surface-2'], 'ui'),
  ...grid('Danger button border', ['alert-control'], ['surface', 'app'], 'ui'),
  { element: 'Invalid input border', fg: 'alert', bg: 'surface', kind: 'ui' },
  { element: 'Warning input border', fg: 'warn', bg: 'surface', kind: 'ui' },
  ...grid('Selected segment / tab outline', ['control-border'], ['seg-selected'], 'ui'),
  // ---- non-text: focus ring (2px ink outline, offset onto the ground) ----
  ...grid('Focus ring', ['ink'], [...NEUTRAL, 'ok-soft', 'warn-soft', 'alert-soft'], 'ui'),
  // ---- non-text: toggle ----
  { element: 'Toggle off: track ring on its card', fg: 'switch-off-ring', bg: 'surface', kind: 'ui' },
  { element: 'Toggle on: track on its card', fg: 'switch-on', bg: 'surface', kind: 'ui' },
  { element: 'Toggle on: knob on the track', fg: 'switch-on-knob', bg: 'switch-on', kind: 'ui' },
  { element: 'Checkbox / radio border', fg: 'input-border', bg: 'surface', kind: 'ui' },
  // ---- non-text: status marks and chart fills ----
  ...grid('Status dot / bar fill', ['online', 'warn', 'alert', 'muted'], ['surface', 'app', 'row-selected', 'surface-2'], 'ui'),
  ...grid('Offline dot / bar fill', ['offline'], ['surface', 'app', 'row-selected'], 'ui'),
  ...grid('Bar fill on its track', ['online', 'warn', 'alert', 'offline', 'secondary'], ['line-soft'], 'ui'),
  // The "not reported" segment is a light fill (--bar-idle) whose edge is a 1px --muted ring (Bars.css).
  ...grid('Not-reported bar segment ring', ['muted'], ['surface', 'app', 'line-soft'], 'ui'),
  { element: 'Sidebar update dot (ok) on its card', fg: 'online', bg: 'app', kind: 'ui' },
  // ---- icons that carry meaning ----
  ...grid('Icon in a button / nav item', ['text-2', 'secondary'], ['surface', 'hover', 'surface-2'], 'ui'),
  // ---- disabled: exempt, recorded ----
  { element: 'Disabled text (faint)', fg: 'faint', bg: 'surface', kind: 'exempt' },
  { element: 'Disabled primary button', fg: 'disabled-ink', bg: 'disabled-bg', kind: 'exempt' },
];
