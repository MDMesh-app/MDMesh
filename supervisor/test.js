const t = require('node:test');
const a = require('node:assert');
const { semverGt, pickRelease, shapeStatus, imageTags, nextPhase, isTerminal, apkAsset, sha256Matches, recoveryPage, isPublishTemp,
  assetRequest, fetchAsset, envValue, applyLine, applyRefusal } = require('./lib');

t.test('semverGt', () => {
  a.equal(semverGt('1.2.4', '1.2.3'), true);
  a.equal(semverGt('1.2.3', '1.2.3'), false);
  a.equal(semverGt('v2.0.0', 'v1.9.9'), true);
  a.equal(semverGt('1.0.0', '2.0.0'), false);
  a.equal(semverGt('bad', '1.0.0'), false);
});

t.test('pickRelease — newest stable with a manifest', () => {
  const rs = [
    { tag_name: 'v1.0.0', assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.2.0', prerelease: true, assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.1.0', assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.3.0', assets: [{ name: 'other' }] },
    { tag_name: 'v9.9.9', draft: true, assets: [{ name: 'manifest.json' }] },
  ];
  a.equal(pickRelease(rs, 'stable').tag_name, 'v1.1.0'); // prerelease/no-manifest/draft excluded
  a.equal(pickRelease(rs, 'beta').tag_name, 'v1.2.0');   // prerelease allowed on beta
  a.equal(pickRelease([], 'stable'), null);
});

t.test('shapeStatus', () => {
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: true }).updateAvailable, true);
  a.equal(shapeStatus({ current: '1.1.0', manifest: { version: '1.1.0' }, verified: true }).updateAvailable, false);
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: false }).updateAvailable, false);
});

t.test('imageTags', () => {
  const m = { version: '1.1.0', components: { serverImage: 'ghcr.io/o/mdmesh-server:1.1.0', webImage: 'ghcr.io/o/mdmesh-web:1.1.0' } };
  a.deepEqual(imageTags(m), { serverImage: 'ghcr.io/o/mdmesh-server:1.1.0', webImage: 'ghcr.io/o/mdmesh-web:1.1.0', version: '1.1.0' });
  a.deepEqual(imageTags(null), { serverImage: null, webImage: null, version: null });
});

t.test('apkAsset', () => {
  const manifest = { version: '1.2.0', components: { apk: { file: 'mdmesh-agent.apk', versionCode: 120, sha256: 'abc', signatureChecksum: 'x' } } };
  const release = { assets: [{ name: 'mdmesh-agent.apk', browser_download_url: 'https://gh/dl/mdmesh-agent.apk',
    url: 'https://api.gh/repos/o/r/releases/assets/7' }, { name: 'manifest.json' }] };
  // Both URLs are kept: a private repo only serves the bytes through the asset API URL (see assetRequest).
  a.deepEqual(apkAsset(release, manifest), { version: '1.2.0', versionCode: 120, sha256: 'abc', url: 'https://gh/dl/mdmesh-agent.apk',
    apiUrl: 'https://api.gh/repos/o/r/releases/assets/7' });
  const noApi = { assets: [{ name: 'mdmesh-agent.apk', browser_download_url: 'https://gh/dl/mdmesh-agent.apk' }] };
  a.equal(apkAsset(noApi, manifest).apiUrl, null);
  a.equal(apkAsset({ assets: [] }, manifest), null);     // asset not present
  a.equal(apkAsset(release, { version: '1.2.0', components: {} }), null); // no apk block
  a.equal(apkAsset(null, null), null);
});

// A private repo 404s browser_download_url even with a token; only the asset API URL + octet-stream + token serves
// the bytes (live rehearsal, brain/reviews/live-apply-rollback.md).
t.test('assetRequest — token: asset API URL with octet-stream + Bearer; no token: browser_download_url, no auth', () => {
  const asset = { url: 'https://api.github.com/repos/o/r/releases/assets/1', browser_download_url: 'https://github.com/o/r/releases/download/v1/m.json' };
  a.deepEqual(assetRequest(asset, 'tok'), { url: asset.url,
    headers: { 'User-Agent': 'mdmesh-updater', Accept: 'application/octet-stream', Authorization: 'Bearer tok' } });
  a.deepEqual(assetRequest(asset, ''), { url: asset.browser_download_url, headers: { 'User-Agent': 'mdmesh-updater' } });
  // No API URL known (an older cached shape): the browser URL, and the token is never sent to it.
  a.deepEqual(assetRequest({ browser_download_url: asset.browser_download_url }, 'tok'),
    { url: asset.browser_download_url, headers: { 'User-Agent': 'mdmesh-updater' } });
});

t.test('assetRequest — the token goes only to an https://api.github.com asset URL; anything else gets the browser URL, no token', () => {
  const bdu = 'https://github.com/o/r/releases/download/v1/m.json';
  for (const url of ['https://evil.example/repos/o/r/releases/assets/1', 'http://api.github.com/repos/o/r/releases/assets/1',
    'https://api.github.com.evil.example/x', 'https://user@evil.example/x', 'not a url']) {
    a.deepEqual(assetRequest({ url, browser_download_url: bdu }, 'tok'), { url: bdu, headers: { 'User-Agent': 'mdmesh-updater' } }, url);
  }
  a.equal(assetRequest({ url: 'https://api.github.com:443/repos/o/r/releases/assets/1', browser_download_url: bdu }, 'tok').headers.Authorization,
    'Bearer tok');
});

t.test('fetchAsset — refuses a non-https URL: the first one and every redirect hop', async () => {
  const plain = mockFetch({ 'http://github.com/dl/m.json': { status: 200, body: 'X' } });
  await a.rejects(fetchAsset({ browser_download_url: 'http://github.com/dl/m.json' }, '', plain), /https/);
  a.equal(plain.calls.length, 0, 'nothing is fetched over http');
  const hop = mockFetch({
    'https://api.github.com/repos/o/r/releases/assets/1': { status: 302, location: 'http://cdn.example/x' },
    'http://cdn.example/x': { status: 200, body: 'X' },
  });
  await a.rejects(fetchAsset({ url: 'https://api.github.com/repos/o/r/releases/assets/1' }, 'tok', hop), /https/);
  a.deepEqual(hop.calls.map((c) => c.url), ['https://api.github.com/repos/o/r/releases/assets/1'], 'the http hop is never requested');
});

t.test('fetchAsset — cancels each redirect response body before following it (no dangling sockets)', async () => {
  let cancelled = 0;
  const redirect = { status: 302, headers: new Headers({ location: 'https://cdn.test/x' }), body: { cancel: async () => { cancelled++; } } };
  const final = new Response('X', { status: 200 });
  const f = async (url) => (url === 'https://api.github.com/repos/o/r/releases/assets/1' ? redirect : final);
  const r = await fetchAsset({ url: 'https://api.github.com/repos/o/r/releases/assets/1' }, 'tok', f);
  a.equal(await r.text(), 'X');
  a.equal(cancelled, 1);
  // A redirect without a body (body: null) is fine too.
  const f2 = async (url) => (url === 'https://api.github.com/a' ? { status: 302, headers: new Headers({ location: '/b' }), body: null } : final);
  a.equal((await fetchAsset({ url: 'https://api.github.com/a' }, 'tok', f2)).status, 200);
});

/** A scripted fetch: `routes` maps URL → { status, location?, body? }; every call is recorded with its headers. */
function mockFetch(routes) {
  const calls = [];
  const fn = async (url, opts) => {
    calls.push({ url: String(url), headers: { ...(opts && opts.headers) }, redirect: opts && opts.redirect });
    const r = routes[String(url)];
    if (!r) return new Response('nope', { status: 404 });
    return new Response(r.body == null ? null : r.body, { status: r.status || 200, headers: r.location ? { location: r.location } : {} });
  };
  fn.calls = calls;
  return fn;
}

t.test('fetchAsset — follows the API 302 to the CDN and never sends the token to another origin', async () => {
  const asset = { url: 'https://api.github.com/repos/o/r/releases/assets/1', browser_download_url: 'https://github.com/dl/m.json' };
  const f = mockFetch({
    [asset.url]: { status: 302, location: 'https://objects.githubusercontent.com/signed?sig=x' },
    'https://objects.githubusercontent.com/signed?sig=x': { status: 200, body: 'BYTES' },
  });
  const r = await fetchAsset(asset, 'tok', f);
  a.equal(r.status, 200);
  a.equal(await r.text(), 'BYTES');
  a.equal(f.calls.length, 2);
  a.equal(f.calls[0].headers.Authorization, 'Bearer tok');
  a.equal(f.calls[0].headers.Accept, 'application/octet-stream');
  a.equal(f.calls[1].headers.Authorization, undefined, 'the token must not reach the CDN host');
  a.ok(f.calls.every((c) => c.redirect === 'manual'), 'redirects are followed by hand, not by fetch');
});

t.test('fetchAsset — same-origin redirect keeps the token; once dropped it stays dropped; relative Location resolves', async () => {
  const f = mockFetch({
    'https://api.github.com/a': { status: 301, location: '/b' },
    'https://api.github.com/b': { status: 302, location: 'https://cdn.example/c' },
    'https://cdn.example/c': { status: 307, location: 'https://api.github.com/d' },
    'https://api.github.com/d': { status: 200, body: 'D' },
  });
  const r = await fetchAsset({ url: 'https://api.github.com/a' }, 'tok', f);
  a.equal(await r.text(), 'D');
  a.deepEqual(f.calls.map((c) => [c.url, c.headers.Authorization || null]), [
    ['https://api.github.com/a', 'Bearer tok'],
    ['https://api.github.com/b', 'Bearer tok'],
    ['https://cdn.example/c', null],
    ['https://api.github.com/d', null],
  ]);
});

