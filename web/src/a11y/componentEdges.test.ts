// Which component edges must reach 3:1 (WCAG 1.4.11), and that each really paints with a token whose pairs
// tokenContrast.test.ts checks. Owner's rule, "inputs strong, buttons soft": inputs, selects, the search box, toggles,
// icon-only buttons, the password toggle, the colour picker, the ring on a selected segment / tab / nav item /
// section, the focus ring and the not-reported bar segment are 3:1. Text buttons, chips and cards keep the soft v2
// edges: their visible label identifies them (rows of kind 'exempt' in contrastPairs.ts).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { PAIRS } from './contrastPairs';

const SRC = resolve(import.meta.dirname, '..');

const STRONG_EDGES: Array<[what: string, file: string, selector: string, token: string]> = [
  ['text input / select / textarea', 'ui/Input.css', '.v2 .v2-input {', 'input-border'],
  ['labelled toolbar select', 'ui/Input.css', '.v2 .v2-lselect {', 'input-border'],
  ['search box', 'ui/Input.css', '.v2 .v2-search {', 'input-border'],
  ['icon-only button', 'ui/Button.css', '.v2 .v2-iconbtn {', 'control-border'],
  ['password show/hide toggle', 'ui/Password.css', '.v2 .v2-password__toggle {', 'control-border'],
  ['colour picker button', 'ui/ColourInput.css', '.v2 .v2-colour__btn {', 'control-border'],
  ['selected segment', 'ui/Segmented.css', '.v2 .v2-seg__opt.is-selected {', 'control-border'],
  ['selected tab', 'ui/Tabs.css', '.v2 .v2-tabs__tab.is-selected {', 'control-border'],
  ['current sidebar item', 'ui/Sidebar.css', '.v2 .v2-navitem.is-active {', 'control-border'],
  ['current section-nav item', 'ui/SectionNav.css', '.v2 .v2-secnav__item.is-current {', 'control-border'],
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

describe('component edges that must reach 3:1', () => {
  it.each(STRONG_EDGES)('%s paints with its 3:1 token', (_what, file, selector, token) => {
    expect(rule(file, selector)).toContain(`var(--${token})`);
    // …and that token is one whose 3:1 pairs the token test checks.
    expect(PAIRS.some((p) => p.fg === token && p.kind === 'ui')).toBe(true);
  });
});
