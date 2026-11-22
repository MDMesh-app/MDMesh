// MDMesh updater/recovery supervisor — decoupled, always-up. Polls GitHub for new releases, verifies
// the minisign-signed manifest against the baked public key, serves /update/status + a recovery page,
// and drives compose to apply/rollback updates against the host daemon.
const http = require('http');
const fs = require('fs');
const os = require('os');
const cp = require('child_process');
const path = require('path');
const crypto = require('crypto');
const { semverGt, isReleaseVersion, agentOffer, pickRelease, shapeStatus, updateBlocked, blockedLogLine, hostPlatform, imageTags, imagePins, isTerminal, apkAsset, sha256Matches, recoveryPage, isPublishTemp, fetchAsset,
  envValue, applyLine, applyRefusal, deploymentAdminVerdict, legacyDeploymentAdmin } = require('./lib');

const PORT = +(process.env.SUPERVISOR_PORT || 9000);
// Bind address. Docker keeps the default (all interfaces — the container has no published ports);
// the native install sets 127.0.0.1 so only Tomcat's loopback passthrough can reach it.
const BIND = process.env.SUPERVISOR_BIND || '0.0.0.0';
const REPO = process.env.GITHUB_REPO || '';
const CHANNEL = process.env.UPDATE_CHANNEL || 'stable';
const PUB = process.env.MANIFEST_PUBKEY || '/app/minisign.pub';
const TOKEN = process.env.GITHUB_TOKEN || '';
// Whether this deployment can self-apply server/web updates. The apply/rollback machinery drives
// docker compose, so native (source-built) installs set 0: status advertises it, the mutating
// routes refuse, and unattended auto-apply never fires. Rollouts/APK mirroring are unaffected.
const APPLY_SUPPORTED = process.env.APPLY_SUPPORTED !== '0';
// When set, the offered agent (the running release's; setOffer) is also atomically published to this path: the native
// install points it at Tomcat's files dir, which serves /files/agent.apk. Docker doesn't need it: Caddy routes
// /files/agent.apk to this process directly.
const PUBLISH_APK_TO = process.env.PUBLISH_APK_TO || '';
const APPLY_SCRIPT = path.join(__dirname, 'apply.sh');
const POLL_MS = (+(process.env.POLL_INTERVAL_HOURS || 6)) * 3600 * 1000;
// A running release that couldn't be looked up is asked again this soon (5 min; sooner with a short poll interval),
// not only at the next poll.
const RETRY_MS = Math.min(POLL_MS / 10, 5 * 60 * 1000);
const ROLLBACK_SCRIPT = path.join(__dirname, 'rollback.sh');

// The running release. With one-click apply (Docker) the source of truth is CURRENT_VERSION in the project's .env
// (mounted at /project): apply.sh and rollback.sh rewrite it there, and compose reads the image tags from the same
// file, while the container's own env is frozen at whatever it was when the supervisor was created. So it is re-read
// at start and after every apply or rollback; the container env is only the fallback. Native installs
// (APPLY_SUPPORTED=0) have no project .env: their CURRENT_VERSION comes from /etc/mdmesh/supervisor.env, which the
// installer rewrites and then restarts the unit.
const PROJECT_ENV = path.join(process.env.COMPOSE_PROJECT_DIR || '/project', '.env');
function readCurrentVersion(fallback = process.env.CURRENT_VERSION || '0.0.0') {
  if (!APPLY_SUPPORTED) return fallback;
  try { return envValue(fs.readFileSync(PROJECT_ENV, 'utf8'), 'CURRENT_VERSION') || fallback; }
  catch (e) { console.log('[version] cannot read', PROJECT_ENV + ':', String((e && e.code) || e), '- using', fallback); return fallback; }
}
let currentVersion = readCurrentVersion();
/** IMAGE_OWNER from the project .env: the GHCR owner compose pulls server/web/supervisor from (compose's default,
 *  mdmesh-app, when unset). apply.sh pins exactly the digests the signed manifest names under that owner. */
function readImageOwner() {
  try { return envValue(fs.readFileSync(PROJECT_ENV, 'utf8'), 'IMAGE_OWNER'); } catch { return null; }
}
// Last verified manifest from poll() — the source of the image refs an apply will deploy.
let lastManifest = null;
// The agent APK this install offers {version,versionCode,sha256,url,apiUrl,of} (setOffer); null if none.
let lastApk = null;
// Release notes / link / date for the picked release {notes,url,publishedAt}; null when no release.
let lastRelease = null;
// In-flight apply state surfaced via /update/status; null when no apply has run.
let apply = null;

// Unattended ("auto-update") state, persisted on the backups volume as {auto, skipVersion} so it survives a restart
// or recreate. `auto` is seeded from AUTO_UPDATE, then overridden by the saved file if present. `skipVersion` is the
// release unattended mode must not apply: one whose apply failed (manual or automatic; prevents a rollback crash-loop)
// or one an operator rolled back from (auto-applying it would undo the rollback on the next poll). It is an exact
// match: a newer release still auto-applies, and a manual Update still applies the skipped one.
const AUTO_FILE = process.env.AUTO_FILE || '/backups/auto.json';
let autoUpdate = process.env.AUTO_UPDATE === '1' || process.env.AUTO_UPDATE === 'true';
let skipVersion = null;
try {
  const j = JSON.parse(fs.readFileSync(AUTO_FILE, 'utf8'));
  if (typeof j.auto === 'boolean') autoUpdate = j.auto;
  if (typeof j.skipVersion === 'string' && j.skipVersion) skipVersion = j.skipVersion;
} catch { /* no saved state yet */ }
// Wall-clock of the last completed poll() — used to rate-limit the on-demand /update/check route.
let lastPollAt = 0;
/** Persist {auto, skipVersion}: written to a temp file and renamed over AUTO_FILE (like ensureApk), so a crash mid-write
 *  never leaves a torn file (which would silently drop the skip at the next start). */
