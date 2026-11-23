import { act, screen } from '@testing-library/react';
import { useEffect } from 'react';
import { Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { AuthUser } from '../api/auth';
import { renderInConsole, ROLES, stubApi } from '../test/console';
import { useAuth } from './AuthContext';
import { ProtectedRoute } from './ProtectedRoute';

const KEY = 'hmdm.admin.user';

function otherTabWrites(value: AuthUser) {
  localStorage.setItem(KEY, JSON.stringify(value));
  window.dispatchEvent(new StorageEvent('storage', { key: KEY, newValue: JSON.stringify(value) }));
}

/** A page that counts its mounts, as a page's loading effect would run. */
function mountCounter() {
  const mounts: number[] = [];
  function Page() {
    useEffect(() => {
      mounts.push(mounts.length + 1);
    }, []);
    const permissions = useAuth().user?.userRole?.permissions?.map((p) => p.name).join(',');
    return <p data-permissions={permissions}>device page</p>;
  }
  const ui = (
    <Routes>
      <Route element={<ProtectedRoute />}>
        <Route path="/devices/:id" element={<Page />} />
      </Route>
    </Routes>
  );
  return { mounts, ui };
}

describe('ProtectedRoute: another tab changes the account', () => {
  it('mounts the pages afresh when another tab signs in as someone else', async () => {
    stubApi((path) => (path.endsWith('/rest/private/users/current') ? ROLES.admin : null));
    const { mounts, ui } = mountCounter();
    renderInConsole(ui, { user: ROLES.admin, path: '/devices/WH-12' });
    await screen.findByText('device page');
    expect(mounts).toHaveLength(1);

    act(() => otherTabWrites(ROLES.observer));
    screen.getByText('device page');
    expect(mounts).toHaveLength(2);
  });

  it('keeps the pages mounted when only the role changes', async () => {
    stubApi((path) => (path.endsWith('/rest/private/users/current') ? ROLES.observer : null));
    const { mounts, ui } = mountCounter();
    renderInConsole(ui, { user: ROLES.observer, path: '/devices/WH-12' });
    await screen.findByText('device page');

    const promoted = { ...ROLES.observer, userRole: { ...ROLES.observer.userRole, permissions: [{ name: 'configurations' }] } };
    act(() => otherTabWrites(promoted));
    expect(screen.getByText('device page').dataset.permissions).toBe('configurations');
    expect(mounts).toHaveLength(1);
  });
});
