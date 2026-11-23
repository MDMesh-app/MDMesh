import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import {
  deleteRole,
  deleteUser,
  listAssignableRoles,
  listEditableRoles,
  listPermissions,
  listUsers,
  managementError,
  type ManagedUser,
  type Permission,
  type UserRole,
  type ManagementCapabilities,
} from '../../api/users';
import {
  Badge,
  Button,
  Callout,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  Field,
  Menu,
  Modal,
  PasswordInput,
  SearchInput,
  SkeletonRows,
  Table,
  TabPanel,
  Tabs,
  useStickyValue,
  useToast,
  type MenuItem,
} from '../../ui';
import { loneButtonItem } from '../../ui/loneAction';
import { useRefocusAfterRemove } from '../../ui/useRefocusAfterRemove';
import { UserEditor } from '../users/UserEditor';
import { RoleEditor } from '../users/RoleEditor';
import './UsersSection.css';

type Snapshot = {
  capabilities: ManagementCapabilities;
  users: ManagedUser[];
  assignable: UserRole[];
  roles: UserRole[];
  permissions: Permission[];
};
type Deleting = { kind: 'user' | 'role'; id: number; name: string };
type View = 'users' | 'roles';

const TABS_ID = 'v2-users-tabs';

/** Text kept to one line, ending in "…" when its column is narrow (the full text on hover). */
function Clip({ text, sub }: { text: string; sub?: boolean }) {
  return (
    <span className={sub ? 'v2-users-clip v2-users-clip--sub' : 'v2-users-clip'} title={text}>
      {text}
    </span>
  );
}

/** Row actions, shown on row hover or focus: a "…" menu, or its one safe action as a visible button (loneButtonItem). */
function RowActions({ name, items }: { name: string; items: MenuItem[] }) {
  if (items.length === 0) return null;
  const only = loneButtonItem(items);
  return (
    <div className="v2-rowactions">
      {only ? (
        <Button size="sm" variant="ghost" aria-label={`${only.textValue} ${name}`} onClick={only.onSelect}>
          {only.label}
        </Button>
      ) : (
        <Menu label={`More actions for ${name}`} iconSize="xs" items={items} />
      )}
    </div>
  );
}

