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
  const read = readQrParameters(text);
  if ('error' in read) return { entries: {}, ignored: [], error: read.error };
  const entries: Record<string, unknown> = {};
  const ignored: string[] = [];
  for (const [key, value] of Object.entries(read.entries)) {
    if (PROTECTED_QR_KEYS.has(key)) ignored.push(key);
    else entries[key] = value;
  }
  return { entries, ignored, error: null };
}

/** Every entry of a configuration's Extra QR parameters, protected ones included, or why the text can't be read. */
export function readQrParameters(text: unknown): { entries: Record<string, unknown> } | { error: string } {
  if (typeof text !== 'string' || text.trim() === '') return { entries: {} };
  let body = text.trim();
  if (body.startsWith('{')) body = body.replace(/,\s*}\s*$/, '}');
  else if (!body.startsWith('[')) body = `{${body.replace(/,\s*$/, '')}}`;
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    return { error: `invalid JSON (${(e as Error).message})` };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { error: 'not JSON members ("key": value) or one JSON object' };
  }
  return { entries: parsed as Record<string, unknown> };
}

/** What a configuration's QR entries do, for the Enroll page's summary (it words them). */
export interface QrExtrasSummary {
  locale: string | null;
  timeZone: string | null;
  skipIntro: boolean;
  mobileData: boolean;
  hiddenWifi: boolean;
  /** Entries other than those above. */
  others: number;
}

export function summarizeQrExtras(entries: Record<string, unknown>): QrExtrasSummary {
  const p = QR_EXTRA_PREFIX;
  const known = ['LOCALE', 'TIME_ZONE', 'SKIP_EDUCATION_SCREENS', 'USE_MOBILE_DATA', 'WIFI_HIDDEN'].map((k) => `${p}PROVISIONING_${k}`);
  const text = (k: string) => {
    const v = entries[`${p}PROVISIONING_${k}`];
    return typeof v === 'string' && v ? v : null;
  };
  return {
    locale: text('LOCALE'),
    timeZone: text('TIME_ZONE'),
    skipIntro: entries[`${p}PROVISIONING_SKIP_EDUCATION_SCREENS`] === true,
    mobileData: entries[`${p}PROVISIONING_USE_MOBILE_DATA`] === true,
    hiddenWifi: entries[`${p}PROVISIONING_WIFI_HIDDEN`] === true,
    others: Object.keys(entries).filter((k) => !known.includes(k)).length,
  };
}

/** The `android.app.extra.` prefix every provisioning entry carries. */
export const QR_EXTRA_PREFIX = 'android.app.extra.';

/**
 * Entries the configuration editor shows as settings of their own (names without the prefix). They are kept in the
 * Extra QR parameters, first, one per line, and the editor's Extra QR parameters field shows only what follows them.
 */
export const QR_SETTING_KEYS: readonly string[] = [
  'PROVISIONING_WIFI_HIDDEN',
  'PROVISIONING_SKIP_EDUCATION_SCREENS',
  'PROVISIONING_LOCALE',
  'PROVISIONING_TIME_ZONE',
];

/** The Extra QR parameters, split: the settings (by short name) and the other entries' text, as typed. */
export interface QrParametersSplit {
  settings: Record<string, unknown>;
  rest: string;
}

const SETTING_LINE = new RegExp(
  `^\\s*"${QR_EXTRA_PREFIX.replace(/\./g, '\\.')}(${QR_SETTING_KEYS.join('|')})"\\s*:\\s*(.+?)\\s*,?\\s*$`,
);

/**
 * Splits the Extra QR parameters into the settings and the rest. The settings are the leading lines the editor
 * writes; the rest is kept exactly as typed, even when it isn't valid JSON yet. A setting written anywhere else stays
 * in the rest, where the editor points it out (settingsInRest): nothing is moved without the user seeing it.
 */
export function splitQrParameters(text: unknown): QrParametersSplit {
  const lines = typeof text === 'string' ? text.split('\n') : [];
  const settings: Record<string, unknown> = {};
  let i = 0;
  for (; i < lines.length; i++) {
    const m = SETTING_LINE.exec(lines[i]);
    if (!m) break;
    try {
      settings[m[1]] = JSON.parse(m[2]);
    } catch {
      break;
    }
  }
  return { settings, rest: lines.slice(i).join('\n') };
}

/**
 * The Extra QR parameters text for these settings and other entries: the settings that are set (not null, empty or
 * false, Android's own default for each), one per line, then the rest as typed. An object typed as the rest is
 * written back as members, so the two join.
 */
export function joinQrParameters(settings: Record<string, unknown>, rest: string): string {
  const lines = QR_SETTING_KEYS.filter((k) => settings[k] != null && settings[k] !== false && settings[k] !== '').map(
    (k) => `${JSON.stringify(QR_EXTRA_PREFIX + k)}: ${JSON.stringify(settings[k])}`,
  );
  let tail = rest.trim() === '' ? '' : rest;
  if (tail.trim().startsWith('{')) {
    const read = readQrParameters(tail);
    if (!('error' in read)) tail = membersText(read.entries);
  }
  if (tail) lines.push(tail);
  return lines.join(',\n');
}

/** Entries of the rest that have a setting of their own: that setting wins over them once it is set. */
export function settingsInRest(rest: string): string[] {
  const read = readQrParameters(rest);
  if ('error' in read) return [];
  return QR_SETTING_KEYS.filter((k) => QR_EXTRA_PREFIX + k in read.entries);
}

function membersText(entries: Record<string, unknown>): string {
  return Object.entries(entries)
    .map(([k, v]) => `${JSON.stringify(k)}: ${JSON.stringify(v)}`)
    .join(',\n');
}

/**
 * What a configuration adds to the enrollment QR: its setup settings, the other Extra QR parameters (left out, with
 * the reason, while they aren't valid JSON), and PROVISIONING_USE_MOBILE_DATA when "Enroll over mobile data" is on.
 * A setting that is set wins over the same entry among the others; those win over mobile data.
 */
export function configurationQrExtras(config: Record<string, unknown> | null | undefined): ExtraQrParameters {
  const split = splitQrParameters(config?.qrParameters);
  const others = parseExtraQrParameters(split.rest);
  const entries: Record<string, unknown> = {};
  if (config?.mobileEnrollment === true) entries[`${QR_EXTRA_PREFIX}PROVISIONING_USE_MOBILE_DATA`] = true;
  Object.assign(entries, others.entries);
  for (const [k, v] of Object.entries(split.settings)) {
    if (v != null && v !== false && v !== '') entries[QR_EXTRA_PREFIX + k] = v;
  }
  return { entries, ignored: others.ignored, error: others.error };
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