t.test('fetchAsset — no token: browser_download_url; non-redirect errors are returned; redirect loops are cut off', async () => {
  const f = mockFetch({ 'https://github.com/dl/m.json': { status: 404 } });
  const r = await fetchAsset({ url: 'https://api.github.com/x', browser_download_url: 'https://github.com/dl/m.json' }, '', f);
  a.equal(r.status, 404);
  a.equal(f.calls[0].url, 'https://github.com/dl/m.json');
  a.equal(f.calls[0].headers.Authorization, undefined);
  const loop = mockFetch({ 'https://h/a': { status: 302, location: 'https://h/a' } });
  await a.rejects(fetchAsset({ browser_download_url: 'https://h/a' }, '', loop), /too many redirects/);
  a.equal(loop.calls.length, 6);
  await a.rejects(fetchAsset({}, 'tok', mockFetch({})), /no download URL/);
});

t.test('envValue — reads KEY=value from .env text the way apply.sh get_env does (first match), tolerating quotes/CRLF', () => {
  const env = 'A=1\nCURRENT_VERSION=0.0.2\nCURRENT_VERSION=9.9.9\n# CURRENT_VERSION=bad\n';
  a.equal(envValue(env, 'CURRENT_VERSION'), '0.0.2');
  a.equal(envValue('CURRENT_VERSION="0.3.1"\r\n', 'CURRENT_VERSION'), '0.3.1');
  a.equal(envValue("CURRENT_VERSION='0.3.1'  \n", 'CURRENT_VERSION'), '0.3.1');
  a.equal(envValue('XCURRENT_VERSION=1\n', 'CURRENT_VERSION'), null);
  a.equal(envValue('CURRENT_VERSION=\n', 'CURRENT_VERSION'), null);  // empty = unset
  a.equal(envValue('', 'CURRENT_VERSION'), null);
  a.equal(envValue(null, 'CURRENT_VERSION'), null);
});

t.test('applyRefusal — the Update guard says what is actually true', () => {
  const m = (v) => ({ version: v });
  a.equal(applyRefusal({ manifest: m('0.0.3'), current: '0.0.2', skipVersion: null }), null, 'a newer verified release applies');
  a.equal(applyRefusal({ manifest: m('0.0.3'), current: '0.0.2', skipVersion: '0.0.3' }), null, 'the auto skip never blocks a manual Update');
  a.equal(applyRefusal({ manifest: null, current: '0.0.2' }), 'no verified update available');
  a.equal(applyRefusal({ manifest: {}, current: '0.0.2' }), 'the verified manifest has no version');
  // The semverGt guard: never re-apply the running (or an older) version: it would overwrite /backups/latest.
  a.equal(applyRefusal({ manifest: m('0.0.4'), current: '0.0.4', skipVersion: null }), 'already running 0.0.4');
  a.equal(applyRefusal({ manifest: m('0.0.3'), current: '0.0.4', skipVersion: null }), 'already running 0.0.4 (newer than 0.0.3)');
  a.equal(applyRefusal({ manifest: m('0.0.4'), current: '0.0.4', skipVersion: '0.0.4' }),
    'already running 0.0.4, whose update failed: if it is not healthy, use Roll back (/recovery) to return to the previous version');
  // A non-release manifest version (not X.Y.Z) is refused for what it is, not as "already running".
  a.equal(applyRefusal({ manifest: m('nightly'), current: '0.0.4', skipVersion: null }),
    'the verified release version "nightly" is not a release version (X.Y.Z), so it cannot be applied');
  a.equal(applyRefusal({ manifest: m('nightly'), current: 'latest', skipVersion: null }),
    'the verified release version "nightly" is not a release version (X.Y.Z), so it cannot be applied');
  a.equal(applyRefusal({ manifest: m('0.0.4'), current: 'latest', skipVersion: null }),
    'the running version "latest" is not a release version (X.Y.Z), so it cannot be compared: update by hand');
});

t.test('sha256Matches — the APK publish gate', () => {
  const buf = Buffer.from('hello');
  const sha = require('crypto').createHash('sha256').update(buf).digest('hex');
  a.equal(sha256Matches(buf, sha), true);
  a.equal(sha256Matches(buf, sha.toUpperCase()), true);   // case-insensitive
  a.equal(sha256Matches(buf, 'deadbeef'), false);         // mismatch → refuse
  a.equal(sha256Matches(buf, ''), false);                 // missing → refuse
  a.equal(sha256Matches(Buffer.from('hellö'), sha), false);
});

t.test('apply phase state machine', () => {
  a.equal(nextPhase('authorizing'), 'backup');
  a.equal(nextPhase('backup'), 'pull');
  a.equal(nextPhase('healthcheck'), 'done');
  a.equal(nextPhase('done'), null);     // end of happy path
  a.equal(nextPhase('rollback'), null); // not on the happy path
  a.equal(isTerminal('done'), true);
  a.equal(isTerminal('rolled_back'), true);
  a.equal(isTerminal('failed'), true);
  a.equal(isTerminal('pull'), false);
  a.equal(isTerminal('rollback'), false);
});

t.test('applyLine — PHASE sets the phase; every ERR line is kept (the first cause and the recovery steps both show)', () => {
  let ap = { phase: 'authorizing', error: null };
  ap = applyLine(ap, 'PHASE healthcheck');
  a.equal(ap.phase, 'healthcheck');
  ap = applyLine(ap, 'ERR health check failed after 180s');
  ap = applyLine(ap, 'PHASE rollback');
  ap = applyLine(ap, 'ERR database restore failed: ERROR: boom');
  a.equal(ap.phase, 'rollback');
  a.equal(ap.error, 'health check failed after 180s | database restore failed: ERROR: boom');
  a.equal(applyLine(ap, 'Container x Started'), ap, 'other lines leave the view alone');
  // A terminal phase is published by the close handler, together with the new `current`, never straight from the
  // script's line: a client that stops polling at `done`/`rolled_back` must not read the old version.
  for (const p of ['done', 'rolled_back', 'failed']) a.equal(applyLine(ap, 'PHASE ' + p).phase, 'rollback', p);
  a.equal(applyLine(ap, 'OK 1.2.3'), ap);
});

