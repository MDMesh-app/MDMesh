// Representative sample data for the MDMesh docs screenshots. The harness renders the REAL SPA and
// answers its REST calls with these fixtures (Playwright request interception). Not a live fleet —
// believable demo data so the UI shows something worth looking at.

export function buildFixtures(now = Date.now()) {
  const min = 60_000, hour = 60 * min, day = 24 * hour;

  const user = {
    id: 1, login: 'admin', name: 'Operations', email: 'ops@acme.example',
    superAdmin: true, userRole: { superAdmin: true, permissions: [] },
  };

  const configurations = {
    1: { id: 1, name: 'Default', qrCodeKey: 'def' },
    2: { id: 2, name: 'Retail Kiosk', qrCodeKey: 'kio' },
    3: { id: 3, name: 'Field Ops', qrCodeKey: 'fld' },
  };
  const configList = [
    { id: 1, name: 'Default' }, { id: 2, name: 'Retail Kiosk' }, { id: 3, name: 'Field Ops' },
  ];

  const devices = [
    d(101, 'WH-SCAN-07', 1, 'Warehouse scanner', 'green', now - 2 * min, '13', false, ['Warehouse']),
    d(102, 'KIOSK-FRONT', 2, 'Front-desk kiosk', 'green', now - 40 * 1000, '14', true, ['Retail']),
    d(103, 'FIELD-1242', 3, 'Field tablet — North', 'yellow', now - 22 * min, '12', false, ['Field']),
    d(104, 'KIOSK-CAFE', 2, 'Café menu board', 'green', now - 5 * min, '13', true, ['Retail']),
    d(105, 'WH-SCAN-09', 1, 'Warehouse scanner', 'green', now - 3 * min, '13', false, ['Warehouse']),
    d(106, 'FIELD-1247', 3, 'Field tablet — South', 'red', now - 3 * hour, '11', false, ['Field']),
    d(107, 'LOBBY-PAD', 2, 'Lobby check-in', 'green', now - 90 * 1000, '14', true, ['Retail']),
    d(108, 'WH-SCAN-11', 1, 'Warehouse scanner', 'grey', now - 6 * day, '12', false, ['Warehouse']),
  ];

  function d(id, number, configurationId, description, statusCode, lastUpdate, androidVersion, kioskMode, groupNames) {
    return {
      id, number, configurationId, description, statusCode, lastUpdate, androidVersion, kioskMode,
      enrollTime: now - 40 * day, mdmMode: true, launcherVersion: '0.1.15',
      groups: groupNames.map((n, i) => ({ id: i + 1, name: n })),
    };
  }

  const events = [
    ev(9, 'enrolled', now - 40 * day, null),
    ev(8, 'boot', now - 3 * hour, null),
    ev(7, 'appInstalled', now - 2 * hour, 'com.acme.fieldops 4.2.0'),
    ev(6, 'connectivityChange', now - 55 * min, 'Wi-Fi → LTE'),
    ev(5, 'commandResult', now - 40 * min, 'device.lockscreenMessage · done'),
    ev(4, 'commandResult', now - 22 * min, 'app.install · done'),
    ev(3, 'lowBattery', now - 12 * min, '14%'),
    ev(2, 'commandResult', now - 6 * min, 'kiosk.enter · done'),
    ev(1, 'boot', now - 2 * min, null),
  ];
  function ev(id, type, ts, detail) { return { id, type, ts, detail }; }

  const state = {
    battery: 84, charging: true, locked: false, kioskActive: false,
    androidRelease: '13', lastBootAt: now - 3 * hour, updatedAt: now - 30 * 1000,
    agentVersion: '0.1.15', powerMode: 'adaptive',
  };

  const telemetry = {
    dynamic: {
      networkType: 'Wi-Fi', network: 'acme-corp', localIp: '10.20.4.51', publicIp: '203.0.113.42',
      location: { lat: 37.7793, lon: -122.4192, accuracyM: 12, provider: 'fused', capturedAt: now - 4 * min },
    },
    hardware: { osRelease: '13', storage: '64 GB', storageFree: '41 GB', localIp: '10.20.4.51' },
    identity: { serial: 'ZX1G42・8K', imei: '35 902 108 447 201' },
    security: { isDeviceOwner: true },
  };

  const locations = [
    loc(37.7793, -122.4192, now - 4 * min), loc(37.7799, -122.4185, now - 30 * min),
    loc(37.7782, -122.4205, now - 70 * min), loc(37.7771, -122.4218, now - 110 * min),
  ];
  function loc(lat, lon, capturedAt) { return { lat, lon, accuracy: 12, provider: 'fused', capturedAt }; }

  const applications = [
    app(1, 'MDMesh Agent', 'com.lunacy.mdm.agent', '0.1.15', 16),
    app(2, 'Field Ops', 'com.acme.fieldops', '4.2.0', 420),
    app(3, 'Chrome', 'com.android.chrome', '120.0', 6000),
    app(4, 'Microsoft Teams', 'com.microsoft.teams', '1.0.0', 100),
    app(5, 'Zebra DataWedge', 'com.symbol.datawedge', '11.3', 1130),
    app(6, 'Signage Player', 'com.acme.signage', '2.7.1', 271),
  ];
  function app(id, name, pkg, version, versionCode) {
    return { id, name, pkg, version, versionCode, type: 'app', url: `https://mdm.acme.example/files/${pkg}.apk` };
  }

  const updateStatus = {
    current: '0.1.14', latest: '0.1.15', updateAvailable: true, verified: true,
    channel: 'stable', checkedAt: now - 18 * min, error: null, apply: null, auto: false,
    apk: { version: '0.1.15', versionCode: 16, sha256: 'b3a1…f29c', available: true },
  };

  const activeRollout = {
    id: 7, targetVersion: '0.1.15', packageName: 'com.lunacy.mdm.agent', apkVersionCode: 16,
    stage: 'canary', createdAt: now - 20 * min, updatedAt: now - 2 * min,
    progress: {
      stage: 'canary', targetVersion: '0.1.15',
      canary: { total: 4, updated: 2, pending: 1, outstanding: 1, ineligible: 0 },
      fleet: null,
    },
  };

  const authOptions = { signup: false, recover: false };

  return { user, configurations, configList, devices, events, state, telemetry, locations, applications, updateStatus, activeRollout, authOptions };
}
