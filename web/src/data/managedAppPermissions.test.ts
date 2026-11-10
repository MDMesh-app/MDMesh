import { describe, expect, it } from 'vitest';
import { manageableAppPermissions } from './managedAppPermissions';

describe('manageableAppPermissions', () => {
  it('keeps only controls the current agent manages from a raw APK manifest', () => {
    expect(
      manageableAppPermissions(
        'android.permission.ACCESS_COARSE_LOCATION,android.permission.POST_NOTIFICATIONS,android.permission.WRITE_EXTERNAL_STORAGE',
        [],
      ),
    ).toEqual(['android.permission.ACCESS_COARSE_LOCATION']);
  });

  it('does not turn Jellyfin manifest declarations into nearby-device or notification controls', () => {
    expect(
      manageableAppPermissions('android.permission.BLUETOOTH_CONNECT,android.permission.POST_NOTIFICATIONS,android.permission.INTERNET', []),
    ).toEqual([]);
  });

  it('retains an existing rule so it can be returned to Default', () => {
    expect(manageableAppPermissions(null, ['android.permission.CAMERA'])).toEqual(['android.permission.CAMERA']);
  });
});