t.test('recoveryPage — marks the page with whether apply/rollback is supported', () => {
  const html = require('fs').readFileSync(require('path').join(__dirname, 'recovery.html'), 'utf8');
  a.equal(html.split('<body>').length, 2, 'recovery.html must have exactly one bare <body> tag to mark');
  const off = recoveryPage(html, false), on = recoveryPage(html, true);
  a.ok(off.includes('<body data-apply="0">'));
  a.ok(on.includes('<body data-apply="1">'));
  a.ok(!off.includes('<body>') && !on.includes('<body>'));
  // The page itself hides the Roll back card and shows the manual steps under data-apply="0" (CSS, no JS needed).
  a.match(html, /body\[data-apply="0"\] #rbcard\{display:none\}/);
  a.match(html, /body:not\(\[data-apply="0"\]\) #manual\{display:none\}/);
  a.ok(html.includes('git pull &amp;&amp; ./setup.sh') && html.includes('git pull &amp;&amp; sudo ./setup.sh --native'));
});

t.test('recovery.html escapes status strings before they reach innerHTML', () => {
  const html = require('fs').readFileSync(require('path').join(__dirname, 'recovery.html'), 'utf8');
  const m = html.match(/^function esc\(x\)\{.*\}$/m);
  a.ok(m, 'recovery.html defines a one-line function esc(x){…}');
  const esc = new Function(m[0] + '; return esc;')();
  a.equal(esc('<img src=x onerror="a()">&\''), '&lt;img src=x onerror=&quot;a()&quot;&gt;&amp;&#39;');
  a.equal(esc(null), '');
  a.equal(esc(42), '42');
  // Every server/network-derived string concatenated into markup goes through esc() (or v(), which wraps it).
  a.match(html, /function v\(x\)\{return x==null\?'—':esc\(x\)\}/);
  for (const raw of ["'+s.error+'", "'+a.error+'", '(PH[a.phase]||a.phase)', "(a.fromVersion||'?')", "' → '+a.toVersion",
                     "'+(b.error||", "'+e+'"]) {
    a.ok(!html.includes(raw), 'unescaped interpolation left in recovery.html: ' + raw);
  }
});

t.test('isPublishTemp — only publishApk\'s own temp names', () => {
  a.equal(isPublishTemp('agent.apk.0123456789abcdef.tmp', 'agent.apk'), true);
  a.equal(isPublishTemp('agent.apk.0123456789ABCDEF.tmp', 'agent.apk'), false); // randomBytes().toString('hex') is lowercase
  a.equal(isPublishTemp('agent.apk.tmp', 'agent.apk'), false);
  a.equal(isPublishTemp('agent.apk.0123456789abcde.tmp', 'agent.apk'), false);  // 15 hex
  a.equal(isPublishTemp('agent.apk.0123456789abcdef0.tmp', 'agent.apk'), false); // 17 hex
  a.equal(isPublishTemp('agent.apk.0123456789abcdef.tmp.x', 'agent.apk'), false);
  a.equal(isPublishTemp('other.apk.0123456789abcdef.tmp', 'agent.apk'), false);
  a.equal(isPublishTemp('agentXapk.0123456789abcdef.tmp', 'agent.apk'), false);  // the '.' in the name is literal
  a.equal(isPublishTemp('agent.apk.backup', 'agent.apk'), false);
  a.equal(isPublishTemp('a+b(1).apk.0123456789abcdef.tmp', 'a+b(1).apk'), true);  // regex metacharacters in the name
});

// --- Process-level: the real server.js against a local fake GitHub (no network). Skipped without minisign; the
// supervisor image (where CI runs this file) ships it. ---
const cp = require('child_process');
const HAS_MINISIGN = cp.spawnSync('minisign', ['-v']).status === 0;

// publishApk's temp files (PUBLISH_APK_TO.<16 hex>.tmp) sit in Tomcat's public files/ dir; one left by a process that
// died mid-copy would be served under /files/. The supervisor removes them when it starts (and before each publish,
// below): only that exact shape, only a regular file or a link (the link itself, never its target).
t.test('stale publish temp files are removed at start; nothing else in the files dir is touched', { timeout: 20000 }, async (tt) => {
  const fs = require('fs'), os = require('os'), path = require('path'), http = require('http');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-tmp-'));
  let child = null;
  tt.after(async () => {
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = new Promise((r) => child.once('exit', r));
      child.kill();
      await exited;
    }
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const files = path.join(dir, 'files'), outside = path.join(dir, 'outside');
  fs.mkdirSync(files);
  fs.writeFileSync(outside, 'OUTSIDE');
  fs.writeFileSync(path.join(files, 'agent.apk'), 'CURRENT APK');
  fs.writeFileSync(path.join(files, 'agent.apk.0123456789abcdef.tmp'), 'PARTIAL COPY');     // stale: removed
  fs.symlinkSync(outside, path.join(files, 'agent.apk.fedcba9876543210.tmp'));              // stale link: unlinked, target kept
  fs.mkdirSync(path.join(files, 'agent.apk.aaaaaaaaaaaaaaaa.tmp'));                         // a directory: left alone
  for (const keep of ['agent.apk.tmp', 'agent.apk.backup', 'agent.apk.0123.tmp', 'other.apk.0123456789abcdef.tmp']) {
    fs.writeFileSync(path.join(files, keep), 'KEEP');
  }
  const port = await new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
  child = cp.spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    stdio: ['ignore', 'pipe', 'pipe'],
    // No GITHUB_REPO: the startup poll does nothing, so nothing is published and only the start-up cleanup can act.
    env: { ...process.env, GITHUB_REPO: '', GITHUB_TOKEN: '', SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1',
      APPLY_SUPPORTED: '0', AUTO_UPDATE: '0', MANIFEST_PUBKEY: path.join(dir, 'none.pub'), APK_CACHE_DIR: path.join(dir, 'apk'),
      PUBLISH_APK_TO: path.join(files, 'agent.apk'), AUTO_FILE: path.join(dir, 'auto.json'),
      RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token') } });
  let log = '';
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('supervisor never listened:\n' + log)), 10000);
    const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
    const onData = (d) => { log += d; if (log.includes('supervisor on')) { clearTimeout(timer); child.off('exit', onExit); resolve(); } };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', onExit);
  });
  a.deepEqual(fs.readdirSync(files).sort(), ['agent.apk', 'agent.apk.0123.tmp', 'agent.apk.aaaaaaaaaaaaaaaa.tmp', 'agent.apk.backup',
    'agent.apk.tmp', 'other.apk.0123456789abcdef.tmp'], 'only the stale temp file and link are gone');
  a.equal(fs.readFileSync(outside, 'utf8'), 'OUTSIDE', 'the stale link\'s target is untouched');
  a.equal(fs.readFileSync(path.join(files, 'agent.apk'), 'utf8'), 'CURRENT APK');
});

t.test('/update/status reports the mirrored APK as available once the warm-up download lands, without re-polling; '
  + 'the published copy never goes through a link planted at a temp name, and stale publish temps are removed first',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-apk-'));
    let gh = null, child = null;
    // One hook, in order: the supervisor and the fake GitHub are fully down before the temp dir they use is removed.
    tt.after(async () => {
      if (child && child.exitCode === null && child.signalCode === null) {
        const exited = new Promise((r) => child.once('exit', r));
        child.kill();
        await exited;
      }
      if (gh) { gh.closeAllConnections(); await new Promise((r) => gh.close(r)); }
      fs.rmSync(dir, { recursive: true, force: true });
    });

    // A signed release: throwaway key pair, a manifest naming the APK by sha256, the detached signature beside it.
    const apk = crypto.randomBytes(4096);
    const manifest = { version: '9.9.9', channel: 'stable', components: { apk: {
      file: 'mdmesh-agent.apk', versionCode: 999, sha256: crypto.createHash('sha256').update(apk).digest('hex') } } };
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest));
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'k.pub'), '-s', path.join(dir, 'k.key')], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-S', '-s', path.join(dir, 'k.key'), '-m', path.join(dir, 'manifest.json')], { stdio: 'ignore' });

    // Fake GitHub: the releases API (counted) and the three release assets.
    let releaseCalls = 0;
    gh = http.createServer((req, res) => {
      const base = `http://127.0.0.1:${gh.address().port}`;
      if (req.url.startsWith('/repos/o/r/releases')) {
        releaseCalls++;
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify([{ tag_name: 'v9.9.9', html_url: base + '/rel', assets: ['manifest.json', 'manifest.json.minisig', 'mdmesh-agent.apk']
          .map((name) => ({ name, browser_download_url: `https://github.com/dl/${name}` })) }]));
      } else if (req.url === '/dl/mdmesh-agent.apk') {
        // A stale publish temp that appears after start-up (not seen by the start-up cleanup): the publish removes it.
        fs.writeFileSync(path.join(dir, 'files', 'agent.apk.1111111111111111.tmp'), 'STALE');
        res.end(apk);
      }
      else if (req.url === '/dl/manifest.json' || req.url === '/dl/manifest.json.minisig') res.end(fs.readFileSync(path.join(dir, req.url.slice(4))));
      else { res.statusCode = 404; res.end(); }
    });
    await new Promise((r) => gh.listen(0, '127.0.0.1', r));

    // server.js calls the fixed https://api.github.com origin and the https release URLs; a preload maps both to the fake.
    const preload = writePreload(dir);
    // On a native install PUBLISH_APK_TO is in Tomcat's files/ directory, and older versions ran the supervisor as root
    // there. A link planted at the predictable temp name the publish step used to write through must be left alone,
    // target included.
    const files = path.join(dir, 'files'), outside = path.join(dir, 'outside');
    fs.mkdirSync(files);
    fs.writeFileSync(outside, 'NOT AN APK');
    fs.symlinkSync(outside, path.join(files, 'agent.apk.tmp'));
    const port = await new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
    child = cp.spawn(process.execPath, ['--require', preload, path.join(__dirname, 'server.js')], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, FAKE_ORIGINS: JSON.stringify({ 'https://api.github.com': `http://127.0.0.1:${gh.address().port}`,
        'https://github.com': `http://127.0.0.1:${gh.address().port}` }), GITHUB_REPO: 'o/r', GITHUB_TOKEN: '',
        SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1', CURRENT_VERSION: '9.9.9', APPLY_SUPPORTED: '0', AUTO_UPDATE: '0',
        UPDATE_CHANNEL: 'stable', POLL_INTERVAL_HOURS: '6',
        MANIFEST_PUBKEY: path.join(dir, 'k.pub'), APK_CACHE_DIR: path.join(dir, 'apk'), PUBLISH_APK_TO: path.join(files, 'agent.apk'),
        AUTO_FILE: path.join(dir, 'auto.json'), RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token') } });

    // Wait for the startup poll's warm-up download to land. server.js logs "[apk] mirrored" in the same synchronous
    // block that publishes the file, so any request answered after this line sees the post-download state.
    let log = '';
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('APK never mirrored; supervisor output:\n' + log)), 10000);
      const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
      const onData = (d) => { log += d; if (log.includes('[apk] mirrored')) { clearTimeout(timer); child.off('exit', onExit); resolve(); } };
      child.stdout.on('data', onData);
      child.stderr.on('data', onData);
      child.on('exit', onExit);
    });

    const status = await (await fetch(`http://127.0.0.1:${port}/update/status`)).json();
    a.equal(status.verified, true, 'the fake release verifies against the throwaway key');
    a.deepEqual({ versionCode: status.apk && status.apk.versionCode, available: status.apk && status.apk.available },
      { versionCode: 999, available: true }, '/update/status must say available once the APK is being served');
    a.equal(releaseCalls, 1, 'the refresh comes from the download itself, not from another poll');

    // publishApk runs in the same synchronous block as the "[apk] mirrored" line, so it has finished by now.
    a.ok(fs.lstatSync(path.join(files, 'agent.apk')).isFile(), 'PUBLISH_APK_TO is a regular file, not the planted link');
    a.ok(fs.readFileSync(path.join(files, 'agent.apk')).equals(apk), 'the verified APK is published to PUBLISH_APK_TO');
    a.equal(fs.readFileSync(outside, 'utf8'), 'NOT AN APK', 'the planted link was not written through');
    a.deepEqual(fs.readdirSync(files).sort(), ['agent.apk', 'agent.apk.tmp'],
      'no temp file is left behind, and the stale one that appeared before the publish is gone');
  });

// --- Shared harness for the process-level tests below: a free port, and server.js spawned with a clean env. ---
function freePort() {
  const http = require('http');
  return new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
}
/** Spawn server.js with `env` (merged over a minimal safe base) and resolve once `until` appears in its output.
 *  Returns { child, port, log() }. The caller's tt.after must kill it (use stopChild). */
