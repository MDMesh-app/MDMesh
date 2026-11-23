import { describe, expect, it } from 'vitest';
import type { CommandHistoryItem } from '../api/commands';
import { appsCsv, byLabel, csvField, filterApps, latestScan, normaliseApps, scanProgress } from './deviceApps';

const scan = (id: number, status: string, detail: string | null, completedAt?: number): CommandHistoryItem => ({
  id,
  type: 'apps.scan',
  status,
  detail,
  completedAt,
});
const result = (apps: unknown) => JSON.stringify({ apps });

describe('normaliseApps', () => {
  it('falls back to the package name for a missing or non-string label', () => {
    expect(normaliseApps([{ pkg: 'x' }, { pkg: 'y', label: 5 }, { pkg: 'z', label: '' }]).map((a) => a.label)).toEqual(['x', 'y', 'z']);
  });

  it('keeps only versions of the right type, and system only when it is true', () => {
    expect(normaliseApps([{ pkg: 'x', versionName: { a: 1 }, versionCode: '7', system: 'yes' }])).toEqual([
      { pkg: 'x', label: 'x', system: false, versionName: undefined, versionCode: undefined },
    ]);
    expect(normaliseApps([{ pkg: 'x', label: 'X', versionName: '1.2', versionCode: 12, system: true }])).toEqual([
      { pkg: 'x', label: 'X', system: true, versionName: '1.2', versionCode: 12 },
    ]);
  });

  it('drops entries without a package, and a second entry for the same one', () => {
    expect(normaliseApps([null, 'a', { label: 'L' }, { pkg: '' }, { pkg: 3 }, { pkg: 'a', label: 'A' }, { pkg: 'a', label: 'B' }])).toEqual([
      { pkg: 'a', label: 'A', system: false, versionName: undefined, versionCode: undefined },
    ]);
    expect(normaliseApps({ pkg: 'a' })).toEqual([]);
  });
});

describe('latestScan', () => {
  it('takes the newest finished scan, with the server’s completion time', () => {
    const history = [
      { id: 9, type: 'device.ring', status: 'done', detail: null },
      scan(8, 'done', result([{ pkg: 'new' }]), 2000),
      scan(5, 'done', result([{ pkg: 'old' }]), 1000),
    ];
    expect(latestScan(history)).toEqual({ apps: [expect.objectContaining({ pkg: 'new' })], scannedAt: 2000 });
  });

  it('skips pending, failed and unparsable scans', () => {
    const history = [
      scan(10, 'pending', null),
      scan(9, 'failed', 'Result too large'),
      scan(8, 'done', '{not json'),
      scan(7, 'done', '"text"'),
      scan(6, 'done', result('nope')),
      scan(5, 'done', result([{ pkg: 'ok' }]), 1000),
    ];
    expect(latestScan(history)?.apps.map((a) => a.pkg)).toEqual(['ok']);
  });

  it('gives a scan that listed no apps, and null when there is no scan', () => {
    expect(latestScan([scan(1, 'done', result([]), 5)])).toEqual({ apps: [], scannedAt: 5 });
    expect(latestScan([scan(1, 'pending', null)])).toBeNull();
    expect(latestScan([])).toBeNull();
  });
});

describe('scanProgress', () => {
  it('is pending until the device answers, also while the history doesn’t list the command', () => {
    expect(scanProgress([], '4')).toEqual({ state: 'pending' });
    expect(scanProgress([scan(4, 'delivered', null)], '4')).toEqual({ state: 'pending' });
    expect(scanProgress([scan(4, 'done', result([]))], '4')).toEqual({ state: 'done' });
  });

  it('fails with the reason, or the status when there is none', () => {
    expect(scanProgress([scan(4, 'failed', 'Result too large')], '4')).toEqual({ state: 'failed', reason: 'Result too large' });
    expect(scanProgress([scan(4, 'expired', null)], '4')).toEqual({ state: 'failed', reason: 'Device returned expired' });
    expect(scanProgress([scan(4, 'unsupported', '')], '4')).toEqual({ state: 'failed', reason: 'Device returned unsupported' });
  });
});

describe('sorting and filtering', () => {
  const apps = normaliseApps([
    { pkg: 'com.b', label: 'beta' },
    { pkg: 'com.android.settings', label: 'Settings', system: true },
    { pkg: 'com.a', label: 'Alpha' },
  ]);

  it('sorts by name, ignoring case', () => {
    expect([...apps].sort(byLabel).map((a) => a.label)).toEqual(['Alpha', 'beta', 'Settings']);
  });

  it('hides system apps unless asked, and searches name and package', () => {
    expect(filterApps(apps, '', false).map((a) => a.pkg)).toEqual(['com.b', 'com.a']);
    expect(filterApps(apps, ' SETT ', true).map((a) => a.pkg)).toEqual(['com.android.settings']);
    expect(filterApps(apps, 'com.a', false).map((a) => a.pkg)).toEqual(['com.a']);
  });
});

describe('CSV export', () => {
  it('prefixes a value a spreadsheet would read as a formula', () => {
    expect(csvField('=1+1')).toBe("'=1+1");
    expect(csvField('+1')).toBe("'+1");
    expect(csvField('-1')).toBe("'-1");
    expect(csvField('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvField('\tx')).toBe("'\tx");
    expect(csvField('\rx')).toBe(`"'\rx"`);
    expect(csvField('a=b')).toBe('a=b');
  });

  it('quotes commas, quotes and line breaks, and writes nothing for an empty value', () => {
    expect(csvField('a,b')).toBe('"a,b"');
    expect(csvField('a"b')).toBe('"a""b"');
    expect(csvField('a\nb')).toBe('"a\nb"');
    expect(csvField(undefined)).toBe('');
    expect(csvField('')).toBe('');
    expect(csvField(42)).toBe('42');
  });

  it('writes a header and one CRLF line per app', () => {
    const apps = normaliseApps([
      { pkg: 'com.a', label: 'A, the app', versionName: '1.0', versionCode: 3 },
      { pkg: 'com.s', label: '=cmd', system: true },
    ]);
    expect(appsCsv(apps)).toBe('name,package,versionName,versionCode,system\r\n"A, the app",com.a,1.0,3,no\r\n\'=cmd,com.s,,,yes\r\n');
  });
});
