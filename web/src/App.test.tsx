import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from './App';
import demo from './ext/__fixtures__/demo';
import { IconPulse } from './ui/icons';
import { ROLES, registryOf, renderInConsole, stubApi } from './test/console';
import { defineExtension, lazyComponent } from './ext/types';

const fleet = (path: string) => (path.endsWith('/private/devices/search') ? { devices: { totalItemsCount: 248, items: [] } } : null);

function navLinks() {
  const nav = screen.getByRole('navigation', { name: 'Main' });
  return within(nav)
    .getAllByRole('link')
    .slice(1) // the wordmark
    .map((a) => a.textContent);
}

describe('the shell renders core and extension contributions', () => {
  it('shows an extension’s nav item in its ORDER place and its page inside the shell', async () => {
    stubApi(fleet);
    renderInConsole(<AppRoutes />, { registry: registryOf(demo), path: '/demo' });
    expect(await screen.findByText('Demo page')).toBeTruthy();
    expect(screen.getByText('Signed in as role-1')).toBeTruthy();
    await waitFor(() =>
      expect(navLinks()).toEqual(['Overview', 'Devices248', 'Demo', 'Configurations', 'Apps', 'Enroll', 'Settings']),
    );
    expect(screen.getByRole('link', { name: 'Demo' }).className).toContain('is-active');
  });

  it('renders the core sidebar unchanged without extensions: the Devices badge is its badge contribution', async () => {
    stubApi(fleet);
    renderInConsole(<AppRoutes />, { path: '/demo' });
    await waitFor(() => expect(navLinks()).toEqual(['Overview', 'Devices248', 'Configurations', 'Apps', 'Enroll', 'Settings']));
    const devices = screen.getByRole('link', { name: /Devices/ });
    expect(devices.querySelector('.v2-navitem__label')?.textContent).toBe('Devices');
    expect(devices.querySelector('.v2-badge')?.textContent).toBe('248');
    // Items without a badge keep their bare label (no wrapper span), as before.
    expect(screen.getByRole('link', { name: 'Apps' }).querySelector('.v2-navitem__label')).toBeNull();
  });

  it('sends an unknown path home, as before', async () => {
    stubApi(fleet);
    renderInConsole(<AppRoutes />, { registry: registryOf(demo), path: '/no-such-page' });
    await waitFor(() => expect(screen.getByRole('link', { name: 'Overview' }).className).toContain('is-active'));
  });
});

describe('server plugin gating', () => {
  const plugged = defineExtension({
    id: 'plug',
    apiVersion: 1,
    title: 'Plugged',
    requires: { serverPlugin: 'plug' },
    contributions: [
      { slot: 'route.page', id: 'plug', path: '/plug', component: lazyComponent(async () => ({ Plug: () => <p>Plug page</p> }), 'Plug') },
      { slot: 'nav.item', id: 'plug', order: 1, label: 'Plug', icon: IconPulse, to: '/plug' },
    ],
  });
  const active = (ids: string[]) => (path: string) =>
    path.endsWith('/plugin/main/private/available') ? ids.map((identifier) => ({ identifier })) : fleet(path);

  it('shows neither the page nor "no access" while the active plugins load, then the page', async () => {
    stubApi(active(['plug']));
    renderInConsole(<AppRoutes />, { registry: registryOf(plugged), path: '/plug' });
    expect(screen.queryByText('You don’t have access to this page')).toBeNull();
    expect(screen.queryByRole('link', { name: 'Plug' })).toBeNull();
    expect(await screen.findByText('Plug page')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Plug' })).toBeTruthy();
  });

  it('explains the page when the plugin is not active for the customer', async () => {
    stubApi(active(['other']));
    renderInConsole(<AppRoutes />, { registry: registryOf(plugged), path: '/plug' });
    expect(await screen.findByText('You don’t have access to this page')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Plug' })).toBeNull();
  });
});

describe('permission gating: the console hides what the role cannot use', () => {
  it('hides from an Observer the core items its role cannot use', async () => {
    stubApi(fleet);
    renderInConsole(<AppRoutes />, { user: ROLES.observer, registry: registryOf(demo), path: '/demo' });
    await screen.findByText('Demo page');
    await waitFor(() => expect(navLinks()).toEqual(['Overview', 'Devices248', 'Demo', 'Apps', 'Settings']));
  });

  it('explains a page the role cannot use instead of redirecting (core page)', async () => {
    stubApi(fleet);
    renderInConsole(<AppRoutes />, { user: ROLES.observer, path: '/configurations' });
    expect(await screen.findByText('You don’t have access to this page')).toBeTruthy();
  });

  it('never loads a denied page, so none of its requests go out', async () => {
    const fetch = stubApi((path) => (path.endsWith('/configurations/search') ? [] : fleet(path)));
    const pageCalls = () =>
      fetch.mock.calls.map(([input]) => new URL(String(input), 'http://console').pathname).filter((p) => p.includes('/configurations'));
    const { unmount } = renderInConsole(<AppRoutes />, { user: ROLES.observer, path: '/configurations' });
    await screen.findByText('You don’t have access to this page');
    await waitFor(() => expect(navLinks()).toEqual(['Overview', 'Devices248', 'Apps', 'Settings'])); // the shell has loaded
    expect(pageCalls()).toEqual([]);
    unmount();
    localStorage.clear();

    // The check can see them: a role that may open the page loads its list.
    renderInConsole(<AppRoutes />, { user: ROLES.user, path: '/configurations' });
    await waitFor(() => expect(pageCalls()).toContain('/rest/private/configurations/search'));
  });

  it('explains a page the role cannot use (extension page), and shows it to a role that can', async () => {
    stubApi(fleet);
    const { unmount } = renderInConsole(<AppRoutes />, { user: ROLES.user, registry: registryOf(demo), path: '/demo/admin' });
    expect(await screen.findByText('You don’t have access to this page')).toBeTruthy();
    unmount();
    localStorage.clear();

    renderInConsole(<AppRoutes />, { user: ROLES.admin, registry: registryOf(demo), path: '/demo/admin' });
    expect(await screen.findByText('Demo page')).toBeTruthy();
  });
});
