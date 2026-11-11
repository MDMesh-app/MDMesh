import { describe, expect, it } from 'vitest';
import { addressProblem, wifiProblem } from './checks';
import { buildProvisioningPayload } from './provisioning';

describe('addressProblem', () => {
  it('accepts the public https address the server knows itself by', () => {
    expect(addressProblem('https://mdm.acme.test', 'https://mdm.acme.test')).toBeNull();
    expect(addressProblem('https://mdm.acme.test', 'https://mdm.acme.test/')).toBeNull();
    expect(addressProblem('https://mdm.acme.test', null)).toBeNull();
  });

  it('stops a code made on localhost, and points at the server address', () => {
    const p = addressProblem('http://localhost:8080', 'https://mdm.acme.test');
    expect(p?.tone).toBe('alert');
    expect(p?.text).toContain('only this computer can reach');
    expect(p?.text).toContain('Open the console at https://mdm.acme.test');
    expect(addressProblem('https://127.0.0.1:8443', null)?.tone).toBe('alert');
    expect(addressProblem('http://[::1]:8080', null)?.tone).toBe('alert');
  });

  it('stops a code made over http', () => {
    const p = addressProblem('http://mdm.acme.test', null);
    expect(p?.tone).toBe('alert');
    expect(p?.text).toContain('only over https://');
  });

  it('warns when the console is open at another address than the server setting', () => {
    const p = addressProblem('https://mdm-old.acme.test', 'https://mdm.acme.test');
    expect(p?.tone).toBe('warn');
    expect(p?.text).toContain('keep it until a factory reset');
  });

  it('warns about a local address', () => {
    expect(addressProblem('https://192.168.1.20', null)?.tone).toBe('warn');
    expect(addressProblem('https://10.0.0.5:8443', 'https://10.0.0.5:8443')?.tone).toBe('warn');
    expect(addressProblem('https://172.20.0.2', null)?.tone).toBe('warn');
    expect(addressProblem('https://mdm.local', null)?.tone).toBe('warn');
    expect(addressProblem('https://mdmesh', null)?.tone).toBe('warn');
    expect(addressProblem('https://172.40.0.2', null)).toBeNull();
  });

  it('ignores what it cannot parse', () => {
    expect(addressProblem('not a url', null)).toBeNull();
    expect(addressProblem('https://mdm.acme.test', 'not a url')).toBeNull();
  });
});

describe('wifiProblem', () => {
  it('passes a WPA network with a password, an open one, and no network', () => {
    expect(wifiProblem({ ssid: 'Office', password: 'correct horse', security: 'WPA' })).toBeNull();
    expect(wifiProblem({ ssid: 'Guest', security: 'NONE' })).toBeNull();
    expect(wifiProblem({ ssid: '  ' })).toBeNull();
    expect(wifiProblem(undefined)).toBeNull();
  });

  it('catches what would stop setup at the Wi-Fi step', () => {
    expect(wifiProblem({ ssid: 'Office', password: '', security: 'WPA' })).toContain('Enter the Wi-Fi password');
    expect(wifiProblem({ ssid: 'Office', password: '', security: 'WEP' })).toContain('Enter the Wi-Fi password');
    expect(wifiProblem({ ssid: 'Corp', password: 'x', security: 'EAP' })).toContain('EAP');
    expect(wifiProblem({ ssid: 'Office', password: 'short', security: 'WPA' })).toContain('at least 8');
  });
});

describe('buildProvisioningPayload Wi-Fi', () => {
  const extras = (wifi: Parameters<typeof buildProvisioningPayload>[1]) =>
    JSON.parse(buildProvisioningPayload('tok', wifi)) as Record<string, unknown>;

  it('puts a joinable network in the code', () => {
    const p = extras({ ssid: 'Office', password: 'correct horse', security: 'WPA' });
    expect(p['android.app.extra.PROVISIONING_WIFI_SSID']).toBe('Office');
    expect(p['android.app.extra.PROVISIONING_WIFI_PASSWORD']).toBe('correct horse');
  });

  it('leaves out a network the device could not join', () => {
    for (const wifi of [
      { ssid: 'Office', password: '', security: 'WPA' as const },
      { ssid: 'Corp', password: 'x', security: 'EAP' as const },
    ]) {
      const p = extras(wifi);
      expect(p['android.app.extra.PROVISIONING_WIFI_SSID']).toBeUndefined();
      expect(p['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE']).toBeUndefined();
    }
  });
});
