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

  // --- v2 settings fixtures ---
  // Release notes, date and link for the ready update (the supervisor's `release`), in the GitHub release-body
  // shape the notes renderer parses (title line, section lines, "- " bullets with wrapped lines).
  updateStatus.applySupported = true;
  updateStatus.autoSkipped = null;
  updateStatus.release = {
    notes: [
      'v0.1.15',
      '',
      'New',
      '- Devices can be moved between configurations from the bulk bar, and the move applies right away.',
      '- The rollout picker searches the whole fleet.',
      '',
      'Fixes',
      '- Agent updates that got stuck are sent again, and the agent no longer freezes waiting on an',
      '  install.',
    ].join('\n'),
    url: 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.1.15',
    publishedAt: new Date(now - 6 * day).toISOString(),
  };
  // --- end v2 settings fixtures ---

  const authOptions = { signup: false, recover: false };

  return { user, configurations, configList, devices, events, state, telemetry, locations, applications, updateStatus, activeRollout, authOptions };
}

// --- v2 overview fixtures ---
// Configuration sync for the Overview (and the classic Dashboard, which asks the same endpoints): per-configuration
// summary rows and per-device configStatus, consistent with the devices above (online = seen in the last 10 min).
export function buildOverviewFixtures() {
  const rev = (c) => c.repeat(64);
  const ok = (configurationId) => ({
    configurationId, currentRevision: rev('a'), appliedRevision: rev('a'), appliedAt: Date.now() - 3_600_000,
    inSync: true, supported: true, lastCommand: { id: 30 + configurationId, status: 'done', detail: null },
  });
  const syncSummary = [
    { configurationId: 1, total: 3, inSync: 2, outOfSync: 0, unsupported: 0, neverSeen: 1, deferred: 0 },
    { configurationId: 2, total: 3, inSync: 1, outOfSync: 1, unsupported: 1, neverSeen: 0, deferred: 0 },
    { configurationId: 3, total: 2, inSync: 1, outOfSync: 1, unsupported: 0, neverSeen: 0, deferred: 0 },
  ];
  const configStatus = {
    'WH-SCAN-07': ok(1),
    'WH-SCAN-09': ok(1),
    'KIOSK-FRONT': ok(2),
    // Last config.apply failed (USB storage needs device owner).
    'KIOSK-CAFE': {
      configurationId: 2, currentRevision: rev('b'), appliedRevision: rev('a'), appliedAt: Date.now() - 86_400_000,
      inSync: false, supported: true,
      lastCommand: { id: 41, status: 'failed', detail: JSON.stringify({ revision: rev('b'), outcomes: { usbStorage: 'failed: needs device owner' } }) },
    },
    // Agent without config.apply.
    'LOBBY-PAD': { configurationId: 2, currentRevision: rev('b'), appliedRevision: null, inSync: false, supported: false, lastCommand: null },
  };
  return { syncSummary, configStatus };
}
// --- v2 device-detail fixtures ---
// Extra data for /devices/:id: the agent's real telemetry keys (merged over the classic sample, whose
// `storage` / `localIp` keys the agent never sends), a configuration status with per-key outcomes, a command
// history and the configuration itself. Test data only.
export function buildDeviceDetailFixtures(base, now = Date.now()) {
  const min = 60_000, hour = 60 * min, day = 24 * hour;
  const telemetry = {
    ...base.telemetry,
    publicIp: '203.0.113.42',
    hardware: {
      ...base.telemetry.hardware,
      manufacturer: 'Zebra', brand: 'Zebra', model: 'TC52', device: 'TC52', sdkInt: 33,
      securityPatch: '2026-08-01', buildFingerprint: 'Zebra/TC52/TC52:13/13-34-14.00-TN-U00-STD/1:user/release-keys',
      abis: ['arm64-v8a', 'armeabi-v7a'], totalRamBytes: 4_000_000_000, totalStorageBytes: 32_000_000_000,
      screenWidthPx: 720, screenHeightPx: 1280, densityDpi: 320,
    },
    dynamic: {
      ...base.telemetry.dynamic,
      networkType: 'wifi', wifiSsid: 'WH-North', wifiBssid: '3c:84:6a:12:9e:41', ipAddress: '10.20.4.51',
      wifiRssi: -58, wifiLinkSpeedMbps: 144, roaming: false,
      batteryPct: 84, batteryHealth: 'good', batteryTempC: 29.5, batteryVoltageMv: 4182, chargingSource: 'usb',
      freeStorageBytes: 13_800_000_000, freeRamBytes: 1_600_000_000,
      screenOn: true, locked: false, kioskActive: false, foregroundApp: 'com.acme.fieldops',
      uptimeMs: 3 * hour + 12 * min, lastBootAt: now - 3 * hour - 12 * min,
    },
    security: {
      ...base.telemetry.security,
      storageEncrypted: true, deviceSecure: true, adbEnabled: false, devOptionsEnabled: false,
      unknownSourcesAllowed: false, patchAgeDays: 75,
    },
    identity: { ...base.telemetry.identity, imei: ['359021084472016'], imsi: [], iccid: [], phoneNumber: [] },
  };
  const configStatus = {
    configurationId: 1, currentRevision: '8b04d7aa5c1e9f3b2d7a6c4e1f0b9a8d7c6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a',
    appliedRevision: '3f9a2c1e7b6d5a4c3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d',
    appliedAt: now - 2 * hour, inSync: false, supported: true,
    lastCommand: {
      id: 412, status: 'failed', createdAt: now - 4 * min, completedAt: now - 3 * min,
      detail: JSON.stringify({
        revision: '8b04d7aa5c1e9f3b2d7a6c4e1f0b9a8d7c6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a',
        outcomes: {
          kiosk: 'applied', 'policies.kioskHome': 'applied', 'policies.iconSize': 'applied',
          'policies.disableScreenshots': 'applied', 'policies.usbStorage': 'failed: permission denied',
          'policies.bluetooth': 'unsupported',
        },
      }),
    },
  };
  const commands = [
    { id: 412, type: 'config.apply', status: 'failed', createdAt: now - 4 * min, completedAt: now - 3 * min, detail: configStatus.lastCommand.detail },
    { id: 411, type: 'device.ring', status: 'done', createdAt: now - 5 * min, deliveredAt: now - 4 * min, completedAt: now - 3 * min },
    { id: 405, type: 'app.install', status: 'done', subject: 'com.acme.fieldops', createdAt: now - 2 * hour - 5 * min, completedAt: now - 2 * hour },
    { id: 398, type: 'device.lock', status: 'expired', createdAt: now - 2 * day, completedAt: now - day - 2 * hour, detail: 'Not delivered within 24h' },
    { id: 390, type: 'device.reboot', status: 'done', createdAt: now - 3 * day - 10 * min, completedAt: now - 3 * day },
    { id: 381, type: 'device.lockscreenMessage', status: 'done', createdAt: now - 4 * day, completedAt: now - 4 * day },
  ];
  const configuration = { id: 1, name: 'Default', requestUpdates: 'WIFI', kioskMode: false, applications: [] };
  return { telemetry, configStatus, commands, configuration };
}

