import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../App';
import { ROLES, renderInConsole, stubApi } from '../../test/console';
import type { AuthUser } from '../../api/auth';
import type { ManagedUser, UserRole } from '../../api/users';

beforeEach(() => {
  HTMLElement.prototype.scrollIntoView = vi.fn();
});

const permission = { id: 10, name: 'applications' };
const role: UserRole = { id: 7, name: 'Operators', description: 'Fleet operators', permissions: [permission] };
const account: ManagedUser = {
  id: 8,
  login: 'operator',
  name: 'Operator',
  email: 'op@example.org',
  userRole: role,
  allDevicesAvailable: false,
  allConfigAvailable: false,
  groups: [{ id: 9, name: 'Warehouse' }],
  configurations: [{ id: 4, name: 'Kiosk' }],
  editable: true,
};
function open(
  { user = ROLES.admin, manageUsers = true, manageRoles = true, path = '/settings', others = [account] } = {} as {
    user?: AuthUser;
    manageUsers?: boolean;
    manageRoles?: boolean;
    path?: string;
    others?: ManagedUser[];
  },
) {
  const fetch = stubApi((path) => {
    switch (path.replace('/rest/private', '')) {
      case '/users/management':
        return { users: manageUsers, roles: manageRoles };
      case '/users/all':
        return [{ ...account, id: user.id, login: 'myself' }, ...others];
      case '/users/roles':
        return [role];
      case '/users/8':
        return { ...account, authToken: 'never-forward', passwordReset: true };
      case '/roles/all':
        return [role];
      case '/roles/permissions':
        return [permission, { id: 11, name: 'edit_devices' }];
      case '/groups/search':
        return account.groups;
      case '/configurations/list':
        return account.configurations;
      default:
        return null;
    }
  });
  renderInConsole(<AppRoutes />, { user, path });
  return fetch;
}
const body = (fetch: ReturnType<typeof open>, path: string) => {
  const call = fetch.mock.calls.find(([p, init]) => String(p).endsWith(path) && init?.method === 'PUT');
  return call ? JSON.parse(String(call[1]?.body)) : undefined;
};

