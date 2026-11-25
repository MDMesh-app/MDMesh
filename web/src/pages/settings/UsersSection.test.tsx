import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { chooseOption } from '../../test/select';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../App';
import { ROLES, renderInConsole, settled, stubApi } from '../../test/console';
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
/** MD5 of "password" and of the caller's own "my-own-secret", as the console sends them. */
const PASSWORD_MD5 = '5F4DCC3B5AA765D61D8327DEB882CF99';
const MY_SECRET = 'my-own-secret';
const MY_SECRET_MD5 = 'CE92A62EEF7AC5BD657D8E82E613F1C7';
function open(
  {
    user = ROLES.admin,
    manageUsers = true,
    manageRoles = true,
    path = '/settings',
    others = [account],
    current = null,
  } = {} as {
    user?: AuthUser;
    manageUsers?: boolean;
    manageRoles?: boolean;
    path?: string;
    others?: ManagedUser[];
    /** What GET /users/current answers: the caller as the server has them now. */
    current?: AuthUser | null;
  },
) {
  const fetch = stubApi((path) => {
    switch (path.replace('/rest/private', '')) {
      case '/users/current':
        return current;
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
/** Opens a row's "…" menu and picks an item ("Edit", "Delete"). */
async function rowAction(name: string, item: string) {
  fireEvent.click(await screen.findByRole('button', { name: `More actions for ${name}` }));
  fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: item }));
}
const body = (fetch: ReturnType<typeof open>, path: string, method = 'PUT') => {
  const call = fetch.mock.calls.find(([p, init]) => String(p).endsWith(path) && init?.method === method);
  return call ? JSON.parse(String(call[1]?.body)) : undefined;
};
/** Answers every write with `message` as the server's error, and the rest as before. */
function refuseWrites(fetch: ReturnType<typeof open>, message: string) {
  const original = fetch.getMockImplementation()!;
  fetch.mockImplementation((input, init) =>
    init?.method === 'PUT' || init?.method === 'DELETE'
      ? Promise.resolve(new Response(JSON.stringify({ status: 'ERROR', message, data: null })))
      : original(input, init),
  );
}
/** A new user's form, filled in up to the caller's current password. */
async function fillNewUser() {
  fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
  const dialog = within(await screen.findByRole('dialog'));
  const login = await dialog.findByLabelText('Login');
  fireEvent.change(login, { target: { value: 'new-operator' } });
  chooseOption(dialog.getByLabelText('Role'), { value: '7' });
  fireEvent.change(dialog.getByLabelText('Password'), { target: { value: 'password' } });
  fireEvent.change(dialog.getByLabelText('Confirm password'), { target: { value: 'password' } });
  return { dialog, form: login.closest('form')! };
}

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
    await settled();
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
    await settled();
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
    expect(screen.queryByRole('tab', { name: 'Roles' })).toBeNull();
    expect(fetch.mock.calls.some(([p]) => String(p).includes('/private/roles/'))).toBe(false);
    expect(screen.queryByRole('button', { name: 'Edit myself' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'More actions for myself' })).toBeNull();
  });

  it('offers Edit but not Delete on another super-admin account', async () => {
    const root = { ...account, id: 12, login: 'root', userRole: { ...role, superAdmin: true } };
    open({ user: ROLES.superAdmin, others: [account, root] });
    // One action is a visible button, not a menu of one item.
    expect(await screen.findByRole('button', { name: 'Edit root' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'More actions for root' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'More actions for operator' }));
    const items = within(await screen.findByRole('menu')).getAllByRole('menuitem').map((m) => m.textContent);
    expect(items).toEqual(['Edit', 'Delete']);
  });

  it('creates an account with a wire-compatible hashed password and the selected access scopes', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = within(await screen.findByRole('dialog'));
    const login = await dialog.findByLabelText('Login');
    fireEvent.change(login, { target: { value: 'new-operator' } });
    chooseOption(dialog.getByLabelText('Role'), { value: '7' });
    fireEvent.change(dialog.getByLabelText('Password'), { target: { value: 'password' } });
    fireEvent.change(dialog.getByLabelText('Confirm password'), { target: { value: 'password' } });
    fireEvent.click(dialog.getByRole('checkbox', { name: 'Warehouse' }));
    fireEvent.click(dialog.getByRole('checkbox', { name: 'Kiosk' }));
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    fireEvent.submit(login.closest('form')!);
    await waitFor(() =>
      expect(body(fetch, '/private/users')).toEqual({
        login: 'new-operator',
        name: '',
        email: '',
        userRole: { id: 7 },
        newPassword: PASSWORD_MD5,
        oldPassword: MY_SECRET_MD5,
        allDevicesAvailable: false,
        allConfigAvailable: false,
        groups: [{ id: 9 }],
        configurations: [{ id: 4 }],
      }),
    );
    expect(await screen.findByText('User saved')).toBeTruthy();
    await settled();
  });

  it('preserves restricted access on an edit and never forwards account secrets or a reset flag', async () => {
    const fetch = open();
    await rowAction('operator', 'Edit');
    const dialog = within(await screen.findByRole('dialog'));
    const name = await dialog.findByLabelText('Name');
    fireEvent.change(name, { target: { value: 'Updated operator' } });
    expect((dialog.getByRole('checkbox', { name: 'Warehouse' }) as HTMLInputElement).checked).toBe(true);
    // Neither the email nor the password changes, so the caller's own password is not asked for
    expect(dialog.queryByLabelText('Your current password')).toBeNull();
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
    await settled();
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
    chooseOption(dialog.getByLabelText('Role'), { value: '7' });
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

  it('starts a new account with no access until it is chosen', async () => {
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = within(await screen.findByRole('dialog'));
    const all = (await dialog.findByRole('checkbox', { name: 'All device groups' })) as HTMLInputElement;
    expect(all.checked).toBe(false);
    expect(all.disabled).toBe(false);
    expect((dialog.getByRole('checkbox', { name: 'All configurations' }) as HTMLInputElement).checked).toBe(false);
    expect((dialog.getByRole('checkbox', { name: 'Warehouse' }) as HTMLInputElement).checked).toBe(false);
  });

  it('offers a limited admin only the access they have, and never a checked box they cannot clear', async () => {
    const limited: AuthUser = { ...ROLES.admin, allDevicesAvailable: false, allConfigAvailable: false };
    open({ user: limited, current: limited });
    fireEvent.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = within(await screen.findByRole('dialog'));
    const all = (await dialog.findByRole('checkbox', { name: 'All device groups' })) as HTMLInputElement;
    expect(all.checked).toBe(false);
    expect(all.disabled).toBe(true);
    expect((dialog.getByRole('checkbox', { name: 'All configurations' }) as HTMLInputElement).disabled).toBe(true);
    expect(dialog.getAllByText('Your own access is limited, so you can give only what you have.')).toHaveLength(2);
    // Their own groups and configurations stay open to choose
    expect((dialog.getByRole('checkbox', { name: 'Warehouse' }) as HTMLInputElement).disabled).toBe(false);
  });

  it('lets a limited admin check a stored "All" again within the same edit', async () => {
    const limited: AuthUser = { ...ROLES.admin, allDevicesAvailable: false, allConfigAvailable: false };
    const wide: ManagedUser = { ...account, allDevicesAvailable: true };
    const fetch = open({ user: limited, current: limited, others: [wide] });
    const original = fetch.getMockImplementation()!;
    fetch.mockImplementation((input, init) =>
      String(input).endsWith('/users/8')
        ? Promise.resolve(new Response(JSON.stringify({ status: 'OK', message: null, data: wide })))
        : original(input, init),
    );
    await rowAction('operator', 'Edit');
    const dialog = within(await screen.findByRole('dialog'));
    const all = (await dialog.findByRole('checkbox', { name: 'All device groups' })) as HTMLInputElement;
    await waitFor(() => expect(all.checked).toBe(true));
    fireEvent.click(all);
    expect(all.checked).toBe(false);
    expect(all.disabled).toBe(false);
    fireEvent.click(all);
    expect(all.checked).toBe(true);
    // Configurations were never "All": still not the limited admin's to give
    expect((dialog.getByRole('checkbox', { name: 'All configurations' }) as HTMLInputElement).disabled).toBe(true);
  });

  it('opens the editor when the caller’s own access can’t be read', async () => {
    const fetch = open();
    await screen.findByRole('button', { name: 'More actions for operator' });
    const original = fetch.getMockImplementation()!;
    fetch.mockImplementation((input, init) =>
      String(input).endsWith('/users/current')
        ? Promise.resolve(new Response('', { status: 500 }))
        : original(input, init),
    );
    await rowAction('operator', 'Edit');
    const dialog = within(await screen.findByRole('dialog'));
    expect(await dialog.findByLabelText('Name')).toBeTruthy();
    expect((dialog.getByRole('checkbox', { name: 'Warehouse' }) as HTMLInputElement).checked).toBe(true);
  });

  it('asks for the caller’s own password to create an account', async () => {
    const fetch = open();
    const { dialog, form } = await fillNewUser();
    const save = dialog.getByRole('button', { name: 'Save user' });
    expect(dialog.getByText('Needed to create an account.')).toBeTruthy();
    expect(save).toHaveProperty('disabled', true);
    fireEvent.submit(form);
    expect(await dialog.findByRole('alert')).toHaveProperty('textContent', 'Enter your current password.');
    expect(body(fetch, '/private/users')).toBeUndefined();

    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    expect(save).toHaveProperty('disabled', false);
    fireEvent.submit(form);
    await waitFor(() => expect(body(fetch, '/private/users')?.oldPassword).toBe(MY_SECRET_MD5));
    expect(await screen.findByText('User saved')).toBeTruthy();
    await settled();
  });

  it('asks for the caller’s own password to change another account’s email or password', async () => {
    const fetch = open();
    await rowAction('operator', 'Edit');
    const dialog = within(await screen.findByRole('dialog'));
    const email = await dialog.findByLabelText('Email');
    fireEvent.change(email, { target: { value: 'new@example.org' } });
    expect(dialog.getByLabelText('Your current password')).toBeTruthy();
    expect(dialog.getByText('Needed to change another account’s email or password.')).toBeTruthy();
    // Back to the stored address: nothing to confirm
    fireEvent.change(email, { target: { value: ' op@example.org ' } });
    expect(dialog.queryByLabelText('Your current password')).toBeNull();
    fireEvent.change(dialog.getByLabelText('New password'), { target: { value: 'password' } });
    fireEvent.change(dialog.getByLabelText('Confirm password'), { target: { value: 'password' } });
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    fireEvent.submit(email.closest('form')!);
    await waitFor(() =>
      expect(body(fetch, '/private/users')).toMatchObject({ newPassword: PASSWORD_MD5, oldPassword: MY_SECRET_MD5 }),
    );
    expect(await screen.findByText('User saved')).toBeTruthy();
    await settled();
  });

  it('says plainly when the caller’s own password is wrong or the change is beyond their access', async () => {
    const fetch = open();
    const { dialog, form } = await fillNewUser();
    refuseWrites(fetch, 'error.password.wrong');
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: 'not-it' } });
    fireEvent.submit(form);
    expect(await dialog.findByRole('alert')).toHaveProperty('textContent', 'Your current password is not correct.');

    refuseWrites(fetch, 'error.permission.denied');
    fireEvent.submit(form);
    await waitFor(() =>
      expect(dialog.getByRole('alert').textContent).toBe(
        'Not allowed. You can give only the access you have, and change only accounts within your own access.',
      ),
    );
  });

  it('asks for the caller’s own password when the server needs it for a change the form did not show', async () => {
    // Someone changed the account's email meanwhile, so this save would change it back
    const fetch = open();
    await rowAction('operator', 'Edit');
    const dialog = within(await screen.findByRole('dialog'));
    const name = await dialog.findByLabelText('Name');
    refuseWrites(fetch, 'error.password.wrong');
    fireEvent.submit(name.closest('form')!);
    expect(await dialog.findByRole('alert')).toHaveProperty(
      'textContent',
      'Enter your current password to save this change.',
    );
    expect(dialog.getByLabelText('Your current password')).toBeTruthy();
  });

  it('asks for the caller’s own password to delete an account and sends it with the delete only', async () => {
    const fetch = open();
    await rowAction('operator', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    const remove = dialog.getByRole('button', { name: 'Delete user' });
    expect(remove).toHaveProperty('disabled', true);
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    expect(remove).toHaveProperty('disabled', false);
    fireEvent.click(remove);
    await waitFor(() =>
      expect(body(fetch, '/private/users/other/8', 'DELETE')).toEqual({ oldPassword: MY_SECRET_MD5 }),
    );
    expect(await screen.findByText('User deleted')).toBeTruthy();
    await settled();
  });

  it('keeps a delete refused over the caller’s password open, with the reason', async () => {
    const fetch = open();
    await rowAction('operator', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    refuseWrites(fetch, 'error.password.wrong');
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: 'not-it' } });
    fireEvent.click(dialog.getByRole('button', { name: 'Delete user' }));
    expect(await dialog.findByRole('alert')).toHaveProperty('textContent', 'Your current password is not correct.');
  });

  it('says why a change that needs the caller’s password is refused while signed in as another account', async () => {
    const fetch = open();
    await rowAction('operator', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    refuseWrites(fetch, 'error.password.impersonated');
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    fireEvent.click(dialog.getByRole('button', { name: 'Delete user' }));
    expect(await dialog.findByRole('alert')).toHaveProperty(
      'textContent',
      'You are signed in as another account, so this needs its owner. Sign in with your own account to do it.',
    );
  });

  it('marks the accounts the caller cannot administer and offers no action on them', async () => {
    const wider = { ...account, id: 13, login: 'fleet-lead', name: '', allDevicesAvailable: true, editable: false };
    open({ others: [account, wider] });
    const table = await screen.findByRole('table', { name: 'Users' });
    const row = (await within(table).findByText('fleet-lead')).closest('tr')!;
    expect(within(row).getByText('Beyond your access')).toBeTruthy();
    expect(within(row).queryByRole('button')).toBeNull();
    const own = within(table).getByText('operator').closest('tr')!;
    expect(within(own).queryByText('Beyond your access')).toBeNull();
  });

  it('saves role permissions by ID and preserves the description', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('tab', { name: 'Roles' }));
    await rowAction('Operators', 'Edit');
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
    await settled();
  });

  it('keeps a refused role deletion open and explains that the role is still assigned', async () => {
    const fetch = open();
    fireEvent.click(await screen.findByRole('tab', { name: 'Roles' }));
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
    await rowAction('Operators', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    fireEvent.click(dialog.getByRole('button', { name: 'Delete role' }));
    expect(await dialog.findByRole('alert')).toHaveProperty(
      'textContent',
      'This role is assigned to users. Assign them another role before deleting it.',
    );
    // The page behind the open dialog is hidden from assistive tech (aria-hidden), so look past that.
    expect(screen.getByRole('table', { name: 'Roles', hidden: true }).textContent).toContain('Operators');
  });

  it('after deleting a user, focus moves to the next row, not to the page', async () => {
    const packer: ManagedUser = { ...account, id: 21, login: 'packer', name: 'Packer' };
    const others = [account, packer];
    const fetch = open({ others });
    const base = fetch.getMockImplementation()!;
    fetch.mockImplementation(async (input, init) => {
      // The server forgets the account: the reload after the delete no longer lists it.
      if (init?.method === 'DELETE') others.splice(0, 1);
      return base(input, init);
    });
    await screen.findByRole('button', { name: 'More actions for operator' }, { timeout: 10_000 });
    await settled();
    await rowAction('operator', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    fireEvent.click(dialog.getByRole('button', { name: 'Delete user' }));
    expect(await screen.findByText('User deleted')).toBeTruthy();
    const row = (await screen.findByRole('button', { name: 'More actions for packer' })).closest('tr')!;
    expect(screen.queryByRole('button', { name: 'More actions for operator' })).toBeNull();
    await waitFor(() => expect(row.contains(document.activeElement)).toBe(true));
    await settled();
  });

  it('after a delete refused over the caller’s password and then retried, focus still moves to the next row', async () => {
    const packer: ManagedUser = { ...account, id: 21, login: 'packer', name: 'Packer' };
    const others = [account, packer];
    const fetch = open({ others });
    const base = fetch.getMockImplementation()!;
    fetch.mockImplementation(async (input, init) => {
      if (init?.method === 'DELETE') {
        if (JSON.parse(String(init.body)).oldPassword !== MY_SECRET_MD5)
          return new Response(JSON.stringify({ status: 'ERROR', message: 'error.password.wrong', data: null }));
        others.splice(0, 1);
      }
      return base(input, init);
    });
    await screen.findByRole('button', { name: 'More actions for operator' }, { timeout: 10_000 });
    await settled();
    await rowAction('operator', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    const password = dialog.getByLabelText('Your current password');
    fireEvent.change(password, { target: { value: 'not-it' } });
    fireEvent.click(dialog.getByRole('button', { name: 'Delete user' }));
    expect(await dialog.findByRole('alert')).toHaveProperty('textContent', 'Your current password is not correct.');
    fireEvent.change(password, { target: { value: MY_SECRET } });
    fireEvent.click(dialog.getByRole('button', { name: 'Delete user' }));
    expect(await screen.findByText('User deleted')).toBeTruthy();
    const row = (await screen.findByRole('button', { name: 'More actions for packer' })).closest('tr')!;
    await waitFor(() => expect(row.contains(document.activeElement)).toBe(true));
    await settled();
  });

  it('a cancelled delete clears the caller’s password, and focus returns to the row’s "…"', async () => {
    open();
    await screen.findByRole('button', { name: 'More actions for operator' }, { timeout: 10_000 });
    await settled();
    await rowAction('operator', 'Delete');
    let dialog = within(await screen.findByRole('alertdialog'));
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));
    const more = screen.getByRole('button', { name: 'More actions for operator' });
    await waitFor(() => expect(document.activeElement).toBe(more));
    await rowAction('operator', 'Delete');
    dialog = within(await screen.findByRole('alertdialog'));
    expect(dialog.getByLabelText('Your current password')).toHaveProperty('value', '');
    expect(dialog.getByRole('button', { name: 'Delete user' })).toHaveProperty('disabled', true);
    await settled();
  });

  it('a deleted user leaves the list at once, even when the reload after it fails', async () => {
    const packer: ManagedUser = { ...account, id: 21, login: 'packer', name: 'Packer' };
    const fetch = open({ others: [account, packer] });
    const base = fetch.getMockImplementation()!;
    let deleted = false;
    fetch.mockImplementation(async (input, init) => {
      if (init?.method === 'DELETE') deleted = true;
      else if (deleted && String(input).includes('/users/all')) return new Response('', { status: 500 });
      return base(input, init);
    });
    await screen.findByRole('button', { name: 'More actions for operator' }, { timeout: 10_000 });
    await settled();
    await rowAction('operator', 'Delete');
    const dialog = within(await screen.findByRole('alertdialog'));
    fireEvent.change(dialog.getByLabelText('Your current password'), { target: { value: MY_SECRET } });
    fireEvent.click(dialog.getByRole('button', { name: 'Delete user' }));
    expect(await screen.findByText('User deleted')).toBeTruthy();
    await settled();
    expect(screen.getByText(/Couldn.t load users and roles/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'More actions for operator' })).toBeNull();
    const row = screen.getByRole('button', { name: 'More actions for packer' }).closest('tr')!;
    await waitFor(() => expect(row.contains(document.activeElement)).toBe(true));
  });
});
