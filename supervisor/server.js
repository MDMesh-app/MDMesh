// MDMesh updater/recovery supervisor — decoupled, always-up. Polls GitHub for new releases, verifies
// the minisign-signed manifest against the baked public key, serves /update/status + a recovery page,
// and drives compose to apply/rollback updates against the host daemon.
const http = require('http');
const fs = require('fs');
const os = require('os');
const cp = require('child_process');
const path = require('path');
const crypto = require('crypto');
const { pickRelease, shapeStatus, imageTags, isTerminal, apkAsset, sha256Matches, recoveryPage, isPublishTemp, fetchAsset,
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
// When set, every verified APK is also atomically published to this path (the native install
// points it at Tomcat's files dir so /files/agent.apk always serves the latest verified release;
// Docker doesn't need it — Caddy routes /files/agent.apk to this process directly).
const PUBLISH_APK_TO = process.env.PUBLISH_APK_TO || '';
const APPLY_SCRIPT = path.join(__dirname, 'apply.sh');
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
// Last verified manifest from poll() — the source of the image refs an apply will deploy.
let lastManifest = null;
// Downloadable APK for the latest verified release {version,versionCode,sha256,url,apiUrl}; null if none.
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

let state = { current: currentVersion, latest: null, updateAvailable: false, verified: false, checkedAt: null, error: 'not polled yet', apply: null, auto: autoUpdate, autoSkipped: skipVersion, applySupported: APPLY_SUPPORTED };

async function ghJson(url) {
  const headers = { 'User-Agent': 'mdmesh-updater', Accept: 'application/vnd.github+json' };
  if (TOKEN) headers.Authorization = 'Bearer ' + TOKEN;
  const r = await fetch(url, { headers });
  if (!r.ok) throw new Error('github ' + r.status);
  return r.json();
}

/** Download a release asset: through the asset API URL with the token when one is set (the only way a private repo
 *  serves it), else browser_download_url. The one download path for the manifest, its signature and the APK. */
const ghAsset = (asset) => fetchAsset(asset, TOKEN);

/** A fetch/exec error as one loggable line: the message plus the low-level cause code, never headers or URLs
 *  (a redirect target is a signed URL, and the token lives in a header). */
function errText(e) {
  const cause = e && e.cause && (e.cause.code || e.cause.message);
  return String((e && e.message) || e) + (cause ? ' (' + cause + ')' : '');
}

/** Download + minisign-verify a release's manifest. Returns { manifest } when verified, else { error }. A silent
 *  refusal made a private-repo 404 look like a bad signature, so the reason is logged in full ([verify] line) — but
 *  /update/status is public (Caddy proxies it), so `error` names only the kind of failure: no upstream output
 *  (minisign's text, fetch causes) and no configuration hints. */
async function verifyManifest(mAsset, sAsset) {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'mf'));
  const fail = (what, detail) => {
    console.log('[verify] manifest not verified:', what + (detail ? ': ' + detail : ''));
    return { error: 'manifest not verified: ' + what + ' (details in the supervisor log)' };
  };
  try {
    for (const [asset, f] of [[mAsset, 'manifest.json'], [sAsset, 'manifest.json.minisig']]) {
      let r;
      try { r = await ghAsset(asset); } catch (e) { return fail(`${f}: download failed`, errText(e)); }
      if (!r.ok) return fail(`${f}: HTTP ${r.status}`, r.status === 404 && !TOKEN ? 'a private repo needs GITHUB_TOKEN' : '');
      fs.writeFileSync(path.join(d, f), Buffer.from(await r.arrayBuffer()));
    }
    const v = cp.spawnSync('minisign', ['-V', '-p', PUB, '-m', path.join(d, 'manifest.json')], { encoding: 'utf8' });
    if (v.error) return fail('minisign could not run', errText(v.error));
    if (v.status !== 0) {
      const why = (v.stderr || v.stdout || '').split(d + path.sep).join('').replace(/\s+/g, ' ').trim();
      return fail('minisign signature check failed', why || 'exit ' + v.status);
    }
    try { return { manifest: JSON.parse(fs.readFileSync(path.join(d, 'manifest.json'), 'utf8')) }; }
    catch (e) { return fail('manifest.json is not valid JSON', errText(e)); }
  } catch (e) {
    return fail('download or check failed', errText(e)); // unreachable / unreadable → refuse, but say why in the log
  } finally {
    fs.rmSync(d, { recursive: true, force: true });
  }
}

