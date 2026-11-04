# Console users and roles

Open **Settings → Users & roles** (`/settings#users`). The section needs the server and console from the same
release; it uses the existing database tables and needs no schema migration or agent update.

## Accounts

Super-admins and organization admins can create, edit and delete accounts within the server's existing organization
boundaries. The section and its Settings navigation link appear only for account administrators: a role with the
`settings` permission alone does not grant account administration. The server reports administration capabilities
through `GET /rest/private/users/management`; the console does not assume an admin role ID.

Create an account with a unique login, a password, a role and optionally a name and email. The console asks for a
password of at least 8 characters, as Change password does. Leave both password fields empty when editing to keep the
existing password. Setting another account's password ends its existing sessions and applies the server's password
reset policy. An edit without a password preserves any pending password reset.

Access can cover all devices/configurations, or selected device groups/configurations. A restricted list with nothing
selected grants access to none. The editor loads the account's complete access lists before allowing a save, and an
unavailable access-options request blocks saving so it cannot silently replace restrictions.

Your own account cannot be edited or deleted here; change your password in **Settings**. Only a super-admin can edit
or delete a super-admin account. The console shows no Delete button for super-admin accounts, but the server accepts
the deletion from another super-admin. The console submits explicit account fields only, never account secrets or
role permission objects.

## Roles

On a single-customer installation, an organization admin or super-admin can create roles and choose their permissions.
On a multi-customer installation, global role management is reserved for super-admins; organization admins can still
assign the available roles to accounts in their organization.

The role editor lists only editable roles. The built-in organization-admin and super-admin roles are protected on the
server, including against direct API calls. Only permission IDs from the server's assignable permission list are
accepted. A role assigned to any account cannot be deleted: reassign the accounts first. Role and permission changes
are transactional. Role descriptions survive list/edit/save round trips.

Permission checks run on every server request. An open console refreshes its role when the tab becomes visible again
or the page is reloaded, as in v0.4.0.
