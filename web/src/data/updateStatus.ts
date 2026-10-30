// Wording and rules for the supervisor's update status, shared by the Overview server card, the sidebar's update
// card and Settings → Updates.
import type { UpdateStatus } from '../api/updates';

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