// --- APK mirror: download the verified release APK and serve it from the deployment's own origin, so
// devices never need to reach GitHub. Served only after SHA-256 matches the signed manifest. ---
const APK_DIR = process.env.APK_CACHE_DIR || '/backups/apk';
function apkPath() { return lastApk ? path.join(APK_DIR, `agent-${lastApk.versionCode}.apk`) : null; }
function apkReady() { const p = apkPath(); return !!(p && fs.existsSync(p)); }
let apkFetching = null;
async function ensureApk() {
  if (!lastApk) return false;
  const dest = apkPath();
  if (fs.existsSync(dest)) return true;
  if (apkFetching) return apkFetching; // coalesce concurrent first-requests
  apkFetching = (async () => {
    const tmp = dest + '.tmp';
    try {
      fs.mkdirSync(APK_DIR, { recursive: true });
      const r = await ghAsset({ url: lastApk.apiUrl, browser_download_url: lastApk.url }); // 302s to a CDN; see fetchAsset
      if (!r.ok) { console.log('[apk] download failed', r.status); return false; }
      const buf = Buffer.from(await r.arrayBuffer());
      if (!sha256Matches(buf, lastApk.sha256)) { console.log('[apk] sha256 mismatch — refusing to serve'); return false; }
      fs.writeFileSync(tmp, buf);
      fs.renameSync(tmp, dest); // atomic publish only after verification
      console.log('[apk] mirrored', dest);
      publishApk(dest);
      refreshApkAvailable();
      return true;
    } catch (e) {
      console.log('[apk] error', errText(e));
      try { fs.rmSync(tmp, { force: true }); } catch { /* ignore */ }
      return false;
    } finally { apkFetching = null; }
  })();
  return apkFetching;
}

/** Re-derive `available` once a download lands: setStatus() snapshots apkReady() before poll()'s warm-up download
 *  finishes, and nothing else rebuilds `state` until the next poll (POLL_INTERVAL_HOURS). */
function refreshApkAvailable() {
  if (state.apk) state.apk = { ...state.apk, available: apkReady() };
}

/** Copy a freshly-verified APK over the deployment's static hosting path (native installs). The copy goes to a new,
 *  exclusively-created temp name (COPYFILE_EXCL never opens an existing file or link), so nothing left in that
 *  directory, such as a link planted at a predictable name or a leftover from an earlier run, is written through
 *  or blocks the publish. Temp files a killed process left behind are removed first (removeStalePublishTemps). */
