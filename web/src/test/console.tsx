// Test helpers: the seeded server roles as signed-in users, a stubbed API, and a render inside the console's
// providers with a chosen registry.
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { vi } from 'vitest';
import type { AuthUser } from '../api/auth';
import { AuthProvider } from '../auth/AuthContext';
import { CORE_EXTENSION } from '../ext/core';
import { createRegistry, type Registry } from '../ext/registry';
import { RegistryProvider } from '../ext/RegistryContext';
import type { ExtensionDefinition } from '../ext/types';
import { ThemeProvider } from '../ui/theme';
import { ToastProvider } from '../ui/toast';

function role(id: number, permissions: string[], superAdmin = false, deploymentAdmin = false): AuthUser {
  return {
    id: 10 + id,
    login: `role-${id}`,
    superAdmin,
    deploymentAdmin,
    userRole: { id, superAdmin, permissions: permissions.map((name) => ({ name })) },
  };
}

/**
 * Headwind's seeded roles with the permissions server/src/main/resources/liquibase/db.changelog.xml grants them
 * (Observer: changeSets for `applications` and `files`; its `edit_device_desc` is taken back in 26.10.13), as the
 * server answers them on a single-customer install: the super-admin and the org admin (2) may manage updates
 * (`deploymentAdmin`, v0.3.8), the others may not.
 */
export const ROLES = {
  superAdmin: role(1, ['superadmin'], true, true),
  admin: role(2, [
    'settings', 'configurations', 'edit_devices', 'edit_device_desc', 'edit_device_app_settings', 'add_config',
    'copy_config', 'push_api', 'get_updates', 'applications', 'edit_applications', 'edit_application_versions',
    'files', 'edit_files', 'enroll_devices',
  ], false, true),
  user: role(3, [
    'configurations', 'edit_devices', 'edit_device_desc', 'copy_config', 'push_api', 'applications',
    'edit_applications', 'edit_application_versions', 'files', 'edit_files', 'enroll_devices',
  ]),
  observer: role(100, ['applications', 'files']),
} satisfies Record<string, AuthUser>;

/** A registry of core plus `extensions`, failing the test on any validation problem. */
export function registryOf(...extensions: ExtensionDefinition[]): Registry {
  const { registry, problems } = createRegistry(CORE_EXTENSION, extensions);
  if (problems.length) throw new Error(problems.join('\n'));
  return registry;
}

/**
 * Answer every API call with an empty OK envelope, or with `data(path, init)` when it returns something. Returns
 * the fetch mock, to assert on the calls.
 */
export function stubApi(data: (path: string, init?: RequestInit) => unknown = () => null) {
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    return new Response(JSON.stringify({ status: 'OK', message: null, data: data(path, init) ?? null }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

/**
 * Render `ui` signed in as `user` (the stored-user hint AuthProvider starts from), at `path`; a list is the history
 * so far, its last entry the current one. `ui` is the one splat route of a data router, as in App.tsx, so navigation
 * goes through the router's transitions as in the browser.
 */
export function renderInConsole(
  ui: ReactElement,
  {
    user = ROLES.superAdmin,
    registry = registryOf(),
    path = '/',
  }: { user?: AuthUser | null; registry?: Registry; path?: string | string[] } = {},
) {
  const entries = typeof path === 'string' ? [path] : path;
  if (user) localStorage.setItem('hmdm.admin.user', JSON.stringify(user));
  const router = createMemoryRouter([{ path: '*', element: ui }], { initialEntries: entries, initialIndex: entries.length - 1 });
  return render(
    <AuthProvider>
      <ThemeProvider>
        <ToastProvider>
          <RegistryProvider registry={registry}>
            <RouterProvider router={router} />
          </RegistryProvider>
        </ToastProvider>
      </ThemeProvider>
    </AuthProvider>,
  );
}