function saveAuto() {
  const tmp = AUTO_FILE + '.tmp';
  try {
    fs.mkdirSync(path.dirname(AUTO_FILE), { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify({ auto: autoUpdate, skipVersion }));
    fs.renameSync(tmp, AUTO_FILE);
  } catch (e) {
    console.log('[auto] persist failed:', String((e && e.message) || e));
    try { fs.rmSync(tmp, { force: true }); } catch { /* ignore */ }
  }
}
/** The skip only protects against auto-applying a version that is NOT running. Once it is the running version (applied
 *  by hand, or reached outside the supervisor) there is nothing left to protect, so it is forgotten — except while this
 *  process's last apply/rollback ended `failed`: then `current` can be the broken version the operator is about to roll
 *  back by hand, and clearing the skip would let auto-apply re-apply it after that rollback and a restart. */
function maybeClearSkip() {
  if (skipVersion && skipVersion === currentVersion && !(apply && apply.phase === 'failed')) clearAutoSkip();
}
/** Forget the skip (the skipped version is running). */
function clearAutoSkip() {
  skipVersion = null;
  saveAuto();
  state.autoSkipped = null;
}
/** Never auto-apply `v` again (see skipVersion). Persisted at once, and shown as autoSkipped in /update/status. */
function blockAuto(v) {
  if (!v || v === skipVersion) return;
  skipVersion = v;
  saveAuto();
  state.autoSkipped = skipVersion;
  console.log('[auto] will not auto-apply', v, 'again (apply failed or rolled back); Update still applies it by hand');
}

let state = { current: currentVersion, latest: null, updateAvailable: false, blocked: null, blockedCode: null, platform: hostPlatform(), verified: false, checkedAt: null, error: 'not polled yet', missedSecurity: [], apply: null, auto: autoUpdate, autoSkipped: skipVersion, applySupported: APPLY_SUPPORTED };

async function ghJson(url) {
  const headers = { 'User-Agent': 'mdmesh-updater', Accept: 'application/vnd.github+json' };
  if (TOKEN) headers.Authorization = 'Bearer ' + TOKEN;
  const r = await fetch(url, { headers, signal: AbortSignal.timeout(30_000) });
  if (!r.ok) throw Object.assign(new Error('github ' + r.status), { status: r.status });
  return r.json();
}

/** Download a release asset: through the asset API URL with the token when one is set (the only way a private repo
 *  serves it), else browser_download_url. The one download path for the manifest, its signature (30 s) and the APK
 *  (`ms`: 10 min), each bounded so a stuck GitHub can't hold a poll or a lookup. */
const ghAsset = (asset, ms = 30_000) => fetchAsset(asset, TOKEN, globalThis.fetch, 5, AbortSignal.timeout(ms));

/** A fetch/exec error as one loggable line: the message plus the low-level cause code, never headers or URLs
 *  (a redirect target is a signed URL, and the token lives in a header). */
function errText(e) {
  const cause = e && e.cause && (e.cause.code || e.cause.message);
  return String((e && e.message) || e) + (cause ? ' (' + cause + ')' : '');
}

/** minisign-verify DIR/manifest.json against DIR/manifest.json.minisig and the release key: null when it verifies,
 *  else { what, detail } (detail: minisign's own words, without DIR, for the log only). */
function minisignProblem(dir) {
  const v = cp.spawnSync('minisign', ['-V', '-p', PUB, '-m', path.join(dir, 'manifest.json')], { encoding: 'utf8' });
  if (v.error) return { what: 'minisign could not run', detail: errText(v.error), transient: true };
  if (v.status === 0) return null;
  return { what: 'minisign signature check failed',
    detail: (v.stderr || v.stdout || '').split(dir + path.sep).join('').replace(/\s+/g, ' ').trim() || 'exit ' + v.status };
}

/** Download + minisign-verify a release's manifest. Returns { manifest, files } when verified (files: the manifest's and
 *  its signature's bytes, which runningAgent persists), else { error, transient }: transient when a download failed
 *  (the release lists the files, so this is GitHub or the network, this time), not when the signature or JSON is bad.
 *  The reason is logged in full ([verify] line), but /update/status is public (Caddy proxies it), so `error` names
 *  only the kind of failure. */
async function verifyManifest(mAsset, sAsset) {
  let d = null;
  const fail = (what, detail, transient = false) => {
    console.log('[verify] manifest not verified:', what + (detail ? ': ' + detail : ''));
    return { error: 'manifest not verified: ' + what + ' (details in the supervisor log)', transient };
  };
  try {
    d = fs.mkdtempSync(path.join(os.tmpdir(), 'mf'));
    const files = {};
    for (const [asset, f] of [[mAsset, 'manifest.json'], [sAsset, 'manifest.json.minisig']]) {
      let r;
      try { r = await ghAsset(asset); } catch (e) { return fail(`${f}: download failed`, errText(e), true); }
      if (!r.ok) return fail(`${f}: HTTP ${r.status}`, r.status === 404 && !TOKEN ? 'a private repo needs GITHUB_TOKEN' : '', true);
      files[f] = Buffer.from(await r.arrayBuffer());
      fs.writeFileSync(path.join(d, f), files[f]);
    }
    const bad = minisignProblem(d);
    if (bad) return fail(bad.what, bad.detail, !!bad.transient);
    try { return { manifest: JSON.parse(files['manifest.json'].toString('utf8')), files }; }
    catch (e) { return fail('manifest.json is not valid JSON', errText(e)); }
  } catch (e) {
    return fail('download or check failed', errText(e), true); // unreachable / unreadable → refuse, but say why in the log
  } finally {
    if (d) fs.rmSync(d, { recursive: true, force: true });
  }
}

// --- APK mirror: the offered agent's APK, served from the deployment's own origin so devices never need to reach
// GitHub, and only with the SHA-256 of the verified manifest it came from. ---
const APK_DIR = process.env.APK_CACHE_DIR || '/backups/apk';
const cachePath = (desc) => path.join(APK_DIR, `agent-${desc.versionCode}.apk`);
function apkReady() { return !!lastApk && cachedApkOk(lastApk); }
// Cached files whose bytes were hashed against a verified descriptor: path → `${sha256} ${size} ${mtimeMs}`.
const hashedFiles = new Map();
/** The cached copy of `desc`'s agent is there and has its verified SHA-256 (hashed once per file version, again if the
 *  file changes). A copy that doesn't match (tampered, or another release's agent with the same versionCode) is
 *  removed, so it is downloaded again. */
function cachedApkOk(desc) {
  const p = cachePath(desc);
  let st;
  try { st = fs.statSync(p); } catch { return false; }
  const key = `${desc.sha256} ${st.size} ${st.mtimeMs}`;
  if (hashedFiles.get(p) === key) return true;
  let ok = false;
  try { ok = sha256Matches(fs.readFileSync(p), desc.sha256); } catch { /* unreadable (a directory, EACCES): not cached */ }
  if (ok) { hashedFiles.set(p, key); return true; }
  console.log('[apk] cached', p, 'does not match its signed sha256 — removing it');
  hashedFiles.delete(p);
  try { fs.rmSync(p, { force: true }); } catch { /* ignore */ }
  return false;
}
const fetchingApk = new Map();   // sha256 → the download in flight for it
let publishedSha = null;         // the agent last copied to PUBLISH_APK_TO in this process
/** Make sure `desc`'s agent is in the cache with its verified SHA-256, downloading it if needed (one download per
 *  agent at a time). Resolves true when it is. */
function ensureApk(desc) {
  if (!desc) return Promise.resolve(false);
  if (cachedApkOk(desc)) { publishOffered(desc); return Promise.resolve(true); }
  const key = desc.sha256;
  if (!fetchingApk.has(key)) {
    fetchingApk.set(key, (async () => {
      const dest = cachePath(desc), tmp = `${dest}.${key.slice(0, 12)}.tmp`; // one writer per name; a leftover is overwritten
      try {
        fs.mkdirSync(APK_DIR, { recursive: true });
        const r = await ghAsset({ url: desc.apiUrl, browser_download_url: desc.url }, 10 * 60_000); // 302s to a CDN
        if (!r.ok) { console.log('[apk] download failed', r.status); return false; }
        const buf = Buffer.from(await r.arrayBuffer());
        if (!sha256Matches(buf, desc.sha256)) { console.log('[apk] sha256 mismatch — refusing to serve'); return false; }
        fs.writeFileSync(tmp, buf);
        fs.renameSync(tmp, dest); // atomic: only verified bytes ever sit at dest
        console.log('[apk] mirrored', dest);
        publishOffered(desc);
        refreshApkAvailable();
        return true;
      } catch (e) {
        console.log('[apk] error', errText(e));
        try { fs.rmSync(tmp, { force: true }); } catch { /* ignore */ }
        return false;
      } finally { fetchingApk.delete(key); }
    })());
  }
  return fetchingApk.get(key);
}
/** A native install's enrollment copy follows the offered agent, also when it changes back to one already cached. */
function publishOffered(desc) {
  if (!PUBLISH_APK_TO || desc !== lastApk || publishedSha === desc.sha256) return;
  if (publishApk(cachePath(desc))) publishedSha = desc.sha256; // a failed copy is tried again at the next offer
}

/** Re-derive `available` once a download lands: setStatus() snapshots apkReady() before the download finishes, and
 *  nothing else rebuilds `state` until the next poll (POLL_INTERVAL_HOURS). */
function refreshApkAvailable() {
  if (state.apk) state.apk = { ...state.apk, available: apkReady() };
}

/** Copy a freshly-verified APK over the deployment's static hosting path (native installs). The copy goes to a new,
 *  exclusively-created temp name (COPYFILE_EXCL never opens an existing file or link), so nothing left in that
 *  directory, such as a link planted at a predictable name or a leftover from an earlier run, is written through
 *  or blocks the publish. Temp files a killed process left behind are removed first (removeStalePublishTemps). */
function publishApk(src) {
  if (!PUBLISH_APK_TO) return false;
  removeStalePublishTemps();
  const tmp = `${PUBLISH_APK_TO}.${crypto.randomBytes(8).toString('hex')}.tmp`;
  try {
    fs.mkdirSync(path.dirname(PUBLISH_APK_TO), { recursive: true });
    fs.copyFileSync(src, tmp, fs.constants.COPYFILE_EXCL);
    fs.renameSync(tmp, PUBLISH_APK_TO); // atomic — a device mid-download never sees a torn file
    console.log('[apk] published', PUBLISH_APK_TO);
    return true;
  } catch (e) {
    console.log('[apk] publish failed:', String((e && e.message) || e));
    try { fs.rmSync(tmp, { force: true }); } catch { /* ignore */ }
    return false;
  }
}

/** Temp files publishApk left behind when the process died between the copy and the rename. They sit in the public
 *  files directory (Tomcat serves it under /files/), so they are removed at start-up and before each publish. Only
 *  names of exactly publishApk's shape (isPublishTemp), and only a regular file or a link: lstat never follows a link,
 *  and unlink removes the link itself, never its target. Anything else there (a directory, other names) is left alone. */
function removeStalePublishTemps() {
  if (!PUBLISH_APK_TO) return;
  const dir = path.dirname(PUBLISH_APK_TO), base = path.basename(PUBLISH_APK_TO);
  let names;
  try { names = fs.readdirSync(dir); } catch { return; } // no files dir yet: nothing to clean
  for (const name of names) {
    if (!isPublishTemp(name, base)) continue;
    const p = path.join(dir, name);
    try {
      const st = fs.lstatSync(p);
      if (st.isFile() || st.isSymbolicLink()) { fs.unlinkSync(p); console.log('[apk] removed stale temp file', p); }
    } catch (e) { console.log('[apk] could not remove stale temp file', p, String((e && e.message) || e)); }
  }
}

/** Rebuild `state` from a poll result while preserving the live `apply`/`auto` view. A newer release this install
 *  can't apply (updateBlocked) is shown with its reason instead of as an update, so auto-apply leaves it alone; the
 *  reason is logged once per release and reason, not on every poll. */
let lastBlockedLog = null;
function setStatus(args) {
  maybeClearSkip();
  const blocked = blockedFor(args.manifest, args.verified, args.current);
  const logged = blockedLogLine(lastBlockedLog, blocked);
  if (logged.line) console.log(logged.line);
  lastBlockedLog = logged.lastReason;
  state = {
    ...shapeStatus({ ...args, blocked }),
    apply,
    auto: autoUpdate,
    autoSkipped: skipVersion,
    applySupported: APPLY_SUPPORTED,
    platform: hostPlatform(),   // the platform an update's images must include (blockedCode 'platform')
    apkServed: !PUBLISH_APK_TO,  // this supervisor serves /files/agent.apk (Docker); else a published copy (native)
    // `of`: whose agent this is: 'running' (the release the server runs), or 'latest' when that is unknown (setOffer).
    apk: lastApk ? { version: lastApk.version, versionCode: lastApk.versionCode, sha256: lastApk.sha256, available: apkReady(),
      of: lastApk.of } : null,
    release: lastRelease,
  };
  maybeAutoApply();
}

/** Why this install can't install the release in a verified `manifest` (updateBlocked), or null. */
function blockedFor(manifest, verified = true, current = currentVersion) {
  return updateBlocked({ manifest, verified, current, imageOwner: readImageOwner(), applySupported: APPLY_SUPPORTED });
}

// --- The agent this install offers (state.apk, /update/agent.apk, a native install's /files/agent.apk) ---
// It is the running release's own agent: never one ahead of the server it talks to (the release notes always say
// "update the server first"). That agent is a verified fact, resolved per running version and kept on disk with its
// signed manifest, so a restart with GitHub down still has it. Only when the running version is unknown does the
// latest release's agent stand in, so a QR code still downloads something (lib.js agentOffer).
const running = new Map();     // version → { kind: 'agent', desc } | { kind: 'unknown' | 'failed', why }: the kept
                               // store (loaded at start-up) and lookups; poll() drops all but 'agent', so a release
                               // published or re-signed later is asked again
const resolving = new Map();   // version → the resolution in flight
let latestAgent = null;        // the latest verified release's agent, { ...desc, of: 'latest' }
let lastVerified = null;       // the latest release poll() verified: { rel, manifest, files }
let lastList = null;           // the release list poll() last read
const persisted = (version, ext) => path.join(APK_DIR, `v${version}.${ext}`);
const tagVersion = (rel) => String((rel && rel.tag_name) || '').replace(/^v/, '');

/** The running release's agent for `version` (lookUpRunning), one lookup per version at a time; kept unless it was
 *  { kind: 'transient' } (GitHub didn't answer), so the next attempt asks again. */
function resolveRunning(version, list) {
  if (running.has(version)) return Promise.resolve(running.get(version));
  if (!resolving.has(version)) {
    resolving.set(version, (async () => {
      const result = await lookUpRunning(version, list);
      if (result.kind !== 'transient') running.set(version, result);
      return result;
    })().finally(() => resolving.delete(version)));
  }
  return resolving.get(version);
}
/** In order: from disk (re-verified; for when minisign couldn't run at start-up); the latest release poll() verified,
 *  when that is the one running (no second download, and what an apply installs); the release in `list`, else looked
 *  up by tag. Only a 404 there means no such release (unknown); a failed lookup or download, or anything unexpected, is
 *  transient. A manifest that doesn't verify is 'failed': nothing is offered. */
async function lookUpRunning(version, list) {
  try {
    if (!isReleaseVersion(version)) return { kind: 'unknown', why: 'not a published release version' };
    const latest = loadPersisted(version) || fromLatest(version);
    if (latest) return latest;
    let rel = (list || []).find((x) => tagVersion(x) === version);
    if (!rel) {
      try { rel = await ghJson(`https://api.github.com/repos/${REPO}/releases/tags/v${version}`); }
      catch (e) { return e.status === 404 ? { kind: 'unknown', why: 'no such release' } : { kind: 'transient' }; }
    }
    const asset = (n) => (rel.assets || []).find((a) => a.name === n);
    if (!asset('manifest.json') || !asset('manifest.json.minisig')) return { kind: 'unknown', why: 'no signed manifest' };
    const v = await verifyManifest(asset('manifest.json'), asset('manifest.json.minisig'));
    if (!v.manifest) return v.transient ? { kind: 'transient' } : { kind: 'failed', why: v.error };
    if (v.manifest.version !== version) return { kind: 'failed', why: `its manifest is for ${v.manifest.version}` };
    return keep(version, rel, v);
  } catch (e) {
    console.log('[apk] looking up the running release failed:', errText(e));
    return { kind: 'transient' };
  }
}
/** The latest release poll() verified, as the running release's agent when it is `version`; else null. */
function fromLatest(version) {
  return lastVerified && lastVerified.manifest.version === version ? keep(version, lastVerified.rel, lastVerified) : null;
}
/** A verified release's agent as a result, built by apkAsset from the release's (or the kept) asset entry. */
function asResult(rel, manifest) {
  const desc = apkAsset(rel, manifest);
  return desc ? { kind: 'agent', desc: { ...desc, of: 'running' } } : { kind: 'unknown', why: 'no agent in its manifest' };
}
/** Keep a verified release's manifest, its signature and its APK's asset entry (where the bytes come from) on disk,
 *  each written whole, then renamed; then its result. Nothing here is trusted on the way back in: loadPersisted
 *  verifies the signature again, and the APK's bytes are checked against the signed sha256. */
function keep(version, rel, v) {
  const result = asResult(rel, v.manifest);
  if (result.kind !== 'agent') return result;
  try {
    fs.mkdirSync(APK_DIR, { recursive: true });
    const write = (f, bytes) => { fs.writeFileSync(f + '.tmp', bytes); fs.renameSync(f + '.tmp', f); };
    const a = rel.assets.find((x) => x.name === v.manifest.components.apk.file);
    write(persisted(version, 'manifest.json.minisig'), v.files['manifest.json.minisig']);
    write(persisted(version, 'asset.json'), JSON.stringify({ name: a.name, url: a.url, browser_download_url: a.browser_download_url }));
    write(persisted(version, 'manifest.json'), v.files['manifest.json']);
  } catch (e) { console.log('[apk] could not keep the manifest of', version + ':', errText(e)); }
  return result;
}
/** The agent of `version` from disk, its manifest verified again (minisign, offline); null when there is none or it
 *  can't be read or checked now (minisign didn't run). A manifest that doesn't verify, or is another version's, is
 *  removed (and fetched again). */
function loadPersisted(version) {
  const m = persisted(version, 'manifest.json');
  if (!fs.existsSync(m)) return null;
  let d = null, bad = null;
  const remove = (why) => {
    console.log(`[apk] the kept manifest of ${version} is not usable (${why}) — removing it`);
    for (const ext of ['manifest.json', 'manifest.json.minisig', 'asset.json']) fs.rmSync(persisted(version, ext), { force: true });
  };
  try {
    d = fs.mkdtempSync(path.join(os.tmpdir(), 'mf'));
    fs.copyFileSync(m, path.join(d, 'manifest.json'));
    fs.copyFileSync(persisted(version, 'manifest.json.minisig'), path.join(d, 'manifest.json.minisig'));
    const problem = minisignProblem(d);
    if (problem && problem.transient) throw new Error(problem.detail);
    if (problem) bad = `${problem.what}: ${problem.detail}`;
    const manifest = bad ? null : JSON.parse(fs.readFileSync(path.join(d, 'manifest.json'), 'utf8'));
    if (manifest && manifest.version !== version) bad = 'manifest of another version';
    if (bad) { remove(bad); return null; }
    // Only an agent is ever kept, so anything else is damage (asset.json isn't signed): fetch it again.
    const result = asResult({ assets: [JSON.parse(fs.readFileSync(persisted(version, 'asset.json'), 'utf8'))] }, manifest);
    if (result.kind !== 'agent') { remove(`its kept asset entry doesn't fit it (${result.why})`); return null; }
    return result;
  } catch (e) {
    console.log(`[apk] the kept manifest of ${version} can't be checked now: ${errText(e)}`);
    return null;
  } finally { if (d) fs.rmSync(d, { recursive: true, force: true }); }
}
/** The kept store, loaded once at start-up: every release this install has run whose manifest still verifies. */
function loadKept() {
  let names = [];
  try { names = fs.readdirSync(APK_DIR); } catch { /* nothing kept yet */ }
  for (const n of names) {
    const v = /^v(.+)\.manifest\.json$/.exec(n);
    if (v && isReleaseVersion(v[1])) { const r = loadPersisted(v[1]); if (r) running.set(v[1], r); }
  }
}

/** Every agent read from a verified manifest: the latest's, and the running or kept releases'. */
const verifiedAgents = () => [latestAgent, ...[...running.values()].map((x) => x.desc)].filter(Boolean);
const knownAgent = (versionCode) => verifiedAgents().find((d) => String(d.versionCode) === String(versionCode)) || null;

/** Offer what `result` (lookUpRunning's) says, logging why when it isn't the running release's agent; a lookup that
 *  failed is retried within minutes. */
let offerLog = '';
let retry = null;
function setOffer(result) {
  const line = result.kind === 'unknown' ? `[apk] running release v${currentVersion} is unknown (${result.why}); offering the latest release's agent`
    : result.kind === 'failed' ? `[apk] running release v${currentVersion} has no verified agent (${result.why}); offering none`
      : result.kind === 'transient' ? `[apk] running release v${currentVersion} could not be looked up yet; offering none, retrying` : '';
  if (line && line !== offerLog) console.log(line);
  offerLog = line;
  lastApk = agentOffer(result, latestAgent);
  retirePublished();
  if (result.kind === 'transient' && !retry) retry = setTimeout(() => { retry = null; void offerAgain(); }, RETRY_MS);
}
/** Resolve the running version and offer its agent. A result for a version that is no longer running is dropped (its
 *  replacement is on its way). Never throws: it runs from timers and request handlers. */
async function offerAgain() {
  try {
    const version = currentVersion;
    const result = await resolveRunning(version, lastList);
    if (version !== currentVersion) return;
    setOffer(result);
    refreshStatus(false);
    void ensureApk(lastApk);
  } catch (e) { console.log('[apk] offering the agent failed:', errText(e)); }
}
/** A native install's published /files/agent.apk that is a known agent newer than the server (published by an
 *  installer, or before a rollback) is removed when nothing replaces it, so enrollment gets nothing rather than an
 *  agent ahead of its server. */
function retirePublished() {
  if (!PUBLISH_APK_TO || !isReleaseVersion(currentVersion) || (lastApk && publishedSha === lastApk.sha256)) return;
  let sha;
  try { sha = crypto.createHash('sha256').update(fs.readFileSync(PUBLISH_APK_TO)).digest('hex'); } catch { return; }
  const desc = verifiedAgents().find((d) => d.sha256 === sha);
  if (!desc || desc === lastApk || !semverGt(desc.version, currentVersion)) return;
  try { fs.rmSync(PUBLISH_APK_TO, { force: true }); publishedSha = null; console.log(`[apk] removed the published agent ${desc.version}: it is newer than this server (${currentVersion})`); }
  catch (e) { console.log('[apk] could not remove the published agent:', errText(e)); }
}

/** If unattended is on and a fresh verified update is available, apply it (once per version). */
function maybeAutoApply() {
  if (!APPLY_SUPPORTED) return;
  if (!autoUpdate || !state.updateAvailable) return;
  if (apply && !isTerminal(apply.phase)) return; // an apply is already running
  const { version } = imageTags(lastManifest);
  if (version && version === skipVersion) return; // failed or rolled back from — don't loop
  console.log('[auto] verified update', version, '→ self-applying');
  const r = startApply('auto');
  if (!r.ok) console.log('[auto] not applying', version + ':', r.msg);
}

async function poll() {
  lastPollAt = Date.now(); // stamp up-front so /update/check throttling also covers an in-flight poll
  // A running version found unknown (404) or unverifiable is asked again: it may be published, or re-signed, since.
  for (const [version, result] of running) if (result.kind !== 'agent') running.delete(version);
  if (!REPO) { lastManifest = null; lastRelease = null; setStatus({ current: currentVersion, manifest: null, verified: false, checkedAt: Date.now(), error: 'GITHUB_REPO not set' }); return; }
  try {
    lastList = await ghJson(`https://api.github.com/repos/${REPO}/releases?per_page=10`);
    const rel = pickRelease(lastList, CHANNEL);
    if (!rel) { lastManifest = null; lastRelease = null; setStatus({ current: currentVersion, manifest: null, verified: false, checkedAt: Date.now(), error: 'no matching release' }); return; }
    lastRelease = { notes: rel.body || null, url: rel.html_url || null, publishedAt: rel.published_at || null };
    const m = rel.assets.find((a) => a.name === 'manifest.json');
    const s = rel.assets.find((a) => a.name === 'manifest.json.minisig');
    const v = (m && s) ? await verifyManifest(m, s) : { error: 'release has no manifest.json.minisig' };
    const manifest = v.manifest || null;
    lastManifest = manifest; // only verified manifests are ever stored
    if (manifest) {
      const a = apkAsset(rel, manifest);
      latestAgent = a && { ...a, of: 'latest' };
      lastVerified = { rel, manifest, files: v.files };
    }
    // The offer first, so a status with this poll's checkedAt carries this poll's offer too.
    await offerAgain(); // warms the mirror too, so a rollout or enrollment is instant
    setStatus({ current: currentVersion, manifest, verified: !!manifest, checkedAt: Date.now(), error: manifest ? null : v.error });
  } catch (e) {
    await offerAgain(); // the running release's agent may be kept on disk
    state = { ...state, current: currentVersion, checkedAt: Date.now(), error: String((e && e.message) || e) };
  }
}

/** Re-derive the status from what is already known (the last verified manifest) after `current` changed, without
 *  waiting for GitHub: the follow-up poll can be slow or fail, and until then `current`/`updateAvailable` were stale. */
function refreshStatus(reoffer = true) {
  // `current` changed (apply, rollback): the old offer may be ahead of it, so what is known for the new version is
  // offered before anything is published, and offerAgain resolves the rest (from the kept store or the manifest the
  // last poll verified, without network, before this tick ends).
  if (reoffer) lastApk = agentOffer(running.get(currentVersion) || null, latestAgent);
  setStatus({ current: currentVersion, manifest: lastManifest, verified: !!lastManifest, checkedAt: state.checkedAt, error: state.error });
  if (reoffer) void offerAgain();
}

// Spawn a phase-emitting script (apply.sh/rollback.sh) and stream its PHASE/ERR lines into the live
// `apply` view. A terminal PHASE line is held back (applyLine ignores it) and handed to `onClose(code, final)`, which
// publishes it together with the new `current`. Shared by apply + rollback.
function spawnPhases(args, onClose) {
  const child = cp.spawn('bash', args, { cwd: process.env.COMPOSE_PROJECT_DIR || '/project', env: process.env });
  let buf = '';
  let final = null;
  const onData = (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (!line) continue;
      console.log('[apply]', line);
      if (line.startsWith('PHASE ') && isTerminal(line.slice(6).trim())) final = line.slice(6).trim();
      apply = applyLine(apply, line);
      state.apply = apply;
    }
  };
  child.stdout.on('data', onData);
  child.stderr.on('data', onData);
  child.on('error', (e) => { apply = { ...apply, phase: 'failed', error: String((e && e.message) || e), finishedAt: Date.now() }; state.apply = apply; });
  child.on('close', (code) => onClose(code, final));
}

