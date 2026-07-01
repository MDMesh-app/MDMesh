// MDMesh updater/recovery supervisor — decoupled, always-up. Polls GitHub for new releases, verifies
// the minisign-signed manifest against the baked public key, serves /update/status + a recovery page,
// and drives compose to apply/rollback updates against the host daemon.
const http = require('http');
const fs = require('fs');
const os = require('os');
const cp = require('child_process');
const path = require('path');
const crypto = require('crypto');
const { pickRelease, shapeStatus, imageTags, isTerminal, apkAsset, sha256Matches } = require('./lib');

const PORT = +(process.env.SUPERVISOR_PORT || 9000);
const REPO = process.env.GITHUB_REPO || '';
const CHANNEL = process.env.UPDATE_CHANNEL || 'stable';
const PUB = process.env.MANIFEST_PUBKEY || '/app/minisign.pub';
const TOKEN = process.env.GITHUB_TOKEN || '';
const APPLY_SCRIPT = path.join(__dirname, 'apply.sh');
const ROLLBACK_SCRIPT = path.join(__dirname, 'rollback.sh');

// `current` is mutable: a successful apply advances it so the banner clears without a restart.
let currentVersion = process.env.CURRENT_VERSION || '0.0.0';
// Last verified manifest from poll() — the source of the image refs an apply will deploy.
let lastManifest = null;
// Downloadable APK for the latest verified release {version,versionCode,sha256,url}; null if none.
let lastApk = null;
// Release notes / link / date for the picked release {notes,url,publishedAt}; null when no release.
let lastRelease = null;
// In-flight apply state surfaced via /update/status; null when no apply has run.
let apply = null;

// Unattended ("auto-update") toggle, persisted on the backups volume so it survives a recreate.
// Seeded from AUTO_UPDATE, then overridden by the saved file if present.
const AUTO_FILE = process.env.AUTO_FILE || '/backups/auto.json';
let autoUpdate = process.env.AUTO_UPDATE === '1' || process.env.AUTO_UPDATE === 'true';
try { const j = JSON.parse(fs.readFileSync(AUTO_FILE, 'utf8')); if (typeof j.auto === 'boolean') autoUpdate = j.auto; } catch { /* no saved pref yet */ }
// Version whose auto-apply already failed — never auto-retry it (prevents a rollback crash-loop).
let lastAutoFailed = null;
// Wall-clock of the last completed poll() — used to rate-limit the on-demand /update/check route.
let lastPollAt = 0;
function saveAuto() {
  try { fs.mkdirSync(path.dirname(AUTO_FILE), { recursive: true }); fs.writeFileSync(AUTO_FILE, JSON.stringify({ auto: autoUpdate })); }
  catch (e) { console.log('[auto] persist failed:', String((e && e.message) || e)); }
}

let state = { current: currentVersion, latest: null, updateAvailable: false, verified: false, checkedAt: null, error: 'not polled yet', apply: null, auto: autoUpdate };

async function ghJson(url) {
  const headers = { 'User-Agent': 'mdmesh-updater', Accept: 'application/vnd.github+json' };
  if (TOKEN) headers.Authorization = 'Bearer ' + TOKEN;
  const r = await fetch(url, { headers });
  if (!r.ok) throw new Error('github ' + r.status);
  return r.json();
}

async function verifyManifest(manifestUrl, sigUrl) {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'mf'));
  try {
    for (const [u, f] of [[manifestUrl, 'manifest.json'], [sigUrl, 'manifest.json.minisig']]) {
      const r = await fetch(u);
      if (!r.ok) return null;
      fs.writeFileSync(path.join(d, f), Buffer.from(await r.arrayBuffer()));
    }
    cp.execFileSync('minisign', ['-V', '-p', PUB, '-m', path.join(d, 'manifest.json')], { stdio: 'ignore' });
    return JSON.parse(fs.readFileSync(path.join(d, 'manifest.json'), 'utf8'));
  } catch {
    return null; // unsigned / tampered / unreachable → refuse
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
      const r = await fetch(lastApk.url, { redirect: 'follow' }); // GitHub asset 302s to a CDN
      if (!r.ok) { console.log('[apk] download failed', r.status); return false; }
      const buf = Buffer.from(await r.arrayBuffer());
      if (!sha256Matches(buf, lastApk.sha256)) { console.log('[apk] sha256 mismatch — refusing to serve'); return false; }
      fs.writeFileSync(tmp, buf);
      fs.renameSync(tmp, dest); // atomic publish only after verification
      console.log('[apk] mirrored', dest);
      return true;
    } catch (e) {
      console.log('[apk] error', String((e && e.message) || e));
      try { fs.rmSync(tmp, { force: true }); } catch { /* ignore */ }
      return false;
    } finally { apkFetching = null; }
  })();
  return apkFetching;
}

