// Wording and rules for the supervisor's update status, shared by the Overview server card, the sidebar's update
// card and Settings → Updates.
import { isApplyTerminal, type UpdateStatus } from '../api/updates';
import { compareVersions } from './releaseNotes';

/** The live line for each apply phase (the v0.3 console's UpdateBanner wording). */
export const APPLY_PHASE_LABEL: Readonly<Record<string, string>> = {
  authorizing: 'Authorizing…',
  backup: 'Backing up the database…',
  pull: 'Downloading the new images…',
  recreate: 'Restarting services…',
  healthcheck: 'Checking health…',
  rollback: 'Rolling back…',
};

export const applyPhaseLabel = (phase: string): string => APPLY_PHASE_LABEL[phase] ?? phase;

/** The supervisor's placeholder error until its first GitHub poll after a (re)start: not a failed check. */
const NOT_POLLED_YET = 'not polled yet';

/** The last update check's error, or null when it succeeded or simply hasn't run yet. */
export function lastCheckError(s: UpdateStatus | null | undefined): string | null {
  const e = s?.error;
  return e && e !== NOT_POLLED_YET ? e : null;
}

/** A release link is shown only when it is an https: URL (it comes from the release feed, not from us). */
export function safeReleaseUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    return new URL(url).protocol === 'https:' ? url : null;
  } catch {
    return null;
  }
}

const RELEASE_VERSION = /^\d+\.\d+\.\d+$/;

/**
 * A newer release, as every update hint shows it: on offer (`ready`), or found but not installable by this server's
 * updater (`blocked`: the supervisor's `blocked`, absent before 0.6.0), with the security releases this server is
 * missing (the supervisor's `missedSecurity`, from the verified manifest only; absent before 0.5.0). For `ready` they
 * are the ones the update skips over; for `blocked`, also the release itself when it has security fixes. Null when
 * there's nothing newer to show.
 */
export interface UpdateOffer {
  kind: 'ready' | 'blocked';
  latest: string;
  missed: string[];
}
export function updateOffer(s: UpdateStatus | null | undefined): UpdateOffer | null {
  if (!s?.latest) return null;
  const kind = s.updateAvailable ? 'ready' : s.blocked ? 'blocked' : null;
  if (!kind) return null;
  const missed = Array.isArray(s.missedSecurity)
    ? s.missedSecurity.filter((v): v is string => typeof v === 'string' && RELEASE_VERSION.test(v))
    : [];
  return { kind, latest: s.latest, missed };
}

/** The security releases this server is missing while a newer release is shown (updateOffer), oldest first. */
export function missedSecurity(s: UpdateStatus | null | undefined): string[] {
  return updateOffer(s)?.missed ?? [];
}

/** "MDMesh X is the latest release" / "Up to date": the last check worked, and found nothing newer on offer, blocked
 *  or unverified. */
export function upToDate(s: UpdateStatus | null | undefined): boolean {
  return !!s && !lastCheckError(s) && !updateOffer(s) && !unverifiedRelease(s);
}

/** A newer release was found whose signature couldn't be verified (never offered or installed). */
export function unverifiedRelease(s: UpdateStatus | null | undefined): boolean {
  return (
    !!s &&
    !s.updateAvailable &&
    !s.verified &&
    !!s.latest &&
    RELEASE_VERSION.test(s.latest) &&
    RELEASE_VERSION.test(s.current) &&
    compareVersions(s.latest, s.current) > 0
  );
}

/**
 * The updater has checked and has no agent APK to offer, and it is the one serving /files/agent.apk, which the
 * enrollment QR code points at: a device that scans the code can't install the agent. (A native install serves its
 * last published copy; a supervisor before 0.6.0 doesn't say, and serves it on the installs that update themselves.
 * A console without a reachable updater can't tell.)
 */
export function agentUnavailable(s: UpdateStatus | null | undefined): boolean {
  if (!s || !s.checkedAt || s.apk) return false;
  return s.apkServed ?? s.applySupported !== false;
}

/**
 * Settings → Agent rollout: the agent of a newer release, which comes with that release's server and isn't offered
 * until the server runs it (the updater never offers an agent ahead of the server). Null when there's none.
 */
export function agentAfterServerUpdate(s: UpdateStatus | null | undefined): string | null {
  if (!s?.latest || !RELEASE_VERSION.test(s.latest) || !RELEASE_VERSION.test(s.current)) return null;
  if (compareVersions(s.latest, s.current) <= 0 || s.apk?.version === s.latest) return null;
  return s.latest;
}

/**
 * An active rollout's agent is newer than the server (after a rollback, say): the updater refuses to hand that agent
 * out (HTTP 409), so devices that haven't installed it yet can't. The server's version when so, else null.
 */
export function rolloutAheadOfServer(targetVersion: string | null | undefined, s: UpdateStatus | null | undefined): string | null {
  if (!targetVersion || !s || !RELEASE_VERSION.test(targetVersion) || !RELEASE_VERSION.test(s.current)) return null;
  return compareVersions(targetVersion, s.current) > 0 ? s.current : null;
}