export function UsersSection({
  capabilities,
  anchorId,
  headingId,
}: {
  capabilities: ManagementCapabilities;
  anchorId: string;
  headingId: string;
}) {
  const { user } = useAuth();
  const toast = useToast();
  const [data, setData] = useState<Snapshot | null>(null);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<View>('users');
  const [q, setQ] = useState('');
  const [editingUser, setEditingUser] = useState<ManagedUser | 'new' | null>(null);
  const [editingRole, setEditingRole] = useState<UserRole | 'new' | null>(null);
  // Each opening is a fresh form (a new key); a closed dialog stays mounted with what it showed, so it animates out.
  const [session, setSession] = useState(0);
  const shownUser = useStickyValue(editingUser);
  const shownRole = useStickyValue(editingRole);
  const [deleting, setDeleting] = useState<Deleting | null>(null);
  // After a delete, focus goes to the next row rather than falling to the page with the deleted row's "…".
  const deleteFocus = useRefocusAfterRemove();
  const shownDelete = useStickyValue(deleting);
  const [deleteError, setDeleteError] = useState('');
  // Deleting an account takes the caller's own current password; kept for this dialog only
  const [deletePassword, setDeletePassword] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    // A reload after a save or delete keeps the table up until the new one is in (rows keep their place and focus).
    setLoading(true);
    setError('');
    (async () => {
      if (!capabilities.users) return { capabilities, users: [], assignable: [], roles: [], permissions: [] };
      const [users, assignable, roles, permissions] = await Promise.all([
        listUsers(controller.signal),
        listAssignableRoles(controller.signal),
        capabilities.roles ? listEditableRoles(controller.signal) : Promise.resolve([]),
        capabilities.roles ? listPermissions(controller.signal) : Promise.resolve([]),
      ]);
      return {
        capabilities,
        users: users ?? [],
        assignable: assignable ?? [],
        roles: roles ?? [],
        permissions: permissions ?? [],
      };
    })()
      .then((snapshot) => {
        if (!controller.signal.aborted) setData(snapshot);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(managementError(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision, user, capabilities]);
  // The shell remounts pages across account changes. A permission refresh also closes open administration forms and
  // drops the lists loaded for the old permissions until the new ones are in.
  useEffect(() => {
    setEditingUser(null);
    setEditingRole(null);
    setDeleting(null);
    setDeletePassword('');
    setData(null);
  }, [user]);
  const editUser = (target: ManagedUser | 'new') => {
    setSession((n) => n + 1);
    setEditingUser(target);
  };
  const editRole = (target: UserRole | 'new') => {
    setSession((n) => n + 1);
    setEditingRole(target);
  };
  const saved = (title: string, body: string) => {
    setEditingUser(null);
    setEditingRole(null);
    setDeleting(null);
    setDeletePassword('');
    toast.push('ok', title, body);
    setRevision((r) => r + 1);
  };
  const confirmDelete = async () => {
    if (!deleting || busy || (deleting.kind === 'user' && !deletePassword)) return;
    setBusy(true);
    setDeleteError('');
    try {
      if (deleting.kind === 'user') await deleteUser(deleting.id, deletePassword);
      else await deleteRole(deleting.id);
      // Gone from the list now, not when the reload lands: a failed reload can't leave it listed, nor a second
      // Delete reach it.
      const { kind, id } = deleting;
      setData((d) => {
        if (!d) return d;
        if (kind === 'user') return { ...d, users: d.users.filter((u) => u.id !== id) };
        return { ...d, roles: d.roles.filter((r) => r.id !== id), assignable: d.assignable.filter((r) => r.id !== id) };
      });
      if (deleting.kind === 'user') saved('User deleted', `${deleting.name} can no longer sign in.`);
      else saved('Role deleted', `${deleting.name} is no longer available.`);
    } catch (e) {
      setDeleteError(managementError(e));
    } finally {
      setBusy(false);
    }
  };
  const askDelete = (kind: 'user' | 'role', id: number, name: string) => {
    deleteFocus.remember();
    setDeleteError('');
    setDeletePassword('');
    setDeleting({ kind, id, name });
  };
  const cancelDelete = () => {
    deleteFocus.forget();
    setDeleting(null);
    setDeletePassword('');
  };
  if (!capabilities.users) return null;
  const needle = q.trim().toLowerCase();
  const rolesTab = tab === 'roles' && !!data?.capabilities.roles;
  const iAmSuper = !!(user?.superAdmin || user?.userRole?.superAdmin);
  // The server decides (UserResource.getUsers, UserDAO.manageableAccounts); the caller's own row has its own routes.
  const beyondReach = (u: ManagedUser) => u.id !== user?.id && u.editable === false;

  /** What this account may do to another one (the server enforces the same). */
  const userActions = (u: ManagedUser): MenuItem[] => {
    if (u.id === user?.id || u.editable === false || (u.userRole?.superAdmin && !iAmSuper)) return [];
    const items: MenuItem[] = [{ key: 'edit', label: 'Edit', textValue: 'Edit', onSelect: () => editUser(u) }];
    if (!u.userRole?.superAdmin) {
      items.push({
        key: 'delete',
        label: 'Delete',
        textValue: 'Delete',
        danger: true,
        onSelect: () => askDelete('user', u.id!, u.login),
      });
    }
    return items;
  };
  const roleActions = (r: UserRole): MenuItem[] => [
    { key: 'edit', label: 'Edit', textValue: 'Edit', onSelect: () => editRole(r) },
    { key: 'delete', label: 'Delete', textValue: 'Delete', danger: true, onSelect: () => askDelete('role', r.id!, r.name) },
  ];

  const usersTable = data ? (
    <Table
      minWidth={600}
      aria-label="Users"
      className="v2-users-table"
      empty={<EmptyState bare title={needle ? 'No matching users' : 'No users yet'} />}
      rowKey={(u) => u.id!}
      rows={data.users.filter((u) => `${u.login} ${u.name ?? ''} ${u.email ?? ''}`.toLowerCase().includes(needle))}
      onRowClick={(u) => {
        if (userActions(u).length) editUser(u);
      }}
      columns={[
        {
          key: 'user',
          header: 'User',
          className: 'v2-users-col v2-users-col--wide',
          cell: (u) => (
            <span className="v2-users-who">
              <span className="v2-users-who__line">
                <Clip text={u.name || u.login} />
                {u.id === user?.id ? <Badge tone="neutral">You</Badge> : null}
                {beyondReach(u) ? (
                  <Badge tone="neutral" title="This account reaches more than you do, or only a super-admin manages it.">
                    Beyond your access
                  </Badge>
                ) : null}
              </span>
              {u.name ? <Clip text={u.login} sub /> : null}
            </span>
          ),
        },
        { key: 'email', header: 'Email', className: 'v2-users-col', cell: (u) => (u.email ? <Clip text={u.email} /> : '—') },
        { key: 'role', header: 'Role', className: 'v2-users-col', cell: (u) => <Clip text={u.userRole?.name || '—'} /> },
        {
          key: 'access',
          header: 'Access',
          className: 'v2-users-col v2-users-col--wide',
          cell: (u) => (
            <Clip
              text={`${u.allDevicesAvailable ? 'All devices' : 'Selected device groups'} · ${
                u.allConfigAvailable ? 'All configurations' : 'Selected configurations'
              }`}
            />
          ),
        },
        {
          key: 'actions',
          header: 'Actions',
          headerHidden: true,
          shrink: true,
          align: 'right',
          cell: (u) => <RowActions name={u.login} items={userActions(u)} />,
        },
      ]}
    />
  ) : null;

  const rolesTable = data ? (
    <Table
      minWidth={480}
      aria-label="Roles"
      className="v2-users-table"
      empty={<EmptyState bare title={needle ? 'No matching roles' : 'No editable roles yet'} />}
      rowKey={(r) => r.id!}
      rows={data.roles.filter((r) => `${r.name} ${r.description ?? ''}`.toLowerCase().includes(needle))}
      onRowClick={(r) => editRole(r)}
      columns={[
        { key: 'name', header: 'Role', className: 'v2-users-col', cell: (r) => <Clip text={r.name} /> },
        {
          key: 'description',
          header: 'Description',
          className: 'v2-users-col v2-users-col--wide',
          cell: (r) => (r.description ? <Clip text={r.description} /> : '—'),
        },
        {
          key: 'permissions',
          header: 'Permissions',
          align: 'right',
          shrink: true,
          cell: (r) => <span className="v2-num">{(r.permissions ?? []).length}</span>,
        },
        {
          key: 'actions',
          header: 'Actions',
          headerHidden: true,
          shrink: true,
          align: 'right',
          cell: (r) => <RowActions name={r.name} items={roleActions(r)} />,
        },
      ]}
    />
  ) : null;

  return (
    <Card id={anchorId} aria-labelledby={headingId} padding="list" className="v2-settings-card v2-users">
      <CardHeader
        inset
        titleId={headingId}
        title="Users & roles"
        subtitle="Console accounts, what they may do, and which devices they reach."
        actions={
          data ? (
            <Button variant="primary" size="sm" onClick={() => (rolesTab ? editRole('new') : editUser('new'))}>
              {rolesTab ? 'New role' : 'New user'}
            </Button>
          ) : undefined
        }
      />
      {error ? (
        <div className="v2-users-pad">
          <ErrorState thing="users and roles" detail={error} onRetry={() => setRevision((r) => r + 1)} />
        </div>
      ) : null}
      {loading && !data ? (
        <SkeletonRows count={3} label="Loading users and roles" />
      ) : data ? (
        <>
          <div className="v2-users-toolbar">
            {data.capabilities.roles ? (
              <Tabs<View>
                aria-label="Manage"
                size="md"
                idBase={TABS_ID}
                value={tab}
                onChange={(next) => {
                  if (next === tab) return;
                  setTab(next);
                  setQ('');
                }}
                items={[
                  { key: 'users', label: 'Users' },
                  { key: 'roles', label: 'Roles' },
                ]}
              />
            ) : null}
            <SearchInput
              className="v2-users-search"
              value={q}
              onChange={setQ}
              placeholder={rolesTab ? 'Search roles' : 'Search users'}
              aria-label={rolesTab ? 'Search roles' : 'Search users'}
            />
          </div>
          {data.capabilities.roles ? (
            <TabPanel idBase={TABS_ID} tabKey={tab} className="v2-users-panel">
              {rolesTab ? (
                <>
                  <div className="v2-users-pad">
                    <Callout>Administrator roles are protected, so only roles you can edit are listed.</Callout>
                  </div>
                  {rolesTable}
                </>
              ) : (
                usersTable
              )}
            </TabPanel>
          ) : (
            usersTable
          )}
        </>
      ) : null}
      {shownUser ? (
        <UserEditor
          key={`user-${session}`}
          open={editingUser !== null}
          target={shownUser}
          roles={data?.assignable ?? []}
          onClose={() => setEditingUser(null)}
          onSaved={() => saved('User saved', 'The account list is up to date.')}
        />
      ) : null}
      {shownRole ? (
        <RoleEditor
          key={`role-${session}`}
          open={editingRole !== null}
          target={shownRole}
          permissions={data?.permissions ?? []}
          onClose={() => setEditingRole(null)}
          onSaved={() => saved('Role saved', 'The role list is up to date.')}
        />
      ) : null}
      <Modal
        open={deleting !== null}
        role="alertdialog"
        size="sm"
        title={`Delete ${shownDelete?.name ?? ''}?`}
        dismissOnBackdrop={false}
        onClose={() => {
          if (!busy) cancelDelete();
        }}
        footer={
          <>
            <Button size="lg" disabled={busy} onClick={cancelDelete}>
              Cancel
            </Button>
            <Button
              variant="danger-primary"
              size="lg"
              disabled={busy || (shownDelete?.kind === 'user' && !deletePassword)}
              onClick={() => void confirmDelete()}
            >
              {busy ? 'Deleting…' : shownDelete?.kind === 'role' ? 'Delete role' : 'Delete user'}
            </Button>
          </>
        }
      >
        <p className="v2-users-text">
          {shownDelete?.kind === 'role'
            ? 'Only a role no one is assigned to can be deleted.'
            : 'This account can no longer sign in to the console.'}
        </p>
        {shownDelete?.kind === 'user' ? (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void confirmDelete();
            }}
          >
            <Field label="Your current password" help="Needed to delete an account.">
              <PasswordInput
                value={deletePassword}
                required
                disabled={busy}
                autoComplete="current-password"
                toggleLabel="Show your current password"
                onChange={(e) => setDeletePassword(e.target.value)}
              />
            </Field>
          </form>
        ) : null}
        {deleteError ? (
          <Callout tone="alert" role="alert">
            {deleteError}
          </Callout>
        ) : null}
      </Modal>
    </Card>
  );
}
