// Pure helpers for the updater supervisor — no I/O of their own (fetchAsset takes its fetch as a parameter),
// unit-tested (supervisor/test.js).

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

/** Why an Update must not start, or null if it may. `manifest` is the last VERIFIED manifest (null if none), `current`
 *  the running version, `skipVersion` the auto-apply skip (it never blocks a manual Update; it only marks a running
 *  version whose update failed). The version guard matters beyond the banner: re-applying the running (or an older)
 *  version would overwrite /backups/latest with a dump of the post-update database, leaving nothing to roll back to. */
function applyRefusal({ manifest, current, skipVersion }) {
  if (!manifest) return 'no verified update available';
  const to = manifest.version;
  if (!to) return 'the verified manifest has no version';
  if (!parseSemver(to)) return `the verified release version "${to}" is not a release version (X.Y.Z), so it cannot be applied`;
  if (!parseSemver(current)) return `the running version "${current}" is not a release version (X.Y.Z), so it cannot be compared: update by hand`;
  if (semverGt(to, current)) return null;
  if (to === current || !semverGt(current, to)) {
    return current === skipVersion
      ? `already running ${current}, whose update failed: if it is not healthy, use Roll back (/recovery) to return to the previous version`
      : `already running ${current}`;
  }
  return `already running ${current} (newer than ${to})`;
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
    apiUrl: asset.url || null, // the asset API URL: the only one a private repo serves bytes from (assetRequest)
  };
}

/** Parse `u` as a URL, or null. */
function parseUrl(u) {
  try { return new URL(String(u)); } catch { return null; }
}

/** Where and how to download a release asset. A PRIVATE repo 404s `browser_download_url` even with a token; only the
 *  asset API URL (`asset.url`) with `Accept: application/octet-stream` and the token serves the bytes. The token is
 *  attached ONLY when that URL is https on api.github.com (the release JSON is data: an asset URL pointing anywhere
 *  else must not receive it); otherwise the browser URL is used, never with the token. */
function assetRequest(asset, token) {
  const headers = { 'User-Agent': 'mdmesh-updater' };
  const api = token && asset && parseUrl(asset.url);
  if (api && api.protocol === 'https:' && api.hostname === 'api.github.com' && !api.username && !api.password) {
    return { url: asset.url, headers: { ...headers, Accept: 'application/octet-stream', Authorization: 'Bearer ' + token } };
  }
  return { url: (asset && asset.browser_download_url) || null, headers };
}

/** Download a release asset (manifest, signature or APK) per assetRequest, following redirects by hand. Every URL,
 *  the first and each redirect hop, must be https: nothing (least of all the token) goes over plain http. The asset
 *  API answers 302 to a short-lived signed CDN URL on another host: the token must never go there, so Authorization
 *  is dropped as soon as a redirect changes origin, and never re-added. (Node's fetch strips it too on a cross-origin
 *  redirect, but that is an implementation detail; this makes the rule explicit and testable.) `fetchImpl` is
 *  injected so tests can script the responses. Returns the final Response; the caller checks `ok`. */
async function fetchAsset(asset, token, fetchImpl = globalThis.fetch, maxRedirects = 5) {
  const req = assetRequest(asset, token);
  if (!req.url) throw new Error('asset has no download URL');
  const httpsOnly = (u) => {
    const p = parseUrl(u);
    if (!p || p.protocol !== 'https:') throw new Error('refusing a non-https download URL' + (p ? ' (' + p.protocol + ')' : ''));
    return p;
  };
  let cur = httpsOnly(req.url);
  let headers = req.headers;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const r = await fetchImpl(cur.href, { headers, redirect: 'manual' });
    const loc = r.status >= 300 && r.status < 400 && r.headers.get('location');
    if (!loc) return r;
    try { await (r.body && r.body.cancel()); } catch { /* already consumed or closed */ } // free the connection
    const next = httpsOnly(new URL(loc, cur).href);
    if (next.origin !== cur.origin && headers.Authorization) {
      const { Authorization, ...rest } = headers; // eslint-disable-line no-unused-vars
      headers = rest;
    }
    cur = next;
  }
  throw new Error('too many redirects');
}

/** The value of `key` in .env text, read like apply.sh's get_env (the first `KEY=` line), with surrounding quotes,
 *  trailing whitespace and a CR removed. Empty or absent → null. */
