// Which component edges must reach 3:1 (WCAG 1.4.11), and that each really paints with a token whose pairs
// tokenContrast.test.ts checks. Owner's rule, "inputs strong, buttons soft": inputs, selects, the search box, toggles,
// icon-only buttons, the password toggle, the colour picker, the ring on a selected segment / tab / nav item /
// section, the focus ring and the not-reported bar segment are 3:1. Text buttons, chips and cards keep the soft v2
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
  ['text input / select / textarea', 'ui/Input.css', '.v2 .v2-input {', 'input-border'],
  ['labelled toolbar select', 'ui/Input.css', '.v2 .v2-lselect {', 'input-border'],
  ['search box', 'ui/Input.css', '.v2 .v2-search {', 'input-border'],
  ['rows-per-page select', 'ui/Pagination.css', '.v2 .v2-pager__select {', 'input-border'],
  ['icon-only button (IconButton: drawer toggle, dialog close, row and card "…" menus, rename)', 'ui/Button.css', '.v2 .v2-iconbtn {', 'control-border'],
  ['"More actions" menu trigger (icon-only Button)', 'ui/Menu.css', '.v2 .v2-btn.v2-menu__iconbtn:not(:disabled) {', 'control-border'],
  ['password show/hide toggle', 'ui/Password.css', '.v2 .v2-password__toggle {', 'control-border'],
  ['colour picker button', 'ui/ColourInput.css', '.v2 .v2-colour__btn {', 'control-border'],
  ['selected segment', 'ui/Segmented.css', '.v2 .v2-seg__opt.is-selected {', 'control-border'],
  ['selected tab', 'ui/Tabs.css', '.v2 .v2-tabs__tab.is-selected {', 'control-border'],
  ['current sidebar item', 'ui/Sidebar.css', '.v2 .v2-navitem.is-active {', 'control-border'],
  ['current section-nav item', 'ui/SectionNav.css', '.v2 .v2-secnav__item.is-current {', 'control-border'],
  ['kiosk background focal point picker', 'pages/configurations/KioskPlacement.css', '.v2 .v2-kbp__area {', 'control-border'],
  ['selected kiosk preview thumbnail (ring around the device)', 'pages/configurations/KioskPreview.css', ".v2 .v2-kbg__thumb-button[aria-pressed='true'] .v2-kbg__device {", 'ink'],
  ['toggle (off) track', 'ui/Toggle.css', '.v2 .v2-toggle__track {', 'switch-off-ring'],
  ['focus ring', 'tokens.css', '.v2 :focus-visible {', 'ink'],
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
  ['danger text button', 'ui/Button.css', '.v2 .v2-btn--danger:hover {', 'alert-line'],
  ['read-only input', 'ui/Input.css', ".v2 .v2-input[readonly] {", 'border'],
  ['disabled input', 'ui/Input.css', '.v2 .v2-input:disabled {', 'border'],
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

  it('a select is never a bare native one: every <select> in core uses a styled class', () => {
    // #181 added a bare <select>; the shared Select (v2-input) or the pager/toolbar selects carry the 3:1 edge.
    const files = Object.fromEntries(
      ['pages', 'ui'].flatMap((dir) => tsxFiles(resolve(SRC, dir))).map((f) => [f, readFileSync(f, 'utf8')]),
    );
    expect(Object.keys(files).length).toBeGreaterThan(20);
    expect(Object.values(files).join('').match(/<select\b/g)?.length).toBeGreaterThan(2);
    const bare = Object.entries(files)
      .filter(([f]) => !f.includes('.test.'))
      .flatMap(([f, src]) => [...src.matchAll(/<select\b[^>]*>/g)].filter((m) => !/className=/.test(m[0])).map(() => f));
    expect(bare).toEqual([]);
  });
});
