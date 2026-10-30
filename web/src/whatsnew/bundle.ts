// Which CHANGELOG.md section this console's What's new shows, from the notes bundled at build time
// (vite.changelog.ts, changelogNotes).
//
// The version is the one the server reports (`/update/status` `current`, which Settings and the sign-in page show):
// the supervisor knows it on every kind of install, while a console build knows its own only in release images
// (VITE_APP_VERSION). The build's version is the fallback when the supervisor can't be reached. A version this build
// has no section for shows nothing. In a dev build (`vite`) that has something under Unreleased, it is Unreleased.
import { hasUnreleased, load, versions } from 'virtual:mdmesh-changelog';
import { RELEASE_VERSION, type ChangelogSection } from './changelog';

/** The notes a console shows: `id` keys "seen" (seen.ts); `version` is null for Unreleased. */
export interface WhatsNewTarget {
  id: string;
  version: string | null;
}

export const UNRELEASED: WhatsNewTarget = { id: 'unreleased', version: null };

const release = (v: string | null | undefined): string | null => {
  const s = v?.trim().replace(/^v/, '');
  return s && RELEASE_VERSION.test(s) ? s : null;
};

/** The build's own version (release images only). */
export const BUILD_VERSION = release(import.meta.env.VITE_APP_VERSION);

/** The first of `candidates` (the server's version, then the build's) whose section this build carries. */
export function whatsNewTarget(candidates: ReadonlyArray<string | null | undefined>): WhatsNewTarget | null {
  if (hasUnreleased) return UNRELEASED;
  for (const c of candidates) {
    const v = release(c);
    if (v && versions.includes(v)) return { id: v, version: v };
  }
  return null;
}

/** The section's text (its chunk loads on first use); null if it isn't there or the chunk can't load. */
export async function loadSection(target: WhatsNewTarget): Promise<ChangelogSection | null> {
  try {
    const notes = await load();
    return target.version === null ? notes.unreleased : (notes.sections[target.version] ?? null);
  } catch {
    return null;
  }
}

/** The GitHub release of a version; for Unreleased, the changelog on main. */
export function releaseNotesUrl(target: WhatsNewTarget): string {
  return target.version === null
    ? 'https://github.com/MDMesh-app/MDMesh/blob/main/CHANGELOG.md'
    : `https://github.com/MDMesh-app/MDMesh/releases/tag/v${target.version}`;
}
