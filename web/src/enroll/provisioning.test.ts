import { describe, expect, it } from 'vitest';
import {
  buildProvisioningPayload,
  configurationQrExtras,
  parseExtraQrParameters,
  joinQrParameters,
  QR_SETTING_KEYS,
  settingsInRest,
  splitQrParameters,
  summarizeQrExtras,
} from './provisioning';

const LOCALE = 'android.app.extra.PROVISIONING_LOCALE';
const TZ = 'android.app.extra.PROVISIONING_TIME_ZONE';
const SKIP = 'android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS';
const COMPONENT = 'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME';
const EXTRAS = 'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE';

describe('parseExtraQrParameters', () => {
  it('adds nothing for empty text', () => {
    expect(parseExtraQrParameters(null)).toEqual({ entries: {}, ignored: [], error: null });
    expect(parseExtraQrParameters('  \n ')).toEqual({ entries: {}, ignored: [], error: null });
  });

  it("reads Headwind's members-without-braces form, trailing comma included", () => {
    const r = parseExtraQrParameters(`"${LOCALE}": "fr_FR",\n"${TZ}": "Europe/Paris",\n"${SKIP}": true,\n`);
    expect(r.error).toBeNull();
    expect(r.entries).toEqual({ [LOCALE]: 'fr_FR', [TZ]: 'Europe/Paris', [SKIP]: true });
  });

  it('reads one JSON object', () => {
    expect(parseExtraQrParameters(`{ "${LOCALE}": "de_DE", }`).entries).toEqual({ [LOCALE]: 'de_DE' });
  });

  it('says why bad text adds nothing', () => {
    const r = parseExtraQrParameters(`"${LOCALE}": fr_FR`);
    expect(r.entries).toEqual({});
    expect(r.error).toContain('valid JSON');
    expect(parseExtraQrParameters('[1, 2]').error).toContain('JSON members');
  });

  it('leaves out the protected entries', () => {
    const r = parseExtraQrParameters(`"${COMPONENT}": "evil/.Admin", "${LOCALE}": "fr_FR"`);
    expect(r.entries).toEqual({ [LOCALE]: 'fr_FR' });
    expect(r.ignored).toEqual([COMPONENT]);
  });
});

describe('buildProvisioningPayload with extras', () => {
  it('adds the extras and keeps the token, the agent and the page Wi-Fi', () => {
    const p = JSON.parse(
      buildProvisioningPayload('tok-1', { ssid: 'club', password: 'correct horse', security: 'WPA' }, {
        [LOCALE]: 'fr_FR',
        [COMPONENT]: 'evil/.Admin',
        'android.app.extra.PROVISIONING_WIFI_SSID': 'other',
      }),
    );
    expect(p[LOCALE]).toBe('fr_FR');
    expect(p[COMPONENT]).not.toBe('evil/.Admin');
    expect(p[EXTRAS]['com.mdmesh.ENROLL_TOKEN']).toBe('tok-1');
    expect(p['android.app.extra.PROVISIONING_WIFI_SSID']).toBe('club');
  });

  it('lets an extra change a default entry', () => {
    const p = JSON.parse(buildProvisioningPayload('t', undefined, { 'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': false }));
    expect(p['android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED']).toBe(false);
  });
});

describe('setup settings kept in the Extra QR parameters', () => {
  const SKIP = 'android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS';

  it('writes the settings first, one per line, then the other entries as typed', () => {
    const text = joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR', PROVISIONING_SKIP_EDUCATION_SCREENS: true }, '"x.y":   1');
    expect(text).toBe(`"${SKIP}": true,\n"${LOCALE}": "fr_FR",\n"x.y":   1`);
    expect(splitQrParameters(text)).toEqual({
      settings: { PROVISIONING_SKIP_EDUCATION_SCREENS: true, PROVISIONING_LOCALE: 'fr_FR' },
      rest: '"x.y":   1',
    });
  });

  it('leaves out settings switched off or cleared', () => {
    expect(joinQrParameters({ PROVISIONING_SKIP_EDUCATION_SCREENS: false, PROVISIONING_LOCALE: '' }, '')).toBe('');
  });

  it('keeps other entries that are not valid JSON yet, and the settings still read', () => {
    const text = joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, '"x.y": tru');
    expect(splitQrParameters(text)).toEqual({ settings: { PROVISIONING_LOCALE: 'fr_FR' }, rest: '"x.y": tru' });
    expect(configurationQrExtras({ qrParameters: text }).entries).toEqual({ [LOCALE]: 'fr_FR' });
    expect(configurationQrExtras({ qrParameters: text }).error).toMatch(/invalid JSON/);
  });

  it('leaves a setting written elsewhere in the other entries, where it still counts until the setting is set', () => {
    const legacy = `{"x.y": 1, "${TZ}": "Europe/Paris"}`;
    expect(splitQrParameters(legacy)).toEqual({ settings: {}, rest: legacy });
    expect(settingsInRest(legacy)).toEqual(['PROVISIONING_TIME_ZONE']);
    expect(configurationQrExtras({ qrParameters: legacy }).entries[TZ]).toBe('Europe/Paris');
  });

  it('writes an object typed as the other entries back as members, so the text stays one list', () => {
    expect(joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, '{"x.y": 1}')).toBe(`"${LOCALE}": "fr_FR",\n"x.y": 1`);
  });

  it('names other entries that are settings, and the setting wins in the code', () => {
    const text = joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, `"${SKIP}": true`);
    expect(settingsInRest(splitQrParameters(text).rest)).toEqual([]);
    const typed = `"${LOCALE}": "de_DE"`;
    expect(settingsInRest(typed)).toEqual(['PROVISIONING_LOCALE']);
    expect(configurationQrExtras({ qrParameters: `"${LOCALE}": "fr_FR",\n"x": 1,\n${typed}` }).entries[LOCALE]).toBe('fr_FR');
  });

  it('lists every setting the editor shows', () => {
    expect(QR_SETTING_KEYS).toContain('PROVISIONING_SKIP_EDUCATION_SCREENS');
  });
});

describe('what a configuration adds to the enrollment QR', () => {
  const MOBILE = 'android.app.extra.PROVISIONING_USE_MOBILE_DATA';

  it('adds mobile data when Enroll over mobile data is on, an explicit entry winning', () => {
    expect(configurationQrExtras({ mobileEnrollment: true }).entries).toEqual({ [MOBILE]: true });
    expect(configurationQrExtras({ mobileEnrollment: false }).entries).toEqual({});
    expect(configurationQrExtras({ mobileEnrollment: true, qrParameters: `"${MOBILE}": false` }).entries).toEqual({
      [MOBILE]: false,
    });
  });

  it('keeps mobile data when the other entries are unreadable, and reports why', () => {
    const r = configurationQrExtras({ mobileEnrollment: true, qrParameters: '"broken' });
    expect(r.error).toMatch(/invalid JSON/);
    expect(r.entries).toEqual({ [MOBILE]: true });
  });

  it('summarizes the known entries and counts the rest', () => {
    const s = summarizeQrExtras({
      [LOCALE]: 'fr_FR',
      [TZ]: 'Europe/Paris',
      'android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS': true,
      [MOBILE]: true,
      'android.app.extra.PROVISIONING_WIFI_HIDDEN': false,
      'android.app.extra.PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT': true,
    });
    expect(s).toEqual({ locale: 'fr_FR', timeZone: 'Europe/Paris', skipIntro: true, mobileData: true, hiddenWifi: false, others: 1 });
  });
});
