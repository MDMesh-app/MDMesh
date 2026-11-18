import { useCallback } from 'react';
import { matchPath } from 'react-router';
import { useRegistry } from './RegistryContext';
import { preloadComponent } from './types';

/**
 * A function that starts loading the page a path leads to (its route.page chunk), for links to call on hover and
 * focus: by the click, the page is usually there, so navigation never shows an empty frame.
 */
export function usePrefetchRoute() {
  const registry = useRegistry();
  return useCallback(
    (to: string) => {
      const pathname = to.split(/[?#]/)[0];
      const entry = registry
        .entries('route.page')
        .find((e) => matchPath({ path: e.contribution.path, end: true }, pathname));
      if (entry) preloadComponent(entry.contribution.component);
    },
    [registry],
  );
}