async function spawnSupervisor(dir, env, until, extraArgs = []) {
  const path = require('path');
  const port = await freePort();
  const child = cp.spawn(process.execPath, [...extraArgs, path.join(__dirname, 'server.js')], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, GITHUB_REPO: '', GITHUB_TOKEN: '', SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1',
      APPLY_SUPPORTED: '0', AUTO_UPDATE: '0', UPDATE_CHANNEL: 'stable', POLL_INTERVAL_HOURS: '6', CURRENT_VERSION: '0.0.0',
      MANIFEST_PUBKEY: path.join(dir, 'none.pub'), APK_CACHE_DIR: path.join(dir, 'apk'), PUBLISH_APK_TO: '',
      AUTO_FILE: path.join(dir, 'auto.json'), RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token'),
      COMPOSE_PROJECT_DIR: path.join(dir, 'no-project'), ...env } });
  let log = '';
  const onData = (d) => { log += d; };
  child.stdout.on('data', onData);
  child.stderr.on('data', onData);
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`never saw ${until}; supervisor output:\n` + log)), 10000);
      const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
      const check = () => { if (until.test(log)) { clearTimeout(timer); child.off('exit', onExit); child.stdout.off('data', check); child.stderr.off('data', check); resolve(); } };
      child.stdout.on('data', check);
      child.stderr.on('data', check);
      child.on('exit', onExit);
      check();
    });
  } catch (e) {
    await stopChild(child); // never leave a supervisor running (it would keep the test runner alive)
    throw e;
  }
  return { child, port, log: () => log };
}
async function stopChild(child) {
  if (child && child.exitCode === null && child.signalCode === null) {
    const exited = new Promise((r) => child.once('exit', r));
    child.kill();
    await exited;
  }
}
async function waitFor(fn, what, ms = 10000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error('timed out waiting for ' + what);
    await new Promise((r) => setTimeout(r, 50));
  }
}

/** A --require preload that maps logical https origins (FAKE_ORIGINS: {"https://api.github.com": "http://127.0.0.1:N/api",
 *  ...}) onto local http servers. server.js keeps seeing the real https URLs (so its https-only and api.github.com
 *  rules apply unchanged); only the socket goes to the fake. */
function writePreload(dir) {
  const p = require('path').join(dir, 'fake-origins.js');
  require('fs').writeFileSync(p, `const f = globalThis.fetch;
const m = JSON.parse(process.env.FAKE_ORIGINS || '{}');
globalThis.fetch = (u, o) => {
  let s = String(u);
  for (const [k, v] of Object.entries(m)) if (s === k || s.startsWith(k + '/')) { s = v + s.slice(k.length); break; }
  return f(s, o);
};
`);
  return p;
}

/** A fake GitHub for owner/repo o/r with one signed release `version` (throwaway minisign key → `pub`), on three
 *  logical origins: the API (https://api.github.com), the web (https://github.com, browser_download_url) and the CDN
 *  the asset API redirects to (https://cdn.test). opts.privateRepo: browser URLs 404 and the asset API needs
 *  `Bearer <opts.token>` + octet-stream; opts.apk: a Buffer published as mdmesh-agent.apk; opts.failAfter: the releases
 *  list answers 500 once it has been served that many times. The CDN refuses any request carrying Authorization. */
async function fakeGitHub(dir, opts) {
  const fs = require('fs'), path = require('path'), http = require('http'), crypto = require('crypto');
  const kd = fs.mkdtempSync(path.join(dir, 'gh-'));
  const manifest = { version: opts.version, channel: 'stable', components: {} };
  if (opts.apk) manifest.components.apk = { file: 'mdmesh-agent.apk', versionCode: 999, sha256: crypto.createHash('sha256').update(opts.apk).digest('hex') };
  fs.writeFileSync(path.join(kd, 'manifest.json'), JSON.stringify(manifest));
  cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(kd, 'k.pub'), '-s', path.join(kd, 'k.key')], { stdio: 'ignore' });
  cp.execFileSync('minisign', ['-S', '-s', path.join(kd, 'k.key'), '-m', path.join(kd, 'manifest.json')], { stdio: 'ignore' });
  const bytes = { 'manifest.json': fs.readFileSync(path.join(kd, 'manifest.json')),
    'manifest.json.minisig': fs.readFileSync(path.join(kd, 'manifest.json.minisig')) };
  if (opts.apk) bytes['mdmesh-agent.apk'] = opts.apk;
  const hits = { releases: 0, api: [], web: 0, cdn: [] };
  const missing = new Set(opts.missing || []); // listed in the release, but 404 wherever it is downloaded
  const tag = 'v' + opts.version;
  const server = http.createServer((req, res) => {
    const u = req.url;
    const assetsPrefix = '/api/repos/o/r/releases/assets/';
    if (u.startsWith(assetsPrefix)) {
      const name = decodeURIComponent(u.slice(assetsPrefix.length));
      hits.api.push({ auth: req.headers.authorization || null, accept: req.headers.accept || null });
      if (opts.privateRepo && (req.headers.authorization !== 'Bearer ' + opts.token || req.headers.accept !== 'application/octet-stream')) {
        res.statusCode = 404; res.end(); return;
      }
      res.writeHead(302, { location: `https://cdn.test/signed/${encodeURIComponent(name)}?X-Amz-Signature=abc` });
      res.end('redirect body');
    } else if (u.startsWith('/api/repos/o/r/releases')) {
      hits.releases++;
      if (opts.failAfter != null && hits.releases > opts.failAfter) { res.statusCode = 500; res.end(); return; }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify([{ tag_name: tag, html_url: 'https://github.com/o/r/releases/tag/' + tag, assets: Object.keys(bytes)
        .map((name) => ({ name, url: 'https://api.github.com/repos/o/r/releases/assets/' + encodeURIComponent(name),
          browser_download_url: `https://github.com/o/r/releases/download/${tag}/${name}` })) }]));
    } else if (u.startsWith(`/web/o/r/releases/download/${tag}/`)) {
      hits.web++;
      const name = decodeURIComponent(u.split('/').pop());
      if (opts.privateRepo || !bytes[name] || missing.has(name)) { res.statusCode = 404; res.end(); return; }
      res.end(bytes[name]);
    } else if (u.startsWith('/cdn/signed/')) {
      hits.cdn.push({ auth: req.headers.authorization || null });
      if (req.headers.authorization) { res.statusCode = 400; res.end('auth header forwarded'); return; }
      const name = decodeURIComponent(u.slice('/cdn/signed/'.length).replace(/\?.*$/, ''));
      if (bytes[name] && !missing.has(name)) res.end(bytes[name]); else { res.statusCode = 404; res.end(); }
    } else { res.statusCode = 404; res.end(); }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    hits, pub: path.join(kd, 'k.pub'), preload: writePreload(dir),
    env: { GITHUB_REPO: 'o/r', MANIFEST_PUBKEY: path.join(kd, 'k.pub'),
      FAKE_ORIGINS: JSON.stringify({ 'https://api.github.com': base + '/api', 'https://github.com': base + '/web', 'https://cdn.test': base + '/cdn' }) },
    close: async () => { server.closeAllConnections(); await new Promise((r) => server.close(r)); },
  };
}

/** A fake Headwind server for authorizeApply: any request with a JSESSIONID cookie is an admin. */
async function fakeAuthz() {
  const http = require('http');
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify(/JSESSIONID=/.test(req.headers.cookie || '') ? { status: 'OK', data: [] } : { status: 'ERROR' }));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { base: `http://127.0.0.1:${server.address().port}`,
    close: async () => { server.closeAllConnections(); await new Promise((r) => server.close(r)); } };
}
const ADMIN = { 'X-MDMesh-Console': '1', Cookie: 'JSESSIONID=abc', 'content-type': 'application/json' };

t.test('private repo: with GITHUB_TOKEN the manifest, signature and APK come through the asset API URL, and the token '
  + 'never reaches the CDN it redirects to', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
  const fs = require('fs'), os = require('os'), path = require('path'), crypto = require('crypto');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-priv-'));
  let gh = null, sup = null;
  tt.after(async () => { await stopChild(sup && sup.child); if (gh) await gh.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  const TOKEN = 'ghp_TESTTOKEN_' + crypto.randomBytes(6).toString('hex');
  // Private-repo GitHub: browser_download_url is always 404; the asset API URL needs the token + octet-stream, then
  // 302s to https://cdn.test, which refuses any request carrying Authorization.
  gh = await fakeGitHub(dir, { version: '9.9.9', privateRepo: true, token: TOKEN, apk: crypto.randomBytes(2048) });
  sup = await spawnSupervisor(dir, { ...gh.env, GITHUB_TOKEN: TOKEN, CURRENT_VERSION: '9.9.0' }, /\[apk\] mirrored/, ['--require', gh.preload]);
  const status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
  a.equal(status.verified, true, 'the private release verifies:\n' + sup.log());
  a.equal(status.updateAvailable, true);
  a.equal(status.apk && status.apk.available, true, 'the APK was mirrored through the API URL');
  a.equal(gh.hits.api.length, 3, 'manifest, signature and APK all went through the asset API URL');
  a.ok(gh.hits.api.every((h) => h.auth === 'Bearer ' + TOKEN && h.accept === 'application/octet-stream'));
  a.equal(gh.hits.web, 0, 'browser_download_url is not used with a token');
  a.equal(gh.hits.cdn.length, 3);
  a.ok(gh.hits.cdn.every((h) => h.auth === null), 'no request to the CDN carried the token');
  a.ok(!sup.log().includes(TOKEN), 'the token never appears in the log');
});

t.test('an unverifiable release logs why (HTTP status / minisign), without secrets, and says so in /update/status',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-bad-'));
    let gh = null, sup = null;
    tt.after(async () => { await stopChild(sup && sup.child); if (gh) await gh.close(); fs.rmSync(dir, { recursive: true, force: true }); });
    // Signed with the fake's key, verified against another one.
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'b.pub'), '-s', path.join(dir, 'b.key')], { stdio: 'ignore' });
    gh = await fakeGitHub(dir, { version: '9.9.9' });
    sup = await spawnSupervisor(dir, { ...gh.env, MANIFEST_PUBKEY: path.join(dir, 'b.pub') }, /\[verify\]/, ['--require', gh.preload]);
    a.match(sup.log(), /\[verify\] .*minisign.*key id/i, 'a wrong-key signature is logged with minisign\'s own reason');
    let status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    a.equal(status.verified, false);
    // /update/status is public (Caddy proxies it): the kind of failure only, never upstream output.
    a.equal(status.error, 'manifest not verified: minisign signature check failed (details in the supervisor log)');
    await stopChild(sup.child);
    await gh.close();

    gh = await fakeGitHub(dir, { version: '9.9.9', missing: ['manifest.json.minisig'] });
    sup = await spawnSupervisor(dir, { ...gh.env }, /\[verify\]/, ['--require', gh.preload]);
    a.match(sup.log(), /\[verify\] .*manifest\.json\.minisig.*HTTP 404/, 'a missing asset is logged with its HTTP status');
    status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    a.equal(status.error, 'manifest not verified: manifest.json.minisig: HTTP 404 (details in the supervisor log)');
    await stopChild(sup.child);
    await gh.close();

    // A private repo without GITHUB_TOKEN: the hint is for the operator's log, not the public status.
    gh = await fakeGitHub(dir, { version: '9.9.9', privateRepo: true, token: 'x' });
    sup = await spawnSupervisor(dir, { ...gh.env }, /\[verify\]/, ['--require', gh.preload]);
    a.match(sup.log(), /\[verify\] .*manifest\.json: HTTP 404.*GITHUB_TOKEN/);
    status = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    a.equal(status.error, 'manifest not verified: manifest.json: HTTP 404 (details in the supervisor log)');
  });