/** REST answers for the v2 device-detail page, or undefined to fall through to the shared fixtures. */
export function deviceDetailRest(method, p, dd) {
  if (method === 'GET' && /\/devices\/[^/]+\/telemetry$/.test(p)) return dd.telemetry;
  if (method === 'GET' && /\/devices\/[^/]+\/configStatus$/.test(p)) return dd.configStatus;
  if (method === 'GET' && /\/agent\/v1\/devices\/[^/]+\/commands/.test(p)) return dd.commands;
  if (method === 'GET' && /\/private\/configurations\/1$/.test(p)) return dd.configuration; // device 101's configuration
  if (method === 'POST' && /\/devices\/[^/]+\/syncApps$/.test(p)) return { queued: 2 };
  if (method === 'POST' && /\/agent\/v1\/devices\/[^/]+\/commands$/.test(p)) return { id: 413, status: 'pending' };
  return undefined;
}
// --- end v2 device-detail fixtures ---
// --- v2 configurations fixtures ---
// Full configuration rows for /configurations/search (same ids and names as configList, plus the fields the
// configuration list and editor read), each configuration's app matrix (/configurations/applications/{id}: every
// library app with `selected`), and the per-configuration sync summary. `fx` is buildFixtures()'s result.
export function configurationsFixtures(fx) {
  const base = {
    type: 0, pushOptions: 'mqttWorker', appPermissions: 'GRANTALL', downloadUpdates: 'UNLIMITED',
    desktopHeader: 'NO_HEADER', defaultFilePath: '/', systemUpdateType: 0, useDefaultDesignSettings: true,
    kioskMode: false, password: '', defaultKioskPin: false, iconSize: 'SMALL', requestUpdates: 'DONOTTRACK',
    wifi: null, wifiRadio: null, bluetooth: null, usbStorage: null, disableScreenshots: null,
    disableFactoryReset: null, disableSafeBoot: null, mainAppId: null, baseUrl: 'https://mdm.acme.example',
  };
  const full = {
    1: { description: 'Warehouse scanners and shared handhelds. USB storage blocked, no location tracking.', usbStorage: false },
    2: {
      description: 'Front-desk and café screens pinned to Signage Player. Status bar shown, power menu locked.',
      kioskMode: true, password: '12345678', defaultKioskPin: true, mainAppId: 2710,
      kioskExit: null, kioskHome: null, kioskRecents: false, kioskNotifications: null, kioskSystemInfo: true,
      kioskKeyguard: null, kioskLockButtons: true, kioskLockVolume: null,
      backgroundColor: '#15181D', textColor: '#FFFFFF', iconSize: 'LARGE',
      wifi: true, bluetooth: false, requestUpdates: 'WIFI', usbStorage: false, disableScreenshots: true,
      disableFactoryReset: true, disableSafeBoot: true,
      wifiSSID: 'acme-setup', wifiPassword: 'setup-2026', wifiSecurityType: 'WPA',
    },
    3: { description: 'Field tablets on the road. GPS location reporting, screenshots blocked.', requestUpdates: 'GPS', disableScreenshots: true },
  };
  const configs = fx.configList.map((c) => ({ ...base, ...c, qrCodeKey: fx.configurations[c.id]?.qrCodeKey, ...full[c.id] }));
  // configId → { appId: [action, showIcon, usedVersionId] }
  const picks = {
    1: { 1: [1, true, 16], 5: [1, true, 1130] },
    2: { 6: [1, true, 2710], 3: [1, false, 6000], 4: [0, true, 100] },
    3: { 2: [1, true, 420], 3: [1, true, 6000], 4: [1, true, 100] },
  };
  const appsFor = (id) => fx.applications.map((a) => {
    const p = picks[id]?.[a.id];
    // The Headwind launcher layout columns ride along on assigned rows: a save must send them back as loaded.
    const layout = p ? { screenOrder: a.id, keyCode: a.id === 3 ? 131 : null, bottom: a.id === 4, longTap: false } : {};
    return { ...a, selected: !!p, action: p ? p[0] : 1, showIcon: p ? p[1] : true, usedVersionId: p ? p[2] : undefined, remove: false, ...layout };
  });
  const syncSummary = [
    { configurationId: 1, total: 3, inSync: 2, outOfSync: 0, unsupported: 1, neverSeen: 0, deferred: 0 },
    { configurationId: 2, total: 3, inSync: 3, outOfSync: 0, unsupported: 0, neverSeen: 0, deferred: 0 },
    { configurationId: 3, total: 2, inSync: 1, outOfSync: 0, unsupported: 0, neverSeen: 1, deferred: 0 },
  ];
  // GET /configurations/{id} (getConfigurationByIdFull) adds what the /search rows lack: configuration files, app
  // settings and app usage parameters, with `applications` empty (the editor reads them from
  // /configurations/applications/{id}). Plus a Headwind-only value and a key the console does not know, so the
  // parity tests can check a save sends all of it back untouched.
  const fullExtras = (id) => ({
    applications: [],
    files: [{
      id: 500 + id, configurationId: id, fileId: 90 + id, description: 'Wi-Fi certificate', path: '/sdcard/certs',
      overridePath: true, remove: false, lastUpdate: 1767225600000, url: 'https://mdm.acme.example/files/acme-ca.pem',
    }],
    applicationSettings: [{
      id: 700 + id, extRefId: id, applicationId: 6, name: 'player.url', type: 1,
      value: 'https://signage.acme.example/lobby', comment: null, readonly: false, lastUpdate: 1767225600000, variable: false,
    }],
    applicationUsageParameters: [{ id: 800 + id, configurationId: id, applicationId: 6, skipVersionCheck: true }],
    timeZone: 'Europe/Berlin',
    futureServerSetting: { mode: 'strict', levels: [1, 2] },
  });
  return { configs, appsFor, syncSummary, fullExtras };
}
// A configuration with a long app list (14), for the editor's search, On devices filter and sort. Only the parity
// test that asks for it serves it (as configuration 4), so no other page or screenshot sees it.
export function manyAppsFixture(cfx) {
  const id = 4;
  // [id, name, pkg, version, action]: 8 Install, 3 Do not install, 3 Remove, not in name order.
  const rows = [
    [1, 'MDMesh Agent', 'com.lunacy.mdm.agent', '0.1.15', 1],
    [3, 'Chrome', 'com.android.chrome', '120.0', 1],
    [21, 'Zebra Scanner', 'com.zebra.scanner', '3.1', 1],
    [2, 'Field Ops', 'com.acme.fieldops', '4.2.0', 1],
    [4, 'Microsoft Teams', 'com.microsoft.teams', '1.0.0', 0],
    [22, 'Barcode Reader', 'org.scan.barcode', '2.4', 2],
    [23, 'Maps', 'com.google.android.apps.maps', '11.9', 1],
    [24, 'Camera', 'com.acme.camera', '1.3', 0],
    [6, 'Signage Player', 'com.acme.signage', '2.7.1', 1],
    [25, 'Notes', 'com.acme.notes', '5.0', 1],
    [26, 'Wallet', 'com.acme.wallet', '1.8', 2],
    [27, 'Delivery Log', 'com.acme.deliverylog', '0.9', 1],
    [28, 'Old Launcher', 'com.acme.launcher.legacy', '0.4', 2],
    [29, 'Time Clock', 'com.acme.timeclock', '2.2', 0],
  ];
  const apps = rows.map(([appId, name, pkg, version, action]) => ({
    id: appId, name, pkg, version, type: 'app', selected: true, action, showIcon: true, usedVersionId: 1000 + appId,
    remove: false, screenOrder: appId, keyCode: null, bottom: false, longTap: false,
  }));
  const [base] = cfx.configs;
  const config = { ...base, ...cfx.fullExtras(id), id, name: 'Shared tablets', description: 'Tablets shared across shifts.', kioskMode: false };
  return { id, config, apps };
}
// --- end v2 configurations fixtures ---
// --- v2 apps fixtures ---
// Library apps of every source the v2 Apps page infers (hosted upload, link with and without a SHA-256, split
// bundle, f-droid.org), each app's configuration links (/applications/configurations/{id}), one installable version
// per app, and an F-Droid search result (14 apps, one without a checksum, two already in the library). Test data only.
export function buildAppsFixtures(base) {
  const host = 'https://mdm.acme.example';
  const cfgName = Object.fromEntries(base.configList.map((c) => [c.id, c.name]));
  const fdroid = (pkg, vc) => `https://f-droid.org/repo/${pkg}_${vc}.apk`;
  // id, name, pkg, version, versionCode, url, hasApkHash, parts, used-in configuration ids
  const rows = [
    [1, 'MDMesh Agent', 'com.lunacy.mdm.agent', '0.1.15', 16, `${host}/files/com.lunacy.mdm.agent.apk`, true, null, [1, 2, 3]],
    [2, 'Field Ops', 'com.acme.fieldops', '4.2.0', 420, `${host}/files/com.acme.fieldops.apk`, false, null, [3]],
    [3, 'Chrome', 'com.android.chrome', '120.0', 6000, 'https://dl.acme-cdn.example/chrome-120.apk', true, null, [2, 3]],
    [4, 'Microsoft Teams', 'com.microsoft.teams', '1.0.0', 100, 'https://downloads.acme-cdn.example/teams-1.0.0.apk', false, null, []],
    [5, 'Zebra DataWedge', 'com.symbol.datawedge', '11.3', 1130, undefined, false, 3, [1]],
    [6, 'Signage Player', 'com.acme.signage', '2.7.1', 271, `${host}/files/com.acme.signage.apk`, false, null, [2]],
    [7, 'Organic Maps', 'app.organicmaps', '2025.03.02', 25030200, fdroid('app.organicmaps', 25030200), true, null, [3]],
    [8, 'Binary Eye', 'de.markusfisch.android.binaryeye', '1.63.6', 135, fdroid('de.markusfisch.android.binaryeye', 135), true, null, []],
  ];
  const parts = (pkg, n) =>
    JSON.stringify(Array.from({ length: n }, (_, i) => ({
      url: `${host}/files/bundles/${pkg}/split_${i}.apk`, sha256: `${String(i).repeat(2)}${'ab'.repeat(31)}`, name: i ? `split_config.${['arm64_v8a', 'xxhdpi'][i - 1]}.apk` : 'base.apk',
    })));
  const applications = rows.map(([id, name, pkg, version, versionCode, url, hasApkHash, n, used]) => ({
    id, name, pkg, version, versionCode, type: 'app', system: false, latestVersion: id * 10,
    url, hasApkHash, parts: n ? parts(pkg, n) : undefined, deletionProhibited: used.length > 0,
    commonApplication: false,
  }));
  const links = Object.fromEntries(rows.map(([id, name, , , , , , , used]) => [id, base.configList.map((c, i) => ({
    id: used.includes(c.id) ? id * 100 + i : null, configurationId: c.id, configurationName: cfgName[c.id],
    applicationId: id, applicationName: name, action: used.includes(c.id) ? 1 : 0, showIcon: true,
  }))]));
  const versions = Object.fromEntries(applications.map((a) => [a.id, [{
    id: a.latestVersion, applicationId: a.id, version: a.version, versionCode: a.versionCode, url: a.url,
    apkHash: a.hasApkHash ? 'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0=' : undefined, parts: a.parts,
  }]]));
  const fd = (name, pkg, summary, versionName, versionCode, sha = true) => ({
    packageName: pkg, name, summary, iconUrl: null, versionName, versionCode, apkUrl: fdroid(pkg, versionCode),
    sha256: sha ? `${(versionCode % 97).toString(16).padStart(2, '0')}${'c3'.repeat(31)}` : null, size: 8_400_000,
  });
  const fdroidApps = [
    fd('AntennaPod', 'de.danoeh.antennapod', 'Podcast manager and player with sync.', '3.7.0', 3070095),
    fd('Binary Eye', 'de.markusfisch.android.binaryeye', 'Barcode scanner for many formats.', '1.63.6', 135),
    fd('Catima', 'me.hackerchick.catima', 'Keeps loyalty and membership cards on the phone.', '2.33.0', 147),
    fd('Collabora Office', 'com.collabora.libreoffice', 'Edits documents, spreadsheets and slides.', '24.04.12.2', 131),
    fd('DAVx⁵', 'at.bitfire.davdroid', 'Syncs calendars and contacts over CalDAV and CardDAV.', '4.4.8', 404080000),
    fd('Etar', 'ws.xsoh.etar', 'Calendar with agenda, day, week and month views.', '1.0.47', 47),
    fd('Feeder', 'com.nononsenseapps.feeder', 'Reads RSS and Atom feeds offline.', '2.10.2', 3200),
    fd('Fossify Gallery', 'org.fossify.gallery', 'Photo and video gallery without ads.', '1.3.1', 13),
    fd('Kiosk Notes', 'org.example.kiosknotes', 'Shows one note full screen on a shared device.', '0.4.0', 40, false),
    fd('Mull', 'us.spotco.fennec_dos', 'Privacy-focused web browser.', '134.0.1', 21340120),
    fd('Organic Maps', 'app.organicmaps', 'Offline maps and navigation built on OpenStreetMap.', '2025.03.02', 25030200),
    fd('Simple Keyboard', 'rkr.simplekeyboard.inputmethod', 'Light keyboard without network access.', '6.5', 117),
    fd('Termux', 'com.termux', 'Terminal and Linux packages.', '0.118.1', 1002),
    fd('Tusky', 'com.keylesspalace.tusky', 'Client for Mastodon servers.', '27.2', 125),
  ];
  return { applications, links, versions, fdroidApps, baseUrl: host };
}

