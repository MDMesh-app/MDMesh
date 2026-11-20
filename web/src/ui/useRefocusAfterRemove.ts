import { useCallback, useLayoutEffect, useRef } from 'react';
import { focusPlace, placeOf, type FocusPlace } from './focusPlace';

/**
 * Keeps keyboard focus in a list when an action removes the item it came from (a card's or row's Delete in its "…"),
 * after the dialog has handed focus back to the item's trigger: the reload then drops the item and focus would fall
 * to the page. (An item gone before the dialog closes is the Modal's to handle: it returns focus the same way.)
 * Call the returned `remember()` from the item's action while focus is on its trigger (the Menu puts it there before
 * an item runs). Once that item is gone with focus on nothing, focus moves to the next item's first control, else the
 * previous one's, else the page's main region; focus anywhere else by then (the user moved on) stays put. The check
 * runs after each render of the component that calls this, so call it in the one that owns the list.
 */
export function useRefocusAfterRemove(): () => void {
  const pending = useRef<FocusPlace | null>(null);
  useLayoutEffect(() => {
    const p = pending.current;
    if (!p?.item || p.item.isConnected) return;
    pending.current = null;
    focusPlace(p);
  });
  return useCallback(() => {
    const active = document.activeElement;
    pending.current = active instanceof HTMLElement ? placeOf(active) : null;
  }, []);
}
