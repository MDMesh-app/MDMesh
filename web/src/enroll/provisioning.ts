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
  // MDMesh's own defaults: system apps stay enabled (the Play Store among them) and setup doesn't stop to encrypt.
  'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED',
  'android.app.extra.PROVISIONING_SKIP_ENCRYPTION',
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
  const entries: Record<string, unknown> = Object.create(null);
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
    // Every entry the summary doesn't word still goes in the code: count it (a string "true", LOCALE: true, false).
    others: Object.entries(entries).filter(([k, v]) => {
      if (k === `${p}PROVISIONING_LOCALE` || k === `${p}PROVISIONING_TIME_ZONE`) return !(typeof v === 'string' && v);
      return !known.includes(k) || v !== true;
    }).length,
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

/** A setting as joinQrParameters writes it: `"android.app.extra.KEY": value`, one per line, comma optional. */
const SETTING_LINE = new RegExp(
  `^"${QR_EXTRA_PREFIX.replace(/\./g, '\\.')}(${QR_SETTING_KEYS.join('|')})": (.+?),?$`,
);

/** A setting is set unless null, empty or false: Android's own default for each, never written. */
const isSet = (v: unknown) => v != null && v !== false && v !== '';

/**
 * Splits the Extra QR parameters into the settings and the rest, the way joinQrParameters writes them: the settings
 * first, one line each, in QR_SETTING_KEYS order and only when set, then a blank line, then the rest exactly as typed.
 * Text that isn't in that shape is all rest (Headwind's form, an object, a hand edit): a setting entry in it stays
 * there, where the editor points it out (settingsInRest), and is never taken out without the user seeing it. A
 * leading blank line means no settings (joinQrParameters writes one when the typed text could read as settings).
 */
export function splitQrParameters(text: unknown): QrParametersSplit {
  const all = typeof text === 'string' ? text : '';
  const lines = all.split('\n');
  // A leading blank line: no settings, and the rest follows exactly as typed (see joinQrParameters).
  if (lines.length > 1 && lines[0] === '') return { settings: {}, rest: lines.slice(1).join('\n') };
  const settings: Record<string, unknown> = {};
  let last = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i] === '' && i > 0) return { settings, rest: lines.slice(i + 1).join('\n') };
    const m = SETTING_LINE.exec(lines[i]);
    const index = m ? QR_SETTING_KEYS.indexOf(m[1]) : -1;
    let value: unknown;
    try {
      value = m ? JSON.parse(m[2]) : undefined;
    } catch {
      value = undefined;
    }
    if (!m || index <= last || !isSet(value)) return { settings: {}, rest: all };
    settings[m[1]] = value;
    last = index;
  }
  return { settings, rest: '' };
}

/**
 * The Extra QR parameters text for these settings and other entries (see splitQrParameters): the settings that are
 * set, one per line, then a blank line and the rest as typed. An object typed as the rest is written back as members,
 * so the text stays one list of JSON members, which Headwind's server QR takes as it is.
 */
export function joinQrParameters(settings: Record<string, unknown>, rest: string): string {
  const head = QR_SETTING_KEYS.filter((k) => isSet(settings[k])).map(
    (k) => `${JSON.stringify(QR_EXTRA_PREFIX + k)}: ${JSON.stringify(settings[k])}`,
  );
  let tail = rest.trim() === '' ? '' : rest;
  if (tail.trim().startsWith('{')) {
    const read = readQrParameters(tail);
    if (!('error' in read)) tail = membersText(read.entries);
  }
  if (!head.length) {
    // Typed text that would read back as settings (it starts with setting lines) or that starts with a blank line
    // gets a leading blank line, which says "no settings", so it always reads back exactly as typed.
    const ambiguous = tail.startsWith('\n') || Object.keys(splitQrParameters(tail).settings).length > 0;
    return ambiguous ? `\n${tail}` : tail;
  }
  return tail ? `${head.join(',\n')},\n\n${tail}` : head.join(',\n');
}

