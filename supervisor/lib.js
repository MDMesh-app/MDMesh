// Pure helpers for the updater supervisor — no I/O, unit-tested (supervisor/test.js).

function parseSemver(v) {
  const m = String(v || '').replace(/^v/, '').match(/^(\d+)\.(\d+)\.(\d+)/);
  return m ? [+m[1], +m[2], +m[3]] : null;
}

function semverGt(a, b) {
  const x = parseSemver(a), y = parseSemver(b);
  if (!x || !y) return false;
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i];
  return false;
}

/** Newest non-draft release for the channel that actually ships a signed manifest. */
function pickRelease(releases, channel) {
  const allowPrerelease = channel === 'beta';
  return (releases || [])
    .filter((r) => !r.draft
      && (allowPrerelease || !r.prerelease)
      && (r.assets || []).some((a) => a.name === 'manifest.json'))
    .sort((a, b) => (semverGt((a.tag_name || '').replace(/^v/, ''), (b.tag_name || '').replace(/^v/, '')) ? -1 : 1))[0]
    || null;
}

/** Build the status object the console + recovery page read. Update only offered if VERIFIED. */
function shapeStatus({ current, manifest, verified, checkedAt, error }) {
  const latest = manifest && manifest.version;
  return {
    current,
    latest: latest || null,
    updateAvailable: !!(verified && latest && semverGt(latest, current)),
    verified: !!verified,
    channel: (manifest && manifest.channel) || null,
    checkedAt: checkedAt || null,
    error: error || null,
  };
}

/** Pull the image refs + target version a verified manifest applies. */
function imageTags(manifest) {
  const c = (manifest && manifest.components) || {};
  return {
    serverImage: c.serverImage || null,
    webImage: c.webImage || null,
    version: (manifest && manifest.version) || null,
  };
}

/** Resolve the downloadable APK for a verified release: the manifest's apk block + the GitHub asset's
 *  download URL (matched by file name). Returns null if any piece is missing. */
function apkAsset(release, manifest) {
  const apk = manifest && manifest.components && manifest.components.apk;
  if (!apk || !apk.file || apk.versionCode == null || !apk.sha256) return null;
  const asset = ((release && release.assets) || []).find((a) => a.name === apk.file);
  if (!asset || !asset.browser_download_url) return null;
  return {
    version: (manifest && manifest.version) || null,
    versionCode: apk.versionCode,
    sha256: apk.sha256,
    url: asset.browser_download_url,
  };
}

// Apply is a linear state machine the console + recovery page poll. The happy path advances
// authorizing → backup → pull → recreate → healthcheck → done. On any failure apply.sh jumps to
// `rollback` (transient) and ends at `rolled_back` or, if rollback itself fails, `failed`.
const APPLY_PHASES = ['authorizing', 'backup', 'pull', 'recreate', 'healthcheck', 'done'];
const APPLY_TERMINAL = ['done', 'rolled_back', 'failed'];

/** Next happy-path phase after `p`, or null at/after the end. */
function nextPhase(p) {
  const i = APPLY_PHASES.indexOf(p);
  return i < 0 || i >= APPLY_PHASES.length - 1 ? null : APPLY_PHASES[i + 1];
}

/** True once an apply has reached a state the UI should stop polling on. */
function isTerminal(p) {
  return APPLY_TERMINAL.includes(p);
}

/** True iff `buf` hashes to `expectedSha` (hex, case-insensitive). The gate that decides whether a
 *  downloaded APK is allowed to be published to devices — keep it pure + tested. */
function sha256Matches(buf, expectedSha) {
  if (!expectedSha) return false;
  const got = require('crypto').createHash('sha256').update(buf).digest('hex');
  return got.toLowerCase() === String(expectedSha).toLowerCase();
}

module.exports = {
  parseSemver, semverGt, pickRelease, shapeStatus,
  imageTags, nextPhase, isTerminal, APPLY_PHASES, APPLY_TERMINAL,
  apkAsset, sha256Matches,
};