/** Rebuild `state` from a poll result while preserving the live `apply`/`auto` view. */
function setStatus(args) {
  state = {
    ...shapeStatus(args),
    apply,
    auto: autoUpdate,
    apk: lastApk ? { version: lastApk.version, versionCode: lastApk.versionCode, sha256: lastApk.sha256, available: apkReady() } : null,
    release: lastRelease,
  };
  maybeAutoApply();
}

/** If unattended is on and a fresh verified update is available, apply it (once per version). */
function maybeAutoApply() {
  if (!autoUpdate || !state.updateAvailable) return;
  if (apply && !isTerminal(apply.phase)) return; // an apply is already running
  const { version } = imageTags(lastManifest);
  if (version && version === lastAutoFailed) return; // already failed on this version — don't loop
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
    const manifest = (m && s) ? await verifyManifest(m.browser_download_url, s.browser_download_url) : null;
    lastManifest = manifest; // only verified manifests are ever stored (verifyManifest returns null otherwise)
    lastApk = manifest ? apkAsset(rel, manifest) : null;
    setStatus({ current: currentVersion, manifest, verified: !!manifest, checkedAt: Date.now(),
      error: manifest ? null : 'manifest missing or signature invalid' });
    if (lastApk) void ensureApk(); // warm the mirror cache (download+verify) so a rollout is instant
  } catch (e) {
    state = { ...state, checkedAt: Date.now(), error: String((e && e.message) || e) };
  }
}

// Spawn a phase-emitting script (apply.sh/rollback.sh) and stream its PHASE/ERR lines into the live
// `apply` view. `onClose(code)` finalizes the terminal phase. Shared by apply + rollback.
function spawnPhases(args, onClose) {
  const child = cp.spawn('bash', args, { cwd: process.env.COMPOSE_PROJECT_DIR || '/project', env: process.env });
  let buf = '';
  const onData = (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (!line) continue;
      console.log('[apply]', line);
      if (line.startsWith('PHASE ')) { apply = { ...apply, phase: line.slice(6).trim() }; state.apply = apply; }
      else if (line.startsWith('ERR ')) { apply = { ...apply, error: line.slice(4).trim() }; state.apply = apply; }
    }
  };
  child.stdout.on('data', onData);
  child.stderr.on('data', onData);
  child.on('error', (e) => { apply = { ...apply, phase: 'failed', error: String((e && e.message) || e), finishedAt: Date.now() }; state.apply = apply; });
  child.on('close', onClose);
}

// Apply the verified update. `trigger` is 'manual' or 'auto'. Only called when an update is available.
function startApply(trigger) {
  if (apply && !isTerminal(apply.phase)) return { ok: false, code: 409, msg: 'apply already in progress' };
  if (!state.updateAvailable || !lastManifest) return { ok: false, code: 400, msg: 'no verified update available' };
  const { version: toVersion } = imageTags(lastManifest);
  if (!toVersion) return { ok: false, code: 400, msg: 'manifest has no version' };

  apply = { phase: 'authorizing', fromVersion: currentVersion, toVersion, trigger, startedAt: Date.now(), finishedAt: null, error: null };
  state.apply = apply;
  spawnPhases([APPLY_SCRIPT, toVersion], (code) => {
    if (code === 0) { currentVersion = toVersion; apply = { ...apply, phase: 'done', finishedAt: Date.now() }; }
    else if (!isTerminal(apply.phase)) { apply = { ...apply, phase: 'failed', finishedAt: Date.now() }; }
    else { apply = { ...apply, finishedAt: Date.now() }; }
    if (code !== 0 && apply.trigger === 'auto') lastAutoFailed = toVersion; // don't auto-retry a bad version
    state.apply = apply;
    poll(); // refresh latest/updateAvailable against the (possibly new) current version
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
    if (!isTerminal(apply.phase)) apply = { ...apply, phase: code === 0 ? 'rolled_back' : 'failed' };
    apply = { ...apply, finishedAt: Date.now() };
    state.apply = apply;
    poll();
  });
  return { ok: true };
}