/** The plain rule, for Rollout: the newer release's agent comes after its server. */
export const serverFirstText = (version: string): string =>
  `Agent ${version} comes with server ${version}: update the server first, then roll it out here.`;

/** A blocked release, in a few words for the sidebar and the Overview (its cause, unless security comes first). */
export function blockedShort(s: UpdateStatus, missed: readonly string[]): string {
  if (missed.length > 0) return `This server is missing security fixes from ${joinVersions(missed)}, and can’t install them itself.`;
  switch (s.blockedCode) {
    case 'platform':
      return `Its images don’t include this server’s platform${s.platform ? ` (${s.platform})` : ''}.`;
    case 'owner':
      return 'Its images are published under another owner.';
    case 'digests':
      return 'Its images aren’t pinned by digest.';
    default:
      return 'This server can’t install it itself.';
  }
}

/** Settings → Updates: why a blocked release can't be installed here, and what to do. */
export function blockedText(s: UpdateStatus): string {
  switch (s.blockedCode) {
    case 'platform':
      return `Its images don’t include this server’s platform${s.platform ? ` (${s.platform})` : ''}. This server keeps running ${s.current}. To follow later releases, wait for one with images for this platform, or move to the native install (DEPLOY.md, Option C), which runs on x86_64 and Arm64.`;
    case 'owner':
      return 'It publishes its images under another owner than this server pulls from. Set GITHUB_REPO or IMAGE_OWNER in the server’s .env to match, then run docker compose up -d.';
    case 'digests':
      return 'Its signed manifest doesn’t pin its images by digest, so it can only be installed by hand (docs/UPGRADING.md).';
    default:
      return s.blocked ? `The updater says: ${s.blocked}.` : '';
  }
}

/** "0.1.13", "0.1.13 and 0.1.14", "0.1.12, 0.1.13 and 0.1.14". */
export function joinVersions(vs: readonly string[]): string {
  if (vs.length <= 1) return vs[0] ?? '';
  return `${vs.slice(0, -1).join(', ')} and ${vs[vs.length - 1]}`;
}

/** The line under an update that skips security releases (the sidebar card, the Overview's Server card). */
export const missedSecurityShort = (vs: readonly string[]): string =>
  `Includes security fixes from ${joinVersions(vs)} that this server is missing.`;

/** Settings → Updates: the notice's text (`blocked`: the release that has them can't be installed here). */
export const missedSecurityText = (vs: readonly string[], current: string, latest: string, blocked = false): string =>
  blocked
    ? `${joinVersions(vs)} fixed security issues this server doesn’t have yet. It runs ${current}, and can’t install ${latest} itself (below).`
    : `${joinVersions(vs)} fixed security issues this server doesn’t have yet. It runs ${current}, and updating to ${latest} includes those fixes.`;

/** The GitHub repository page a release link belongs to (`https://github.com/<owner>/<repo>`), or null. */
function releaseRepo(releaseUrl: string | null | undefined): string | null {
  const safe = safeReleaseUrl(releaseUrl);
  if (!safe) return null;
  const u = new URL(safe);
  if (u.hostname !== 'github.com' || u.port || u.username || u.password || u.search || u.hash) return null;
  const m = /^\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)\/releases\/tag\/[^/]+$/.exec(u.pathname);
  return m ? `https://github.com/${m[1]}/${m[2]}` : null;
}

/**
 * The notice's link, on the offered release's repository (its link must be an https github.com release page): one
 * missed release links to its own page ("What 0.1.13 fixed"); several link to the releases list ("Release notes").
 */
export function missedSecurityLink(
  vs: readonly string[],
  releaseUrl: string | null | undefined,
): { href: string; label: string } | null {
  const repo = releaseRepo(releaseUrl);
  if (!repo || vs.length === 0) return null;
  return vs.length === 1
    ? { href: `${repo}/releases/tag/v${vs[0]}`, label: `What ${vs[0]} fixed` }
    : { href: `${repo}/releases`, label: 'Release notes' };
}

/** Settings' section list: the Updates entry's meta. An apply running or failed comes first; then missing security
 *  fixes in amber ("Security fixes"), then a release this server can't install (quiet), then a plain ready update. */
export function updatesNavMeta(s: UpdateStatus | null | undefined): { meta?: string; metaTone?: 'ok' | 'warn' | 'alert' } {
  const apply = s?.apply;
  if (apply && !isApplyTerminal(apply.phase)) return { meta: 'Updating', metaTone: 'warn' };
  if (apply && (apply.phase === 'failed' || apply.phase === 'rolled_back')) return { meta: 'Failed', metaTone: 'alert' };
  const offer = updateOffer(s);
  if (offer && offer.missed.length > 0) return { meta: 'Security fixes', metaTone: 'warn' };
  if (offer?.kind === 'blocked') return { meta: 'Can’t install' };
  return offer ? { meta: '1 ready', metaTone: 'ok' } : {};
}
