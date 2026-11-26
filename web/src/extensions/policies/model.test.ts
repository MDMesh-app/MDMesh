import { describe, expect, it } from 'vitest';
import { brandSummary, offPolicy } from './groups';
import {
  CONTROLS,
  compliance,
  controlState,
  controlValue,
  deviceBrand,
  allowedPackages,
  findExtras,
  brandLabel,
  groupForDevice,
  setCustomBrandNames,
  matchingPreset,
  newOnHost,
  normalizeBrand,
  parsePolicy,
  planAllowList,
  POLICY_MAX_BYTES,
  policyBytes,
  PRESETS,
  protectionSummary,
  removable,
  syncState,
  unsupportedSettings,
  type AppControl,
  type ConfigAppRow,
  type LibraryApp,
} from './model';

const control = (over: Partial<AppControl> = {}): AppControl => ({
  mode: 'remove',
  notify: true,
  exceptions: [],
  ...over,
});

describe('parsePolicy', () => {
  it('gives an empty policy for missing or malformed data', () => {
    for (const raw of [undefined, null, 'x', 3, [], { apps: 7 }]) {
      expect(parsePolicy(raw)).toEqual({ v: 1, apps: { mode: 'report', notify: true, exceptions: [] } });
    }
  });

  it('keeps valid entries and drops bad, duplicate or foreign ones', () => {
    const p = parsePolicy({
      host: ' P-7 ',
      // Unknown keys (an older version's groups, another client's fields) are dropped.
      groups: [{ id: 'oppo-family', name: 'Oppo family', brands: ['oppo'] }],
      client: 'own',
      apps: {
        mode: 'auto',
        notify: false,
        blocked: [{ pkg: 'com.a.b', label: 'A' }, { pkg: 'com.a.b' }, { pkg: 'not a package' }, { pkg: 'x' }],
        exceptions: [{ pkg: 'com.a.b', device: 'WH-1', note: 'Boss' }, { pkg: 'com.a.b', device: 'WH-1' }, { pkg: 'com.a.b' }],
      },
    });
    expect(Object.keys(p).sort()).toEqual(['apps', 'host', 'v']);
    expect(p.host).toBe('P-7');
    // An old blocklist is dropped: the allowed list is the configuration's apps now.
    expect(p.apps).toEqual({ mode: 'auto', notify: false, exceptions: [{ pkg: 'com.a.b', device: 'WH-1', note: 'Boss' }] });
  });

  it('measures policy data as the server does (UTF-8 JSON bytes)', () => {
    const p = parsePolicy({ host: 'Göteborg-1' });
    expect(policyBytes(p)).toBe(new TextEncoder().encode(JSON.stringify(p)).length);
    expect(policyBytes(p)).toBeLessThan(POLICY_MAX_BYTES);
  });

  it('keeps the reviewed host apps as valid, unique, sorted packages', () => {
    expect(parsePolicy({ hostSeen: ['com.b.app', 'com.a.app', 'com.b.app', 'not a package', 7] }).hostSeen).toEqual(['com.a.app', 'com.b.app']);
    expect('hostSeen' in parsePolicy({})).toBe(false);
  });

  it('lists apps new on the host since the review, and none before the first review', () => {
    const host = [{ pkg: 'com.whatsapp' }, { pkg: 'com.new.game', label: 'Game' }, { pkg: 'com.allowed' }];
    expect(newOnHost(host, new Set(['com.allowed']), ['com.whatsapp']).map((a) => a.pkg)).toEqual(['com.new.game']);
    expect(newOnHost(host, new Set(), undefined)).toEqual([]);
  });

  it('treats an unknown mode as report only, and leaves out a missing host', () => {
    expect(parsePolicy({ apps: { mode: 'nuke' } }).apps.mode).toBe('report');
    expect('host' in parsePolicy({ host: '  ' })).toBe(false);
  });
});

