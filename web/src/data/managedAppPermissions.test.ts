import { describe, expect, it } from 'vitest';
import { manageableAppPermissions } from './managedAppPermissions';

describe('manageableAppPermissions', () => {
  it('keeps curated runtime-permission candidates and excludes special app access', () => {
    expect(
      manageableAppPermissions(
        'android.permission.ACCESS_COARSE_LOCATION,android.permission.POST_NOTIFICATIONS,android.permission.WRITE_EXTERNAL_STORAGE,android.permission.MANAGE_EXTERNAL_STORAGE',
        [],
      ),
    ).toEqual([
      'android.permission.ACCESS_COARSE_LOCATION',
      'android.permission.POST_NOTIFICATIONS',
      'android.permission.WRITE_EXTERNAL_STORAGE',
    ]);
  });

  it('offers Jellyfin candidates, which the agent resolves independently on each device', () => {
    expect(
      manageableAppPermissions('android.permission.BLUETOOTH_CONNECT,android.permission.POST_NOTIFICATIONS,android.permission.INTERNET', []),
    ).toEqual(['android.permission.BLUETOOTH_CONNECT', 'android.permission.POST_NOTIFICATIONS']);
  });

  it('retains an existing rule so it can be returned to Default', () => {
    expect(manageableAppPermissions(null, ['android.permission.CAMERA'])).toEqual(['android.permission.CAMERA']);
  });
});
