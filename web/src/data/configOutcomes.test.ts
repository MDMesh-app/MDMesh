import { describe, expect, it } from 'vitest';
import { classifyOutcome, outcomeKeyLabel } from './configOutcomes';

describe('outcomeKeyLabel', () => {
  it('names core’s keys after their configuration fields', () => {
    expect(outcomeKeyLabel('policies.usbStorage')).toBe('USB storage');
    expect(outcomeKeyLabel('kiosk')).toBe('Kiosk mode');
    expect(outcomeKeyLabel('location')).toBe('Location reporting');
    expect(outcomeKeyLabel('connectivity')).toBe('Connectivity');
  });

  it('names a server plugin’s key (policies.x.<plugin>.<key>) by its plugin and its key in words', () => {
    expect(outcomeKeyLabel('policies.x.netaccess.vpn')).toBe('netaccess: vpn');
    expect(outcomeKeyLabel('policies.x.net-access.blockTethering')).toBe('net-access: block tethering');
    expect(classifyOutcome('policies.x.netaccess.vpn', 'unsupported')).toMatchObject({ label: 'netaccess: vpn', text: 'Not supported' });
  });

  it('keeps acronyms in a server plugin’s key as they are', () => {
    expect(outcomeKeyLabel('policies.x.netaccess.enableUSB')).toBe('netaccess: enable USB');
    expect(outcomeKeyLabel('policies.x.netaccess.USBMode')).toBe('netaccess: USB mode');
    expect(outcomeKeyLabel('policies.x.netaccess.blockUSBTethering')).toBe('netaccess: block USB tethering');
    expect(outcomeKeyLabel('policies.x.netaccess.usbStorage')).toBe('netaccess: usb storage');
  });

  it('writes the mixed-case words it knows as they are spelt: IPv4, IPv6, Wi-Fi, 2FA', () => {
    expect(outcomeKeyLabel('policies.x.netaccess.enableIPv6')).toBe('netaccess: enable IPv6');
    expect(outcomeKeyLabel('policies.x.netaccess.IPv4Only')).toBe('netaccess: IPv4 only');
    expect(outcomeKeyLabel('policies.x.netaccess.allowWiFi')).toBe('netaccess: allow Wi-Fi');
    expect(outcomeKeyLabel('policies.x.netaccess.blockWiFiDirect')).toBe('netaccess: block Wi-Fi direct');
    expect(outcomeKeyLabel('policies.x.netaccess.enable2FA')).toBe('netaccess: enable 2FA');
    expect(outcomeKeyLabel('policies.x.netaccess.preferIPv6OverWiFi')).toBe('netaccess: prefer IPv6 over Wi-Fi');
    // Only as a whole word: "WiFix" is not Wi-Fi.
    expect(outcomeKeyLabel('policies.x.netaccess.useWiFix')).toBe('netaccess: use wi fix');
  });

  it('shows any other unknown key as it is', () => {
    expect(outcomeKeyLabel('policies.somethingNew')).toBe('policies.somethingNew');
    expect(outcomeKeyLabel('policies.x.netaccess')).toBe('policies.x.netaccess');
  });
});
