// Discovery: the console's registry, built from core and every web/src/extensions/<id>/index.ts (or .tsx). This is the
// only module that imports extensions/ (a guard test enforces it), so a fork adds a folder and edits no core file.
//
// Manifests load eagerly: they are small typed data, and the routes and sidebar must be known on the first render (a
// reload on an extension page resolves at once). Their components are lazy chunks (lazyComponent). The cost is that a
// manifest which throws while loading stops the console from starting; the guard test imports this module, so CI
// catches it first.
import { CORE_EXTENSION } from './core';
import { createRegistry, type Registry } from './registry';
import type { ExtensionDefinition } from './types';

const manifests = import.meta.glob<{ default?: unknown }>('../extensions/*/index.{ts,tsx}', { eager: true });

export interface Discovery {
  definitions: ExtensionDefinition[];
  problems: string[];
}

/**
 * One extension per folder, in folder order, its id the folder name. `disabled` is VITE_EXTENSIONS_DISABLED
 * (`netaccess,reports`): those are left out of the registry, to bisect a broken build. Their manifests are still
 * imported, as the glob is resolved at build time.
 */
export function selectExtensions(modules: Record<string, { default?: unknown }>, disabled: string | undefined): Discovery {
  const off = new Set((disabled ?? '').split(',').map((s) => s.trim()).filter(Boolean));
  const definitions: ExtensionDefinition[] = [];
  const problems: string[] = [];
  for (const path of Object.keys(modules).sort()) {
    const folder = /\/extensions\/([^/]+)\/index\.tsx?$/.exec(path)?.[1] ?? path;
    if (off.has(folder)) continue;
    try {
      const definition = modules[path].default as ExtensionDefinition | undefined;
      if (!definition || typeof definition !== 'object') {
        problems.push(`[ext:${folder}] ${path} has no default export: export default defineExtension({ ... })`);
      } else if (definition.id !== folder) {
        problems.push(`[ext:${folder}] the extension id "${String(definition.id)}" must be its folder name`);
      } else {
        definitions.push(definition);
      }
    } catch (error) {
      // A throwing getter on the module or its id; createRegistry guards everything read after this.
      problems.push(`[ext:${folder}] could not be read: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  return { definitions, problems };
}

/** Under test a problem fails the run; in the console it is logged and the broken part left out. */
function report(problems: readonly string[]) {
  if (!problems.length) return;
  if (import.meta.env.MODE === 'test') throw new Error(`Console extension problems:\n${problems.join('\n')}`);
  for (const problem of problems) console.error(problem);
}

const discovery = selectExtensions(manifests, import.meta.env.VITE_EXTENSIONS_DISABLED);
const built = createRegistry(CORE_EXTENSION, discovery.definitions);
report([...discovery.problems, ...built.problems]);

export const registry: Registry = built.registry;
