const ITEM = 'li, tr';
const DIALOG = '[role="dialog"], [role="alertdialog"]';
/** A Modal's body (ui/Modal.tsx), under its title and Close button. */
const DIALOG_BODY = '.v2-modal__body';

/** What can take focus (a hidden input can't: focusing it does nothing). Shared with Modal. */
export const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Where focus stood: the element; the list item (li or tr) it sits in, with that item's neighbours; and the dialog it
 * sits in, if any (a list inside a dialog).
 */
export interface FocusPlace {
  el: HTMLElement;
  item: Element | null;
  next: Element | null;
  prev: Element | null;
  dialog: HTMLElement | null;
}

export function placeOf(el: HTMLElement): FocusPlace {
  const item = el.closest(ITEM);
  return {
    el,
    item,
    next: item?.nextElementSibling ?? null,
    prev: item?.previousElementSibling ?? null,
    dialog: el.closest<HTMLElement>(DIALOG),
  };
}

/**
 * Focus back where `place` was: its element while it is still on the page. When that element is gone, and focus with
 * it, the nearest thing still there: the first control of its own list item (the item stayed, its control changed),
 * of the next item, or of the previous one (the item went: a Delete); else the dialog it was in (a control in its
 * body first, as when it opened); else the page's main region. So focus never falls to the page body, nor out of an
 * open dialog. Focus that something else has taken by then (a page the action opened) stays where it is.
 */
export function focusPlace(place: FocusPlace) {
  if (place.el.isConnected) {
    place.el.focus();
    return;
  }
  const active = document.activeElement;
  if (active && active !== document.body) return;
  const first = (x: Element | null) => (x?.isConnected ? x.querySelector<HTMLElement>(FOCUSABLE) : null);
  const near = first(place.item) ?? first(place.next) ?? first(place.prev);
  if (near) {
    near.focus();
  } else if (place.dialog?.isConnected) {
    // As the dialog focuses when it opens (Modal): a control in its body before the header's Close button.
    (first(place.dialog.querySelector(DIALOG_BODY)) ?? first(place.dialog) ?? place.dialog).focus();
  } else {
    document.getElementById('v2-main')?.focus({ preventScroll: true });
  }
}
