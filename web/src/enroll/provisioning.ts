// Builds the Android QR provisioning payload for the MDMesh agent. Crucially it carries the
// SERVER_URL extra, so one prebuilt APK works for any deployment (the agent reads it at enrollment).
//
// Deploy-specific values default to the shipped debug build and can be overridden at web-build time:
//   VITE_AGENT_PACKAGE     applicationId of the distributed APK (default com.mdmesh.agent.debug)
//   VITE_AGENT_CHECKSUM    PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM of the signing cert
//   VITE_AGENT_APK_URL     where the APK is hosted (default <origin>/files/agent.apk)

import { wifiProblem } from './checks';

const env = import.meta.env as Record<string, string | undefined>;

const AGENT_PACKAGE = env.VITE_AGENT_PACKAGE || 'com.mdmesh.agent.debug';
const ADMIN_COMPONENT = `${AGENT_PACKAGE}/com.mdmesh.agent.admin.AdminReceiver`;
const SIGNATURE_CHECKSUM = env.VITE_AGENT_CHECKSUM || 'YMEWhg_-ydciRFgtdOZbQ-W_aPPz7orHKuqfn13Ujaw';

/** Server origin — same-origin in production (Caddy serves the SPA and proxies the API). */
export function serverBaseUrl(): string {
  return window.location.origin;
}

export function agentApkUrl(): string {
  // VITE_AGENT_APK_URL may be absolute (https://…) or origin-relative (/update/agent.apk → resolved
  // against this deployment's origin). Release builds set it to the supervisor's verified APK mirror.
  const u = env.VITE_AGENT_APK_URL;
  if (u) return u.startsWith('/') ? `${serverBaseUrl()}${u}` : u;
  return `${serverBaseUrl()}/files/agent.apk`;
}

export type WifiSecurity = 'WPA' | 'WEP' | 'NONE' | 'EAP';
/** Optional Wi-Fi to bake into the QR so the device joins it DURING provisioning (before it downloads
 *  the agent). The password is embedded in the QR in plaintext — that's inherent to Android QR Wi-Fi. */
export interface WifiConfig {
  ssid: string;
  password?: string;
  security?: WifiSecurity;
}

/**
 * Entries the configuration's Extra QR parameters can never replace: they say which agent is installed, where from,
 * how it is verified, and which server and token it enrolls with.
 */
export const PROTECTED_QR_KEYS: ReadonlySet<string> = new Set([
  'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME',
  'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM',
  'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM',
  'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION',
  'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME',
  'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE',
]);

/** The configuration's Extra QR parameters, read: the entries to add, or why they can't be. */
export interface ExtraQrParameters {
  entries: Record<string, unknown>;
  /** Protected entries that were left out (see PROTECTED_QR_KEYS). */
  ignored: string[];
  /** Set when the text isn't JSON: nothing is added then. */
  error: string | null;
}

/**
 * Reads a configuration's Extra QR parameters (`qrParameters`). Two forms are accepted: a JSON object, or its members
 * without the braces, one or more per line, as Headwind's "Extra QR parameters" field took them, e.g.
 *
 *     "android.app.extra.PROVISIONING_LOCALE": "fr_FR",
 *     "android.app.extra.PROVISIONING_TIME_ZONE": "Europe/Paris"
 *
 * A trailing comma is allowed. Empty or blank text adds nothing.
 */
export function parseExtraQrParameters(text: unknown): ExtraQrParameters {
  const none: ExtraQrParameters = { entries: {}, ignored: [], error: null };
  if (typeof text !== 'string' || text.trim() === '') return none;
  let body = text.trim();
  if (body.startsWith('{')) body = body.replace(/,\s*}\s*$/, '}');
  else if (!body.startsWith('[')) body = `{${body.replace(/,\s*$/, '')}}`;
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    return { ...none, error: `Extra QR parameters aren't valid JSON: ${(e as Error).message}` };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ...none, error: 'Extra QR parameters must be JSON members ("key": value), or one JSON object.' };
  }
  const entries: Record<string, unknown> = {};
  const ignored: string[] = [];
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (PROTECTED_QR_KEYS.has(key)) ignored.push(key);
    else entries[key] = value;
  }
  return { entries, ignored, error: null };
}

/**
 * The provisioning JSON a fresh device scans (6-tap → QR scanner). Pass `wifi` to pre-connect it, and `extras` (from
 * {@link parseExtraQrParameters}) for the configuration's own entries: they are added after the core ones and can
 * change any but the protected ones; the Wi-Fi chosen on the Enroll page wins over Wi-Fi entries in them.
 */
export function buildProvisioningPayload(token: string, wifi?: WifiConfig, extras?: Record<string, unknown>): string {
  const payload: Record<string, unknown> = {
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': ADMIN_COMPONENT,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': SIGNATURE_CHECKSUM,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': agentApkUrl(),
    'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE': {
      'com.mdmesh.ENROLL_TOKEN': token,
      'com.mdmesh.SERVER_URL': serverBaseUrl(),
    },
    'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
  };
  for (const [key, value] of Object.entries(extras ?? {})) {
    if (!PROTECTED_QR_KEYS.has(key)) payload[key] = value;
  }
  const ssid = wifi?.ssid?.trim();
  // A network the device can't join (no password, EAP) would stop setup at the Wi-Fi step: left out instead, the
  // device asks for Wi-Fi on its screen. The Enroll page says why (enroll/checks.ts).
  if (ssid && !wifiProblem(wifi)) {
    const security: WifiSecurity = wifi?.security ?? (wifi?.password ? 'WPA' : 'NONE');
    payload['android.app.extra.PROVISIONING_WIFI_SSID'] = ssid;
    payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = security;
    if (security !== 'NONE' && wifi?.password) {
      payload['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifi.password;
    }
  }
  return JSON.stringify(payload);
}
