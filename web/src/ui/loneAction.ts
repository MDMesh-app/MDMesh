import type { MenuEntry, MenuItem } from './Menu';

/**
 * A row's or card's actions as one entry or a "…" menu (one place per surface): a menu of one safe entry is that
 * entry as a visible button instead; a lone destructive one (Delete, Remove) stays in the "…", out of reach of a
 * stray click, as do two or more. Returns the entry to show as a button, or null to keep the menu.
 */
export function loneButtonItem(items: ReadonlyArray<MenuEntry>): MenuItem | null {
  const real = items.filter((it): it is MenuItem => it !== 'separator');
  return real.length === 1 && !real[0].danger ? real[0] : null;
}
