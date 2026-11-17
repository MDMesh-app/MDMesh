// Wording and rules for the supervisor's update status, shared by the Overview server card, the sidebar's update
// card and Settings → Updates.
import { isApplyTerminal, type UpdateStatus } from '../api/updates';

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
 * The security releases the offered update skips over, oldest first: the supervisor's `missedSecurity` (from the
 * verified manifest only; absent from a supervisor before 0.5.0). Empty unless an update is on offer.
 */
export function missedSecurity(s: UpdateStatus | null | undefined): string[] {
  if (!s?.updateAvailable || !s.latest || !Array.isArray(s.missedSecurity)) return [];
  return s.missedSecurity.filter((v): v is string => typeof v === 'string' && RELEASE_VERSION.test(v));
}

/** "0.1.13", "0.1.13 and 0.1.14", "0.1.12, 0.1.13 and 0.1.14". */
export function joinVersions(vs: readonly string[]): string {
  if (vs.length <= 1) return vs[0] ?? '';
  return `${vs.slice(0, -1).join(', ')} and ${vs[vs.length - 1]}`;
}

/** The line under an update that skips security releases (the sidebar card, the Overview's Server card). */
export const missedSecurityShort = (vs: readonly string[]): string =>
  `Includes security fixes from ${joinVersions(vs)} that this server is missing.`;

/** Settings → Updates: the notice's text. */
export const missedSecurityText = (vs: readonly string[], current: string, latest: string): string =>
  `${joinVersions(vs)} fixed security issues this server doesn’t have yet. It runs ${current}, and updating to ${latest} includes those fixes.`;

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

/** Settings' section list: the Updates entry's meta. An apply running or failed comes first; then skipped security
 *  fixes in amber ("Security fixes"), then a plain ready update. */
export function updatesNavMeta(s: UpdateStatus | null | undefined): { meta?: string; metaTone?: 'ok' | 'warn' | 'alert' } {
  const apply = s?.apply;
  if (apply && !isApplyTerminal(apply.phase)) return { meta: 'Updating', metaTone: 'warn' };
  if (apply && (apply.phase === 'failed' || apply.phase === 'rolled_back')) return { meta: 'Failed', metaTone: 'alert' };
  if (missedSecurity(s).length > 0) return { meta: 'Security fixes', metaTone: 'warn' };
  return s?.updateAvailable ? { meta: '1 ready', metaTone: 'ok' } : {};
}