// --- Stub docker + curl for driving apply.sh / rollback.sh without a daemon. The stub docker logs every call (one line
// per call: its args) to $STUB_LOG and, for `compose exec -T postgres psql|pg_dump`, reads/writes stdio like the real
// thing. Failure knobs (env): STUB_FAIL_RE (egrep on the args → exit 1), STUB_PSQL_FAIL=1 (the restore psql, the one
// run with --single-transaction, prints an ERROR and exits 3, as psql -v ON_ERROR_STOP=1 does). curl succeeds unless
// STUB_CURL_FAIL=1. ---
function makeStubs(dir) {
  const fs = require('fs'), path = require('path');
  const bin = path.join(dir, 'bin');
  fs.mkdirSync(bin, { recursive: true });
  fs.writeFileSync(path.join(bin, 'docker'), `#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
if [ -n "\${STUB_FAIL_RE:-}" ] && echo "$*" | grep -Eq "$STUB_FAIL_RE"; then echo "stub: forced failure: $*" >&2; exit 1; fi
case "$*" in
  *" pg_dump "*) echo "-- stub dump"; echo "SELECT 1;";;
  *" psql "*"--single-transaction"*)
    cat > "$STUB_LOG.stdin"
    if [ "\${STUB_PSQL_FAIL:-0}" = 1 ]; then echo 'psql:<stdin>:12: ERROR:  relation "x" does not exist' >&2; exit 3; fi;;
esac
exit 0
`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'curl'), '#!/usr/bin/env bash\n[ "${STUB_CURL_FAIL:-0}" = 1 ] && exit 7\nexit 0\n', { mode: 0o755 });
  return bin;
}
/** A deploy dir (project/.env) + backups dir + stubs under a fresh temp dir. HEALTH_TIMEOUT is 5, not 1: healthy()
 *  counts whole seconds, so with 1 a loaded machine can cross the deadline before the first (stubbed, instant) probe. */
function makeDeploy(envText) {
  const fs = require('fs'), os = require('os'), path = require('path');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-sh-'));
  const project = path.join(dir, 'project'), backups = path.join(dir, 'backups');
  fs.mkdirSync(project); fs.mkdirSync(backups);
  fs.writeFileSync(path.join(project, '.env'), envText);
  const bin = makeStubs(dir);
  const log = path.join(dir, 'docker.log');
  fs.writeFileSync(log, '');
  const env = { ...process.env, PATH: bin + path.delimiter + process.env.PATH, STUB_LOG: log, COMPOSE_PROJECT_DIR: project,
    BACKUP_DIR: backups, HEALTH_TIMEOUT: '5', HEALTH_URL: 'http://stub/health' };
  return { dir, project, backups, log, env,
    envFile: () => fs.readFileSync(path.join(project, '.env'), 'utf8'),
    calls: () => fs.readFileSync(log, 'utf8').split('\n').filter(Boolean) };
}
function runScript(name, args, env) {
  const r = cp.spawnSync('bash', [require('path').join(__dirname, name), ...args], { env, encoding: 'utf8', timeout: 30000 });
  return { code: r.status, out: r.stdout, err: r.stderr, all: r.stdout + r.stderr };
}

t.test('supervisor start: CURRENT_VERSION comes from the project .env (what apply/rollback write), not the stale container env',
  { timeout: 20000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
    let sup = null;
    tt.after(async () => { await stopChild(sup && sup.child); fs.rmSync(d.dir, { recursive: true, force: true }); });
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();

    // A restart after an apply: the container env still says 0.0.1, .env says 0.0.2.
    sup = await spawnSupervisor(d.dir, { APPLY_SUPPORTED: '1', CURRENT_VERSION: '0.0.1', COMPOSE_PROJECT_DIR: d.project }, /supervisor on/);
    a.equal((await status()).current, '0.0.2', 'the version apply.sh wrote to .env wins over the container env');
    await stopChild(sup.child);

    // No CURRENT_VERSION in .env (or no .env): the container env is the fallback.
    fs.writeFileSync(path.join(d.project, '.env'), 'SERVER_VERSION=0.0.2\n');
    sup = await spawnSupervisor(d.dir, { APPLY_SUPPORTED: '1', CURRENT_VERSION: '0.0.1', COMPOSE_PROJECT_DIR: d.project }, /supervisor on/);
    a.equal((await status()).current, '0.0.1');
    await stopChild(sup.child);

    // Native (APPLY_SUPPORTED=0): nothing writes a project .env there; the unit's env is the only source, even if a
    // stray .env exists at the project path.
    fs.writeFileSync(path.join(d.project, '.env'), 'CURRENT_VERSION=7.7.7\n');
    sup = await spawnSupervisor(d.dir, { APPLY_SUPPORTED: '0', CURRENT_VERSION: '0.4.0', COMPOSE_PROJECT_DIR: d.project }, /supervisor on/);
    a.equal((await status()).current, '0.4.0');
  });

t.test('rollback resets /update/status current to the version it restored', { timeout: 30000 }, async (tt) => {
  const fs = require('fs'), path = require('path');
  // State after a successful apply 0.0.1 → 0.0.2: .env bumped, backup snapshot of the old versions + dump.
  const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
  fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
  fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n');
  fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
  let sup = null;
  tt.after(async () => { await stopChild(sup && sup.child); fs.rmSync(d.dir, { recursive: true, force: true }); });
  sup = await spawnSupervisor(d.dir, { ...d.env, APPLY_SUPPORTED: '1', CURRENT_VERSION: '0.0.1', SERVER_BASE: 'http://127.0.0.1:9' },
    /supervisor on/);
  const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
  a.equal((await status()).current, '0.0.2');
  const r = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
    headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
  a.equal(r.status, 202);
  const s = await waitFor(async () => { const x = await status(); return isTerminal(x.apply && x.apply.phase) && x; }, 'rollback to finish', 20000);
  a.equal(s.apply.phase, 'rolled_back', sup.log());
  a.equal(s.current, '0.0.1', 'current follows the rollback');
  a.equal(s.apply.toVersion, '0.0.1', 'the rollback view names the version it restored');
  a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
});

t.test('rollback.sh restores CURRENT_VERSION from the snapshot (not SERVER_VERSION), and apply.sh snapshots it', () => {
  const fs = require('fs'), path = require('path');
  // A :latest quick-start (SERVER_VERSION=latest, CURRENT_VERSION=0.0.0) applied 0.0.2; rolling back must restore
  // CURRENT_VERSION=0.0.0, not "latest" (which is no version at all and would hide every future update).
  const d = makeDeploy('SERVER_VERSION=latest\nWEB_VERSION=latest\nCURRENT_VERSION=0.0.0\n');
  try {
    let r = runScript('apply.sh', ['0.0.2'], { ...d.env, STUB_CURL_FAIL: '0' });
    a.equal(r.code, 0, r.all);
    const stamp = fs.readFileSync(path.join(d.backups, 'latest'), 'utf8').trim();
    a.match(fs.readFileSync(path.join(d.backups, stamp + '.env'), 'utf8'), /^CURRENT_VERSION=0\.0\.0$/m, 'apply.sh snapshots CURRENT_VERSION');
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m);
    r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.0$/m);
    a.match(d.envFile(), /^SERVER_VERSION=latest$/m);
    // An older snapshot without CURRENT_VERSION still falls back to its SERVER_VERSION.
    fs.writeFileSync(path.join(d.backups, stamp + '.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\n');
    r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('with AUTO_UPDATE on, a rollback is not undone by auto-applying the release just rolled away from',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
    fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
    fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n');
    fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
    let gh = null, sup = null;
    tt.after(async () => { await stopChild(sup && sup.child); if (gh) await gh.close(); fs.rmSync(d.dir, { recursive: true, force: true }); });
    gh = await fakeGitHub(d.dir, { version: '0.0.2' }); // the signed 0.0.2 release is the latest on the channel
    sup = await spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', AUTO_UPDATE: '1', CURRENT_VERSION: '0.0.1',
      SERVER_BASE: 'http://127.0.0.1:9' }, /supervisor on/, ['--require', gh.preload]);
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    const before = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll');
    a.equal(before.verified, true, sup.log());
    a.equal(before.updateAvailable, false, 'running 0.0.2 already');

    const r = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
      headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
    a.equal(r.status, 202);
    // The rollback ends with a poll that sees 0.0.2 as an update again. setStatus stamps checkedAt and starts any
    // auto-apply synchronously, so once checkedAt has moved on, the decision has been made: it must stay offered only.
    const after = await waitFor(async () => { const x = await status(); return x.checkedAt > before.checkedAt && x; },
      'the post-rollback poll', 20000);
    a.equal(after.apply.trigger, 'rollback', 'no apply started after the rollback:\n' + sup.log());
    a.equal(after.apply.phase, 'rolled_back');
    a.equal(after.current, '0.0.1');
    a.equal(after.updateAvailable, true, '0.0.2 is still offered for a manual Update');
    a.equal(after.autoSkipped, '0.0.2');
    a.equal(JSON.parse(fs.readFileSync(path.join(d.dir, 'auto.json'), 'utf8')).skipVersion, '0.0.2', 'the skip survives a restart');
    a.ok(!d.calls().some((c) => c.includes('pg_dump')), 'apply.sh never ran');
  });

