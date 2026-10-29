// A test of the demo extension written as a fork's own would be (extensions/<id>/*.test.tsx): it imports only
// vitest, @testing-library, `@mdmesh/console/testing` and its own folder.
import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ROLES, renderConsole, stubApi } from '@mdmesh/console/testing';
import demo from './index';

describe('the demo extension, as its own tests see it', () => {
  it('adds its sidebar item and its page', async () => {
    stubApi();
    renderConsole({ extensions: [demo], path: '/demo' });
    expect(await screen.findByText('Demo page', undefined, { timeout: 3000 })).toBeTruthy();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Demo' })).toBeTruthy();
  });

  it('hides its admin page from a role without the settings permission', async () => {
    stubApi();
    renderConsole({ extensions: [demo], path: '/demo/admin', user: ROLES.user });
    expect(await screen.findByText('You don’t have access to this page', undefined, { timeout: 3000 })).toBeTruthy();
  });
});