describe('brands', () => {
  it('normalizes spellings and aliases', () => {
    expect(normalizeBrand('OPPO')).toBe('oppo');
    expect(normalizeBrand(' moto ')).toBe('motorola');
    expect(normalizeBrand('HMD Global')).toBe('nokia');
    expect(normalizeBrand('')).toBe('unknown');
  });

  it('prefers telemetry, then a known first word of the description', () => {
    expect(deviceBrand({ brand: 'realme', manufacturer: 'OPPO' }, 'oppo X')).toEqual({ brand: 'realme', guessed: false });
    expect(deviceBrand({ manufacturer: 'samsung' }, null)).toEqual({ brand: 'samsung', guessed: false });
    expect(deviceBrand(null, 'Vivo Y05 marketing phone')).toEqual({ brand: 'vivo', guessed: true });
    expect(deviceBrand(null, 'Moto-G35 Extra')).toEqual({ brand: 'motorola', guessed: true });
    expect(deviceBrand(null, 'Front desk tablet')).toEqual({ brand: 'unknown', guessed: false });
  });

  it('names brands made by hand as their maker wrote them', () => {
    setCustomBrandNames([{ brand: 'acme tel', label: 'ACME Tel' }]);
    expect(brandLabel('acme tel')).toBe('ACME Tel');
    expect(brandLabel('oneplus')).toBe('OnePlus');
    setCustomBrandNames([]);
    expect(brandLabel('acme tel')).toBe('Acme Tel');
  });
});

describe('device controls', () => {
  it('maps states to columns and back for every kind', () => {
    for (const def of CONTROLS) {
      for (const s of ['unmanaged', 'allow', 'block'] as const) {
        expect(controlState(def, controlValue(def, s))).toBe(s);
      }
    }
    const shots = CONTROLS.find((c) => c.key === 'disableScreenshots')!;
    expect(controlValue(shots, 'block')).toBe(true);
    const cam = CONTROLS.find((c) => c.key === 'camera')!;
    expect(controlValue(cam, 'block')).toBe(false);
  });

  it('recognises a preset and counts protection', () => {
    const strict: Record<string, unknown> = {};
    for (const def of CONTROLS) strict[def.key] = controlValue(def, PRESETS.strict.states[def.key]);
    expect(matchingPreset(strict)).toBe('strict');
    expect(matchingPreset({ ...strict, camera: true })).toBeNull();
    expect(protectionSummary(strict)).toEqual({ blocked: 7, managed: 8, total: 9 });
    expect(protectionSummary({})).toEqual({ blocked: 0, managed: 0, total: 9 });
  });
});

describe('extra apps', () => {
  const apps = [
    { pkg: 'com.instagram.android', label: 'Instagram' },
    { pkg: 'com.coloros.gallery3d', label: 'Photos', system: true },
    { pkg: 'com.whatsapp', label: 'WhatsApp' },
    { pkg: 'com.mdmesh.agent', label: 'MDMesh' },
  ];
  const allowed = new Set(['com.whatsapp']);

  it('lists user apps outside the allowed list, minus the phone exceptions', () => {
    expect(findExtras(apps, allowed, control(), 'WH-1').map((v) => v.pkg)).toEqual(['com.instagram.android']);
    const ex = control({ exceptions: [{ pkg: 'com.instagram.android', device: 'WH-1' }] });
    expect(findExtras(apps, allowed, ex, 'WH-1')).toEqual([]);
    expect(findExtras(apps, allowed, ex, 'WH-2')).toHaveLength(1);
    expect(findExtras(apps, allowed, control({ mode: 'off' }), 'WH-1')).toEqual([]);
  });

  it('goes by the phone own system flag only, so a maker app reported as a normal app is an extra app', () => {
    const redmi = [
      { pkg: 'com.miui.calculator', label: 'Calculator' },
      { pkg: 'com.xiaomi.midrop', label: 'ShareMe' },
      { pkg: 'com.android.deskclock', label: 'Clock' },
      { pkg: 'com.truecaller', label: 'Truecaller' },
      { pkg: 'com.androidx.fake', label: 'Not Android' },
    ];
    expect(findExtras(redmi, new Set(), control(), 'R-1').map((v) => v.pkg)).toEqual([
      'com.miui.calculator',
      'com.android.deskclock',
      'com.androidx.fake',
      'com.xiaomi.midrop',
      'com.truecaller',
    ]);
  });

  it('never lists a preinstalled app or the agent, and never removes one already being removed', () => {
    const v = findExtras(apps, new Set(), control(), 'WH-1', new Set(['com.whatsapp']));
    expect(v.map((x) => x.pkg)).toEqual(['com.instagram.android', 'com.whatsapp']);
    expect(removable(v).map((x) => x.pkg)).toEqual(['com.instagram.android']);
  });
});

