// Which component edges must reach 3:1 (WCAG 1.4.11), and that each really paints with a token whose pairs
// tokenContrast.test.ts checks. Owner's rule, "inputs strong, buttons soft": inputs, selects, the search box, toggles,
// the password toggle, the colour picker, the selected tab's indicator,
// the focus ring and the not-reported bar segment are 3:1. Text buttons, chips and cards keep the soft v2
// edges: their visible label identifies them (rows of kind 'exempt' in contrastPairs.ts).
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { PAIRS } from './contrastPairs';

const SRC = resolve(import.meta.dirname, '..');

function tsxFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) =>
    d.isDirectory() ? tsxFiles(resolve(dir, d.name)) : d.name.endsWith('.tsx') ? [resolve(dir, d.name)] : [],
  );
}

const STRONG_EDGES: Array<[what: string, file: string, selector: string, token: string]> = [
  ['text input / select / textarea (also the toolbar and rows-per-page selects)', 'ui/Input.css', '.v2 .v2-input {', 'input-border'],
  ['search box', 'ui/Input.css', '.v2 .v2-search {', 'input-border'],
  ['checkbox', 'ui/Input.css', '.v2 .v2-check {', 'input-border'],
  // The password toggle sits inside the field (whose edge is the input's, above): a ghost icon button, its icon 3:1.
  ['password show/hide toggle (inside the field; its icon)', 'ui/Password.css', '.v2 .v2-password__toggle {', 'secondary'],
  ['colour picker button', 'ui/ColourInput.css', '.v2 .v2-colour__btn {', 'control-border'],
  ['selected tab indicator', 'ui/Tabs.css', '.v2 .v2-tabs::after {', 'ink'],
  ['kiosk background focal point picker', 'pages/configurations/KioskPlacement.css', '.v2 .v2-kbp__area {', 'control-border'],
  ['selected kiosk preview thumbnail (ring around the device)', 'pages/configurations/KioskPreview.css', ".v2 .v2-kbg__thumb-button[aria-pressed='true'] .v2-kbg__device {", 'ink'],
  ['toggle (off) track', 'ui/Toggle.css', '.v2 .v2-toggle__track {', 'switch-off-ring'],
  ['focus ring', 'tokens.css', '.v2 :focus-visible {', 'focus'],
  ['not-reported bar segment', 'ui/Bars.css', '.v2 .v2-bar--pending {', 'muted'],
];

/** The declarations of the rule that starts with exactly `selector` on its own line. */
function rule(file: string, selector: string): string {
  const css = readFileSync(resolve(SRC, file), 'utf8');
  const lines = css.split('\n');
  const at = lines.findIndex((l) => l === selector);
  if (at < 0) throw new Error(`${file}: no rule "${selector}"`);
  const end = lines.findIndex((l, i) => i > at && l.startsWith('}'));
  return lines.slice(at + 1, end).join('\n');
}

/**
 * Soft on purpose, written down so the choice is not mistaken for a miss: the edge each paints with is not a 3:1 pair.
 * - Text buttons and chips: the visible label identifies them (owner: "inputs strong, buttons soft").
 * - Read-only and disabled inputs: not operable, so 1.4.11 asks nothing of their edge (as on main).
 * - Toast Dismiss: a borderless icon button; the × itself is --text-2 (≥ 3:1, "Icon in a button" pairs) on the toast.
 */
const SOFT_BY_DESIGN: Array<[what: string, file: string, selector: string, token: string]> = [
  ['secondary text button', 'ui/Button.css', '.v2 .v2-btn--secondary:hover,', 'border-strong'],
  ['danger text button', 'ui/Button.css', '.v2 .v2-btn--danger:hover {', 'border-strong'],
  ['read-only input', 'ui/Input.css', ".v2 .v2-input[readonly] {", 'border'],
  ['disabled input', 'ui/Input.css', '.v2 .v2-input[data-disabled] {', 'border'],
  // Icon-only buttons are ghost buttons: the icon itself (--text-2, ≥ 3:1, "Icon in a button" pairs) identifies them.
  ['icon-only button (no edge; its icon is 3:1)', 'ui/Button.css', '.v2 .v2-iconbtn {', 'text-2'],
  // Selected segment and current nav item: a fill plus the label's 500 weight and ink, never colour alone.
  ['selected segment (raised fill, 500 ink label)', 'ui/Segmented.css', '.v2 .v2-seg__opt.is-selected {', 'weight-medium'],
  ['current sidebar item (fill, 500 ink label)', 'ui/Sidebar.css', '.v2 .v2-navitem.is-active {', 'weight-medium'],
  ['current section-nav item (fill, 500 ink label)', 'ui/SectionNav.css', '.v2 .v2-secnav__item.is-current {', 'weight-medium'],
  ['toast Dismiss (icon only, no edge)', 'ui/Toast.css', '.v2 .toast .t-close {', 'text-2'],
];

describe('component edges that must reach 3:1', () => {
  it.each(STRONG_EDGES)('%s paints with its 3:1 token', (_what, file, selector, token) => {
    expect(rule(file, selector)).toContain(`var(--${token})`);
    // …and that token is one whose 3:1 pairs the token test checks.
    expect(PAIRS.some((p) => p.fg === token && p.kind === 'ui')).toBe(true);
  });
});

describe('edges that stay soft by design', () => {
  it.each(SOFT_BY_DESIGN)('%s paints with its soft token', (_what, file, selector, token) => {
    expect(rule(file, selector)).toContain(`var(--${token})`);
  });

  it('a select is never a native one: core uses the shared Select (ui/Input, Radix) with its 3:1 edge', () => {
    // #181 added a bare <select>; since the polish there is none: every choice goes through ui/Input's Select.
    const files = Object.fromEntries(
      ['pages', 'ui'].flatMap((dir) => tsxFiles(resolve(SRC, dir))).map((f) => [f, readFileSync(f, 'utf8')]),
    );
    expect(Object.keys(files).length).toBeGreaterThan(20);
    // ui/Input keeps the deprecated native path for extensions (`native` / `multiple`, until 0.6.0); core uses neither.
    const native = Object.entries(files)
      .filter(([f]) => !f.includes('.test.') && !f.endsWith('/ui/Input.tsx'))
      .flatMap(([f, src]) => [...src.matchAll(/<select\b[^>]*>|<(?:Labelled)?Select\b[^>]*\b(?:native|multiple)\b/g)].map(() => f));
    expect(native).toEqual([]);
  });
});
