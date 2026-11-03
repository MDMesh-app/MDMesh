import { createContext, useContext, type ReactNode } from 'react';
import type { Registry } from './registry';

const RegistryContext = createContext<Registry | null>(null);

/** Hands the registry to every host below. App passes the discovered one (ext/load.ts); tests pass a fixture. */
export function RegistryProvider({ registry, children }: { registry: Registry; children: ReactNode }) {
  return <RegistryContext.Provider value={registry}>{children}</RegistryContext.Provider>;
}

export function useRegistry(): Registry {
  const registry = useContext(RegistryContext);
  if (!registry) throw new Error('useRegistry must be used within a RegistryProvider');
  return registry;
}