// Apply the verified update. `trigger` is 'manual' or 'auto'. Only called when an update is available.
function startApply(trigger) {
  if (apply && !isTerminal(apply.phase)) return { ok: false, code: 409, msg: 'apply already in progress' };
  // The refusal says what is true (no verified release / already running X / X failed: use Roll back), decided from
  // the verified manifest and `current` directly rather than the derived updateAvailable flag.
  const imageOwner = readImageOwner();
  const refusal = applyRefusal({ manifest: lastManifest, current: currentVersion, skipVersion, imageOwner });
  if (refusal) return { ok: false, code: 400, msg: refusal };
  const { version: toVersion } = imageTags(lastManifest);
  // The signed digests apply.sh pins (applyRefusal has checked them; apply.sh checks them again against .env).
  const pins = imagePins(lastManifest, imageOwner);

  apply = { phase: 'authorizing', fromVersion: currentVersion, toVersion, trigger, startedAt: Date.now(), finishedAt: null, error: null };
  state.apply = apply;
  spawnPhases([APPLY_SCRIPT, toVersion, pins.server, pins.web, pins.supervisor], (code, final) => {
    // .env is the record of what is running now: toVersion on success, the restored version after apply.sh rolled back.
    currentVersion = readCurrentVersion(code === 0 ? toVersion : currentVersion);
    // apply.sh exits non-zero after any failure, ending at rolled_back or failed.
    const phase = code === 0 ? 'done' : (final && final !== 'done' ? final : 'failed');
    apply = { ...apply, phase, finishedAt: Date.now() };
    if (code !== 0) blockAuto(toVersion); // never auto-retry a version that failed, however it was started
    else if (toVersion === skipVersion) clearAutoSkip(); // it runs now (applied by hand): nothing left to skip
    refreshStatus(); // current + updateAvailable now, then ask GitHub
    poll();
  });
  return { ok: true };
}