describe('the allowed list', () => {
  const rows: ConfigAppRow[] = [
    { id: 1, pkg: 'com.android.dialer', name: 'Phone', action: 1, showIcon: true, usedVersionId: 10, screenOrder: 3 },
    { id: 2, pkg: 'com.android.chrome', name: 'Chrome', action: 1, showIcon: true, usedVersionId: 20 },
    { id: 3, pkg: 'com.android.systemui', name: 'System UI', action: 1, showIcon: false },
    { id: 4, pkg: 'com.google.android.youtube', name: 'YouTube', action: 0, showIcon: true },
  ];
  const library: LibraryApp[] = [
    { id: 7, pkg: 'com.whatsapp', name: 'WhatsApp', latestVersion: 70, system: true },
    { id: 8, pkg: 'com.company.sales', name: 'Sales', latestVersion: 80, url: 'https://x/sales.apk' },
    { id: 9, pkg: 'com.web', name: 'Web', type: 'web' },
  ];

  it('reads the kiosk allowlist as the server does', () => {
    expect([...allowedPackages(rows)]).toEqual(['com.android.dialer', 'com.android.chrome']);
  });

  it('hides unticked apps, shows ticked ones and adds new ones, touching nothing else', () => {
    const want = new Set(['com.android.dialer', 'com.google.android.youtube', 'com.whatsapp', 'com.company.sales', 'com.web', 'com.new.app']);
    const plan = planAllowList(rows, want, library, 20);
    expect(plan.rows[0]).toBe(rows[0]);
    expect(plan.rows[1]).toEqual({ ...rows[1], showIcon: false });
    expect(plan.rows[2]).toBe(rows[2]);
    expect(plan.rows[3]).toEqual({ ...rows[3], action: 1, showIcon: true, remove: false });
    expect(plan.rows.slice(4).map((r) => r.id)).toEqual([8, 7]);
    expect(plan.rows[5]).toMatchObject({ pkg: 'com.whatsapp', usedVersionId: 70, action: 1, showIcon: true });
    // Chrome was the main app: hiding it clears the main app.
    expect(plan.mainAppId).toBeNull();
    // A web entry doesn't count as the package's Library app.
    expect(plan.missing).toEqual(['com.new.app', 'com.web']);
    expect(plan.installs).toEqual(['Sales']);
    expect(allowedPackages(plan.rows)).toEqual(new Set([...want].filter((p) => p !== 'com.new.app' && p !== 'com.web')));
  });

  it('changes nothing when the list is the same', () => {
    const plan = planAllowList(rows, allowedPackages(rows), library, 10);
    expect(plan.rows).toEqual(rows);
    expect(plan.mainAppId).toBe(10);
    expect(plan.missing).toEqual([]);
  });
});

