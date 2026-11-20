const ITEM = 'li, tr';
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Where focus stood: the element, and the list item (li or tr) it sits in with that item's neighbours. */
export interface FocusPlace {
  el: HTMLElement;
  item: Element | null;
  next: Element | null;
  prev: Element | null;
}

export function placeOf(el: HTMLElement): FocusPlace {
  const item = el.closest(ITEM);
  return { el, item, next: item?.nextElementSibling ?? null, prev: item?.previousElementSibling ?? null };
}

/**
 * Focus back where `place` was: its element while it is still on the page. Once an action removed it with its list
 * item (a Delete), and focus with it, the next item's first control, else the previous one's, else the page's main
 * region, so focus never falls to the page body. Focus that something else has taken by then (a page the action
 * opened) stays where it is.
 */
export function focusPlace(place: FocusPlace) {
  if (place.el.isConnected) {
    place.el.focus();
    return;
  }
  const active = document.activeElement;
  if (active && active !== document.body) return;
  const first = (x: Element | null) => (x?.isConnected ? x.querySelector<HTMLElement>(FOCUSABLE) : null);
  const near = first(place.next) ?? first(place.prev);
  if (near) near.focus();
  else document.getElementById('v2-main')?.focus({ preventScroll: true });
}