// Restore the most recent pre-update backup (.env versions + DB) and recreate server+caddy. This is
// the safety net when an apply leaves the deployment broken — reachable from the recovery page.
function startRollback() {
  if (apply && !isTerminal(apply.phase)) return { ok: false, code: 409, msg: 'an apply/rollback is already in progress' };
  apply = { phase: 'rollback', fromVersion: currentVersion, toVersion: null, trigger: 'rollback', startedAt: Date.now(), finishedAt: null, error: null };
  state.apply = apply;
  spawnPhases([ROLLBACK_SCRIPT], (code) => {
    // After a good restore rollback.sh wrote the restored CURRENT_VERSION to .env, so `current` follows it and the
    // version just rolled away from is offered again; after a failed one .env (and so `current`) is unchanged.
    currentVersion = readCurrentVersion(currentVersion);
    if (apply.fromVersion && apply.fromVersion !== currentVersion) blockAuto(apply.fromVersion);
    apply = { ...apply, phase: code === 0 ? 'rolled_back' : 'failed', toVersion: currentVersion, finishedAt: Date.now() };
    refreshStatus();
    poll();
  });
  return { ok: true };
}

// Authorize an update action by forwarding the caller's session cookie to the server and proving the session may
// manage the deployment. Apply, rollback and auto-update act on the whole deployment, every customer's data included,
// so only a super-admin may, or on a single-customer install its org admin (the operator; private-mode installs have
// no super-admin). A customer's admin on a multi-customer install must not, whatever permissions their role holds.
// The server decides (UnsecureDAO.mayManageDeployment) and says so in GET /rest/private/users/current. The supervisor
// holds no credentials of its own. The checks below:
//   - The server's AuthFilter returns HTTP 403 without a valid session (and while a password change is forced), so
//     anything but 200 refuses.
//   - users/current answers 200 + {"status":"OK","data":<the session's user>} for any signed-in user, so the answer
//     itself must say deploymentAdmin === true (deploymentAdminVerdict). An ERROR body, no user, or any other shape
//     refuses.
//   - A server before 0.3.8 doesn't send the field. The same answer then has to show a super-admin, or the org admin
//     of customer 1 (legacyDeploymentAdmin): the private-mode operator can still apply the release that brings the
//     field when the supervisor was updated first, and a customer's admin can't. From 0.3.8 on, the field is always
//     there and that fallback never runs.
// redirect:'manual' so a login redirect can't read as 200. The path is fixed: another endpoint would answer another
// shape, and these checks only hold for this one.
const SERVER_BASE = process.env.SERVER_BASE || 'http://server:8080';
const AUTHZ_PATH = '/rest/private/users/current';

