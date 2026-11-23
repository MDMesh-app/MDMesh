// Who may see what. One check for core and extension contributions: the console hides what the
// signed-in role cannot use. This is UX only; the server enforces every call.
//
// Where the data comes from: POST /rest/public/auth/login answers with the server's UserView
// (server/.../rest/json/view/user/UserView.java). Its `userRole` is a UserRoleView: { id, name, superAdmin,
// permissions: [{ name }] }, the role's rows of `userRolePermissions` joined to `permissions` (UserMapper.xml,
// resultMaps userRoleLite + permissionLite); the top-level `superAdmin` is the role's flag. auth/AuthContext keeps
// that object in memory and a copy in localStorage (`hmdm.admin.user`), and refreshes its role from
// GET /private/users/current when the console starts and when the tab is shown again, so a role change shows
// without signing in again. The server's own check is SecurityContext.hasPermission
// (common/.../security/SecurityContext.java): super-admin, or any permission whose name equals the asked one
// ignoring case. `hasPermission` below mirrors it.
import { useEffect, useMemo, useState } from 'react';
import type { AuthUser } from '../api/auth';
import { apiClient } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { safeCall } from './safeCall';
import type { CurrentUser, Requirement } from './types';

/**
 * The customer's active server plugins: their ids once known, `'loading'` until then, `'unknown'` when the list
 * could not be read (then plugin requirements pass: the server still answers for itself).
 */
export type ActivePlugins = ReadonlySet<string> | 'loading' | 'unknown';

/** `pending`: the answer depends on the active plugins, which are still loading. */
export type Access = 'allowed' | 'denied' | 'pending';

function isSuperAdmin(user: AuthUser): boolean {
  return !!(user.superAdmin || user.userRole?.superAdmin);
}

/** SecurityContext.hasPermission, client side. */
export function hasPermission(user: AuthUser, name: string): boolean {
  if (isSuperAdmin(user)) return true;
  const wanted = name.toLowerCase();
  return (user.userRole?.permissions ?? []).some((p) => p.name?.toLowerCase() === wanted);
}

function toCurrentUser(user: AuthUser): CurrentUser {
  return Object.freeze({
    id: user.id,
    login: user.login,
    name: user.name,
    email: user.email,
    superAdmin: isSuperAdmin(user),
    allDevices: isSuperAdmin(user) || user.allDevicesAvailable !== false,
    permissions: Object.freeze((user.userRole?.permissions ?? []).map((p) => p.name).filter((n) => typeof n === 'string')),
  });
}

/**
 * Every requirement must pass; within one, `permission` is any-of. Signed out: nothing passes. `label` names the
 * contribution in the log when its `when` throws.
 */
export function evaluate(
  user: AuthUser | null,
  requirements: readonly Requirement[],
  plugins: ActivePlugins,
  label = 'requires',
): Access {
  if (!user) return 'denied';
  let pending = false;
  for (const { permission, serverPlugin, when } of requirements) {
    if (permission !== undefined) {
      const names = typeof permission === 'string' ? [permission] : permission;
      if (!names.some((n) => hasPermission(user, n))) return 'denied';
    }
    if (serverPlugin !== undefined) {
      if (plugins === 'loading') pending = true;
      else if (plugins !== 'unknown' && !plugins.has(serverPlugin)) return 'denied';
    }
    if (when && !safeCall(when, [{ user: toCurrentUser(user) }], false, `${label}.when`)) return 'denied';
  }
  return pending ? 'pending' : 'allowed';
}

/** Whether `user` passes `requires` (a pending plugin answer counts as no). */
export function can(
  user: AuthUser | null,
  requires: Requirement | undefined,
  plugins: ActivePlugins = 'unknown',
): boolean {
  return evaluate(user, requires ? [requires] : [], plugins) === 'allowed';
}

interface PluginRow {
  identifier: string;
}

// One request per signed-in session: keyed by the user object AuthContext holds, which a new sign-in replaces.
// `pluginAnswers` keeps the answer once it is in, so a page mounted later starts from it instead of a blank render.
const pluginRequests = new WeakMap<AuthUser, Promise<ActivePlugins>>();
const pluginAnswers = new WeakMap<AuthUser, ActivePlugins>();

function loadActivePlugins(user: AuthUser): Promise<ActivePlugins> {
  let request = pluginRequests.get(user);
  if (!request) {
    request = apiClient
      .get<PluginRow[]>('/plugin/main/private/available')
      .then((rows): ActivePlugins => new Set((rows ?? []).map((r) => r.identifier)))
      .catch((): ActivePlugins => 'unknown')
      .then((plugins) => {
        pluginAnswers.set(user, plugins);
        return plugins;
      });
    pluginRequests.set(user, request);
  }
  return request;
}

/**
 * The server plugins on for the signed-in user's customer (PluginResource: GET /rest/plugin/main/private/available):
 * in the server's build, with their `plugins` row, not turned off for everyone, and not turned off for the customer.
 * That is what the server's own gate lets run (PluginStatusCache: a plugin's REST, its validators and policy keys).
 * Fetched only when `needed`, so a console without plugin-gated contributions makes no extra request.
 */
export function useActivePlugins(needed = true): ActivePlugins {
  const { user } = useAuth();
  const [loaded, setLoaded] = useState<{ user: AuthUser; plugins: ActivePlugins } | null>(() => {
    const plugins = user && pluginAnswers.get(user);
    return user && plugins ? { user, plugins } : null;
  });
  useEffect(() => {
    if (!needed || !user) return;
    let current = true;
    void loadActivePlugins(user).then((plugins) => {
      if (current) setLoaded({ user, plugins });
    });
    return () => {
      current = false;
    };
  }, [needed, user]);
  if (!needed) return 'unknown';
  if (!loaded || !user) return 'loading';
  // A role refresh replaces the user object, so the list is asked for again. Until that answer is in, the same
  // user keeps the last one: the list is the customer's, the new role is still checked, and the server enforces
  // every call, whereas going back to 'loading' would hide plugin-gated items and unmount an open plugin tab.
  return loaded.user === user || loaded.user.id === user.id ? loaded.plugins : 'loading';
}

/** Whether the signed-in user passes `requires`. */
export function usePermission(requires: Requirement): boolean {
  const { user } = useAuth();
  const plugins = useActivePlugins(requires.serverPlugin !== undefined);
  return can(user, requires, plugins);
}

/** The signed-in user (null when signed out), as extensions see it. */
export function useCurrentUser(): CurrentUser | null {
  const { user } = useAuth();
  return useMemo(() => (user ? toCurrentUser(user) : null), [user]);
}
