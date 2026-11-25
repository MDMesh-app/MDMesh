import { describe, expect, it } from 'vitest';
import { buildProvisioningPayload, parseExtraQrParameters } from './provisioning';

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
