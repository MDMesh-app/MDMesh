import { describe, expect, it } from 'vitest';
import {
  buildProvisioningPayload,
  configurationQrExtras,
  parseExtraQrParameters,
  joinQrParameters,
  QR_SETTING_KEYS,
  settingsInRest,
  splitQrParameters,
  extrasInCode,
  extrasLeftOut,
  extrasUnchecked,
  qrPayloadBytes,
  QR_MAX_BYTES,
  unusedInRest,
  qrSettingValueOk,
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

  it("never lets an extra change MDMesh's defaults (system apps stay enabled, no encryption step)", () => {
    const p = JSON.parse(
      buildProvisioningPayload('t', undefined, {
        'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': false,
        'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': false,
      }),
    );
    expect(p['android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED']).toBe(true);
    expect(p['android.app.extra.PROVISIONING_SKIP_ENCRYPTION']).toBe(true);
  });
});

describe('setup settings kept in the Extra QR parameters', () => {
  const SKIP = 'android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS';

  it('writes the settings first, one per line, then the other entries as typed', () => {
    const text = joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR', PROVISIONING_SKIP_EDUCATION_SCREENS: true }, '"x.y":   1');
    expect(text).toBe(`"${SKIP}": true,\n"${LOCALE}": "fr_FR",\n\n"x.y":   1`);
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
    expect(joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, '{"x.y": 1}')).toBe(`"${LOCALE}": "fr_FR",\n\n"x.y": 1`);
  });

  it('names other entries that are settings, and the setting wins in the code', () => {
    // Typed after a later setting, it stays typed text: pointed out, never read back as the setting.
    const text = joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, `"${SKIP}": true`);
    expect(splitQrParameters(text)).toEqual({ settings: { PROVISIONING_LOCALE: 'fr_FR' }, rest: `"${SKIP}": true` });
    expect(settingsInRest(splitQrParameters(text).rest)).toEqual(['PROVISIONING_SKIP_EDUCATION_SCREENS']);
    const typed = `"${LOCALE}": "de_DE"`;
    expect(settingsInRest(typed)).toEqual(['PROVISIONING_LOCALE']);
    // Written by the editor (settings, blank line, typed text): the setting wins.
    expect(configurationQrExtras({ qrParameters: `"${LOCALE}": "fr_FR",\n\n"x": 1,\n${typed}` }).entries[LOCALE]).toBe('fr_FR');
    // Not in that shape: all typed text, read as JSON (the last entry wins), as the editor shows it.
    expect(configurationQrExtras({ qrParameters: `"${LOCALE}": "fr_FR",\n"x": 1,\n${typed}` }).entries[LOCALE]).toBe('de_DE');
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
    // HIDDEN false still goes in the code, so it is counted with the other entry.
    expect(s).toEqual({ locale: 'fr_FR', timeZone: 'Europe/Paris', skipIntro: true, mobileData: true, hiddenWifi: false, others: 2 });
  });
});

describe('review: typed entries never replace a setting', () => {
  it('keeps an entry typed first that repeats a setting as typed text, also once saved and loaded again', () => {
    const text = joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, `"${LOCALE}": "de_DE"`);
    expect(splitQrParameters(text)).toEqual({ settings: { PROVISIONING_LOCALE: 'fr_FR' }, rest: `"${LOCALE}": "de_DE"` });
    expect(configurationQrExtras({ qrParameters: text }).entries[LOCALE]).toBe('fr_FR');
  });
});

