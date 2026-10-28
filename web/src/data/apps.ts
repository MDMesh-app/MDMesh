// App library rules for the Apps pages: what a library app installs, which URLs this server hosts (and hashes
// itself), what the Deploy list offers, and the deploy subject the deploy dialog takes.
import { appCategory, getVersions, type Application, type ApplicationVersion } from '../api/applications';
import { serverBaseUrl } from '../enroll/provisioning';

/** An app resolved to everything needed to deploy it. */
export interface DeploySubject {
  label: string;
  packageName: string;
  url: string;
  versionCode?: number;
  sha256?: string;
  /** Present only for library apps: required for assign-to-configuration. */
  applicationId?: number;
  /** Split-APK bundle parts: when set, push-to-device installs these together (config-assign ignores this). */
  parts?: { url: string; sha256?: string }[];
}

/**
 * How the role may deploy (ext/core/access.ts): push to devices (edit_devices), add to a configuration
 * (edit_applications).
 */
export interface DeployModes {
  devices: boolean;
  configuration: boolean;
}

/** Whether the role has a way to deploy this subject: only a library app can be added to a configuration. */
export function canDeploy(modes: DeployModes, subject: Pick<DeploySubject, 'applicationId'>): boolean {
  return modes.devices || (modes.configuration && subject.applicationId != null);
}

export const SHA256_HEX = /^[0-9a-fA-F]{64}$/;

/** Split-APK bundle containers the /bundle endpoint unpacks into installable parts. */
export const BUNDLE_EXTS = ['.xapk', '.apks', '.apkm', '.zip'] as const;
export function isBundleName(n: string): boolean {
  const lower = n.toLowerCase();
  return BUNDLE_EXTS.some((e) => lower.endsWith(e));
}

/** APKMirror / APKPure have no usable API and forbid embedding: they're search shortcuts into the Custom APK flow. */
export const EXT_SOURCES: ReadonlyArray<{ label: string; url: string }> = [
  { label: 'APKMirror', url: 'https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=' },
  { label: 'APKPure', url: 'https://apkpure.com/search?q=' },
];

/**
 * The version an app installs, for Deploy and the SHA-256 editor alike: its latest version (what configurations
 * install), else the newest one with something to install (a split-bundle version has parts but no single url).
 */
export function targetVersion(app: Application, versions: ApplicationVersion[]): ApplicationVersion | undefined {
  const installable = versions.filter((v) => v.url || v.parts);
  return (
    installable.find((v) => v.id === app.latestVersion) ??
    [...installable].sort((a, b) => (b.versionCode ?? 0) - (a.versionCode ?? 0))[0]
  );
}

/**
 * A URL of a file this server hosts: the server hashes it itself, so the admin never has to supply a SHA-256.
 * Mirrors the server's HostedFiles: `<base.url>/files/` or `<origin of base.url>/files/`. `baseUrl` is the
 * server's base.url; until it is known (or if it can't be read) the console's own origin stands in.
 */
export function isHostedHere(url: string | undefined, baseUrl: string | null): boolean {
  if (!url) return false;
  const u = url.trim();
  const base = (baseUrl ?? serverBaseUrl()).trim().replace(/\/+$/, '');
  let origin = base;
  try {
    origin = new URL(base).origin;
  } catch {
    /* keep the base as given */
  }
  return u.startsWith(`${base}/files/`) || u.startsWith(`${origin}/files/`);
}

/** Resolve a library app to everything the deploy dialog needs (the version it installs, its hash and parts). */
export async function resolveApp(app: Application): Promise<DeploySubject> {
  let url = app.url;
  let versionCode = app.versionCode;
  let sha256: string | undefined;
  let partsJson: string | undefined = app.parts;
  try {
    const latest = targetVersion(app, await getVersions(app.id));
    if (latest) {
      url = latest.url ?? url;
      versionCode = latest.versionCode ?? versionCode;
      sha256 = latest.apkHash || undefined;
      partsJson = latest.parts ?? partsJson;
    }
  } catch {
    /* fall back to the app's own fields */
  }
  let parts: { url: string; sha256?: string }[] | undefined;
  if (partsJson) {
    try {
      parts = (JSON.parse(partsJson) as { url: string; sha256?: string }[]).map((p) => ({ url: p.url, sha256: p.sha256 }));
    } catch {
      /* malformed parts: ignore, fall back to url */
    }
  }
  if (!url && !(parts && parts.length)) throw new Error('This app has no APK to deploy.');
  return { label: app.name, packageName: app.pkg, url: url ?? '', versionCode, sha256, applicationId: app.id, parts };
}

// Headwind seed apps point at h-mdm.com files that no longer exist. The server removes them at startup, and this keeps
// any it could not (e.g. still referenced elsewhere) off the Deploy list. Only `url` reaches the console.
const HEADWIND_SEED_URL = /^https?:\/\/([^/]*\.)?h-mdm\.com\/|_HMDM_/i;

/** Only uploaded apps with something to install (an APK URL or split-bundle parts) are deployable. */
export function isDeployableLibraryApp(app: Application): boolean {
  if (app.url && HEADWIND_SEED_URL.test(app.url)) return false;
  return appCategory(app) === 'uploaded' && Boolean(app.url || app.parts);
}

