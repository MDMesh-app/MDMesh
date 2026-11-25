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
  wifiExtrasLeftOut,
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

  it('keeps only a hidden network or a proxy with the page network, and lists what it leaves out', () => {
    const extras = { [`${W}HIDDEN`]: true, [`${W}PROXY_HOST`]: 'p', [`${W}EAP_METHOD`]: 'PEAP', [`${W}IDENTITY`]: 'me' };
    expect(Object.keys(extrasInCode(extras, true)).sort()).toEqual([`${W}HIDDEN`, `${W}PROXY_HOST`]);
    expect(wifiExtrasLeftOut(extras, true).sort()).toEqual([`${W}EAP_METHOD`, `${W}IDENTITY`]);
    expect(wifiExtrasLeftOut(extras, false)).toHaveLength(4);
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