// CSRF guard for the cookie-authorized state-changing routes. The console sends X-MDMesh-Console on
// its fetches; a cross-site page cannot set a custom header without a CORS preflight, which the
// supervisor never answers — so forged cross-origin POSTs (even with the victim's cookie) are blocked.
function csrfOk(req) {
  return req.headers['x-mdmesh-console'] === '1';
}

// Recovery token: a random secret on the backups volume. It's the break-glass authz for rollback when
// the API server is down (so cookie-forward authz can't work). Reading it requires host access to the
// volume (`docker compose exec supervisor cat /backups/recovery.token`) — which proves operator trust.
const TOKEN_FILE = process.env.RECOVERY_TOKEN_FILE || '/backups/recovery.token';
let recoveryToken = '';
try { recoveryToken = fs.readFileSync(TOKEN_FILE, 'utf8').trim(); } catch { /* generate below */ }
if (!recoveryToken) {
  try {
    recoveryToken = crypto.randomBytes(24).toString('hex');
    fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true });
    fs.writeFileSync(TOKEN_FILE, recoveryToken, { mode: 0o600 });
  } catch (e) { console.log('[recovery] token generation failed:', String((e && e.message) || e)); }
}
function validToken(req) {
  const t = req.headers['x-recovery-token'];
  if (!t || !recoveryToken) return false;
  const a = Buffer.from(String(t));
  const b = Buffer.from(recoveryToken);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/** GET a private server route with the caller's cookie: its JSON body on HTTP 200, else null. */
async function serverJson(p, cookie) {
  const r = await fetch(SERVER_BASE + p, { headers: { Cookie: cookie, Accept: 'application/json' }, redirect: 'manual',
    signal: AbortSignal.timeout(10_000) });
  if (r.status !== 200) return null;               // 403 = not logged in; 3xx = login redirect
  return r.json().catch(() => null);
}

async function authorizeApply(req) {
  const cookie = req.headers.cookie || '';
  if (!/JSESSIONID=/.test(cookie)) return false;
  try {
    const body = await serverJson(AUTHZ_PATH, cookie);
    const verdict = deploymentAdminVerdict(body);
    if (verdict !== null) return verdict;           // 0.3.8+: the server's answer
    return legacyDeploymentAdmin(body);              // older server: narrowed (see above)
  } catch {
    return false; // server unreachable / non-JSON → refuse (you update from a working console)
  }
}

const RECOVERY = recoveryPage(fs.readFileSync(path.join(__dirname, 'recovery.html'), 'utf8'), APPLY_SUPPORTED);
const json = (res, code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
function readJson(req) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (c) => { b += c; if (b.length > 1e5) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(b || '{}')); } catch { resolve(null); } });
    req.on('error', () => resolve(null));
  });
}