/** REST answers for the v2 Apps page, or undefined to fall through to the shared fixtures. */
export function appsRest(method, p, ax) {
  let m;
  if (method === 'GET' && (p.endsWith('/applications/search') || /\/applications\/search\//.test(p))) return ax.applications;
  if (method === 'GET' && (m = p.match(/\/applications\/configurations\/(\d+)$/))) return ax.links[m[1]] ?? [];
  if (method === 'GET' && (m = p.match(/\/applications\/(\d+)\/versions$/))) return ax.versions[m[1]] ?? [];
  if (method === 'GET' && p.endsWith('/fdroid/search')) return ax.fdroidApps;
  if (method === 'PUT' && p.endsWith('/applications/android')) return { id: 900, name: 'Saved app', pkg: 'saved.app' };
  return undefined;
}
// --- end v2 apps fixtures ---
// --- v2 enroll fixtures ---
// Enroll page: full configuration rows (enrollment Wi-Fi saved on Field Ops) and the minted single-use token
// (POST /private/agent/v1/token, 24 h TTL like the server).
export function buildEnrollFixtures(fx, now = Date.now()) {
  const wifi = {
    3: { wifiSSID: 'Field-Setup', wifiPassword: 'field-setup-2026', wifiSecurityType: 'WPA' },
  };
  const configs = fx.configList.map((c) => ({ ...c, qrCodeKey: fx.configurations[c.id]?.qrCodeKey, ...(wifi[c.id] ?? {}) }));
  let n = 0;
  const token = () => {
    n += 1;
    return { id: n, token: `5f0c9a2e-7b41-4d8e-9c3a-${String(n).padStart(12, '0')}`, used: false, createdAt: now, expiresAt: now + 24 * 3_600_000 };
  };
  return { configs, token, wifi };
}
// --- end v2 enroll fixtures ---
