// Checks the Enroll page runs before an admin hands a QR code to a device. A device keeps the server address from the
// code for good (only a factory reset changes it), and a Wi-Fi it can't join stops setup at the Wi-Fi step, so both
// are caught here, where they can still be fixed, rather than on the device.

import type { WifiConfig } from './provisioning';

export interface AddressProblem {
  /** alert: devices that scan this code can't enroll; warn: only some can, or later moves will hurt. */
  tone: 'alert' | 'warn';
  text: string;
}

function hostOf(u: URL): string {
  return u.hostname.replace(/^\[|\]$/g, '').toLowerCase();
}

function isLoopback(host: string): boolean {
  return host === 'localhost' || host.endsWith('.localhost') || host === '::1' || /^127\./.test(host) || host === '0.0.0.0';
}

function isPrivate(host: string): boolean {
  if (/^10\./.test(host) || /^192\.168\./.test(host) || /^169\.254\./.test(host)) return true;
  const m = /^172\.(\d+)\./.exec(host);
  if (m && Number(m[1]) >= 16 && Number(m[1]) <= 31) return true;
  if (/^f[cd][0-9a-f]{2}:/.test(host) || /^fe80:/.test(host)) return true;
  return host.endsWith('.local') || host.endsWith('.lan') || host.endsWith('.internal') || (!host.includes('.') && !host.includes(':'));
}

/**
 * What is wrong with the address a code made here gives devices: `origin` is where this console is open (the code
 * carries it), `baseUrl` the server's own setting (`base.url`, null when unknown). null when nothing is.
 */
export function addressProblem(origin: string, baseUrl: string | null): AddressProblem | null {
  let here: URL;
  try {
    here = new URL(origin);
  } catch {
    return null;
  }
  const host = hostOf(here);
  let server: URL | null = null;
  try {
    server = baseUrl && baseUrl.trim() ? new URL(baseUrl.trim()) : null;
  } catch {
    server = null;
  }
  const serverOrigin = server && server.protocol.startsWith('http') ? server.origin : null;
  const openThere = serverOrigin && serverOrigin !== here.origin ? ` Open the console at ${serverOrigin} and make the code there.` : '';

  if (isLoopback(host)) {
    return {
      tone: 'alert',
      text: `This console is open at ${here.origin}, which only this computer can reach, so a device that scans this code can't enroll.${openThere || ' Open the console at the server’s public https:// address and make the code there.'}`,
    };
  }
  if (here.protocol !== 'https:') {
    return {
      tone: 'alert',
      text: `This console is open over http://. Android enrolls only over https://, so a device that scans this code can't reach the server.${openThere || ' Open the console at its https:// address and make the code there.'}`,
    };
  }
  if (serverOrigin && serverOrigin !== here.origin) {
    return {
      tone: 'warn',
      text: `This console is open at ${here.origin}, but the server's address is ${serverOrigin}. The code gives devices ${here.origin}, and they keep it until a factory reset.${openThere}`,
    };
  }
  if (isPrivate(host)) {
    return {
      tone: 'warn',
      text: `Devices that scan this code use ${here.origin}, a local address: they enroll only on this network, and keep that address until a factory reset.`,
    };
  }
  return null;
}

/**
 * Why Wi-Fi from this form can't go into the code, or null. A QR code with a network the device can't join stops
 * setup at the Wi-Fi step; without one, the device asks for Wi-Fi on its screen.
 */
export function wifiProblem(wifi: WifiConfig | undefined): string | null {
  if (!wifi || !wifi.ssid.trim()) return null;
  const security = wifi.security ?? (wifi.password ? 'WPA' : 'NONE');
  if (security === 'EAP') {
    return "Enterprise (EAP) Wi-Fi can't be joined from a QR code, so it is left out: connect the device to Wi-Fi on its screen, or use a WPA or open network here.";
  }
  if (security !== 'NONE' && !wifi.password) {
    return 'Enter the Wi-Fi password, or choose Open. Until then the network is left out of the code and the device asks for Wi-Fi on its screen.';
  }
  if (security === 'WPA' && wifi.password && wifi.password.length < 8) {
    return 'A WPA password has at least 8 characters, so the device could not join with this one. It is left out of the code until it is fixed.';
  }
  return null;
}
