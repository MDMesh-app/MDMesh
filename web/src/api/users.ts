import SparkMD5 from 'spark-md5';
import { apiClient } from './client';

export interface Permission {
  id: number;
  name: string;
}
export interface UserRole {
  id?: number;
  name: string;
  description?: string;
  superAdmin?: boolean;
  permissions: Permission[];
}
export interface AccessItem {
  id: number;
  name: string;
}
export interface ManagedUser {
  id?: number;
  login: string;
  name?: string;
  email?: string;
  userRole: UserRole;
  allDevicesAvailable: boolean;
  allConfigAvailable: boolean;
  groups: AccessItem[];
  configurations: AccessItem[];
  passwordReset?: boolean;
  editable?: boolean;
}
export interface ManagementCapabilities {
  users: boolean;
  roles: boolean;
}
export const getManagementCapabilities = (signal?: AbortSignal) =>
  apiClient.get<ManagementCapabilities>('/private/users/management', signal);
export const listUsers = (signal?: AbortSignal) => apiClient.get<ManagedUser[]>('/private/users/all', signal);
export const getUser = (id: number, signal?: AbortSignal) => apiClient.get<ManagedUser>(`/private/users/${id}`, signal);
export const listAssignableRoles = (signal?: AbortSignal) => apiClient.get<UserRole[]>('/private/users/roles', signal);
export const listEditableRoles = (signal?: AbortSignal) => apiClient.get<UserRole[]>('/private/roles/all', signal);
export const listPermissions = (signal?: AbortSignal) =>
  apiClient.get<Permission[]>('/private/roles/permissions', signal);
export const listGroups = (signal?: AbortSignal) => apiClient.get<AccessItem[]>('/private/groups/search', signal);
export const listAccessConfigurations = (signal?: AbortSignal) =>
  apiClient.get<AccessItem[]>('/private/configurations/list', signal);
export async function saveUser(user: ManagedUser, password: string): Promise<void> {
  // Explicit fields only: never round-trip account secrets or client-supplied role permissions.
  await apiClient.put('/private/users', {
    id: user.id,
    login: user.login.trim(),
    name: user.name?.trim(),
    email: user.email?.trim(),
    userRole: { id: user.userRole.id },
    allDevicesAvailable: user.allDevicesAvailable,
    allConfigAvailable: user.allConfigAvailable,
    groups: user.groups.map(({ id }) => ({ id })),
    configurations: user.configurations.map(({ id }) => ({ id })),
    ...(password ? { newPassword: SparkMD5.hash(password).toUpperCase() } : {}),
  });
}
export const deleteUser = (id: number) => apiClient.del<void>(`/private/users/other/${id}`);
export const saveRole = (role: UserRole) =>
  apiClient.put<void>('/private/roles', {
    id: role.id,
    name: role.name.trim(),
    description: role.description?.trim(),
    permissions: role.permissions.map(({ id }) => ({ id })),
  });
export const deleteRole = (id: number) => apiClient.del<void>(`/private/roles/${id}`);

const messages: Record<string, string> = {
  'error.duplicate.login': 'That login is already in use.',
  'error.duplicate.email': 'That email address is already in use.',
  'error.duplicate.role': 'A role with that name already exists.',
  'error.role.protected': 'Administrator roles cannot be changed or deleted.',
  'error.role.in.use': 'This role is assigned to users. Assign them another role before deleting it.',
  'error.role.permission': 'The selected permissions are not valid. Reload the page and try again.',
  'error.role.name': 'Enter a role name of up to 50 characters.',
  'error.role.save': 'Could not save the role. Reload and try again.',
  'error.role.delete': 'Could not delete the role. It may still be assigned to users.',
  'error.password.empty': 'Enter a password for the new account.',
  'error.user.login': 'Enter a login.',
  'error.user.not.found': 'This account no longer exists. Reload the page.',
};
export function managementError(error: unknown): string {
  const message = error instanceof Error ? error.message : 'Could not complete the operation.';
  return messages[message] ?? message;
}
