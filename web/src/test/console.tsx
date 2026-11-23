// Test helpers: the seeded server roles as signed-in users, a stubbed API, and a render inside the console's
// providers with a chosen registry.
import { render, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, vi } from 'vitest';
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

/** The API calls the current stub has not answered yet (settled() waits for none). */
let inFlight = { count: 0 };

/**
 * Answer every API call with an empty OK envelope, or with `data(path, init)` when it returns something. Returns
 * the fetch mock, to assert on the calls or to replace its implementation (held or failing answers); calls reach it
 * through a wrapper that counts the ones not yet answered, for settled().
 */
export function stubApi(data: (path: string, init?: RequestInit) => unknown = () => null) {
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    return new Response(JSON.stringify({ status: 'OK', message: null, data: data(path, init) ?? null }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  // A fresh count per stub: a call a previous test left unanswered can't hold this one's settled().
  const counter = { count: 0 };
  inFlight = counter;
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    counter.count++;
    let counted = false;
    const done = () => {
      if (!counted) counter.count--;
      counted = true;
    };
    // An aborted call is over for the app, whatever a held mock does with it.
    init?.signal?.addEventListener('abort', done, { once: true });
    const answer = fetch(input, init);
    void answer.then((res) => answered(res, done), done);
    return answer;
  });
  return fetch;
}

const BODY_READS = ['text', 'json', 'blob', 'arrayBuffer', 'formData'] as const;

/**
 * Calls `done` once the app has what `res` carries: when its read of the body settles (the data is in hand, about to
 * be set as state), or a macrotask after the answer if nothing starts reading it (a 401 or an error status the
 * client turns into an ApiError without reading). Counting at the bare answer would let settled() pass in the gap
 * before the body is read and the next request goes out.
 */
function answered(res: unknown, done: () => void) {
  if (!(res instanceof Response)) {
    done();
    return;
  }
  let reading = false;
  for (const m of BODY_READS) {
    const read = res[m].bind(res) as () => Promise<unknown>;
    Object.defineProperty(res, m, {
      configurable: true,
      value: () => {
        reading = true;
        const p = read();
        void p.then(done, done);
        return p;
      },
    });
  }
  setTimeout(() => {
    if (!reading) done();
  }, 0);
}

/**
 * Wait until the page has finished loading: every API call answered and no placeholder (skeleton) left. A test ends
 * with this, or with an assertion on the loaded page, so no load is still running when the next test starts.
 *
 * Needs real timers: it waits a macrotask between its checks, and an answer whose body the app never reads counts
 * only a macrotask later. Under vi.useFakeTimers() it throws at once rather than time out; switch back with
 * vi.useRealTimers() first.
 */
export async function settled(timeout = 10_000) {
  if (vi.isFakeTimers()) {
    throw new Error('settled() needs real timers: call vi.useRealTimers() before it (fake timers never run its checks).');
  }
  const SKELETONS = '.v2-skel, .v2-skelrows';
  // Twice, a macrotask apart: an answer's state commits and the effects it triggers (the next request: a row's own
  // detail, a reload) run in between, so a quiet moment between two chained requests doesn't pass for the end.
  const quiet = () => {
    expect(inFlight.count, 'API calls not yet answered').toBe(0);
    expect(document.querySelector(SKELETONS), 'a placeholder still shows').toBeNull();
  };
  await waitFor(
    async () => {
      quiet();
      await new Promise((r) => setTimeout(r, 0));
      quiet();
    },
    { timeout },
  );
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