http.createServer(async (req, res) => {
  try {
    if (req.url === '/healthz') { res.writeHead(200).end('ok'); return; }
    if (req.url.startsWith('/update/status')) { json(res, 200, state); return; }
    if (req.url.startsWith('/update/agent.apk')) {
      // The mirrored agent APK, for enrollment (/files/agent.apk) and device rollouts. Public GET (the device fetches
      // it during an app.install). Every file served was just checked against the SHA-256 of a verified manifest
      // (ensureApk / cachedApkOk), and the agent checks the payload's sha256 too. Without ?v=: the offered agent.
      // ?v=<versionCode> (a rollout's URL): that agent, if it was read from a verified manifest and isn't newer than the
      // server (a rollout started before an update still gets its own bytes); one that is newer since a rollback
      // gets 409, so no device installs an agent ahead of its server.
      const v = new URL(req.url, 'http://x').searchParams.get('v');
      const desc = v && /^\d+$/.test(v) ? knownAgent(v) : lastApk;
      if (!desc) { res.writeHead(404).end(v ? `agent ${v} is not available` : 'no apk'); return; }
      // (The offer itself already follows the rule; when the running version is unknown, any verified agent goes.)
      const ahead = () => desc !== lastApk && semverGt(desc.version, currentVersion)
        && (running.get(currentVersion) || {}).kind !== 'unknown' && isReleaseVersion(currentVersion);
      const refuse = () => json(res, 409, { error: `agent ${desc.version} is newer than this server (${currentVersion}): update the server to ${desc.version} first` });
      if (ahead()) { refuse(); return; }
      if (!(await ensureApk(desc))) { json(res, 502, { error: 'apk unavailable or checksum mismatch' }); return; }
      if (ahead()) { refuse(); return; }   // the server was rolled back while the agent downloaded
      res.writeHead(200, { 'content-type': 'application/vnd.android.package-archive', 'content-length': fs.statSync(cachePath(desc)).size });
      fs.createReadStream(cachePath(desc)).on('error', () => res.destroy()).pipe(res);
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/check')) {
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      if (!(await authorizeApply(req))) { json(res, 401, { error: 'unauthorized (only an admin who may update this deployment)' }); return; }
      // Rate-limit so this can't be used to hammer the GitHub API; otherwise return the current state.
      if (Date.now() - lastPollAt > 15000) await poll();
      json(res, 200, state);
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/apply')) {
      if (!APPLY_SUPPORTED) { json(res, 501, { error: 'self-update apply is not supported on this deployment — pull and re-run the installer' }); return; }
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      if (!(await authorizeApply(req))) { json(res, 401, { error: 'unauthorized (only an admin who may update this deployment)' }); return; }
      const r = startApply('manual');
      if (!r.ok) { json(res, r.code || 400, { error: r.msg }); return; }
      json(res, 202, { status: 'started', apply: state.apply });
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/auto')) {
      if (!APPLY_SUPPORTED) { json(res, 501, { error: 'self-update apply is not supported on this deployment' }); return; }
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      if (!(await authorizeApply(req))) { json(res, 401, { error: 'unauthorized (only an admin who may update this deployment)' }); return; }
      const body = await readJson(req);
      if (!body || typeof body.auto !== 'boolean') { json(res, 400, { error: 'expected {auto:boolean}' }); return; }
      autoUpdate = body.auto; saveAuto(); state.auto = autoUpdate;
      if (autoUpdate) maybeAutoApply(); // turning it on with an update already pending applies now
      json(res, 200, { auto: autoUpdate });
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/rollback')) {
      if (!APPLY_SUPPORTED) { json(res, 501, { error: 'apply/rollback is not supported on this deployment' }); return; }
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      // A session that may manage the deployment OR the break-glass recovery token (for when the server is down).
      const authed = (await authorizeApply(req)) || validToken(req);
      if (!authed) { json(res, 401, { error: 'unauthorized (an admin who may update this deployment, or the recovery token)' }); return; }
      const r = startRollback();
      if (!r.ok) { json(res, r.code || 400, { error: r.msg }); return; }
      json(res, 202, { status: 'started', apply: state.apply });
      return;
    }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(RECOVERY); // any other path (incl. Caddy handle_errors fallback) → recovery page
  } catch (e) {
    json(res, 500, { error: String((e && e.message) || e) });
  }
}).listen(PORT, BIND, () => console.log('supervisor on', BIND + ':' + PORT));

removeStalePublishTemps();
loadKept();
maybeClearSkip(); // at start-up too, even if GitHub is unreachable (the poll's error path does not rebuild the status)
// The kept agent of the running release is offered at once, without waiting for GitHub.
if (running.has(currentVersion)) { setOffer(running.get(currentVersion)); refreshStatus(false); void ensureApk(lastApk); }
poll();
setInterval(poll, POLL_MS);