describe('review: Wi-Fi is the Enroll page\'s choice', () => {
  const SSID = 'android.app.extra.PROVISIONING_WIFI_SSID';
  const PASS = 'android.app.extra.PROVISIONING_WIFI_PASSWORD';
  const HIDDEN = 'android.app.extra.PROVISIONING_WIFI_HIDDEN';
  const extras = { [SSID]: 'other', [PASS]: 'secret', [HIDDEN]: true, [LOCALE]: 'fr_FR' };

  it('leaves every Wi-Fi entry out when the page puts no network in the code', () => {
    const p = JSON.parse(buildProvisioningPayload('t', undefined, extras));
    expect(p[SSID]).toBeUndefined();
    expect(p[PASS]).toBeUndefined();
    expect(p[HIDDEN]).toBeUndefined();
    expect(p[LOCALE]).toBe('fr_FR');
  });

  it('keeps the page\'s network, with a hidden-network entry, never another network or password', () => {
    const p = JSON.parse(buildProvisioningPayload('t', { ssid: 'club', password: 'longenough', security: 'WPA' }, extras));
    expect(p[SSID]).toBe('club');
    expect(p[PASS]).toBe('longenough');
    expect(p[HIDDEN]).toBe(true);
  });

  it('leaves out a page network that cannot be joined, and the Wi-Fi entries with it', () => {
    const p = JSON.parse(buildProvisioningPayload('t', { ssid: 'club', security: 'WPA' }, extras));
    expect(p[SSID]).toBeUndefined();
    expect(p[PASS]).toBeUndefined();
  });
});

describe('review: what is saved reads back the same', () => {
  const HIDDEN = 'android.app.extra.PROVISIONING_WIFI_HIDDEN';
  const roundTrip = (settings: Record<string, unknown>, rest: string) => splitQrParameters(joinQrParameters(settings, rest));

  it('keeps typed duplicates as typed text, the last one winning in the code as in the editor', () => {
    const rest = `"${TZ}": "UTC",\n"${TZ}": "Asia/Tokyo"`;
    expect(roundTrip({ PROVISIONING_LOCALE: 'fr_FR' }, rest)).toEqual({ settings: { PROVISIONING_LOCALE: 'fr_FR' }, rest });
    expect(configurationQrExtras({ qrParameters: joinQrParameters({ PROVISIONING_LOCALE: 'fr_FR' }, rest) }).entries[TZ]).toBe('Asia/Tokyo');
    const two = `"${LOCALE}": "de_DE",\n"${LOCALE}": "en_US"`;
    expect(roundTrip({}, two)).toEqual({ settings: {}, rest: two });
    expect(configurationQrExtras({ qrParameters: joinQrParameters({}, two) }).entries[LOCALE]).toBe('en_US');
  });

  it('never reads an entry set to false as a setting, so it stays where it was typed', () => {
    const rest = `"${HIDDEN}": false,\n"x.y": 1`;
    expect(roundTrip({}, rest)).toEqual({ settings: {}, rest });
  });

  it('reads back settings followed by typed text that starts with blank lines', () => {
    const rest = '\n\n"x.y": 1';
    expect(roundTrip({ PROVISIONING_TIME_ZONE: 'UTC' }, rest)).toEqual({ settings: { PROVISIONING_TIME_ZONE: 'UTC' }, rest });
  });

  it('keeps typed text that could read as settings exactly as typed, with a leading blank line', () => {
    for (const rest of [
      `"${LOCALE}": "de_DE"`,
      `"${LOCALE}": "fr_FR"\n\n"${LOCALE}": "en_US"`,
      `"${LOCALE}": "fr_FR"\n\n, "x": 1`,
      `"${TZ}": "Europe/Paris",\n\n{bad`,
      '\n"x": 1',
    ]) {
      const stored = joinQrParameters({}, rest);
      expect(stored.startsWith('\n')).toBe(true);
      expect(splitQrParameters(stored)).toEqual({ settings: {}, rest });
    }
    expect(configurationQrExtras({ qrParameters: joinQrParameters({}, `"${LOCALE}": "fr_FR"\n\n,"${LOCALE}": "en_US"`) }).entries[LOCALE]).toBe('en_US');
    expect(joinQrParameters({}, '"x.y": 1')).toBe('"x.y": 1');
  });
});