// --- Restore safety (apply.sh rollback path + rollback.sh). The DB restore must run with the server STOPPED (it held
// connections and raced the restore for locks), with psql -v ON_ERROR_STOP=1 in one transaction (a failed restore used
// to exit 0), and a failed restore must fail the script loudly, leaving the server stopped rather than running the
// old version on the un-restored database. ---
const idx = (calls, re) => calls.findIndex((c) => re.test(c));
const PSQL_RE = /compose exec -T postgres psql .*--single-transaction/; // the restore itself

function rollbackDeploy() {
  const fs = require('fs'), path = require('path');
  const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
  fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
  fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  // Like a real pg_dump: its preamble resets lock_timeout to 0 (which would undo a bound set before it).
  fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), '-- dump\nSET lock_timeout = 0;\nSELECT 1;\n');
  return d;
}

function assertSafeRestoreOrder(calls, all) {
  const stop = idx(calls, /^compose stop server$/), psql = idx(calls, PSQL_RE), up = calls.findLastIndex((c) => /^compose up -d --no-deps server caddy$/.test(c));
  a.ok(stop >= 0, 'the server is stopped before the restore:\n' + calls.join('\n') + '\n' + all);
  a.ok(psql > stop, 'psql runs after the server stopped:\n' + calls.join('\n'));
  // Ending the other sessions and the restore are ONE psql session and ONE transaction (no gap for a new lock to slip
  // in), with a bounded lock_timeout set first: -c SET, -c terminate, then -f - (the dump), in that order.
  a.equal(calls.filter((c) => /compose exec -T postgres psql /.test(c)).length, 1, 'a single psql call:\n' + calls.join('\n'));
  a.match(calls[psql], /-c SET lock_timeout = '60s' -c DO \$restore\$.*count\(pg_terminate_backend\(pid\)\).*datname = current_database\(\) AND pid <> pg_backend_pid\(\).*RAISE NOTICE 'ended % other session\(s\)'.*\$restore\$ -f -$/);
  // Result rows (the dump's ~50 setval()s) go nowhere; errors and notices (stderr) still reach restore.log.
  a.match(calls[psql], / -o \/dev\/null /);
  a.ok(up > psql, 'the old server starts only after the restore:\n' + calls.join('\n'));
  a.match(calls[psql], /-v ON_ERROR_STOP=1/);
  a.match(calls[psql], /--single-transaction/);
}