/**
 * Entries typed in the rest that never go in the code, without their prefix, by reason (see leftOutReason): Wi-Fi
 * (set above and on the Enroll page), entries MDMesh sets itself, keys it doesn't pass on, and values of the wrong
 * type. Settings typed here aren't listed: settingsInRest points them out. The editor shows both.
 */
export function unusedInRest(rest: string): Record<LeftOutReason, string[]> {
  const out: Record<LeftOutReason, string[]> = { protected: [], wifi: [], unsupported: [], invalid: [] };
  const read = readQrParameters(rest);
  if ('error' in read) return out;
  for (const [key, value] of Object.entries(read.entries)) {
    const prefixed = key.startsWith(QR_EXTRA_PREFIX);
    // Shown without the prefix when it has one, and in full when it doesn't (the prefix is what's missing).
    const shown = prefixed ? key.slice(QR_EXTRA_PREFIX.length) : key;
    // Hidden network typed here is a setting of its own (settingsInRest), with or without the page's Wi-Fi.
    const why = leftOutReason(key, value, key === `${WIFI_PREFIX}HIDDEN`);
    if (why && !(prefixed && QR_SETTING_KEYS.includes(shown) && why !== 'invalid')) out[why].push(shown);
  }
  return out;
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
  const entries: Record<string, unknown> = Object.create(null);
  if (config?.mobileEnrollment === true) entries[`${QR_EXTRA_PREFIX}PROVISIONING_USE_MOBILE_DATA`] = true;
  Object.assign(entries, others.entries);
  for (const [k, v] of Object.entries(split.settings)) {
    if (isSet(v)) entries[QR_EXTRA_PREFIX + k] = v;
  }
  return { entries, ignored: others.ignored, error: others.error };
}

const WIFI_PREFIX = 'android.app.extra.PROVISIONING_WIFI_';
/**
 * The one Wi-Fi entry a configuration adds, to the page's own network: a hidden network (its Hidden network setting).
 * Wi-Fi is set in the configuration's Enrollment section and on the Enroll page; any other Wi-Fi entry (a network, a
 * password, EAP keys, a proxy) is left out.
 */
const EXTRA_WIFI_KEYS: ReadonlySet<string> = new Set([`${WIFI_PREFIX}HIDDEN`]);

const isBool = (v: unknown) => typeof v === 'boolean';

/** A time zone the browser's IANA database knows, aliases included (UTC, Etc/GMT+1, US/Eastern); not "Europe/Pariss". */
function knownTimeZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}
const shortText = (max: number, pattern?: RegExp) => (v: unknown) =>
  typeof v === 'string' && v.length > 0 && v.length <= max && (!pattern || pattern.test(v));

/**
 * The provisioning entries a configuration may add, with the value each takes. Anything else (an unknown key, a value
 * of the wrong type or size) is left out, and the editor and the Enroll page say so: a configuration can only make
 * the setup choices listed here, never change how Android provisions the device in other ways.
 */
// A Map, not an object: a typed key such as "constructor" or "__proto__" must not find an inherited member.
export const QR_EXTRA_TYPES: ReadonlyMap<string, (v: unknown) => boolean> = new Map<string, (v: unknown) => boolean>([
  ['PROVISIONING_LOCALE', shortText(16, /^[a-z]{2,3}(_[A-Z]{2})?$/)],
  ['PROVISIONING_TIME_ZONE', (v) => shortText(64, /^[A-Za-z0-9_+\-/]+$/)(v) && knownTimeZone(v as string)],
  ['PROVISIONING_SKIP_EDUCATION_SCREENS', isBool],
  ['PROVISIONING_USE_MOBILE_DATA', isBool],
  ['PROVISIONING_WIFI_HIDDEN', isBool],
  ['PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT', isBool],
  ['PROVISIONING_LOCAL_TIME', (v) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0],
  ['PROVISIONING_ORGANIZATION_NAME', shortText(100)],
  ['PROVISIONING_SUPPORT_URL', shortText(200, /^https:\/\/\S+$/)],
]);

