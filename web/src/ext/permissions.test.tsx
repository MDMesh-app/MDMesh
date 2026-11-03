import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ROLES, registryOf, stubApi } from '../test/console';
import { can, evaluate, hasPermission, useActivePlugins, usePermission } from './permissions';
import type { AuthUser } from '../api/auth';

const plugins = (...ids: string[]) => new Set(ids);

describe('hasPermission (SecurityContext.hasPermission, client side)', () => {
  it('matches permission names ignoring case, as the server does', () => {
    expect(hasPermission(ROLES.user, 'EDIT_DEVICES')).toBe(true);
    expect(hasPermission(ROLES.observer, 'edit_devices')).toBe(false);
  });

  it('lets a super-admin through, flagged on the user or on the role', () => {
    expect(hasPermission(ROLES.superAdmin, 'anything')).toBe(true);
    const roleOnly: AuthUser = { id: 1, login: 'r', userRole: { superAdmin: true, permissions: [] } };
    expect(hasPermission(roleOnly, 'anything')).toBe(true);
  });

  it('treats a user without a role as having no permissions', () => {
    expect(hasPermission({ id: 1, login: 'x' }, 'settings')).toBe(false);
  });
});

describe('can / evaluate', () => {
  it('allows anything without requirements, and nothing signed out', () => {
    expect(can(ROLES.observer, undefined)).toBe(true);
    expect(can(null, undefined)).toBe(false);
  });

  it('reads a permission list as any-of', () => {
    expect(can(ROLES.observer, { permission: ['configurations', 'applications'] })).toBe(true);
    expect(can(ROLES.observer, { permission: ['configurations', 'settings'] })).toBe(false);
  });

  it('needs every requirement in the chain (extension, contribution, page)', () => {
    const chain = [{ permission: 'applications' }, { permission: 'settings' }];
    expect(evaluate(ROLES.admin, chain, 'unknown')).toBe('allowed');
    expect(evaluate(ROLES.observer, chain, 'unknown')).toBe('denied');
  });

  it('checks server plugins: active, inactive, still loading, unknown', () => {
    const req = { serverPlugin: 'netaccess' };
    expect(can(ROLES.admin, req, plugins('netaccess'))).toBe(true);
    expect(can(ROLES.admin, req, plugins('audit'))).toBe(false);
    expect(evaluate(ROLES.admin, [req], 'loading')).toBe('pending');
    // The list could not be read: show it, the server still refuses what the customer has not got.
    expect(can(ROLES.admin, req, 'unknown')).toBe(true);
  });

  it('a permission denial beats a pending plugin answer', () => {
    expect(evaluate(ROLES.observer, [{ serverPlugin: 'x' }, { permission: 'settings' }], 'loading')).toBe('denied');
  });

  it('runs `when` with the current user, and counts a throwing rule as no', () => {
    const when = vi.fn(({ user }) => user.permissions.includes('files'));
    expect(can(ROLES.observer, { when })).toBe(true);
    expect(when).toHaveBeenCalledWith({ user: expect.objectContaining({ login: 'role-100', superAdmin: false }) });

    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const broken = () => {
      throw new Error('boom');
    };
    expect(can(ROLES.admin, { when: broken })).toBe(false);
    expect(can(ROLES.admin, { when: broken })).toBe(false);
    expect(error).toHaveBeenCalledTimes(1); // logged once per rule, not on every render
  });
});

describe('core pages by seeded role', () => {
  const visibleNav = (user: AuthUser) =>
    registryOf()
      .entries('nav.item')
      .filter((e) => evaluate(user, e.requires, 'unknown') === 'allowed')
      .map((e) => e.contribution.id);
  const everything = ['overview', 'devices', 'configurations', 'apps', 'enroll', 'settings'];

  it('shows super-admins and admins everything, as before', () => {
    expect(visibleNav(ROLES.superAdmin)).toEqual(everything);
    expect(visibleNav(ROLES.admin)).toEqual(everything);
  });

  it('shows the User role everything too (it holds configurations, applications and edit_devices)', () => {
    expect(visibleNav(ROLES.user)).toEqual(everything);
  });

  it('hides from an Observer the pages the server refuses it', () => {
    expect(visibleNav(ROLES.observer)).toEqual(['overview', 'devices', 'apps', 'settings']);
  });
});

describe('useActivePlugins / usePermission', () => {
  const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
  const signIn = (user: AuthUser) => localStorage.setItem('hmdm.admin.user', JSON.stringify(user));
  // AuthProvider also refreshes the role (GET /private/users/current): count only the plugin list requests.
  const pluginCalls = (fetch: ReturnType<typeof stubApi>) =>
    fetch.mock.calls.filter(([input]) => String(input).endsWith('/plugin/main/private/available'));

  it('makes no request when nothing needs the plugins', () => {
    const fetch = stubApi();
    signIn(ROLES.admin);
    const { result } = renderHook(() => useActivePlugins(false), { wrapper });
    expect(result.current).toBe('unknown');
    expect(pluginCalls(fetch)).toEqual([]);
  });

  it('loads the customer’s plugins (/available, what the server’s gate lets run) once and answers from them', async () => {
    const fetch = stubApi((path) => (path.endsWith('/plugin/main/private/available') ? [{ identifier: 'netaccess' }] : null));
    signIn(ROLES.admin);
    const { result } = renderHook(
      () => [usePermission({ serverPlugin: 'netaccess' }), usePermission({ serverPlugin: 'audit' })],
      { wrapper },
    );
    expect(result.current).toEqual([false, false]); // loading
    await waitFor(() => expect(result.current).toEqual([true, false]));
    expect(pluginCalls(fetch)).toHaveLength(1);
  });

  it('keeps the last answer while a role refresh reloads the list for the same user, so gated items do not blink', async () => {
    // The role comes back changed (a new user object, same id), after the first plugin list is in; the list for the
    // refreshed user is held until the test answers it.
    let answerRole!: () => void;
    const roleHeld = new Promise<void>((resolve) => (answerRole = resolve));
    let answerSecond!: () => void;
    const secondHeld = new Promise<void>((resolve) => (answerSecond = resolve));
    let lists = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input);
        let data: unknown = null;
        if (path.endsWith('/plugin/main/private/available')) {
          lists++;
          if (lists > 1) await secondHeld;
          data = lists > 1 ? [] : [{ identifier: 'netaccess' }];
        } else if (path.endsWith('/private/users/current')) {
          await roleHeld;
          data = { ...ROLES.admin, userRole: { ...ROLES.admin.userRole, permissions: [{ name: 'settings' }] } };
        }
        return Response.json({ status: 'OK', message: null, data });
      }),
    );
    signIn(ROLES.admin);
    const seen: boolean[] = [];
    const { result } = renderHook(
      () => {
        const allowed = usePermission({ serverPlugin: 'netaccess' });
        seen.push(allowed);
        return allowed;
      },
      { wrapper },
    );
    await waitFor(() => expect(result.current).toBe(true));
    const allowedFrom = seen.length - 1;
    await act(async () => answerRole());
    await waitFor(() => expect(lists).toBe(2));
    expect(seen.slice(allowedFrom)).not.toContain(false);
    // The refreshed user's own answer counts once it is in.
    await act(async () => answerSecond());
    await waitFor(() => expect(result.current).toBe(false));
  });

  it('shows plugin-gated items when the list cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 500 })));
    signIn(ROLES.admin);
    const { result } = renderHook(() => useActivePlugins(), { wrapper });
    await waitFor(() => expect(result.current).toBe('unknown'));
  });
});
