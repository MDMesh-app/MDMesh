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
import { Button, Callout, Card, EmptyState, Modal, CardHeader, SearchInput, Table } from '../../ui';
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
  const [data, setData] = useState<Snapshot | null>(null);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<'users' | 'roles'>('users');
  const [q, setQ] = useState('');
  const [editingUser, setEditingUser] = useState<ManagedUser | 'new' | null>(null);
  const [editingRole, setEditingRole] = useState<UserRole | 'new' | null>(null);
  const [deleting, setDeleting] = useState<{ kind: 'user' | 'role'; id: number; name: string } | null>(null);
  const [deleteError, setDeleteError] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setData(null);
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
  // The shell remounts pages across account changes. A permission refresh also closes open administration forms.
  useEffect(() => {
    setEditingUser(null);
    setEditingRole(null);
    setDeleting(null);
  }, [user]);
  const saved = (message: string) => {
    setEditingUser(null);
    setEditingRole(null);
    setDeleting(null);
    setNotice(message);
    setRevision((r) => r + 1);
  };
  const confirmDelete = async () => {
    if (!deleting || busy) return;
    setBusy(true);
    setDeleteError('');
    try {
      if (deleting.kind === 'user') await deleteUser(deleting.id);
      else await deleteRole(deleting.id);
      saved(`${deleting.kind === 'user' ? 'User' : 'Role'} deleted.`);
    } catch (e) {
      setDeleteError(managementError(e));
    } finally {
      setBusy(false);
    }
  };
  const askDelete = (kind: 'user' | 'role', id: number, name: string) => {
    setDeleteError('');
    setDeleting({ kind, id, name });
  };
  if (!capabilities.users) return null;
  const needle = q.trim().toLowerCase();
  const rolesTab = tab === 'roles' && data?.capabilities.roles;
  return (
    <Card id={anchorId} aria-labelledby={headingId} gap={20} className="v2-settings-card users-section">
      <CardHeader
        titleId={headingId}
        title="Users & roles"
        subtitle="Manage console accounts, permissions and access to your fleet."
        actions={
          data && !loading ? (
            <Button variant="primary" onClick={() => (rolesTab ? setEditingRole('new') : setEditingUser('new'))}>
              {rolesTab ? 'New role' : 'New user'}
            </Button>
          ) : undefined
        }
      />
      {notice && (
        <Callout tone="ok" role="status">
          {notice}
        </Callout>
      )}
      {error && (
        <Callout tone="alert" role="alert">
          {error} <Button onClick={() => setRevision((r) => r + 1)}>Retry</Button>
        </Callout>
      )}
      {loading ? (
        <p role="status">Loading users and roles…</p>
      ) : (
        data && (
          <>
            <div className="users-toolbar">
              <div className="users-actions" role="group" aria-label="Manage">
                <Button
                  variant={!rolesTab ? 'primary' : 'secondary'}
                  aria-pressed={!rolesTab}
                  onClick={() => {
                    setTab('users');
                    setQ('');
                  }}
                >
                  Users
                </Button>
                {data.capabilities.roles && (
                  <Button
                    variant={rolesTab ? 'primary' : 'secondary'}
                    aria-pressed={!!rolesTab}
                    onClick={() => {
                      setTab('roles');
                      setQ('');
                    }}
                  >
                    Roles
                  </Button>
                )}
              </div>
              <SearchInput
                value={q}
                onChange={setQ}
                placeholder={rolesTab ? 'Search roles' : 'Search users'}
                aria-label={rolesTab ? 'Search roles' : 'Search users'}
              />
            </div>
            {rolesTab ? (
              <>
                <Callout>Administrator roles are protected. Only roles available for editing appear here.</Callout>
                <div className="users-table">
                  <Table
                    minWidth={500}
                    aria-label="Roles"
                    empty={<EmptyState title={needle ? 'No matching roles' : 'No editable roles'} />}
                    rowKey={(r) => r.id!}
                    rows={data.roles.filter((r) => `${r.name} ${r.description ?? ''}`.toLowerCase().includes(needle))}
                    columns={[
                      { key: 'name', header: 'Role', cell: (r) => r.name },
                      { key: 'description', header: 'Description', cell: (r) => r.description || '—' },
                      { key: 'permissions', header: 'Permissions', cell: (r) => (r.permissions ?? []).length },
                      {
                        key: 'actions',
                        header: 'Actions',
                        shrink: true,
                        cell: (r) => (
                          <div className="users-actions">
                            <Button size="sm" aria-label={`Edit ${r.name}`} onClick={() => setEditingRole(r)}>
                              Edit<span className="v2-visually-hidden"> {r.name}</span>
                            </Button>
                            <Button
                              size="sm"
                              variant="danger"
                              aria-label={`Delete ${r.name}`}
                              onClick={() => askDelete('role', r.id!, r.name)}
                            >
                              Delete<span className="v2-visually-hidden"> {r.name}</span>
                            </Button>
                          </div>
                        ),
                      },
                    ]}
                  />
                </div>
              </>
            ) : (
              <div className="users-table">
                <Table
                  minWidth={680}
                  aria-label="Users"
                  empty={<EmptyState title="No users found" />}
                  rowKey={(u) => u.id!}
                  rows={data.users.filter((u) =>
                    `${u.login} ${u.name ?? ''} ${u.email ?? ''}`.toLowerCase().includes(needle),
                  )}
                  columns={[
                    {
                      key: 'login',
                      header: 'Login',
                      cell: (u) => (
                        <>
                          {u.login}
                          {u.id === user?.id ? ' (you)' : ''}
                        </>
                      ),
                    },
                    { key: 'name', header: 'Name', cell: (u) => u.name || '—' },
                    { key: 'email', header: 'Email', cell: (u) => u.email || '—' },
                    { key: 'role', header: 'Role', cell: (u) => u.userRole?.name || '—' },
                    {
                      key: 'access',
                      header: 'Access',
                      cell: (u) => (
                        <>
                          {u.allDevicesAvailable ? 'All devices' : 'Selected device groups'} ·{' '}
                          {u.allConfigAvailable ? 'All configurations' : 'Selected configurations'}
                        </>
                      ),
                    },
                    {
                      key: 'actions',
                      header: 'Actions',
                      shrink: true,
                      cell: (u) =>
                        u.id !== user?.id &&
                        u.editable !== false &&
                        (!u.userRole?.superAdmin || user?.superAdmin || user?.userRole?.superAdmin) ? (
                          <div className="users-actions">
                            <Button size="sm" aria-label={`Edit ${u.login}`} onClick={() => setEditingUser(u)}>
                              Edit<span className="v2-visually-hidden"> {u.login}</span>
                            </Button>
                            {!u.userRole?.superAdmin && (
                              <Button
                                size="sm"
                                variant="danger"
                                aria-label={`Delete ${u.login}`}
                                onClick={() => askDelete('user', u.id!, u.login)}
                              >
                                Delete<span className="v2-visually-hidden"> {u.login}</span>
                              </Button>
                            )}
                          </div>
                        ) : (
                          '—'
                        ),
                    },
                  ]}
                />
              </div>
            )}
          </>
        )
      )}
      {editingUser && data && (
        <UserEditor
          target={editingUser}
          roles={data.assignable}
          onClose={() => setEditingUser(null)}
          onSaved={() => saved('User saved.')}
        />
      )}
      {editingRole && data && (
        <RoleEditor
          target={editingRole}
          permissions={data.permissions}
          onClose={() => setEditingRole(null)}
          onSaved={() => saved('Role saved.')}
        />
      )}
      <Modal
        open={!!deleting}
        role="alertdialog"
        title={`Delete ${deleting?.kind ?? 'account'}?`}
        dismissOnBackdrop={false}
        onClose={() => {
          if (!busy) setDeleting(null);
        }}
        footer={
          <>
            <Button disabled={busy} onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button variant="danger" disabled={busy} onClick={() => void confirmDelete()}>
              {busy ? 'Deleting…' : 'Delete'}
            </Button>
          </>
        }
      >
        <p>
          Delete “{deleting?.name}”?{' '}
          {deleting?.kind === 'user'
            ? 'This account will lose access to the console.'
            : 'A role can be deleted only when no users are assigned to it.'}
        </p>
        {deleteError && (
          <Callout tone="alert" role="alert">
            {deleteError}
          </Callout>
        )}
      </Modal>
    </Card>
  );
}