describe('compliance', () => {
  it('reads the sync state', () => {
    expect(syncState(null)).toBe('unknown');
    expect(syncState({ configurationId: 1, supported: false })).toBe('agentTooOld');
    expect(syncState({ configurationId: 1, supported: true, inSync: true })).toBe('inSync');
    expect(syncState({ configurationId: 1, supported: true, inSync: false, lastCommand: { status: 'failed' } })).toBe('failed');
    expect(syncState({ configurationId: 1, supported: true, inSync: false, appliedRevision: 'a' })).toBe('outOfSync');
  });

  it('lists unsupported settings', () => {
    const detail = JSON.stringify({ outcomes: { 'policies.bluetooth': 'unsupported', 'policies.camera': 'applied', location: 'unsupported' } });
    expect(unsupportedSettings(detail)).toEqual(['bluetooth', 'location']);
    expect(unsupportedSettings('not json')).toEqual([]);
  });

  it('gives a verdict with reasons', () => {
    const base = { expectedPolicyId: 5, configurationId: 5, sync: 'inSync' as const, unsupported: 0, violations: 0, appMode: 'remove' as const };
    expect(compliance(base)).toEqual({ verdict: 'compliant', reasons: [] });
    expect(compliance({ ...base, configurationId: 4, violations: 2 }).reasons).toEqual([
      'On another policy than its group',
      '2 apps outside the allowed list',
    ]);
    expect(compliance({ ...base, expectedPolicyId: undefined }).verdict).toBe('attention');
    expect(compliance({ ...base, violations: null })).toEqual({ verdict: 'unknown', reasons: ['Apps not scanned yet'] });
    expect(compliance({ ...base, violations: null, appMode: 'off' }).verdict).toBe('compliant');
    expect(compliance({ ...base, sync: 'applying' }).verdict).toBe('unknown');
  });
});

describe('device groups', () => {
  const oppo = { id: 1, name: 'Oppo family', policyId: 4, brands: ['oppo', 'realme'], devices: [9] };
  const vivo = { id: 2, name: 'Vivo', policyId: null, brands: ['vivo'], devices: [] };
  const groups = [oppo, vivo];
  const phone = (id: number, brand: string, configurationId?: number) => {
    const { group } = groupForDevice(groups, id, brand);
    return { device: { id, number: `P-${id}`, configurationId }, brand, group };
  };

  it('finds a phone picked by hand first, then by its brand', () => {
    expect(groupForDevice(groups, 9, 'vivo')).toMatchObject({ group: { id: 1 }, manual: true });
    expect(groupForDevice(groups, 2, 'vivo')).toMatchObject({ group: { id: 2 }, manual: false });
    expect(groupForDevice(groups, 3, 'samsung')).toEqual({ group: undefined, manual: false });
  });

  it('lists what Apply moves: the group phones on another policy, none while it has no policy', () => {
    const phones = [phone(1, 'oppo', 4), phone(2, 'realme', 5), phone(9, 'vivo'), phone(3, 'vivo', 5), phone(4, 'samsung', 5)];
    expect(offPolicy(oppo, phones).map((p) => p.device.id)).toEqual([2, 9]);
    expect(offPolicy(vivo, phones)).toEqual([]);
  });

  it('lists brands by phones, with the ones groups cover and those made by hand', () => {
    const list = brandSummary([{ brand: 'oppo' }, { brand: 'oppo' }, { brand: 'vivo' }], groups, [{ brand: 'nothing' }]);
    expect(list.map((b) => [b.brand, b.count, b.group?.id ?? null, b.custom])).toEqual([
      ['oppo', 2, 1, false],
      ['vivo', 1, 2, false],
      ['nothing', 0, null, true],
      ['realme', 0, 1, false],
    ]);
  });

  it('says why a phone is not where its group wants it', () => {
    const base = { configurationId: 5, sync: 'inSync' as const, unsupported: 0, violations: 0, appMode: 'off' as const };
    expect(compliance({ ...base, expectedPolicyId: undefined }).reasons).toEqual(['In no group']);
    expect(compliance({ ...base, expectedPolicyId: null }).reasons).toEqual(['Its group has no policy yet']);
    expect(compliance({ ...base, expectedPolicyId: 4 }).reasons).toEqual(['On another policy than its group']);
    expect(compliance({ ...base, expectedPolicyId: 5 }).verdict).toBe('compliant');
  });
});
