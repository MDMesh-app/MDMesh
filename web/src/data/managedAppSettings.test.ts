import { describe, expect, it } from 'vitest';
import {
  appSettingsProblems,
  appSettingsSignature,
  dropAppSettings,
  MAX_SETTINGS_PER_APP,
  newAppSetting,
  withType,
  type AppSetting,
} from './managedAppSettings';

const row = (applicationId: number, name: string, type: AppSetting['type'], value: string, extra: Partial<AppSetting> = {}): AppSetting => ({
  ...newAppSetting(applicationId),
  name,
  type,
  value,
  ...extra,
});

describe('application settings', () => {
  it('accepts what the server takes', () => {
    expect(
      appSettingsProblems([
        row(1, 'serverUrl', 'STRING', 'https://vhp.example.hr'),
        row(1, 'kioskMode', 'BOOLEAN', 'true'),
        row(1, 'pollSeconds', 'INTEGER', '-2147483648'),
        row(2, 'serverUrl', 'STRING', ''),
      ]),
    ).toEqual([]);
  });

  it('refuses a blank, long or repeated key within an app, and a value that does not fit its type', () => {
    const problems = appSettingsProblems([
      row(1, ' ', 'STRING', 'x'),
      row(1, 'a'.repeat(201), 'STRING', 'x'),
      row(1, 'token', 'STRING', 'x'),
      row(1, 'token', 'STRING', 'y'),
      row(1, 'flag', 'BOOLEAN', 'yes'),
      row(1, 'n', 'INTEGER', '2147483648'),
      row(1, 'm', 'INTEGER', '1.5'),
      row(1, 'big', 'STRING', 'v'.repeat(4097)),
    ]);
    expect(problems.map((p) => [p.index, p.field])).toEqual([
      [0, 'name'],
      [1, 'name'],
      [3, 'name'],
      [4, 'value'],
      [5, 'value'],
      [6, 'value'],
      [7, 'value'],
    ]);
  });

  it('never quotes a value in a problem', () => {
    const problems = appSettingsProblems([row(1, 'n', 'INTEGER', 'secret-token-123')]);
    expect(problems).toHaveLength(1);
    expect(problems[0].message).not.toContain('secret-token-123');
  });

  it('takes at most 64 settings per app', () => {
    const rows = Array.from({ length: MAX_SETTINGS_PER_APP + 1 }, (_, i) => row(1, `k${i}`, 'STRING', ''));
    expect(appSettingsProblems(rows).map((p) => p.index)).toEqual([MAX_SETTINGS_PER_APP]);
    expect(appSettingsProblems(rows.slice(0, MAX_SETTINGS_PER_APP))).toEqual([]);
  });

  it('a switch to On/off starts from Off unless the value already reads true or false', () => {
    expect(withType(row(1, 'a', 'STRING', 'maybe'), 'BOOLEAN').value).toBe('false');
    expect(withType(row(1, 'a', 'STRING', 'true'), 'BOOLEAN').value).toBe('true');
    expect(withType(row(1, 'a', 'STRING', '12'), 'INTEGER').value).toBe('12');
  });

  it('compares order-free and ignores lastUpdate and ids', () => {
    const a = [row(1, 'a', 'STRING', 'x', { id: 4, lastUpdate: 1 }), row(1, 'b', 'BOOLEAN', 'true')];
    const b = [row(1, 'b', 'BOOLEAN', 'true', { lastUpdate: 9 }), row(1, 'a', 'STRING', 'x')];
    expect(appSettingsSignature(a)).toBe(appSettingsSignature(b));
    expect(appSettingsSignature(null)).toBe(appSettingsSignature([]));
    expect(appSettingsSignature(a)).not.toBe(appSettingsSignature([a[0], { ...a[1], value: 'false' }]));
  });

  it('drops one app’s settings, and leaves an absent list absent', () => {
    const kept = row(2, 'b', 'STRING', '');
    expect(dropAppSettings([row(1, 'a', 'STRING', ''), kept], 1)).toEqual([kept]);
    expect(dropAppSettings(undefined, 1)).toBeUndefined();
  });
});
