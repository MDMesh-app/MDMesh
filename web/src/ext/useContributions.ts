import { useCallback, useMemo } from 'react';
import { useAuth } from '../auth/AuthContext';
import { evaluate, useActivePlugins, type Access } from './permissions';
import type { SlotEntry } from './registry';
import { useRegistry } from './RegistryContext';
import type { ContributionOf, SlotId } from './types';

/** How the signed-in user fares against an entry's requirements. */
export function useAccess(): (entry: SlotEntry) => Access {
  const { user } = useAuth();
  const plugins = useActivePlugins(useRegistry().usesServerPlugins);
  return useCallback((entry: SlotEntry) => evaluate(user, entry.requires, plugins, entry.key), [user, plugins]);
}

/** The slot's entries the signed-in user may see, in display order. */
export function useContributions<S extends SlotId>(slot: S): readonly SlotEntry<ContributionOf<S>>[] {
  const entries = useRegistry().entries(slot);
  const access = useAccess();
  return useMemo(() => entries.filter((e) => access(e) === 'allowed'), [entries, access]);
}