describe('review: Wi-Fi entries and the summary', () => {
  const W = 'android.app.extra.PROVISIONING_WIFI_';
  it('drops a stored password with an open page network', () => {
    const p = JSON.parse(buildProvisioningPayload('t', { ssid: 'club', security: 'NONE' }, { [`${W}PASSWORD`]: 'secret' }));
    expect(p[`${W}SSID`]).toBe('club');
    expect(p[`${W}PASSWORD`]).toBeUndefined();
  });

  it('keeps only a hidden network, with the page network, and lists what it leaves out (a proxy too)', () => {
    const extras = { [`${W}HIDDEN`]: true, [`${W}PROXY_HOST`]: 'p', [`${W}PAC_URL`]: 'u', [`${W}EAP_METHOD`]: 'PEAP' };
    expect(Object.keys(extrasInCode(extras, true))).toEqual([`${W}HIDDEN`]);
    expect(extrasLeftOut(extras, true).wifi.sort()).toEqual([`${W}EAP_METHOD`, `${W}PAC_URL`, `${W}PROXY_HOST`]);
    expect(extrasLeftOut(extras, false).wifi).toHaveLength(4);
  });

  it('names typed entries that never go in the code: Wi-Fi and the entries MDMesh sets', () => {
    const rest = [
      `"${W}SSID": "other"`,
      `"${W}PROXY_HOST": "p"`,
      `"${W}HIDDEN": true`,
      '"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": false',
      '"x.y": 1',
    ].join(',\n');
    expect(unusedInRest(rest)).toEqual({
      wifi: ['PROVISIONING_WIFI_SSID', 'PROVISIONING_WIFI_PROXY_HOST'],
      protected: ['PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED'],
      unsupported: ['x.y'],
      invalid: [],
    });
    expect(unusedInRest('"x": tru')).toEqual({ wifi: [], protected: [], unsupported: [], invalid: [] });
  });

  it('counts every entry the summary cannot word, a default value included, since it goes in the code', () => {
    const s = summarizeQrExtras({
      'android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS': 'true',
      'android.app.extra.PROVISIONING_USE_MOBILE_DATA': false,
    });
    expect(s.skipIntro).toBe(false);
    expect(s.mobileData).toBe(false);
    expect(s.others).toBe(2);
    expect(summarizeQrExtras({ 'android.app.extra.PROVISIONING_LOCALE': true }).others).toBe(1);
  });
});

describe('review: known entries are checked, other provisioning entries go in as typed', () => {
  const P = 'android.app.extra.';
  it('passes on known entries with a valid value and unknown ones with a plain value, and leaves out the rest by reason', () => {
    const extras = {
      [`${P}PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT`]: true,
      [`${P}PROVISIONING_LOCAL_TIME`]: 1767225600000,
      [`${P}PROVISIONING_ORGANIZATION_NAME`]: 'Club',
      [`${P}PROVISIONING_SUPPORT_URL`]: 'https://example.org/help',
      [`${P}PROVISIONING_DISCLAIMERS`]: [],
      [`${P}PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE`]: 1,
      'x.y': 1,
      [`${P}PROVISIONING_SKIP_EDUCATION_SCREENS`]: 'true',
      [`${P}PROVISIONING_LOCALE`]: 'fr_FR; drop',
      [`${P}PROVISIONING_SUPPORT_URL_BAD`]: 'x',
    };
    expect(Object.keys(extrasInCode(extras, false)).sort()).toEqual([
      `${P}PROVISIONING_LOCAL_TIME`,
      `${P}PROVISIONING_ORGANIZATION_NAME`,
      `${P}PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT`,
      `${P}PROVISIONING_SUPPORT_URL`,
      `${P}PROVISIONING_SUPPORT_URL_BAD`,
    ]);
    expect(extrasUnchecked(extras, false)).toEqual([`${P}PROVISIONING_SUPPORT_URL_BAD`]);
    const out = extrasLeftOut(extras, false);
    expect(out.invalid.sort()).toEqual([`${P}PROVISIONING_DISCLAIMERS`, `${P}PROVISIONING_LOCALE`, `${P}PROVISIONING_SKIP_EDUCATION_SCREENS`]);
    expect(out.unsupported).toEqual(['x.y']);
    const p = JSON.parse(buildProvisioningPayload('t', undefined, extras));
    expect(p['x.y']).toBeUndefined();
    expect(p[`${P}PROVISIONING_DISCLAIMERS`]).toBeUndefined();
    expect(p[`${P}PROVISIONING_SKIP_EDUCATION_SCREENS`]).toBeUndefined();
  });

  it('takes offline provisioning as a known true/false entry', () => {
    const key = `${P}PROVISIONING_ALLOW_OFFLINE`;
    expect(JSON.parse(buildProvisioningPayload('t', undefined, { [key]: true }))[key]).toBe(true);
    expect(extrasUnchecked({ [key]: true }, false)).toEqual([]);
    expect(extrasLeftOut({ [key]: 'true' }, false).invalid).toEqual([key]);
  });

  it('never passes on an entry about the agent package or a role holder package, known by name or not', () => {
    const qrParameters = [
      `"${P}PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER": "a=b"`,
      `"${P}PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE": 1`,
      `"${P}PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_LOCATION": "https://example.org/x.apk"`,
    ].join(',\n');
    const read = configurationQrExtras({ qrParameters });
    expect(read.entries).toEqual({});
    expect(read.ignored).toHaveLength(3);
    expect(unusedInRest(qrParameters).protected).toHaveLength(3);
    expect(extrasInCode({ [`${P}PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE`]: 'x' }, false)).toEqual({});
  });

  it('rejects a support URL that is not https, and a negative local time', () => {
    expect(extrasInCode({ [`${P}PROVISIONING_SUPPORT_URL`]: 'http://x', [`${P}PROVISIONING_LOCAL_TIME`]: -1 }, false)).toEqual({});
  });
});