describe('Users & roles administration', () => {
  it('places administration inside Settings without adding a main navigation item', async () => {
    open();
    const settings = await screen.findByRole('heading', { name: 'Settings' });
    const section = await screen.findByRole('region', { name: 'Users & roles' });
    expect(settings).toBeTruthy();
    expect(await within(section).findByRole('table', { name: 'Users' })).toBeTruthy();
    expect(
      within(screen.getByRole('navigation', { name: 'Main' })).queryByRole('link', { name: 'Users & roles' }),
    ).toBeNull();
    expect(
      within(screen.getByRole('navigation', { name: 'Settings sections' }))
        .getByRole('link', { name: 'Users & roles' })
        .getAttribute('href'),
    ).toBe('#users');
  });

  it('keeps Settings available without exposing administration to roles without settings', async () => {
    const fetch = open({ user: ROLES.observer });
    await screen.findByRole('heading', { name: 'Account' });
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Users & roles' })).toBeNull());
    expect(fetch.mock.calls.some(([p]) => String(p).endsWith('/users/all'))).toBe(false);
    expect(
      within(screen.getByRole('navigation', { name: 'Settings sections' })).queryByRole('link', {
        name: 'Users & roles',
      }),
    ).toBeNull();
    expect(screen.queryByRole('link', { name: 'Users & roles' })).toBeNull();
  });

  it('hides the section and its anchor when the server denies administration', async () => {
    const fetch = open({ manageUsers: false });
    await screen.findByRole('heading', { name: 'Account' });
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Users & roles' })).toBeNull());
    expect(fetch.mock.calls.some(([p]) => String(p).endsWith('/users/all'))).toBe(false);
    expect(
      within(screen.getByRole('navigation', { name: 'Settings sections' })).queryByRole('link', {
        name: 'Users & roles',
      }),
    ).toBeNull();
  });

  it('keeps role management out of a tenant admin’s page and never requests global roles', async () => {
    const fetch = open({ manageRoles: false });
    await screen.findByRole('table', { name: 'Users' });
    expect(screen.queryByRole('radio', { name: 'Roles' })).toBeNull();
    expect(fetch.mock.calls.some(([p]) => String(p).includes('/private/roles/'))).toBe(false);
    expect(screen.queryByRole('button', { name: 'Edit myself' })).toBeNull();
  });

  it('offers Edit but not Delete on another super-admin account', async () => {
    const root = { ...account, id: 12, login: 'root', userRole: { ...role, superAdmin: true } };
    open({ user: ROLES.superAdmin, others: [account, root] });
    expect(await screen.findByRole('button', { name: 'Edit root' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Delete root' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Delete operator' })).toBeTruthy();
  });

  it('creates an account with a wire-compatible hashed password and the selected access scopes', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = within(await screen.findByRole('dialog'));
    const login = await dialog.findByLabelText('Login');
    fireEvent.change(login, { target: { value: 'new-operator' } });
    fireEvent.change(dialog.getByLabelText('Role'), { target: { value: '7' } });
    fireEvent.change(dialog.getByLabelText('Password'), { target: { value: 'password' } });
    fireEvent.change(dialog.getByLabelText('Confirm password'), { target: { value: 'password' } });
    fireEvent.click(dialog.getByRole('checkbox', { name: 'All device groups' }));
    fireEvent.click(dialog.getByRole('checkbox', { name: 'Warehouse' }));
    fireEvent.click(dialog.getByRole('checkbox', { name: 'All configurations' }));
    fireEvent.click(dialog.getByRole('checkbox', { name: 'Kiosk' }));
    fireEvent.submit(login.closest('form')!);
    await waitFor(() =>
      expect(body(fetch, '/private/users')).toEqual({
        login: 'new-operator',
        name: '',
        email: '',
        userRole: { id: 7 },
        newPassword: '5F4DCC3B5AA765D61D8327DEB882CF99',
        allDevicesAvailable: false,
        allConfigAvailable: false,
        groups: [{ id: 9 }],
        configurations: [{ id: 4 }],
      }),
    );
    expect(await screen.findByText('User saved')).toBeTruthy();
  });

  it('preserves restricted access on an edit and never forwards account secrets or a reset flag', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit operator' }));
    const dialog = within(await screen.findByRole('dialog'));
    const name = await dialog.findByLabelText('Name');
    fireEvent.change(name, { target: { value: 'Updated operator' } });
    expect((dialog.getByRole('checkbox', { name: 'Warehouse' }) as HTMLInputElement).checked).toBe(true);
    fireEvent.submit(name.closest('form')!);
    await waitFor(() =>
      expect(body(fetch, '/private/users')).toEqual({
        id: 8,
        login: 'operator',
        name: 'Updated operator',
        email: 'op@example.org',
        userRole: { id: 7 },
        allDevicesAvailable: false,
        allConfigAvailable: false,
        groups: [{ id: 9 }],
        configurations: [{ id: 4 }],
      }),
    );
  });

  it('refuses mismatching passwords without sending a write', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = within(await screen.findByRole('dialog'));
    const login = await dialog.findByLabelText('Login');
    fireEvent.change(login, { target: { value: 'new' } });
    fireEvent.change(dialog.getByLabelText('Password'), { target: { value: 'password1' } });
    fireEvent.change(dialog.getByLabelText('Confirm password'), { target: { value: 'password2' } });
    expect(dialog.getByRole('button', { name: 'Save user' })).toHaveProperty('disabled', true);
    fireEvent.submit(login.closest('form')!);
    expect(await dialog.findByRole('alert')).toHaveProperty('textContent', 'The passwords do not match.');
    expect(body(fetch, '/private/users')).toBeUndefined();
  });

  it('applies Change password’s minimum length to a new account’s password', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = within(await screen.findByRole('dialog'));
    const login = await dialog.findByLabelText('Login');
    fireEvent.change(login, { target: { value: 'new' } });
    fireEvent.change(dialog.getByLabelText('Role'), { target: { value: '7' } });
    fireEvent.change(dialog.getByLabelText('Password'), { target: { value: 'short' } });
    fireEvent.change(dialog.getByLabelText('Confirm password'), { target: { value: 'short' } });
    expect(dialog.getByText('At least 8 characters')).toBeTruthy();
    expect(dialog.getByRole('button', { name: 'Save user' })).toHaveProperty('disabled', true);
    fireEvent.submit(login.closest('form')!);
    expect(await dialog.findByRole('alert')).toHaveProperty(
      'textContent',
      'Use a password of at least 8 characters.',
    );
    expect(body(fetch, '/private/users')).toBeUndefined();
  });

  it('saves role permissions by ID and preserves the description', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('radio', { name: 'Roles' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit Operators' }));
    const dialog = within(await screen.findByRole('dialog'));
    fireEvent.click(dialog.getByRole('checkbox', { name: 'edit devices (edit_devices)' }));
    fireEvent.submit(dialog.getByLabelText('Role name').closest('form')!);
    await waitFor(() =>
      expect(body(fetch, '/private/roles')).toEqual({
        id: 7,
        name: 'Operators',
        description: 'Fleet operators',
        permissions: [{ id: 10 }, { id: 11 }],
      }),
    );
  });

  it('keeps a refused role deletion open and explains that the role is still assigned', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('radio', { name: 'Roles' }));
    const original = fetch.getMockImplementation()!;
    fetch.mockImplementation((input, init) =>
      init?.method === 'DELETE'
        ? Promise.resolve(
            new Response(
              JSON.stringify({
                status: 'ERROR',
                message: 'error.role.in.use',
                data: null,
              }),
            ),
          )
        : original(input, init),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Delete Operators' }));
    const dialog = within(await screen.findByRole('alertdialog'));
    fireEvent.click(dialog.getByRole('button', { name: 'Delete' }));
    expect(await dialog.findByRole('alert')).toHaveProperty(
      'textContent',
      'This role is assigned to users. Assign them another role before deleting it.',
    );
    expect(screen.getByRole('table', { name: 'Roles' }).textContent).toContain('Operators');
  });
});