function envValue(text, key) {
  const line = String(text || '').split('\n').find((l) => l.startsWith(key + '='));
  if (line == null) return null;
  const v = line.slice(key.length + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
  return v || null;
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

/** Fold one line of apply.sh/rollback.sh output into the live apply view: `PHASE <p>` sets a non-terminal phase, `ERR <msg>`
 *  appends to the error (kept, not replaced: a failed rollback reports both why the apply failed and what state the
 *  stack is left in); anything else (compose output) returns the view unchanged. */
function applyLine(ap, line) {
  if (line.startsWith('PHASE ')) {
    const p = line.slice(6).trim();
    // Terminal phases are published by the close handler, together with the new `current` (server.js spawnPhases):
    // shown straight from the line, a client that stops polling at `done` could still read the old version.
    return isTerminal(p) ? ap : { ...ap, phase: p };
  }
  if (line.startsWith('ERR ')) {
    const e = line.slice(4).trim();
    return { ...ap, error: ap && ap.error ? ap.error + ' | ' + e : e };
  }
  return ap;
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

/** True iff `name` is one of publishApk's temp files for the published file `base`: `<base>.<16 lowercase hex>.tmp`
 *  (server.js names them with crypto.randomBytes(8).toString('hex')). Only these are ever cleaned up from the
 *  public files directory. */
function isPublishTemp(name, base) {
  const prefix = base + '.';
  return name.startsWith(prefix) && /^[0-9a-f]{16}\.tmp$/.test(name.slice(prefix.length));
}

// Marks the recovery page with whether one-click apply/rollback works on this deployment, server-side, so the
// page hides Roll back (and shows the manual update steps) from the first paint — no JS or status fetch needed.
function recoveryPage(html, applySupported) {
  return html.replace('<body>', '<body data-apply="' + (applySupported ? '1' : '0') + '">');
}

/** What a GET /rest/private/users/current answer (the caller's session, forwarded) says about managing the
 *  deployment. The server answers HTTP 200 with {status:"OK", data:<the session's user>}, and from 0.3.8 on the user
 *  carries deploymentAdmin: true for a super-admin, or for the org admin of a single-customer install (its operator),
 *  false for everyone else (UnsecureDAO.mayManageDeployment). Impersonation puts the impersonated user in the session.
 *  Returns true or false from that field; null when the answer is a signed-in user without the field (a server before
 *  0.3.8); false for anything else (an ERROR, no user, another shape). */
function deploymentAdminVerdict(body) {
  if (!body || typeof body !== 'object' || body.status !== 'OK') return false;
  const u = body.data;
  if (!u || typeof u !== 'object' || Array.isArray(u)) return false;
  if (typeof u.deploymentAdmin === 'boolean') return u.deploymentAdmin;
  return 'deploymentAdmin' in u ? false : null;
}

/** The same users/current answer from a server before 0.3.8, which has no deploymentAdmin field. That server's own
 *  rule let any user with the "settings" permission update, every customer's admin included. Narrowed here to what
 *  0.3.8 decides, as far as the old answer tells: a super-admin (userRole.superAdmin), or the org admin (role 2) of
 *  customer 1, which is a private-mode install's only customer and its operator. 0.3.7 sends customerId,
 *  userRole.id and userRole.superAdmin; an answer without them, or with another type, refuses. */
const ORG_ADMIN_ROLE_ID = 2;
const OPERATOR_CUSTOMER_ID = 1;
function legacyDeploymentAdmin(body) {
  if (!body || typeof body !== 'object' || body.status !== 'OK') return false;
  const u = body.data;
  if (!u || typeof u !== 'object' || Array.isArray(u)) return false;
  const role = u.userRole;
  if (!role || typeof role !== 'object' || Array.isArray(role)) return false;
  if (role.superAdmin === true) return true;
  return role.id === ORG_ADMIN_ROLE_ID && u.customerId === OPERATOR_CUSTOMER_ID;
}

module.exports = {
  parseSemver, semverGt, pickRelease, shapeStatus,
  imageTags, nextPhase, isTerminal, APPLY_PHASES, APPLY_TERMINAL,
  apkAsset, sha256Matches, recoveryPage, isPublishTemp, assetRequest, fetchAsset, envValue, applyLine, applyRefusal,
  deploymentAdminVerdict, legacyDeploymentAdmin,
};
