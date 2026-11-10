/**
 * The small permission surface the current agent can reliably apply with
 * DevicePolicyManager.  An APK manifest is only a declaration, not the
 * device's effective permission list, so do not expose every manifest entry
 * as a control here.
 */
export const MANAGED_APP_PERMISSION_LABELS: Record<string, string> = {
  'android.permission.ACCESS_COARSE_LOCATION': 'Approximate location',
  'android.permission.ACCESS_FINE_LOCATION': 'Precise location',
  'android.permission.CAMERA': 'Camera',
  'android.permission.RECORD_AUDIO': 'Microphone',
  'android.permission.READ_CONTACTS': 'Read contacts',
};

const declared = (requestedPermissions: string | null | undefined): string[] =>
  typeof requestedPermissions === 'string'
    ? requestedPermissions.split(',').map((permission) => permission.trim()).filter((permission) => permission in MANAGED_APP_PERMISSION_LABELS)
    : [];

/**
 * Permission controls inferred from a locally stored APK, plus an existing
 * rule so an administrator can always remove a previously saved override.
 */
export function manageableAppPermissions(
  requestedPermissions: string | null | undefined,
  configuredPermissions: readonly string[],
): string[] {
  return [...new Set([...declared(requestedPermissions), ...configuredPermissions])];
}

export const managedAppPermissionLabel = (permission: string): string =>
  MANAGED_APP_PERMISSION_LABELS[permission] ?? permission;