describe('review: QR size', () => {
  it('measures the payload in UTF-8 bytes', () => {
    expect(qrPayloadBytes('é')).toBe(2);
  });

  it('a base payload stays well under the limit; huge extras would not fit', () => {
    const base = buildProvisioningPayload('t'.repeat(40), { ssid: 'club', password: 'longenough', security: 'WPA' });
    expect(qrPayloadBytes(base)).toBeLessThan(QR_MAX_BYTES / 2);
    const big = buildProvisioningPayload('t', undefined, {
      'android.app.extra.PROVISIONING_ORGANIZATION_NAME': 'n'.repeat(100),
    });
    expect(qrPayloadBytes(big)).toBeLessThan(QR_MAX_BYTES);
  });
});

describe('review: typed keys named like JavaScript object members', () => {
  const P = 'android.app.extra.';
  const names = ['constructor', '__proto__', 'toString', 'hasOwnProperty', 'valueOf', 'isPrototypeOf', 'toLocaleString'];

  it('never reach the code, never throw, and are pointed out', () => {
    for (const name of names) {
      const qrParameters = `"x.y": 1,\n"${P}${name}": {"a": "yyy"},\n"${name}": "x"`;
      const entries = configurationQrExtras({ qrParameters }).entries;
      const payload = JSON.parse(buildProvisioningPayload('t', undefined, entries));
      expect(Object.keys(payload).some((k) => k.endsWith(name))).toBe(false);
      expect(unusedInRest(qrParameters).unsupported).toEqual(expect.arrayContaining([name]));
      expect(() => extrasLeftOut(entries, true)).not.toThrow();
    }
  });
});

describe('review: keys typed without their prefix', () => {
  it('are pointed out in full, settings included, never hidden', () => {
    expect(unusedInRest('"PROVISIONING_LOCALE": "fr_FR",\n"PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT": true').unsupported).toEqual([
      'PROVISIONING_LOCALE',
      'PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT',
    ]);
  });
});

describe('review: setting values Android does not take', () => {
  it('flags a Headwind locale such as fr-FR, and accepts an unset one', () => {
    expect(qrSettingValueOk('PROVISIONING_LOCALE', 'fr-FR')).toBe(false);
    expect(qrSettingValueOk('PROVISIONING_LOCALE', 'fr_FR')).toBe(true);
    expect(qrSettingValueOk('PROVISIONING_TIME_ZONE', null)).toBe(true);
    expect(qrSettingValueOk('constructor', 'x')).toBe(false);
  });
});

describe('review: time zones Android takes', () => {
  it('accepts IANA names and aliases, and rejects a misspelt one', () => {
    for (const z of ['Europe/Paris', 'UTC', 'Etc/GMT+1', 'America/Argentina/Buenos_Aires']) {
      expect(qrSettingValueOk('PROVISIONING_TIME_ZONE', z)).toBe(true);
    }
    expect(qrSettingValueOk('PROVISIONING_TIME_ZONE', 'Europe/Pariss')).toBe(false);
  });
});