// Authorize an apply by forwarding the caller's session cookie to an ADMIN-gated private endpoint and
// proving they hold an admin permission — not merely that they're logged in. The supervisor holds no
// credentials of its own. Two subtleties drive the checks below:
//   - Headwind's AuthFilter returns HTTP 403 for an unauthenticated request, but a logged-in user who
//     LACKS the permission gets HTTP 200 with a JSON body {"status":"ERROR",...} (the Response wrapper
//     is a plain DTO; permission-denied is not an HTTP status). So status===200 alone is NOT enough —
//     we must also require body.status === "OK".
//   - GET /rest/private/users/all is gated by hasPermission("settings"), the strongest admin perm
//     (user management). A read-only/helpdesk role gets ERROR here, so this rejects under-privileged
//     sessions that /rest/private/settings (no role check) would have wrongly accepted.
// redirect:'manual' so a login redirect can't read as 200. AUTHZ_PATH overridable to tighten further.
const SERVER_BASE = process.env.SERVER_BASE || 'http://server:8080';
const AUTHZ_PATH = process.env.AUTHZ_PATH || '/rest/private/users/all';

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

async function authorizeApply(req) {
  const cookie = req.headers.cookie || '';
  if (!/JSESSIONID=/.test(cookie)) return false;
  try {
    const r = await fetch(SERVER_BASE + AUTHZ_PATH, {
      headers: { Cookie: cookie, Accept: 'application/json' },
      redirect: 'manual',
    });
    if (r.status !== 200) return false;            // 403 = not logged in; 3xx = login redirect
    const body = await r.json().catch(() => null); // permission-denied comes back as 200 + status:ERROR
    return !!body && body.status === 'OK';          // require the admin permission, not just a session
  } catch {
    return false; // server unreachable / non-JSON → refuse (you update from a working console)
  }
}

const RECOVERY = fs.readFileSync(path.join(__dirname, 'recovery.html'), 'utf8');
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
      if (!(await authorizeApply(req))) { json(res, 401, { error: 'unauthorized' }); return; }
      // Rate-limit so this can't be used to hammer the GitHub API; otherwise return the current state.
      if (Date.now() - lastPollAt > 15000) await poll();
      json(res, 200, state);
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/apply')) {
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      if (!(await authorizeApply(req))) { json(res, 401, { error: 'unauthorized' }); return; }
      const r = startApply('manual');
      if (!r.ok) { json(res, r.code || 400, { error: r.msg }); return; }
      json(res, 202, { status: 'started', apply: state.apply });
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/auto')) {
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      if (!(await authorizeApply(req))) { json(res, 401, { error: 'unauthorized' }); return; }
      const body = await readJson(req);
      if (!body || typeof body.auto !== 'boolean') { json(res, 400, { error: 'expected {auto:boolean}' }); return; }
      autoUpdate = body.auto; saveAuto(); state.auto = autoUpdate;
      if (autoUpdate) maybeAutoApply(); // turning it on with an update already pending applies now
      json(res, 200, { auto: autoUpdate });
      return;
    }
    if (req.method === 'POST' && req.url.startsWith('/update/rollback')) {
      if (!csrfOk(req)) { json(res, 403, { error: 'missing console header' }); return; }
      // Admin session OR the break-glass recovery token (for when the server is down).
      const authed = (await authorizeApply(req)) || validToken(req);
      if (!authed) { json(res, 401, { error: 'unauthorized (admin session or recovery token required)' }); return; }
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
}).listen(PORT, () => console.log('supervisor on', PORT));

poll();
setInterval(poll, (+(process.env.POLL_INTERVAL_HOURS || 6)) * 3600 * 1000);