t.test('rollback.sh: stop server → restore (ON_ERROR_STOP, one transaction) → start → health', () => {
  const fs = require('fs');
  const d = rollbackDeploy();
  try {
    const r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    assertSafeRestoreOrder(d.calls(), r.all);
    a.equal(fs.readFileSync(d.log + '.stdin', 'utf8'), "-- dump\nSET lock_timeout = '60s';\nSELECT 1;\n",
      "the dump is fed to psql, with its own SET lock_timeout = 0 rewritten to the bound");
    a.match(r.out, /PHASE rolled_back/);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('rollback.sh: a failed restore exits non-zero, says what state the stack is in and how to recover, and leaves the '
  + 'server stopped', () => {
  const fs = require('fs'), path = require('path');
  const d = rollbackDeploy();
  try {
    const r = runScript('rollback.sh', [], { ...d.env, STUB_PSQL_FAIL: '1' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.out, /PHASE failed/);
    a.doesNotMatch(r.out, /PHASE rolled_back/);
    // The psql text goes to the log (a plain line) and restore.log; the ERR line, which reaches the public
    // /update/status, only points at the file.
    a.match(r.err, /^ERR database restore failed: see \/.*\/20260927-120000\.restore\.log$/m, 'a generic ERR');
    a.match(r.err, /^psql: .*relation "x" does not exist/m, 'the psql error is in the log');
    a.doesNotMatch(r.err.split('\n').filter((l) => l.startsWith('ERR ')).join('\n'), /relation/, 'no psql text on an ERR line');
    a.match(r.err, /^ERR .*server is stopped/mi, 'the state is spelled out');
    a.match(r.err, /^ERR .*Roll back again/m, 'how to recover is spelled out');
    const calls = d.calls();
    a.equal(calls.slice(idx(calls, PSQL_RE) + 1).filter((c) => /^compose up /.test(c)).length, 0,
      'the old server is not started on the un-restored database:\n' + calls.join('\n'));
    a.ok(fs.existsSync(path.join(d.backups, '20260927-120000.restore.log')), 'the full psql output is kept');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('rollback.sh: if the server cannot be stopped, nothing is restored and the script fails loudly', () => {
  const fs = require('fs');
  const d = rollbackDeploy();
  try {
    const r = runScript('rollback.sh', [], { ...d.env, STUB_FAIL_RE: '^compose stop server$' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.out, /PHASE failed/);
    a.match(r.err, /^ERR .*could not stop the server/m);
    a.equal(idx(d.calls(), PSQL_RE), -1, 'no restore against a running server');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('rollback.sh: restore ok but the old server does not start or stay healthy → non-zero, failed, and says so', () => {
  const fs = require('fs');
  let d = rollbackDeploy();
  try {
    let r = runScript('rollback.sh', [], { ...d.env, STUB_FAIL_RE: '^compose up ' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.out, /PHASE failed/);
    a.match(r.err, /^ERR .*could not start/m);
    fs.rmSync(d.dir, { recursive: true, force: true });
    d = rollbackDeploy();
    r = runScript('rollback.sh', [], { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.out, /PHASE failed/);
    a.match(r.err, /^ERR still unhealthy/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('apply.sh: a failed health check rolls back with the same safe restore; a failed restore fails the apply loudly', () => {
  const fs = require('fs');
  let d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    let r = runScript('apply.sh', ['0.0.2'], { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0' });
    a.equal(r.code, 1, r.all);
    // The first recreate is the new version's; the rollback's stop → restore → start follows it.
    const calls = d.calls(), firstUp = idx(calls, /^compose up /);
    assertSafeRestoreOrder(calls.slice(firstUp + 1), r.all);
    a.match(r.out, /PHASE rollback/);
    a.match(r.out, /PHASE failed/, 'still unhealthy after the rollback (curl stub always fails)');
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
    fs.rmSync(d.dir, { recursive: true, force: true });

    d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    r = runScript('apply.sh', ['0.0.2'], { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0', STUB_PSQL_FAIL: '1' });
    a.equal(r.code, 1, r.all);
    a.match(r.out, /PHASE failed/);
    a.doesNotMatch(r.out, /PHASE rolled_back/);
    a.match(r.err, /^ERR database restore failed: see \/.*\.restore\.log$/m);
    a.match(r.err, /^psql: .*relation "x" does not exist/m);
    a.match(r.err, /^ERR .*server is stopped/mi);
    const c2 = d.calls();
    a.equal(c2.slice(idx(c2, PSQL_RE) + 1).filter((c) => /^compose up /.test(c)).length, 0, 'server left stopped:\n' + c2.join('\n'));
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('apply.sh: a failed pull changed no container, so it resets .env only: no stop, no restore, no downtime', () => {
  const fs = require('fs');
  const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    const r = runScript('apply.sh', ['0.0.2'], { ...d.env, STUB_FAIL_RE: '^compose pull ' });
    a.equal(r.code, 1, r.all);
    a.match(r.out, /PHASE rolled_back/);
    // --quiet drops the per-layer progress (most of the log in the live rehearsal); errors still print.
    a.ok(d.calls().includes('compose pull --quiet server caddy'), d.calls().join('\n'));
    a.match(r.err, /stub: forced failure: compose pull --quiet server caddy/, 'the pull error output is kept');
    a.match(r.err, /^ERR image pull failed$/m);
    const calls = d.calls();
    a.equal(idx(calls, /^compose stop /), -1, calls.join('\n'));
    a.equal(idx(calls, PSQL_RE), -1, calls.join('\n'));
    a.match(d.envFile(), /^SERVER_VERSION=0\.0\.1$/m);
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

// --- B1: the auto-apply skip is persisted in auto.json {auto, skipVersion}, set by ANY failed apply and by a rollback,
// exact-match only (a newer release still auto-applies), and shown as autoSkipped in /update/status. ---
t.test('auto.json {auto:true, skipVersion} blocks exactly that release across a restart; a newer one still auto-applies',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    fs.writeFileSync(path.join(d.dir, 'auto.json'), JSON.stringify({ auto: true, skipVersion: '0.0.2' }));
    let gh = null, sup = null;
    tt.after(async () => { await stopChild(sup && sup.child); if (gh) await gh.close(); fs.rmSync(d.dir, { recursive: true, force: true }); });
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();

    gh = await fakeGitHub(d.dir, { version: '0.0.2' });
    sup = await spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', AUTO_UPDATE: '1', SERVER_BASE: 'http://127.0.0.1:9' },
      /supervisor on/, ['--require', gh.preload]);
    // setStatus (which is where an auto-apply starts, synchronously) stamps checkedAt: once it is set, the decision is made.
    const s = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll');
    a.equal(s.verified, true, sup.log());
    a.equal(s.updateAvailable, true);
    a.equal(s.apply, null, 'the skipped release was not auto-applied:\n' + sup.log());
    a.equal(s.autoSkipped, '0.0.2');
    a.equal(s.auto, true);
    a.deepEqual(d.calls(), [], 'apply.sh never ran');
    await stopChild(sup.child);
    await gh.close();

    gh = await fakeGitHub(d.dir, { version: '0.0.3' });
    sup = await spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', AUTO_UPDATE: '1', SERVER_BASE: 'http://127.0.0.1:9' },
      /supervisor on/, ['--require', gh.preload]);
    const s3 = await waitFor(async () => { const x = await status(); return x.apply && isTerminal(x.apply.phase) && x; }, 'the auto-apply of 0.0.3', 20000);
    a.equal(s3.apply.trigger, 'auto');
    a.equal(s3.apply.toVersion, '0.0.3');
    a.equal(s3.apply.phase, 'done', sup.log());
  });

t.test('a failed MANUAL apply also blocks auto-apply of that release, persisted in auto.json and shown in /update/status',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    let gh = null, sup = null, authz = null;
    tt.after(async () => {
      await stopChild(sup && sup.child); if (gh) await gh.close(); if (authz) await authz.close();
      fs.rmSync(d.dir, { recursive: true, force: true });
    });
    gh = await fakeGitHub(d.dir, { version: '0.0.2' });
    authz = await fakeAuthz();
    const env = { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', AUTO_UPDATE: '0', SERVER_BASE: authz.base, STUB_FAIL_RE: '^compose pull ' };
    sup = await spawnSupervisor(d.dir, env, /supervisor on/, ['--require', gh.preload]);
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    await waitFor(async () => (await status()).verified, 'the startup poll');

    const r = await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN });
    a.equal(r.status, 202, await r.text());
    const s = await waitFor(async () => { const x = await status(); return x.apply && isTerminal(x.apply.phase) && x; }, 'the apply to fail', 20000);
    a.equal(s.apply.phase, 'rolled_back', sup.log());
    a.equal(s.autoSkipped, '0.0.2');
    a.deepEqual(JSON.parse(fs.readFileSync(path.join(d.dir, 'auto.json'), 'utf8')), { auto: false, skipVersion: '0.0.2' });

    // Turning unattended on does not retry it.
    const t0 = (await status()).checkedAt;
    const on = await fetch(`http://127.0.0.1:${sup.port}/update/auto`, { method: 'POST', headers: ADMIN, body: JSON.stringify({ auto: true }) });
    a.equal(on.status, 200);
    a.equal((await status()).apply.trigger, 'manual', 'turning auto on did not start an apply:\n' + sup.log());
    a.deepEqual(JSON.parse(fs.readFileSync(path.join(d.dir, 'auto.json'), 'utf8')), { auto: true, skipVersion: '0.0.2' });
    a.ok(t0);

    // Nor does a restart (the skip is on disk, not in memory).
    await stopChild(sup.child);
    sup = await spawnSupervisor(d.dir, env, /supervisor on/, ['--require', gh.preload]);
    const s2 = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll after restart');
    a.equal(s2.auto, true);
    a.equal(s2.updateAvailable, true);
    a.equal(s2.apply, null, 'no auto-apply after the restart:\n' + sup.log());
    a.equal(s2.autoSkipped, '0.0.2');
  });

// --- M1: /update/status is current as soon as an apply/rollback ends, even if the follow-up poll fails; and a second
// Update of the version just applied is refused (it would overwrite /backups/latest with a dump of the NEW database). ---
t.test('after an apply, status shows the new current even when the next poll fails, and a repeat Update is refused',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    let gh = null, sup = null, authz = null;
    tt.after(async () => {
      await stopChild(sup && sup.child); if (gh) await gh.close(); if (authz) await authz.close();
      fs.rmSync(d.dir, { recursive: true, force: true });
    });
    gh = await fakeGitHub(d.dir, { version: '0.0.2', failAfter: 1 }); // the post-apply poll gets a 500
    authz = await fakeAuthz();
    sup = await spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', AUTO_UPDATE: '0', SERVER_BASE: authz.base },
      /supervisor on/, ['--require', gh.preload]);
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    const before = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll');
    a.equal(before.updateAvailable, true, sup.log());

    const r = await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN });
    a.equal(r.status, 202);
    const s = await waitFor(async () => { const x = await status(); return x.checkedAt > before.checkedAt && x; }, 'the post-apply poll', 20000);
    a.equal(s.apply.phase, 'done', sup.log());
    a.match(String(s.error), /github 500/, 'the follow-up poll failed');
    a.equal(s.current, '0.0.2', 'current is the applied version even though the poll failed');
    a.equal(s.updateAvailable, false, 'the applied release is no longer offered');

    const backups = fs.readdirSync(d.backups).sort();
    const again = await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN });
    a.equal(again.status, 400, 'a second Update of the running version is refused');
    a.deepEqual(await again.json(), { error: 'already running 0.0.2' }, 'and says why');
    a.deepEqual(fs.readdirSync(d.backups).sort(), backups, 'no second backup overwrote /backups/latest');
  });

// --- M9: .env switches to the backup's versions only after a good restore. Until then it keeps naming the version that
// is running (or was, if the server was stopped), which is the one the database belongs to: a later `docker compose up`
// must never pair the old images with the new version's database. ---
t.test('rollback.sh: when the stop or the restore fails, .env still names the running version, and success switches it', () => {
  const fs = require('fs');
  for (const knob of [{ STUB_FAIL_RE: '^compose stop server$' }, { STUB_PSQL_FAIL: '1' }]) {
    const d = rollbackDeploy();
    try {
      const r = runScript('rollback.sh', [], { ...d.env, ...knob });
      a.notEqual(r.code, 0, r.all);
      a.match(d.envFile(), /^SERVER_VERSION=0\.0\.2$/m, JSON.stringify(knob));
      a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m, JSON.stringify(knob));
      a.match(r.err, /^ERR .*\.env still names 0\.0\.2/m, 'the ERR says which version .env names');
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
  const d = rollbackDeploy();
  try {
    const r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    a.match(d.envFile(), /^SERVER_VERSION=0\.0\.1$/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('apply.sh: if the rollback cannot stop the new server, .env names the new version, which may still be running', () => {
  const fs = require('fs');
  for (const knob of [{ STUB_FAIL_RE: '^compose stop server$' }, { STUB_PSQL_FAIL: '1' }]) {
    const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    try {
      const r = runScript('apply.sh', ['0.0.2'], { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0', ...knob });
      a.equal(r.code, 1, r.all);
      a.match(r.out, /PHASE failed/);
      a.match(d.envFile(), /^SERVER_VERSION=0\.0\.2$/m, JSON.stringify(knob) + '\n' + r.all);
      a.match(d.envFile(), /^WEB_VERSION=0\.0\.2$/m);
      a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m);
      a.match(r.err, /^ERR .*\.env still names 0\.0\.2/m);
      // After a failed recreate the new server may or may not be up: the message must not claim it is running.
      if (knob.STUB_FAIL_RE) a.match(r.err, /^ERR could not stop the server.*which may still be running/m);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
});

// --- M2: the dump sets lock_timeout = 0, so one leftover session holding a lock would hang the restore forever. ---
t.test('rollback.sh: if the other database sessions cannot be ended, the restore (same session) fails loudly', () => {
  const fs = require('fs');
  const d = rollbackDeploy();
  try {
    const r = runScript('rollback.sh', [], { ...d.env, STUB_FAIL_RE: 'pg_terminate_backend' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.out, /PHASE failed/);
    a.match(r.err, /^ERR .*server is stopped/m);
    a.equal(d.calls().filter((c) => /^compose up /.test(c)).length, 0, 'the old server is not started');
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('a failed restore reaches /update/status as a pointer to restore.log; the psql text stays in the supervisor log',
  { timeout: 30000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = rollbackDeploy();
    fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
    let sup = null;
    tt.after(async () => { await stopChild(sup && sup.child); fs.rmSync(d.dir, { recursive: true, force: true }); });
    sup = await spawnSupervisor(d.dir, { ...d.env, APPLY_SUPPORTED: '1', STUB_PSQL_FAIL: '1', SERVER_BASE: 'http://127.0.0.1:9' }, /supervisor on/);
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    const r = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
      headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
    a.equal(r.status, 202);
    const s = await waitFor(async () => { const x = await status(); return x.apply && isTerminal(x.apply.phase) && x; }, 'the rollback', 20000);
    a.equal(s.apply.phase, 'failed');
    a.match(s.apply.error, /database restore failed: see \/.*\/20260927-120000\.restore\.log/);
    a.doesNotMatch(s.apply.error, /relation/, 'no psql output in the public status');
    a.match(sup.log(), /relation "x" does not exist/, 'the psql error is in the supervisor log');
    a.equal(s.current, '0.0.2', '.env (and current) still name the version the database belongs to');
  });

// --- Round 2 minors ---
t.test('auto.json is written atomically (tmp + rename): a link at its path is replaced, never written through',
  { timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-auto-'));
    let sup = null, authz = null;
    tt.after(async () => { await stopChild(sup && sup.child); if (authz) await authz.close(); fs.rmSync(dir, { recursive: true, force: true }); });
    const outside = path.join(dir, 'outside');
    fs.writeFileSync(outside, 'NOT AUTO STATE');
    fs.symlinkSync(outside, path.join(dir, 'auto.json'));
    authz = await fakeAuthz();
    sup = await spawnSupervisor(dir, { APPLY_SUPPORTED: '1', SERVER_BASE: authz.base }, /supervisor on/);
    const r = await fetch(`http://127.0.0.1:${sup.port}/update/auto`, { method: 'POST', headers: ADMIN, body: JSON.stringify({ auto: true }) });
    a.equal(r.status, 200);
    a.ok(fs.lstatSync(path.join(dir, 'auto.json')).isFile(), 'auto.json is a regular file now');
    a.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, 'auto.json'), 'utf8')), { auto: true, skipVersion: null });
    a.equal(fs.readFileSync(outside, 'utf8'), 'NOT AUTO STATE', 'the link target is untouched');
    a.ok(!fs.existsSync(path.join(dir, 'auto.json.tmp')), 'no temp file left behind');
  });

t.test('a successful manual apply of the skipped version clears the skip (auto.json and autoSkipped)',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    fs.writeFileSync(path.join(d.dir, 'auto.json'), JSON.stringify({ auto: false, skipVersion: '0.0.2' }));
    let gh = null, sup = null, authz = null;
    tt.after(async () => {
      await stopChild(sup && sup.child); if (gh) await gh.close(); if (authz) await authz.close();
      fs.rmSync(d.dir, { recursive: true, force: true });
    });
    gh = await fakeGitHub(d.dir, { version: '0.0.2' });
    authz = await fakeAuthz();
    sup = await spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', SERVER_BASE: authz.base }, /supervisor on/, ['--require', gh.preload]);
    const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    const before = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll');
    a.equal(before.autoSkipped, '0.0.2');
    const r = await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN });
    a.equal(r.status, 202, 'a manual Update still applies the skipped version');
    const s = await waitFor(async () => { const x = await status(); return x.checkedAt > before.checkedAt && x; }, 'the post-apply poll', 20000);
    a.equal(s.apply.phase, 'done', sup.log());
    a.equal(s.autoSkipped, null, 'the skip is cleared once that version is running');
    a.deepEqual(JSON.parse(fs.readFileSync(path.join(d.dir, 'auto.json'), 'utf8')), { auto: false, skipVersion: null });
  });

t.test('restore_db: the lock bound is a fixed 60s; no environment value reaches the SQL (not even with a newline)', () => {
  const fs = require('fs');
  // The knob was never wired through compose, and a line-based check let "5s\n<anything>" through: it is gone.
  for (const val of ['5s', "5s\n'; DROP TABLE x; --", '0']) {
    const d = rollbackDeploy();
    try {
      const r = runScript('rollback.sh', [], { ...d.env, RESTORE_LOCK_TIMEOUT: val });
      a.equal(r.code, 0, r.all);
      const call = d.calls().find((c) => /psql .*--single-transaction/.test(c));
      a.ok(call.includes("-c SET lock_timeout = '60s' -c"), JSON.stringify(val) + ': ' + call);
      a.ok(fs.readFileSync(d.log + '.stdin', 'utf8').includes("SET lock_timeout = '60s';"));
      a.ok(!call.includes('DROP TABLE'));
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
});

t.test('backups are private (umask 077): the dump (password hashes), the .env snapshot, the pointer and restore.log are 0600; '
  + 'the host .env keeps its mode', () => {
  const fs = require('fs'), path = require('path');
  const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    fs.chmodSync(path.join(d.project, '.env'), 0o644);
    // umask 022 in the caller (like the supervisor process): the scripts must tighten it themselves.
    const r = cp.spawnSync('bash', ['-c', 'umask 022; exec bash "$0" 0.0.2', path.join(__dirname, 'apply.sh')],
      { env: { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0' }, encoding: 'utf8' });
    a.equal(r.status, 1, r.stdout + r.stderr); // health fails → rollback with a restore → restore.log written too
    const stamp = fs.readFileSync(path.join(d.backups, 'latest'), 'utf8').trim();
    for (const f of [stamp + '.sql', stamp + '.env', 'latest', stamp + '.restore.log']) {
      a.equal(fs.statSync(path.join(d.backups, f)).mode & 0o777, 0o600, f);
    }
    a.equal(fs.statSync(path.join(d.project, '.env')).mode & 0o777, 0o644, 'the host .env (sed -i / >>) keeps its mode');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

// --- Round 3 (polish review) ---
// The lock_timeout rewrite must touch only the pg_dump preamble's `SET lock_timeout = 0;` — never a COPY data row or
// a function-body line that happens to read the same (the first version rewrote every whole-line match).
t.test('restore_db rewrites only the preamble SET lock_timeout = 0; data rows and function bodies reach psql byte-exact', () => {
  const fs = require('fs'), path = require('path');
  const pre = '--\n-- PostgreSQL database dump\n--\n\n\\restrict abc123\n\n-- Dumped from database version 14.24\n\n'
    + 'SET statement_timeout = 0;\n';
  const body = "SET idle_in_transaction_session_timeout = 0;\nSELECT pg_catalog.set_config('search_path', '', false);\n"
    + 'SET client_min_messages = warning;\n\nCREATE TABLE public.t (x text);\n'
    + 'CREATE FUNCTION public.f() RETURNS integer LANGUAGE plpgsql AS $$\nBEGIN\nSET lock_timeout = 0;\nRETURN 1;\nEND $$;\n'
    + 'COPY public.t (x) FROM stdin;\nSET lock_timeout = 0;\nkeep me\n\\.\n\nSET lock_timeout = 0;\n';
  const cases = [
    // [dump, expected psql stdin]
    [pre + 'SET lock_timeout = 0;\n' + body, pre + "SET lock_timeout = '60s';\n" + body],
    // No preamble line at all: nothing may be rewritten (the first whole-line match is then a data row).
    [pre + body, pre + body],
  ];
  for (const [dump, want] of cases) {
    const d = rollbackDeploy();
    try {
      fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), dump);
      const r = runScript('rollback.sh', [], d.env);
      a.equal(r.code, 0, r.all);
      a.equal(fs.readFileSync(d.log + '.stdin', 'utf8'), want);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
});

t.test('the skip is cleared at start-up when that version is already running (nothing left to protect)', { timeout: 20000 }, async (tt) => {
  const fs = require('fs'), path = require('path');
  const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n'); // reached 0.0.2 outside the supervisor
  fs.writeFileSync(path.join(d.dir, 'auto.json'), JSON.stringify({ auto: true, skipVersion: '0.0.2' }));
  let sup = null;
  tt.after(async () => { await stopChild(sup && sup.child); fs.rmSync(d.dir, { recursive: true, force: true }); });
  sup = await spawnSupervisor(d.dir, { ...d.env, APPLY_SUPPORTED: '1', SERVER_BASE: 'http://127.0.0.1:9' }, /supervisor on/);
  const s = await waitFor(async () => { const x = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json(); return x.checkedAt && x; }, 'status');
  a.equal(s.autoSkipped, null);
  a.deepEqual(JSON.parse(fs.readFileSync(path.join(d.dir, 'auto.json'), 'utf8')), { auto: true, skipVersion: null });
});

t.test('after a failed apply whose restore also failed, the skip is KEPT while that failure is live; the refusal advises '
  + 'Roll back only if unhealthy; a restart then clears it', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async (tt) => {
  const fs = require('fs'), path = require('path');
  const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  let gh = null, sup = null, authz = null;
  tt.after(async () => {
    await stopChild(sup && sup.child); if (gh) await gh.close(); if (authz) await authz.close();
    fs.rmSync(d.dir, { recursive: true, force: true });
  });
  gh = await fakeGitHub(d.dir, { version: '0.0.2' });
  authz = await fakeAuthz();
  // Health fails → rollback → the restore fails: .env (and current) stay on 0.0.2, the server is left stopped.
  const env = { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', SERVER_BASE: authz.base, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0', STUB_PSQL_FAIL: '1' };
  sup = await spawnSupervisor(d.dir, env, /supervisor on/, ['--require', gh.preload]);
  const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
  const before = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll');
  a.equal((await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN })).status, 202);
  const s = await waitFor(async () => { const x = await status(); return x.checkedAt > before.checkedAt && x; }, 'the post-apply poll', 20000);
  a.equal(s.apply.phase, 'failed', sup.log());
  a.equal(s.current, '0.0.2');
  a.equal(s.autoSkipped, '0.0.2', 'kept: a by-hand rollback plus a restart must not let auto re-apply 0.0.2');
  const again = await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN });
  a.deepEqual(await again.json(),
    { error: 'already running 0.0.2, whose update failed: if it is not healthy, use Roll back (/recovery) to return to the previous version' });

  await stopChild(sup.child);
  sup = await spawnSupervisor(d.dir, env, /supervisor on/, ['--require', gh.preload]);
  const s2 = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll after restart');
  a.equal(s2.autoSkipped, null, 'after a restart with 0.0.2 running and no live failure, the skip is cleared');
});
