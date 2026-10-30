import { useCallback, useSyncExternalStore } from 'react';

/** Live `matchMedia(query).matches`. */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (notify: () => void) => {
      const mq = window.matchMedia(query);
      mq.addEventListener('change', notify);
      return () => mq.removeEventListener('change', notify);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    () => false,
  );
}

/** The shell switches to the drawer + top bar at this width (same as the v0.3 console). */
export const MOBILE_QUERY = '(max-width: 860px)';
