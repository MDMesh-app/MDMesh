// Whether this browser has seen a version's What's new. Once per version per browser: the key is
// `mdmesh-whatsnew:<version>` (`mdmesh-whatsnew:unreleased` in dev builds).
//
// Never on a first visit. A browser that had used the console before this page load has history: a What's new key
// of any version, the stored sign-in (`hmdm.admin.user`, which every console since 0.1 keeps while signed in), or
// the v0.3 console's density key (`mdmesh-density`), which every v0.3 console wrote on every load and this one never
// writes. The density key matters for the upgrade to 0.4.0: an open v0.3 tab gets 401s once the server restarts on
// the new version and deletes the stored sign-in, so after a reload it alone shows that the browser used the console.
// (`mdmesh-theme` is no sign: this console writes it on every load too, the first one included.) Without history the
// version is remembered as seen and the dialog doesn't open: a fresh install, or an admin's new browser. With it, an
// unseen version opens the dialog once after sign-in. History is read once, as the console starts, before anyone
// signs in on this page load.
//
// Storage that reads but refuses writes can't remember a version, so the dialog doesn't open by itself there (it
// would on every page load); what is marked seen is also kept in memory for the page load. And it opens by itself at
// most once per page load, whatever the server's version does after.

const PREFIX = 'mdmesh-whatsnew:';
/** auth/AuthContext.tsx's stored sign-in. */
const STORED_USER = 'hmdm.admin.user';
/** Written on every load by every v0.3 console (its Density setting), never by this one. */
const V03_DENSITY = 'mdmesh-density';

export const seenKey = (id: string) => `${PREFIX}${id}`;

function readHistory(): boolean {
  try {
    if (localStorage.getItem(STORED_USER) !== null || localStorage.getItem(V03_DENSITY) !== null) return true;
    for (let i = 0; i < localStorage.length; i++) {
      if (localStorage.key(i)?.startsWith(PREFIX)) return true;
    }
  } catch {
    /* storage unavailable: no history, so nothing opens by itself */
  }
  return false;
}

/** Whether this browser's storage keeps what is written to it. Storage that reads but refuses writes (a full quota, a
 * locked-down profile) would show the dialog again on every page load, so then it never opens by itself. */
function canRemember(): boolean {
  const probe = 'mdmesh-whatsnew-probe';
  try {
    localStorage.setItem(probe, '1');
    localStorage.removeItem(probe);
    return true;
  } catch {
    return false;
  }
}

let history = readHistory();
let writable = canRemember();
/** Versions marked seen on this page load, whatever the storage kept. */
let seenNow = new Set<string>();
/** The dialog has opened by itself on this page load: it doesn't again, for another version the server reports late. */
let shownNow = false;

/** Tests: read the browser's history again, as a new page load does (this page load's memory starts empty). */
export function readHistoryAgain(): void {
  history = readHistory();
  writable = canRemember();
  seenNow = new Set();
  shownNow = false;
}

export function isSeen(id: string): boolean {
  if (seenNow.has(id)) return true;
  try {
    return localStorage.getItem(seenKey(id)) !== null;
  } catch {
    return false;
  }
}

export function markSeen(id: string): void {
  seenNow.add(id);
  try {
    localStorage.setItem(seenKey(id), 'seen');
  } catch {
    /* storage unavailable: only this page load remembers it */
  }
}

/**
 * The dialog is opening by itself (WhatsNew.tsx): no other version opens it by itself on this page load. Marked as it
 * is decided, so two versions can't both open while their notes load; `false` takes it back when it didn't open.
 */
export function markShownByItself(shown = true): void {
  shownNow = shown;
}

/**
 * What to do with a version's What's new after sign-in: `seen` nothing; `fresh` (no history, or storage that can't
 * remember it) remember it, without opening; `show` open it and remember it. Once it has opened by itself on this
 * page load, every other version is `seen` until the next one.
 */
export function whatsNewOnSignIn(id: string): 'seen' | 'fresh' | 'show' {
  if (isSeen(id) || shownNow) return 'seen';
  return history && writable ? 'show' : 'fresh';
}