function publishApk(src) {
  if (!PUBLISH_APK_TO) return;
  removeStalePublishTemps();
  const tmp = `${PUBLISH_APK_TO}.${crypto.randomBytes(8).toString('hex')}.tmp`;
  try {
    fs.mkdirSync(path.dirname(PUBLISH_APK_TO), { recursive: true });
    fs.copyFileSync(src, tmp, fs.constants.COPYFILE_EXCL);
    fs.renameSync(tmp, PUBLISH_APK_TO); // atomic — a device mid-download never sees a torn file
    console.log('[apk] published', PUBLISH_APK_TO);
  } catch (e) {
    console.log('[apk] publish failed:', String((e && e.message) || e));
    try { fs.rmSync(tmp, { force: true }); } catch { /* ignore */ }
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

/** Rebuild `state` from a poll result while preserving the live `apply`/`auto` view. */
function setStatus(args) {
  maybeClearSkip();
  state = {
    ...shapeStatus(args),
    apply,
    auto: autoUpdate,
    autoSkipped: skipVersion,
    applySupported: APPLY_SUPPORTED,
    apk: lastApk ? { version: lastApk.version, versionCode: lastApk.versionCode, sha256: lastApk.sha256, available: apkReady() } : null,
    release: lastRelease,
  };
  maybeAutoApply();
}

/** If unattended is on and a fresh verified update is available, apply it (once per version). */
function maybeAutoApply() {
  if (!APPLY_SUPPORTED) return;
  if (!autoUpdate || !state.updateAvailable) return;
  if (apply && !isTerminal(apply.phase)) return; // an apply is already running
  const { version } = imageTags(lastManifest);
  if (version && version === skipVersion) return; // failed or rolled back from — don't loop
  console.log('[auto] verified update', version, '→ self-applying');
  startApply('auto');
}

async function poll() {
  lastPollAt = Date.now(); // stamp up-front so /update/check throttling also covers an in-flight poll
  if (!REPO) { lastManifest = null; lastRelease = null; setStatus({ current: currentVersion, manifest: null, verified: false, checkedAt: Date.now(), error: 'GITHUB_REPO not set' }); return; }
  try {
    const rel = pickRelease(await ghJson(`https://api.github.com/repos/${REPO}/releases?per_page=10`), CHANNEL);
    if (!rel) { lastManifest = null; lastRelease = null; setStatus({ current: currentVersion, manifest: null, verified: false, checkedAt: Date.now(), error: 'no matching release' }); return; }
    lastRelease = { notes: rel.body || null, url: rel.html_url || null, publishedAt: rel.published_at || null };
    const m = rel.assets.find((a) => a.name === 'manifest.json');
    const s = rel.assets.find((a) => a.name === 'manifest.json.minisig');
    const v = (m && s) ? await verifyManifest(m, s) : { error: 'release has no manifest.json.minisig' };
    const manifest = v.manifest || null;
    lastManifest = manifest; // only verified manifests are ever stored
    lastApk = manifest ? apkAsset(rel, manifest) : null;
    setStatus({ current: currentVersion, manifest, verified: !!manifest, checkedAt: Date.now(), error: manifest ? null : v.error });
    if (lastApk) void ensureApk(); // warm the mirror cache (download+verify) so a rollout is instant
  } catch (e) {
    state = { ...state, current: currentVersion, checkedAt: Date.now(), error: String((e && e.message) || e) };
  }
}

/** Re-derive the status from what is already known (the last verified manifest) after `current` changed, without
 *  waiting for GitHub: the follow-up poll can be slow or fail, and until then `current`/`updateAvailable` were stale. */
function refreshStatus() {
  setStatus({ current: currentVersion, manifest: lastManifest, verified: !!lastManifest, checkedAt: state.checkedAt, error: state.error });
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
  const refusal = applyRefusal({ manifest: lastManifest, current: currentVersion, skipVersion });
  if (refusal) return { ok: false, code: 400, msg: refusal };
  const { version: toVersion } = imageTags(lastManifest);

  apply = { phase: 'authorizing', fromVersion: currentVersion, toVersion, trigger, startedAt: Date.now(), finishedAt: null, error: null };
  state.apply = apply;
  spawnPhases([APPLY_SCRIPT, toVersion], (code, final) => {
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
  const r = await fetch(SERVER_BASE + p, { headers: { Cookie: cookie, Accept: 'application/json' }, redirect: 'manual' });
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
      // The mirrored agent APK for device rollouts. Public GET (the device fetches it during an
      // app.install); integrity is guaranteed by the SHA-256 check in ensureApk + the agent's own
      // payload sha256. ?v=<versionCode> is advisory — we always serve the current verified apk.
      if (!lastApk) { res.writeHead(404).end('no apk'); return; }
      const ready = await ensureApk();
      const p = apkPath();
      if (!ready || !p || !fs.existsSync(p)) { json(res, 502, { error: 'apk unavailable or checksum mismatch' }); return; }
      res.writeHead(200, { 'content-type': 'application/vnd.android.package-archive', 'content-length': fs.statSync(p).size });
      fs.createReadStream(p).pipe(res);
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
maybeClearSkip(); // at start-up too, even if GitHub is unreachable (the poll's error path does not rebuild the status)
poll();
setInterval(poll, (+(process.env.POLL_INTERVAL_HOURS || 6)) * 3600 * 1000);
