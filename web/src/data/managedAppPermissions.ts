/**
 * Curated Android runtime permissions that the Device Owner agent can attempt
 * to apply. An APK manifest is only a candidate list: the installed version
 * and Android release decide whether a specific device can actually apply it.
 */
export const MANAGED_APP_PERMISSION_LABELS: Record<string, string> = {
  'android.permission.ACCESS_COARSE_LOCATION': 'Approximate location',
  'android.permission.ACCESS_FINE_LOCATION': 'Precise location',
  'android.permission.CAMERA': 'Camera',
  'android.permission.RECORD_AUDIO': 'Microphone',
  'android.permission.READ_CONTACTS': 'Read contacts',
  'android.permission.WRITE_CONTACTS': 'Edit contacts',
  'android.permission.READ_CALENDAR': 'Read calendar',
  'android.permission.WRITE_CALENDAR': 'Edit calendar',
  'android.permission.READ_PHONE_STATE': 'Phone state',
  'android.permission.READ_PHONE_NUMBERS': 'Read phone numbers',
  'android.permission.CALL_PHONE': 'Make calls',
  'android.permission.READ_CALL_LOG': 'Read call log',
  'android.permission.WRITE_CALL_LOG': 'Edit call log',
  'android.permission.ADD_VOICEMAIL': 'Add voicemail',
  'android.permission.ANSWER_PHONE_CALLS': 'Answer calls',
  'android.permission.READ_SMS': 'Read SMS',
  'android.permission.RECEIVE_SMS': 'Receive SMS',
  'android.permission.SEND_SMS': 'Send SMS',
  'android.permission.RECEIVE_WAP_PUSH': 'Receive WAP push',
  'android.permission.RECEIVE_MMS': 'Receive MMS',
  'android.permission.READ_EXTERNAL_STORAGE': 'Read files and media',
  'android.permission.WRITE_EXTERNAL_STORAGE': 'Edit files and media',
  'android.permission.READ_MEDIA_IMAGES': 'Photos and images',
  'android.permission.READ_MEDIA_VIDEO': 'Videos',
  'android.permission.READ_MEDIA_AUDIO': 'Music and audio',
  'android.permission.READ_MEDIA_VISUAL_USER_SELECTED': 'Selected photos and videos',
  'android.permission.ACCESS_MEDIA_LOCATION': 'Photo location',
  'android.permission.ACTIVITY_RECOGNITION': 'Physical activity',
  'android.permission.BODY_SENSORS': 'Body sensors',
  'android.permission.BODY_SENSORS_BACKGROUND': 'Background body sensors',
  'android.permission.POST_NOTIFICATIONS': 'Notifications',
  'android.permission.BLUETOOTH_SCAN': 'Find nearby devices',
  'android.permission.BLUETOOTH_CONNECT': 'Connect nearby devices',
  'android.permission.BLUETOOTH_ADVERTISE': 'Advertise nearby devices',
  'android.permission.NEARBY_WIFI_DEVICES': 'Nearby Wi-Fi devices',
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
