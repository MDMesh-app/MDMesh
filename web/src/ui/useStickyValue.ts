import { useEffect, useRef } from 'react';

/**
 * The last non-null value: a dialog keeps showing what it showed while it animates closed. Pass the state that
 * picks what the dialog is about; render `<Modal open={value != null}>` with the sticky value's content, so the
 * Modal stays mounted and plays its exit.
 *
 *   const shown = useStickyValue(dialog);
 *   <ActionDialog open={dialog !== null} template={shown} … />
 *
 * The value is remembered after each commit (never written during render), so a fresh object every render is fine.
 */
export function useStickyValue<T>(value: T | null | undefined): T | null {
  const last = useRef<T | null>(value ?? null);
  useEffect(() => {
    if (value !== null && value !== undefined) last.current = value;
  });
  return value ?? last.current;
}