/** Whether a setting's value is one Android takes (QR_EXTRA_TYPES); an unset value counts as fine. */
export function qrSettingValueOk(qrKey: string, value: unknown): boolean {
  if (value == null || value === false || value === '') return true;
  return QR_EXTRA_TYPES.get(qrKey)?.(value) ?? false;
}

/** Why an entry stays out of the code (see extrasInCode). */
export type LeftOutReason = 'protected' | 'wifi' | 'unsupported' | 'invalid';

/** Why this entry stays out of the code, or null when it goes in. */
export function leftOutReason(key: string, value: unknown, joinsWifi: boolean): LeftOutReason | null {
  if (PROTECTED_QR_KEYS.has(key)) return 'protected';
  if (key.startsWith(WIFI_PREFIX) && (!joinsWifi || !EXTRA_WIFI_KEYS.has(key))) return 'wifi';
  const check = key.startsWith(QR_EXTRA_PREFIX) ? QR_EXTRA_TYPES.get(key.slice(QR_EXTRA_PREFIX.length)) : undefined;
  if (!check) return 'unsupported';
  return check(value) ? null : 'invalid';
}

/**
 * The configuration's entries that go in the code: only the ones QR_EXTRA_TYPES lists, with a value of their type.
 * Protected entries never do, and Wi-Fi is set in the configuration's Enrollment section and on the Enroll page, so
 * the one Wi-Fi entry that goes in, a hidden network, goes only with the page's own network (`joinsWifi`).
 */
export function extrasInCode(extras: Record<string, unknown>, joinsWifi: boolean): Record<string, unknown> {
  const out: Record<string, unknown> = Object.create(null);
  for (const [key, value] of Object.entries(extras)) if (!leftOutReason(key, value, joinsWifi)) out[key] = value;
  return out;
}

/** The configuration's entries extrasInCode leaves out (protected ones aside), by reason, for the Enroll page. */
export function extrasLeftOut(extras: Record<string, unknown>, joinsWifi: boolean): Record<'wifi' | 'unsupported' | 'invalid', string[]> {
  const out = { wifi: [] as string[], unsupported: [] as string[], invalid: [] as string[] };
  for (const [key, value] of Object.entries(extras)) {
    const why = leftOutReason(key, value, joinsWifi);
    if (why && why !== 'protected') out[why].push(key);
  }
  return out;
}

/**
 * QR sizes, in bytes of the provisioning JSON: the most a QR code holds at the error correction the console draws with
 * (level M, version 40), and the size above which the code gets dense enough for some setup cameras to struggle.
 */
export const QR_MAX_BYTES = 2331;
export const QR_DENSE_BYTES = 1500;

/** The size of a provisioning JSON in a QR code (UTF-8 bytes). */
export function qrPayloadBytes(payload: string): number {
  return new TextEncoder().encode(payload).length;
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
  const ssid = wifi?.ssid?.trim();
  // A network the device can't join (no password, EAP) would stop setup at the Wi-Fi step: left out instead, the
  // device asks for Wi-Fi on its screen. The Enroll page says why (enroll/checks.ts).
  const joinsWifi = !!ssid && !wifiProblem(wifi);
  Object.assign(payload, extrasInCode(extras ?? {}, joinsWifi));
  if (joinsWifi) {
    const security: WifiSecurity = wifi?.security ?? (wifi?.password ? 'WPA' : 'NONE');
    payload['android.app.extra.PROVISIONING_WIFI_SSID'] = ssid;
    payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = security;
    if (security !== 'NONE' && wifi?.password) {
      payload['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifi.password;
    }
  }
  return JSON.stringify(payload);
}
