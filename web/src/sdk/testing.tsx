// `@mdmesh/console/testing`: what an extension's own tests (`extensions/<id>/*.test.tsx`, run by `npm test`) use to
// render it in the console. Tests only: the import guard lets only test files import it, with vitest and
// @testing-library. Unlike `@mdmesh/console`, it carries no stability promise yet: it may change in any release, and
// the CHANGELOG says so when it does.
import type { AuthUser } from '../api/auth';
import { AppRoutes } from '../App';
import type { ExtensionDefinition } from '../ext/types';
import { ROLES, registryOf, renderInConsole, stubApi } from '../test/console';

export { ROLES, registryOf, stubApi };

/**
 * Render the whole console (its routes, shell and pages) at `path`, signed in as `user` (a super-admin by default),
 * with core and `extensions` registered. A problem with an extension (a bad id, a path core reserves) throws, as it
 * fails CI. Answer the API first with `stubApi`.
 */
export function renderConsole({
  extensions = [],
  path = '/',
  user = ROLES.superAdmin,
}: { extensions?: readonly ExtensionDefinition[]; path?: string; user?: AuthUser } = {}) {
  return renderInConsole(<AppRoutes />, { path, user, registry: registryOf(...extensions) });
}
