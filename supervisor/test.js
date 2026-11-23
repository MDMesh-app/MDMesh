const t = require('node:test');
const a = require('node:assert');
const { semverGt, isReleaseVersion, agentOffer, pickRelease, shapeStatus, updateBlocked, blockedLogLine, missedSecurity, imageTags, nextPhase, isTerminal, apkAsset, sha256Matches, recoveryPage, isPublishTemp,
  assetRequest, fetchAsset, envValue, applyLine, applyRefusal, imagePins, deploymentAdminVerdict,
  legacyDeploymentAdmin } = require('./lib');

// The digest-pinned refs server.js passes apply.sh after the signed manifest verified (components.images).
const PIN = { server: 'ghcr.io/mdmesh-app/mdmesh-server@sha256:' + 'a'.repeat(64),
  web: 'ghcr.io/mdmesh-app/mdmesh-web@sha256:' + 'b'.repeat(64),
  supervisor: 'ghcr.io/mdmesh-app/mdmesh-supervisor@sha256:' + 'c'.repeat(64) };
const applyArgs = (v, pins = PIN) => [v, pins.server, pins.web, pins.supervisor];
// components.platforms of a release with Arm64 images: the tests pass on either kind of host (CI runs them on both).
const PLATFORMS = 'linux/amd64,linux/arm64';

t.test('semverGt', () => {
  a.equal(semverGt('1.2.4', '1.2.3'), true);
  a.equal(semverGt('1.2.3', '1.2.3'), false);
  a.equal(semverGt('v2.0.0', 'v1.9.9'), true);
  a.equal(semverGt('1.0.0', '2.0.0'), false);
  a.equal(semverGt('bad', '1.0.0'), false);
  // Pre-releases order before their release, and among themselves by identifier (semver 2.0 precedence).
  a.equal(semverGt('0.6.0', '0.6.0-rc.1'), true);
  a.equal(semverGt('0.6.0-rc.1', '0.6.0'), false);
  a.equal(semverGt('0.6.0-rc.2', '0.6.0-rc.1'), true);
  a.equal(semverGt('0.6.0-rc.10', '0.6.0-rc.9'), true, 'numeric identifiers compare as numbers');
  a.equal(semverGt('0.6.0-rc.1', '0.6.0-beta.2'), true, 'others as text');
  a.equal(semverGt('0.6.0-rc.1.1', '0.6.0-rc.1'), true, 'more identifiers win when the rest are equal');
  a.equal(semverGt('0.6.0-1', '0.6.0-rc'), false, 'numeric identifiers order before text');
  a.equal(semverGt('0.6.0-rc.1', '0.5.9'), true);
  a.equal(semverGt('0.6.0+build.7', '0.6.0'), false, 'build metadata is ignored');
  a.equal(semverGt('1.2.3abc', '1.0.0'), false, 'not a version');
});

t.test('isReleaseVersion — a version a release can be published as (not 0.0.0, a -dev build or junk)', () => {
  for (const v of ['0.6.0', '1.2.3', '0.6.0-rc.1']) a.equal(isReleaseVersion(v), true, v);
  for (const v of ['0.0.0', '0.6.0-dev', 'latest', '', null, 'v0.6.0', '0.6']) a.equal(isReleaseVersion(v), false, String(v));
});

t.test('agentOffer — the running release\'s agent; the latest\'s only when the running version is unknown', () => {
  const runningDesc = { version: '0.6.0', of: 'running' }, latestDesc = { version: '0.6.1', of: 'latest' };
  a.equal(agentOffer({ kind: 'agent', desc: runningDesc }, latestDesc), runningDesc, 'never the newer one');
  a.equal(agentOffer({ kind: 'unknown', why: 'no such release' }, latestDesc), latestDesc);
  a.equal(agentOffer({ kind: 'unknown', why: 'x' }, null), null);
  a.equal(agentOffer({ kind: 'failed', why: 'signature' }, latestDesc), null, 'a verification failure offers nothing');
  a.equal(agentOffer({ kind: 'transient' }, latestDesc), null, 'not resolved yet: nothing, never the latest');
  a.equal(agentOffer(null, latestDesc), null);
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
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: true }).blocked, null);
  // A newer release this install can't apply (updateBlocked): not offered as an update; the reason is shown instead.
  const blocked = { code: 'platform', reason: 'no images for this host' };
  const st = shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: true, blocked });
  a.equal(st.updateAvailable, false);
  a.equal(st.blocked, 'no images for this host');
  a.equal(st.blockedCode, 'platform');
  a.equal(st.latest, '1.1.0');
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: true }).blockedCode, null);
});

t.test('shapeStatus — a blocked release leaves its own security fixes missing too', () => {
  const manifest = { version: '0.6.1', securityReleases: ['0.5.1', '0.6.0', '0.6.1'] };
  const blocked = { code: 'owner', reason: 'r' };
  a.deepEqual(shapeStatus({ current: '0.5.1', manifest, verified: true }).missedSecurity, ['0.6.0'], 'offered: its notes say so');
  a.deepEqual(shapeStatus({ current: '0.5.1', manifest, verified: true, blocked }).missedSecurity, ['0.6.0', '0.6.1']);
  a.deepEqual(shapeStatus({ current: '0.6.0', manifest: { version: '0.6.1', securityReleases: ['0.6.0'] }, verified: true, blocked })
    .missedSecurity, [], 'no security release newer than the running one');
});

t.test('blockedLogLine — once per reason', () => {
  const b = { code: 'owner', reason: 'r1' };
  let x = blockedLogLine(null, b);
  a.equal(x.line, '[update] not offered: r1');
  x = blockedLogLine(x.lastReason, b);
  a.equal(x.line, null, 'the next poll finds the same: nothing logged');
  x = blockedLogLine(x.lastReason, { code: 'platform', reason: 'r2' });
  a.equal(x.line, '[update] not offered: r2', 'another reason');
  x = blockedLogLine(x.lastReason, null);
  a.deepEqual(x, { line: null, lastReason: null }, 'installable again');
  a.equal(blockedLogLine(x.lastReason, { code: 'platform', reason: 'r2' }).line, '[update] not offered: r2', 'blocked again: logged again');
});

t.test('updateBlocked — why a newer verified release can\'t be applied by this supervisor, or null', () => {
  const m = (v, platforms = PLATFORMS) => ({ version: v, components: { images: PIN, platforms } });
  const args = { current: '0.6.0', verified: true, imageOwner: 'mdmesh-app', platform: 'linux/arm64', applySupported: true };
  a.equal(updateBlocked({ ...args, manifest: m('0.6.1') }), null);
  a.deepEqual(updateBlocked({ ...args, manifest: m('0.6.1', 'linux/amd64') }), { code: 'platform',
    reason: 'the signed release 0.6.1 has no images for this host (linux/arm64), only for linux/amd64, so it cannot be applied' });
  const owner = updateBlocked({ ...args, manifest: m('0.6.1'), imageOwner: 'forkco' });
  a.equal(owner.code, 'owner');
  a.match(owner.reason, /this install pulls ghcr\.io\/forkco/);
  const tags = updateBlocked({ ...args, manifest: { version: '0.6.1' } });
  a.equal(tags.code, 'digests');
  a.match(tags.reason, /names its images by tag only/);
  a.equal(updateBlocked({ ...args, manifest: { version: '0.6.1', components: { images: { ...PIN, web: 'x' } } } }).code, 'digests');
  // Only a newer, verified release on an install that applies updates itself can be blocked.
  a.equal(updateBlocked({ ...args, manifest: m('0.6.0', 'linux/amd64') }), null, 'the running version');
  a.equal(updateBlocked({ ...args, manifest: m('0.5.1', 'linux/amd64') }), null, 'an older one');
  a.equal(updateBlocked({ ...args, manifest: m('0.6.1', 'linux/amd64'), verified: false }), null, 'unverified');
  a.equal(updateBlocked({ ...args, manifest: null }), null);
  a.equal(updateBlocked({ ...args, manifest: m('0.6.1', 'linux/amd64'), applySupported: false }), null,
    'a native install builds from source: the images are not its concern');
});

t.test('missedSecurity — the security releases an update skips over', () => {
  const m = (version, securityReleases) => ({ version, securityReleases });
  // Boundaries: the running version and the offered one are both left out; only what lies strictly between counts.
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.12', '0.1.13', '0.1.15']), true, '0.1.12'), ['0.1.13']);
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.11']), true, '0.1.12'), []);           // older than current: has it
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.16']), true, '0.1.12'), []);           // newer than the offer
  // Several, oldest first, whatever the manifest's order; duplicates once.
  a.deepEqual(missedSecurity(m('0.5.2', ['0.5.1', '0.4.2', '0.3.8', '0.4.2']), true, '0.4.0'), ['0.4.2', '0.5.1']);
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.12', '0.1.13', '0.1.14']), true, '0.1.11'), ['0.1.12', '0.1.13', '0.1.14']);
  // None missed: no security release in between, or already on the latest.
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.10']), true, '0.1.14'), []);
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.13']), true, '0.1.15'), []);
  a.deepEqual(missedSecurity(m('0.1.15', []), true, '0.1.12'), []);
  // The offered release is itself a security release: its notes say so; nothing missed.
  a.deepEqual(missedSecurity(m('0.1.13', ['0.1.13']), true, '0.1.12'), []);
  // A manifest from before the field (0.4.x release tooling), or with something else in it.
  a.deepEqual(missedSecurity({ version: '0.5.2' }, true, '0.4.0'), []);
  a.deepEqual(missedSecurity(m('0.5.2', '0.5.1'), true, '0.4.0'), []);
  a.deepEqual(missedSecurity(m('0.5.2', [1, null, '0.5.1-beta.1', 'v0.5.1', '0.5.1']), true, '0.4.0'), ['0.5.1']);
  // Only a verified manifest is trusted.
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.13']), false, '0.1.12'), []);
  a.deepEqual(missedSecurity(null, true, '0.1.12'), []);
  // A running version that isn't X.Y.Z can't be compared.
  a.deepEqual(missedSecurity(m('0.1.15', ['0.1.13']), true, 'dev'), []);
});

t.test('shapeStatus — missedSecurity', () => {
  const manifest = { version: '0.1.15', securityReleases: ['0.1.13', '0.1.15'] };
  a.deepEqual(shapeStatus({ current: '0.1.12', manifest, verified: true }).missedSecurity, ['0.1.13']);
  a.deepEqual(shapeStatus({ current: '0.1.12', manifest, verified: false }).missedSecurity, []);
  a.deepEqual(shapeStatus({ current: '0.1.12', manifest: { version: '0.1.15' }, verified: true }).missedSecurity, []);
  a.deepEqual(shapeStatus({ current: '0.1.12', manifest: null, verified: false }).missedSecurity, []);
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
// the bytes (seen in a live update against a private repo).
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

t.test('imagePins — the three digest-pinned refs, under the owner this install pulls from, or why not', () => {
  const m = (images, version = '0.5.0') => ({ version,
    components: { serverImage: 'ghcr.io/mdmesh-app/mdmesh-server:' + version, images, platforms: PLATFORMS } });
  a.deepEqual(imagePins(m(PIN), 'mdmesh-app'), PIN);
  a.deepEqual(imagePins(m(PIN), null), PIN, 'no IMAGE_OWNER in .env: compose\'s default owner');
  // A release from tooling before digest pinning (tags only): refused, saying so.
  a.match(imagePins(m(undefined), 'mdmesh-app').error, /^the signed release 0\.5\.0 names its images by tag only, without digests/);
  a.match(imagePins({ version: '0.5.0' }, 'mdmesh-app').error, /by tag only/);
  a.match(imagePins(m([PIN.server]), 'mdmesh-app').error, /by tag only/);
  // Each ref must be repo@sha256:<64 lowercase hex>.
  for (const bad of ['ghcr.io/mdmesh-app/mdmesh-web:0.5.0', 'ghcr.io/mdmesh-app/mdmesh-web@sha256:' + 'B'.repeat(64),
    'ghcr.io/mdmesh-app/mdmesh-web@sha256:' + 'b'.repeat(63), 'mdmesh-web@sha256:' + 'b'.repeat(64), 7, null]) {
    a.equal(imagePins(m({ ...PIN, web: bad }), 'mdmesh-app').error,
      'the signed release 0.5.0 has no valid digest for its web image, so it cannot be applied', String(bad));
  }
  // A fork's install (its own IMAGE_OWNER) following upstream releases, or upstream images in a fork's manifest.
  a.equal(imagePins(m(PIN), 'forkco').error, 'the signed release 0.5.0 pins its server image at ghcr.io/mdmesh-app/mdmesh-server, '
    + 'but this install pulls ghcr.io/forkco/mdmesh-server (IMAGE_OWNER=forkco in .env). Set GITHUB_REPO to the repository '
    + 'that publishes forkco\'s images, or IMAGE_OWNER to the owner this release names, then run docker compose up -d');
  a.match(imagePins(m({ ...PIN, supervisor: 'ghcr.io/other/mdmesh-supervisor@sha256:' + 'c'.repeat(64) }), 'mdmesh-app').error,
    /pins its supervisor image at ghcr\.io\/other\/mdmesh-supervisor/);
  a.match(imagePins(m({ ...PIN, web: 'ghcr.io/mdmesh-app/mdmesh-server@sha256:' + 'b'.repeat(64) }), 'mdmesh-app').error,
    /pins its web image at ghcr\.io\/mdmesh-app\/mdmesh-server/, 'each image must be its own repository');
});

t.test('imagePins — the release has images for this host\'s platform (components.platforms)', () => {
  const m = (platforms) => ({ version: '0.6.0', components: { images: PIN, ...(platforms === undefined ? {} : { platforms }) } });
  a.deepEqual(imagePins(m(PLATFORMS), 'mdmesh-app', 'linux/amd64'), PIN);
  a.deepEqual(imagePins(m(PLATFORMS), 'mdmesh-app', 'linux/arm64'), PIN);
  // A manifest from before Arm64 images has no platforms: linux/amd64 only.
  a.deepEqual(imagePins(m(undefined), 'mdmesh-app', 'linux/amd64'), PIN);
  a.equal(imagePins(m(undefined), 'mdmesh-app', 'linux/arm64').error,
    'the signed release 0.6.0 has no images for this host (linux/arm64), only for linux/amd64, so it cannot be applied');
  a.match(imagePins(m('linux/amd64'), 'mdmesh-app', 'linux/arm64').error, /no images for this host \(linux\/arm64\)/);
  a.match(imagePins(m(PLATFORMS), 'mdmesh-app', 'linux/arm').error, /no images for this host \(linux\/arm\)/, 'a whole name, not a prefix');
  for (const bad of [['linux/amd64'], 7, null, '']) {
    a.match(imagePins(m(bad), 'mdmesh-app', 'linux/amd64').error, /no images for this host/, String(bad));
  }
  // Without a platform argument: the one this supervisor runs on (docker pulled it from the same kind of index).
  a.deepEqual(imagePins(m(PLATFORMS), 'mdmesh-app'), PIN);
});

t.test('applyRefusal — the Update guard says what is actually true', () => {
  const m = (v) => ({ version: v, components: { images: PIN, platforms: PLATFORMS } });
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
  // A newer release must pin its images by digest under this install's owner (imagePins); the version checks come first.
  a.match(applyRefusal({ manifest: { version: '0.0.5' }, current: '0.0.4' }), /names its images by tag only/);
  a.match(applyRefusal({ manifest: m('0.0.5'), current: '0.0.4', imageOwner: 'forkco' }), /this install pulls ghcr\.io\/forkco/);
  a.equal(applyRefusal({ manifest: m('0.0.5'), current: '0.0.4', imageOwner: 'mdmesh-app' }), null);
  a.equal(applyRefusal({ manifest: { version: '0.0.4' }, current: '0.0.4' }), 'already running 0.0.4');
  // ... and have images for this host's platform.
  const amd64Only = { version: '0.0.5', components: { images: PIN, platforms: 'linux/amd64' } };
  a.match(applyRefusal({ manifest: amd64Only, current: '0.0.4', platform: 'linux/arm64' }), /no images for this host \(linux\/arm64\)/);
  a.equal(applyRefusal({ manifest: amd64Only, current: '0.0.4', platform: 'linux/amd64' }), null);
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
  a.ok(html.includes('./setup.sh --update<') && html.includes('sudo ./setup.sh --update --native'));
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
async function spawnSupervisor(dir, env, until, extraArgs = [], attempt = 1) {
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
    // freePort() can't hold the port until the child binds it: another suite may take it first. Try another one.
    if (attempt < 3 && /EADDRINUSE/.test(log)) return spawnSupervisor(dir, env, until, extraArgs, attempt + 1);
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
 *  list answers 500 once it has been served that many times. opts.older: { version, apk, versionCode, unlisted,
 *  otherKey } a second, older signed release listed after it (the running one, say), or only found by its tag
 *  (unlisted), or signed with another key (otherKey). Every release is also served by tag (/releases/tags/<tag>).
 *  `control` changes answers while the test runs (below). The CDN refuses any request carrying Authorization. */
async function fakeGitHub(dir, opts) {
  const fs = require('fs'), path = require('path'), http = require('http'), crypto = require('crypto');
  const kd = fs.mkdtempSync(path.join(dir, 'gh-'));
  cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(kd, 'k.pub'), '-s', path.join(kd, 'k.key')], { stdio: 'ignore' });
  // One signed release: its tag and its assets' bytes. Pinned images under the default owner unless o.images says
  // otherwise (null: a manifest without digests).
  const release = (o, versionCode) => {
    const manifest = { version: o.version, channel: 'stable', components: { platforms: o.platforms || PLATFORMS } };
    const images = o.images === undefined ? PIN : o.images;
    if (images) manifest.components.images = images;
    if (o.apk) manifest.components.apk = { file: 'mdmesh-agent.apk', versionCode, sha256: crypto.createHash('sha256').update(o.apk).digest('hex') };
    const f = path.join(kd, `manifest-${o.version}.json`);
    fs.writeFileSync(f, JSON.stringify(manifest));
    cp.execFileSync('minisign', ['-S', '-s', path.join(kd, 'k.key'), '-m', f], { stdio: 'ignore' });
    const bytes = { 'manifest.json': fs.readFileSync(f), 'manifest.json.minisig': fs.readFileSync(f + '.minisig') };
    if (o.apk) bytes['mdmesh-agent.apk'] = o.apk;
    // Also signed by another key (a fork that rotated its key), served while control.otherKey holds its tag.
    return { tag: 'v' + o.version, bytes, otherSig: o.otherKey === undefined ? null : otherKeySig(f) };
  };
  const otherKeySig = (f) => {
    const k = path.join(kd, 'other');
    cp.execFileSync('minisign', ['-G', '-W', '-p', k + '.pub', '-s', k + '.key'], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-S', '-s', k + '.key', '-m', f, '-x', f + '.other'], { stdio: 'ignore' });
    return fs.readFileSync(f + '.other');
  };
  const rels = [release(opts, 999), ...(opts.older ? [release(opts.older, opts.older.versionCode || 998)] : [])];
  const tag = rels[0].tag;
  const listed = rels.filter((x, i) => i === 0 || !opts.older.unlisted);
  // control: down (every request answers 500), tagStatus (the by-tag lookup answers it), tagDelayMs (and only after
  // that long), fail (a Set of <tag>/<name> assets whose downloads answer 500).
  // hide: a Set of tags that aren't published yet (not listed, 404 by tag).
  // otherKey: a Set of tags whose manifest is served with the other key's signature (opts.older.otherKey: from the start).
  // listDelayMs: the release list answers only after that long; apkDelayMs: so do agent APK downloads.
  const control = { down: false, tagStatus: null, tagDelayMs: 0, listDelayMs: 0, apkDelayMs: 0, fail: new Set(), hide: new Set(),
    otherKey: new Set(opts.older && opts.older.otherKey ? ['v' + opts.older.version] : []) };
  const relJson = (r) => ({ tag_name: r.tag, html_url: 'https://github.com/o/r/releases/tag/' + r.tag,
    assets: Object.keys(r.bytes).map((name) => ({ name,
      url: 'https://api.github.com/repos/o/r/releases/assets/' + encodeURIComponent(`${r.tag}/${name}`),
      browser_download_url: `https://github.com/o/r/releases/download/${r.tag}/${name}` })) });
  // An asset's id in the API and CDN URLs: <tag>/<name>, so two releases' manifest.json stay apart.
  const byId = (id) => {
    const [t, ...n] = id.split('/'); const r = rels.find((x) => x.tag === t);
    return r && (n.join('/') === 'manifest.json.minisig' && control.otherKey.has(t) ? r.otherSig : r.bytes[n.join('/')]);
  };
  // tags: by-tag lookups; got: the <tag>/<name> of every asset download answered (CDN or web).
  const hits = { releases: 0, api: [], web: 0, cdn: [], tags: 0, got: [] };
  const missing = new Set(opts.missing || []); // listed in the release, but 404 wherever it is downloaded
  const server = http.createServer(async (req, res) => {
    const u = req.url;
    const assetsPrefix = '/api/repos/o/r/releases/assets/';
    if (control.down) { res.statusCode = 500; res.end(); return; }
    if (u.startsWith('/api/repos/o/r/releases/tags/')) {
      const t = decodeURIComponent(u.slice('/api/repos/o/r/releases/tags/'.length));
      hits.tags++;
      const found = rels.find((x) => x.tag === t && !control.hide.has(x.tag));
      if (control.tagDelayMs) await new Promise((res2) => setTimeout(res2, control.tagDelayMs));
      if (control.tagStatus) { res.statusCode = control.tagStatus; res.end(); return; }
      if (!found) { res.statusCode = 404; res.end(); return; }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(relJson(found)));
    } else if (u.startsWith(assetsPrefix)) {
      const id = decodeURIComponent(u.slice(assetsPrefix.length));
      hits.api.push({ auth: req.headers.authorization || null, accept: req.headers.accept || null });
      if (opts.privateRepo && (req.headers.authorization !== 'Bearer ' + opts.token || req.headers.accept !== 'application/octet-stream')) {
        res.statusCode = 404; res.end(); return;
      }
      res.writeHead(302, { location: `https://cdn.test/signed/${encodeURIComponent(id)}?X-Amz-Signature=abc` });
      res.end('redirect body');
    } else if (u.startsWith('/api/repos/o/r/releases')) {
      hits.releases++;
      if (control.listDelayMs) await new Promise((res2) => setTimeout(res2, control.listDelayMs));
      if (opts.failAfter != null && hits.releases > opts.failAfter) { res.statusCode = 500; res.end(); return; }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(listed.filter((x) => !control.hide.has(x.tag)).map(relJson)));
    } else if (u.startsWith('/web/o/r/releases/download/')) {
      hits.web++;
      const id = decodeURIComponent(u.slice('/web/o/r/releases/download/'.length));
      const name = id.split('/').pop();
      if (control.apkDelayMs && id.endsWith('.apk')) await new Promise((res2) => setTimeout(res2, control.apkDelayMs));
      if (control.fail.has(id)) { res.statusCode = 500; res.end(); return; }
      if (opts.privateRepo || !byId(id) || missing.has(name)) { res.statusCode = 404; res.end(); return; }
      hits.got.push(id);
      res.end(byId(id));
    } else if (u.startsWith('/cdn/signed/')) {
      hits.cdn.push({ auth: req.headers.authorization || null });
      if (req.headers.authorization) { res.statusCode = 400; res.end('auth header forwarded'); return; }
      const id = decodeURIComponent(u.slice('/cdn/signed/'.length).replace(/\?.*$/, ''));
      if (control.apkDelayMs && id.endsWith('.apk')) await new Promise((res2) => setTimeout(res2, control.apkDelayMs));
      if (control.fail.has(id)) { res.statusCode = 500; res.end(); return; }
      if (byId(id) && !missing.has(id.split('/').pop())) { hits.got.push(id); res.end(byId(id)); } else { res.statusCode = 404; res.end(); }
    } else { res.statusCode = 404; res.end(); }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    hits, tag, control, pub: path.join(kd, 'k.pub'), preload: writePreload(dir),
    env: { GITHUB_REPO: 'o/r', MANIFEST_PUBKEY: path.join(kd, 'k.pub'),
      FAKE_ORIGINS: JSON.stringify({ 'https://api.github.com': base + '/api', 'https://github.com': base + '/web', 'https://cdn.test': base + '/cdn' }) },
    close: async () => { server.closeAllConnections(); await new Promise((r) => server.close(r)); },
  };
}

/** A fake MDMesh server for authorizeApply, shaped like the real one. JSESSIONID=abc is the admin of a private-mode
 *  (single-customer) install: role 2 in customer 1, not a super-admin, and the deployment's operator.
 *  JSESSIONID=root is a super-admin. JSESSIONID=orgadmin is a customer's admin (role 2, customer 5) on a
 *  multi-customer install. All three hold the settings permission; deploymentAdmin in /rest/private/users/current
 *  tells them apart. With { legacy: true } it answers like a 0.3.7 server: no deploymentAdmin field, and
 *  JSESSIONID=noshape is a user whose answer lacks customerId. Anything else is 403 (AuthFilter: no session). */
async function fakeAuthz({ legacy = false } = {}) {
  const http = require('http');
  const users = {
    abc: { id: 1, login: 'admin', customerId: 1, superAdmin: false, deploymentAdmin: true,
      userRole: { id: 2, superAdmin: false } },
    root: { id: 2, login: 'superadmin', customerId: 2, superAdmin: true, deploymentAdmin: true,
      userRole: { id: 1, superAdmin: true } },
    orgadmin: { id: 10, login: 'tenant', customerId: 5, superAdmin: false, deploymentAdmin: false,
      userRole: { id: 2, superAdmin: false } },
    noshape: { id: 11, login: 'odd', deploymentAdmin: true, userRole: { id: 2, superAdmin: false } },
  };
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    const m = /(?:^|;\s*)JSESSIONID=([^;]+)/.exec(req.headers.cookie || '');
    const user = m && Object.prototype.hasOwnProperty.call(users, m[1]) ? { ...users[m[1]] } : null;
    if (!user) { res.statusCode = 403; res.end(); return; }
    if (legacy) delete user.deploymentAdmin;
    if (req.url === '/rest/private/users/all') { res.end(JSON.stringify({ status: 'OK', data: [] })); return; } // 0.3.7's old rule: never asked
    if (req.url === '/rest/private/users/current') { res.end(JSON.stringify({ status: 'OK', data: user })); return; }
    res.statusCode = 404; res.end();
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { base: `http://127.0.0.1:${server.address().port}`,
    close: async () => { server.closeAllConnections(); await new Promise((r) => server.close(r)); } };
}
const ADMIN = { 'X-MDMesh-Console': '1', Cookie: 'JSESSIONID=abc', 'content-type': 'application/json' };
const ORG_ADMIN = { ...ADMIN, Cookie: 'JSESSIONID=orgadmin' };
const UPDATE_ROUTES = [['/update/apply'], ['/update/rollback'], ['/update/auto', { auto: true }], ['/update/check']];

t.test('deploymentAdminVerdict: the server\'s users/current answer decides; no field means an older server', () => {
  a.equal(deploymentAdminVerdict({ status: 'OK', data: { superAdmin: false, deploymentAdmin: true } }), true);
  a.equal(deploymentAdminVerdict({ status: 'OK', data: { superAdmin: true, deploymentAdmin: false } }), false,
    'the server\'s verdict wins');
  a.equal(deploymentAdminVerdict({ status: 'OK', data: { deploymentAdmin: 'true' } }), false, 'a string is not true');
  a.equal(deploymentAdminVerdict({ status: 'OK', data: { superAdmin: true } }), null, 'a server before 0.3.8');
  a.equal(deploymentAdminVerdict({ status: 'OK', data: [] }), false, 'the users/all shape');
  a.equal(deploymentAdminVerdict({ status: 'OK', data: null }), false, 'no user in the session');
  a.equal(deploymentAdminVerdict({ status: 'ERROR', data: { deploymentAdmin: true } }), false);
  a.equal(deploymentAdminVerdict(null), false);
  a.equal(deploymentAdminVerdict('OK'), false);
});

t.test('legacyDeploymentAdmin: before 0.3.8, only a super-admin or the org admin of customer 1; missing fields refuse', () => {
  const ok = (data) => legacyDeploymentAdmin({ status: 'OK', data });
  a.equal(ok({ customerId: 2, userRole: { id: 1, superAdmin: true } }), true, 'a super-admin');
  a.equal(ok({ customerId: 1, userRole: { id: 2, superAdmin: false } }), true, 'the private-mode operator');
  a.equal(ok({ customerId: 5, userRole: { id: 2, superAdmin: false } }), false, 'another customer\'s org admin');
  a.equal(ok({ customerId: 1, userRole: { id: 3, superAdmin: false } }), false, 'a user role in customer 1');
  a.equal(ok({ customerId: 1, userRole: { id: 7, superAdmin: false } }), false, 'a custom role, even with settings');
  a.equal(ok({ userRole: { id: 2, superAdmin: false } }), false, 'no customerId');
  a.equal(ok({ customerId: '1', userRole: { id: 2 } }), false, 'a string customerId');
  a.equal(ok({ customerId: 1, userRole: { superAdmin: false } }), false, 'no role id');
  a.equal(ok({ customerId: 1, userRole: { id: '2' } }), false, 'a string role id');
  a.equal(ok({ customerId: 1, userRole: { id: 1, superAdmin: 'true' } }), false, 'a string is not true');
  a.equal(ok({ customerId: 1 }), false, 'no role');
  a.equal(ok({ customerId: 1, userRole: [] }), false, 'a role array');
  a.equal(ok(null), false, 'no user');
  a.equal(ok([]), false, 'the users/all shape');
  a.equal(legacyDeploymentAdmin({ status: 'ERROR', data: { customerId: 1, userRole: { id: 2 } } }), false);
  a.equal(legacyDeploymentAdmin(null), false);
});

t.test('update routes need an admin who may manage the deployment: a customer admin of a multi-customer install is refused everywhere',
  { timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-superadmin-'));
    let sup = null, authz = null;
    tt.after(async () => { await stopChild(sup && sup.child); if (authz) await authz.close(); fs.rmSync(dir, { recursive: true, force: true }); });
    authz = await fakeAuthz();
    sup = await spawnSupervisor(dir, { APPLY_SUPPORTED: '1', SERVER_BASE: authz.base }, /supervisor on/);
    const url = (p) => `http://127.0.0.1:${sup.port}${p}`;
    for (const [route, body] of UPDATE_ROUTES) {
      const r = await fetch(url(route), { method: 'POST', headers: ORG_ADMIN, body: body && JSON.stringify(body) });
      a.equal(r.status, 401, route + ' refuses a customer admin');
    }
    for (const cookie of ['', 'JSESSIONID=unknown', 'other=1']) {
      const r = await fetch(url('/update/auto'), { method: 'POST', headers: { ...ADMIN, Cookie: cookie }, body: '{"auto":true}' });
      a.equal(r.status, 401, 'cookie ' + JSON.stringify(cookie));
    }
    const s = await (await fetch(url('/update/status'))).json();
    a.equal(s.apply, null, 'nothing started');
    a.equal(s.auto, false, 'auto-update unchanged');
    a.ok(!fs.existsSync(path.join(dir, 'auto.json')), 'nothing saved');

    // The private-mode operator (role 2, no super-admin) and a super-admin pass the same gate.
    for (const headers of [ADMIN, { ...ADMIN, Cookie: 'JSESSIONID=root' }]) {
      const ok = await fetch(url('/update/auto'), { method: 'POST', headers, body: '{"auto":false}' });
      a.equal(ok.status, 200, headers.Cookie);
      a.equal((await fetch(url('/update/check'), { method: 'POST', headers })).status, 200, headers.Cookie);
    }
  });

t.test('against a server before 0.3.8 (no deploymentAdmin) the operator can still apply the release that brings it, '
  + 'and another customer\'s admin can\'t', { timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-legacyauthz-'));
    let sup = null, authz = null;
    tt.after(async () => { await stopChild(sup && sup.child); if (authz) await authz.close(); fs.rmSync(dir, { recursive: true, force: true }); });
    authz = await fakeAuthz({ legacy: true });
    sup = await spawnSupervisor(dir, { APPLY_SUPPORTED: '1', SERVER_BASE: authz.base }, /supervisor on/);
    const url = (p) => `http://127.0.0.1:${sup.port}${p}`;
    for (const headers of [ADMIN, { ...ADMIN, Cookie: 'JSESSIONID=root' }]) {
      a.equal((await fetch(url('/update/auto'), { method: 'POST', headers, body: '{"auto":false}' })).status, 200,
        headers.Cookie);
    }
    for (const cookie of ['JSESSIONID=orgadmin', 'JSESSIONID=noshape', 'JSESSIONID=nobody']) {
      for (const [route, body] of UPDATE_ROUTES) {
        const r = await fetch(url(route), { method: 'POST', headers: { ...ADMIN, Cookie: cookie },
          body: body && JSON.stringify(body) });
        a.equal(r.status, 401, route + ' refuses ' + cookie);
      }
    }
    const s = await (await fetch(url('/update/status'))).json();
    a.equal(s.apply, null, 'nothing started');
  });

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
// run with -c BEGIN, prints an ERROR and exits 3, as psql -v ON_ERROR_STOP=1 does). Like the real restore, the stub
// psql fails when its input does not end with the COMMIT apply.sh/rollback.sh add after a complete dump (they send a
// RAISE instead). curl succeeds unless STUB_CURL_FAIL=1. The edge (caddy) is up and stays up, unless STUB_EDGE says
// otherwise: "restarting" (its config doesn't load), "flapping" (it restarted between two looks), "dies-late" (up for
// the first two looks, then exited), or "gone" (no container); with STUB_EDGE_BAD_WEB, only while .env's WEB_VERSION starts with that value (an edge the new web image
// can't start, the old one can). ---
function makeStubs(dir) {
  const fs = require('fs'), path = require('path');
  const bin = path.join(dir, 'bin');
  fs.mkdirSync(bin, { recursive: true });
  fs.writeFileSync(path.join(bin, 'docker'), `#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
if [ -n "\${STUB_FAIL_RE:-}" ] && echo "$*" | grep -Eq "$STUB_FAIL_RE"; then echo "stub: forced failure: $*" >&2; exit 1; fi
case "$*" in
  "compose config --images "*)
    # What compose resolves the image lines of docker-compose.release.yml to, from the .env in the project (cwd).
    if [ -n "\${STUB_CONFIG_IMAGES:-}" ]; then printf '%s\n' "$STUB_CONFIG_IMAGES"; exit 0; fi
    # Like compose: the environment wins over .env.
    v() { if [ -n "\${!1:-}" ]; then printf '%s' "\${!1}"; else grep -E "^$1=" .env | head -1 | cut -d= -f2-; fi; }
    o=$(v IMAGE_OWNER); o=\${o:-mdmesh-app}
    echo "ghcr.io/$o/mdmesh-server:$(v SERVER_VERSION)"; echo "ghcr.io/$o/mdmesh-web:$(v WEB_VERSION)";;
  "image inspect "*)
    # RepoDigests of the image named by the last argument: that very ref, unless STUB_REPO_DIGESTS says otherwise.
    printf '%s\n' "\${STUB_REPO_DIGESTS:-\${@: -1}}";;
  "compose ps -a -q caddy")
    edge=\${STUB_EDGE:-up}
    if [ -n "\${STUB_EDGE_BAD_WEB:-}" ] && ! grep -q "^WEB_VERSION=\${STUB_EDGE_BAD_WEB}" .env; then edge=up; fi
    [ "$edge" = gone ] || echo edge1;;
  "inspect --format "*)
    edge=\${STUB_EDGE:-up}
    if [ -n "\${STUB_EDGE_BAD_WEB:-}" ] && ! grep -q "^WEB_VERSION=\${STUB_EDGE_BAD_WEB}" .env; then edge=up; fi
    case "$edge" in
      restarting) echo "restarting 2026-11-01T00:00:00Z";;
      flapping) echo "running 2026-11-01T00:00:0$(grep -c '^inspect ' "$STUB_LOG")Z";;
      dies-late) if [ "$(grep -c '^inspect ' "$STUB_LOG")" -le 2 ]; then echo "running 2026-11-01T00:00:00Z"; else echo "exited 2026-11-01T00:00:00Z"; fi;;
      *) echo "running 2026-11-01T00:00:00Z";;
    esac;;
  *" pg_dump "*) echo "-- stub dump"; echo "SELECT 1;"; echo "-- PostgreSQL database dump complete";;
  *" psql "*"-c BEGIN "*)
    cat > "$STUB_LOG.stdin"
    if [ "\${STUB_PSQL_FAIL:-0}" = 1 ]; then echo 'psql:<stdin>:12: ERROR:  relation "x" does not exist' >&2; exit 3; fi
    if [ "$(tail -n1 "$STUB_LOG.stdin")" != "COMMIT;" ]; then echo 'psql:<stdin>:3: ERROR:  the backup is incomplete' >&2; exit 3; fi;;
esac
exit 0
`, { mode: 0o755 });
  // STUB_CURL_OK_TIMES=N: the first N calls succeed, then it fails (a server that stops answering).
  fs.writeFileSync(path.join(bin, 'curl'), '#!/usr/bin/env bash\n[ "${STUB_CURL_FAIL:-0}" = 1 ] && exit 7\n'
    + 'if [ -n "${STUB_CURL_OK_TIMES:-}${STUB_CURL_FAIL_AT:-}" ]; then n=0; [ ! -f "$STUB_LOG.curl" ] || n=$(cat "$STUB_LOG.curl"); n=$((n + 1)); echo "$n" > "$STUB_LOG.curl"; '
    + '[ -z "${STUB_CURL_OK_TIMES:-}" ] || [ "$n" -le "$STUB_CURL_OK_TIMES" ] || exit 7; [ "$n" != "${STUB_CURL_FAIL_AT:-}" ] || exit 7; fi\nexit 0\n', { mode: 0o755 });
  return bin;
}
/** A deploy dir (project/.env) + backups dir + stubs under a fresh temp dir. HEALTH_TIMEOUT is 5, not 1: healthy()
 *  counts whole seconds, so with 1 a loaded machine can cross the deadline before the first (stubbed, instant) probe.
 *  HEALTH_TIMEOUT_MIN=0 lifts the scripts' 30 s floor, so a failing health check (HEALTH_TIMEOUT 0) ends at once. */
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
    BACKUP_DIR: backups, HEALTH_TIMEOUT: '5', HEALTH_TIMEOUT_MIN: '0', HEALTH_URL: 'http://stub/health', EDGE_SETTLE: '0' };
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
  fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
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
    let r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_CURL_FAIL: '0' });
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

t.test('behind your own proxy, apply.sh and rollback.sh refuse a release before 0.6.0 and change nothing (mode-guard.sh)', () => {
  const fs = require('fs'), path = require('path');
  const proxyEnv = 'SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\n'
    + 'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml\n';
  // apply.sh to 0.5.9: refused before the backup, .env untouched.
  let d = makeDeploy(proxyEnv);
  try {
    const r = runScript('apply.sh', applyArgs('0.5.9'), d.env);
    a.notEqual(r.code, 0, r.all);
    a.match(r.all, /^ERR this install runs behind your own proxy .*came in 0\.6\.0, so it can't go to 0\.5\.9/m);
    a.match(r.all, /^PHASE failed$/m);
    a.ok(!d.calls().some((c) => c.includes('pg_dump') || c.includes(' up ')), 'nothing was backed up or recreated');
    a.equal(d.envFile(), proxyEnv);
    // Its own mode allows 0.6.1, and the same .env without the overlay allows 0.5.9.
    a.equal(runScript('apply.sh', applyArgs('0.6.1'), d.env).code, 0);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  // The overlay named as a hand edit might (./, a path ending in it, with a comment after it, or quoted) counts the same.
  for (const cf of ['docker-compose.yml:./docker-compose.proxy.yml', '/srv/mdmesh/docker-compose.yml:/srv/mdmesh/docker-compose.proxy.yml',
    'docker-compose.yml:docker-compose.proxy.yml # behind our nginx', '"docker-compose.yml:docker-compose.proxy.yml" # ok',
    'docker-compose.yml:docker-compose.proxy.yml   ', 'docker-compose.yml:docker-compose.domain.yml\nCOMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml',
    'docker-compose.yml:docker-compose.domain.yml\nexport COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml',
    'docker-compose.yml:docker-compose.domain.yml\nCOMPOSE_FILE = docker-compose.yml:docker-compose.proxy.yml',
    'docker-compose.yml:docker-compose.domain.yml\nCOMPOSE_FILE: docker-compose.yml:docker-compose.proxy.yml',
    ' "docker-compose.yml:docker-compose.proxy.yml"']) {
    // (trailing blanks; a later line wins, as compose reads .env; and an "export " before it)
    d = makeDeploy(`SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\nCOMPOSE_FILE=${cf}\n`);
    try {
      const r = runScript('apply.sh', applyArgs('0.5.9'), d.env);
      a.notEqual(r.code, 0, cf + ': ' + r.all);
      a.match(r.all, /^ERR this install runs behind your own proxy/m);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
  // A last "COMPOSE_FILE=" with no newline after it empties it for compose: not the proxy mode any more.
  d = makeDeploy('SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\nCOMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml\nCOMPOSE_FILE=');
  try {
    const r = runScript('apply.sh', applyArgs('0.5.9'), d.env);
    a.doesNotMatch(r.all, /behind your own proxy/, r.all);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  // Moved out of the proxy mode with TRUSTED_PROXIES="host" left: an older edge would hand it to Caddy and not start.
  for (const tp of ['"host"', 'host,192.0.2.10']) {
    const envText = `SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\nCOMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml\nTRUSTED_PROXIES=${tp}\n`;
    d = makeDeploy(envText);
    try {
      const r = runScript('apply.sh', applyArgs('0.5.9'), d.env);
      a.notEqual(r.code, 0, tp + ': ' + r.all);
      a.match(r.all, /^ERR TRUSTED_PROXIES in \.env still says host .*Set TRUSTED_PROXIES= in \.env/m);
      a.ok(!d.calls().some((c) => c.includes('pg_dump') || c.includes(' up ')), 'nothing was backed up or recreated');
      a.equal(d.envFile(), envText);
      a.equal(runScript('apply.sh', applyArgs('0.6.1'), d.env).code, 0, 'a 0.6.x edge drops host: allowed');
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
  d = makeDeploy('SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\nTRUSTED_PROXIES="host"\n');
  try {
    fs.writeFileSync(path.join(d.backups, 'latest'), '20261101-120000\n');
    fs.writeFileSync(path.join(d.backups, '20261101-120000.env'), 'SERVER_VERSION=0.5.1@sha256:aa\nWEB_VERSION=0.5.1@sha256:bb\nCURRENT_VERSION=0.5.1\n');
    fs.writeFileSync(path.join(d.backups, '20261101-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
    const r = runScript('rollback.sh', [], d.env);
    a.notEqual(r.code, 0, r.all);
    a.match(r.all, /^ERR TRUSTED_PROXIES in \.env still says host/m);
    a.ok(!d.calls().some((c) => c.includes('psql') || c.includes(' up ')), 'nothing was restored');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  d = makeDeploy('SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\nCOMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml\n');
  try {
    a.equal(runScript('apply.sh', applyArgs('0.5.9'), d.env).code, 0, 'another mode is not held back');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }

  // rollback.sh to a 0.5.x snapshot: refused before the database is restored; the supervisor's own COMPOSE_FILE counts too.
  for (const [envText, extra] of [[proxyEnv, {}],
    ['SERVER_VERSION=0.6.0\nWEB_VERSION=0.6.0\nCURRENT_VERSION=0.6.0\n', { COMPOSE_FILE: 'docker-compose.yml:docker-compose.proxy.yml' }]]) {
    d = makeDeploy(envText);
    try {
      fs.writeFileSync(path.join(d.backups, 'latest'), '20261101-120000\n');
      fs.writeFileSync(path.join(d.backups, '20261101-120000.env'), 'SERVER_VERSION=0.5.1@sha256:aa\nWEB_VERSION=0.5.1@sha256:bb\nCURRENT_VERSION=0.5.1\n');
      fs.writeFileSync(path.join(d.backups, '20261101-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
      const r = runScript('rollback.sh', [], { ...d.env, ...extra });
      a.notEqual(r.code, 0, r.all);
      a.match(r.all, /^ERR this install runs behind your own proxy .*can't go to 0\.5\.1/m);
      a.ok(!d.calls().some((c) => c.includes('psql') || c.includes('stop server') || c.includes(' up ')), 'nothing was restored');
      a.equal(d.envFile(), envText);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
});

t.test('with AUTO_UPDATE on, a rollback is not undone by auto-applying the release just rolled away from',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async (tt) => {
    const fs = require('fs'), path = require('path');
    const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
    fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
    fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
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
const PSQL_RE = /compose exec -T postgres psql .*-c BEGIN /; // the restore itself

function rollbackDeploy() {
  const fs = require('fs'), path = require('path');
  const d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
  fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
  fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  // Like a real pg_dump: its preamble resets lock_timeout to 0 (which would undo a bound set before it).
  fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), '-- dump\nSET lock_timeout = 0;\nSELECT 1;\n-- PostgreSQL database dump complete\n');
  return d;
}

function assertSafeRestoreOrder(calls, all) {
  const stop = idx(calls, /^compose stop server$/), psql = idx(calls, PSQL_RE), up = calls.findLastIndex((c) => /^compose up -d --no-deps server caddy$/.test(c));
  a.ok(stop >= 0, 'the server is stopped before the restore:\n' + calls.join('\n') + '\n' + all);
  a.ok(psql > stop, 'psql runs after the server stopped:\n' + calls.join('\n'));
  // Ending the other sessions and the restore are ONE psql session and ONE transaction (no gap for a new lock to slip
  // in), with a bounded lock_timeout set first: -c BEGIN, -c SET, -c terminate, -c the schema reset, then -f - (the
  // dump, then the COMMIT), in that order. The reset drops public with everything in it (an object the update added can't block the restore) and
  // makes it again with the owner and grants it had; in the same transaction, so a failed restore undoes it too.
  a.equal(calls.filter((c) => /compose exec -T postgres psql /.test(c)).length, 1, 'a single psql call:\n' + calls.join('\n'));
  a.match(calls[psql], /-c BEGIN -c SET lock_timeout = '60s' -c DO \$restore\$.*count\(pg_terminate_backend\(pid\)\).*datname = current_database\(\) AND pid <> pg_backend_pid\(\).*RAISE NOTICE 'ended % other session\(s\)'.*\$restore\$ -c DO \$reset\$ .*SELECT nspowner::regrole, nspacl INTO o, a FROM pg_namespace WHERE nspname = 'public'; DROP SCHEMA public CASCADE; EXECUTE format\('CREATE SCHEMA public AUTHORIZATION %s', o\); .*aclexplode\(a\).*GRANT %s ON SCHEMA public TO %s.*\$reset\$ -f -$/);
  // Result rows (the dump's ~50 setval()s) go nowhere; errors and notices (stderr) still reach restore.log.
  a.match(calls[psql], / -o \/dev\/null /);
  a.ok(up > psql, 'the old server starts only after the restore:\n' + calls.join('\n'));
  a.match(calls[psql], /-v ON_ERROR_STOP=1/);
  // Not --single-transaction: it commits whatever input arrived, and after the reset a cut-off stream is an empty database.
  a.doesNotMatch(calls[psql], /--single-transaction/);
}

t.test('rollback.sh: stop server → restore (ON_ERROR_STOP, one transaction) → start → health', () => {
  const fs = require('fs');
  const d = rollbackDeploy();
  try {
    const r = runScript('rollback.sh', [], d.env);
    a.equal(r.code, 0, r.all);
    assertSafeRestoreOrder(d.calls(), r.all);
    a.equal(fs.readFileSync(d.log + '.stdin', 'utf8'), "-- dump\nSET lock_timeout = '60s';\nSELECT 1;\n-- PostgreSQL database dump complete\nCOMMIT;\n",
      "the dump is fed to psql, with its own SET lock_timeout = 0 rewritten to the bound, then the COMMIT");
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

t.test('rollback.sh: a backup cut short is never committed: the stream ends with a RAISE instead of COMMIT, the restore '
  + 'fails and the server stays stopped', () => {
  const fs = require('fs'), path = require('path');
  for (const dump of ['-- dump\nSET lock_timeout = 0;\nSELECT 1;\n', '']) {
    const d = rollbackDeploy();
    try {
      fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), dump || '-- dump\n');
      const r = runScript('rollback.sh', [], d.env);
      a.notEqual(r.code, 0, r.all);
      a.match(r.out, /PHASE failed/);
      const stdin = fs.readFileSync(d.log + '.stdin', 'utf8').trimEnd().split('\n');
      a.match(stdin[stdin.length - 1], /^DO \$x\$ BEGIN RAISE EXCEPTION 'the backup is incomplete \(it does not end like a pg_dump\)'; END \$x\$;$/);
      a.ok(!stdin.includes('COMMIT;'), stdin.join('\n'));
      const calls = d.calls();
      a.equal(calls.slice(idx(calls, PSQL_RE) + 1).filter((c) => /^compose up /.test(c)).length, 0, calls.join('\n'));
      a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m, '.env still names the running version');
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
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

t.test('HEALTH_TIMEOUT: .env wins over the supervisor environment, and an unusable value is ignored', () => {
  const fs = require('fs'), path = require('path');
  const timeoutWith = (envLine, envVar) => {
    const d = rollbackDeploy();
    try {
      if (envLine !== null) fs.appendFileSync(path.join(d.project, '.env'), envLine + '\n');
      const env = { ...d.env, STUB_CURL_FAIL: '1' };
      if (envVar === null) delete env.HEALTH_TIMEOUT; else env.HEALTH_TIMEOUT = envVar;
      const r = runScript('rollback.sh', [], env);
      const m = /still unhealthy after rollback \((\d+)s\)/.exec(r.err);
      a.ok(m, r.all);
      return { seconds: Number(m[1]), err: r.err };
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  };
  // Set in .env (an existing install adds a line): used even when the container's environment says otherwise.
  a.equal(timeoutWith('HEALTH_TIMEOUT=0', '600').seconds, 0);
  a.equal(timeoutWith('HEALTH_TIMEOUT="0"\r', '600').seconds, 0, 'quotes and a CR are tolerated');
  // Not in .env: the environment (compose passes it through).
  a.equal(timeoutWith(null, '0').seconds, 0);
  // Not a whole number: said, and the next source is used.
  const bad = timeoutWith('HEALTH_TIMEOUT=ten', '0');
  a.equal(bad.seconds, 0);
  a.match(bad.err, /HEALTH_TIMEOUT=ten is not a whole number of seconds, at least 0; ignored/);
});

/** What the scripts' health_timeout() answers for this .env text and environment (the real 30 s floor unless set). */
function resolveHealthTimeout(script, envText, extraEnv) {
  const fs = require('fs'), os = require('os'), path = require('path');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-ht-'));
  try {
    const envFile = path.join(dir, '.env');
    fs.writeFileSync(envFile, envText);
    const src = fs.readFileSync(path.join(__dirname, script), 'utf8');
    const fn = /health_timeout\(\) \{[\s\S]*?\n\}\n/.exec(src)[0];
    const env = { PATH: process.env.PATH, ...extraEnv };
    const guard = JSON.stringify(path.join(__dirname, 'mode-guard.sh'));
    const r = cp.spawnSync('bash', ['-c', `. ${guard}\nENV_FILE=${JSON.stringify(envFile)}\n${fn}health_timeout`], { env, encoding: 'utf8' });
    return { value: r.stdout.trim(), err: r.stderr };
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
}

t.test('HEALTH_TIMEOUT: 180 by default; below 30 (0 included, not "no limit") is refused like any bad value', () => {
  for (const script of ['apply.sh', 'rollback.sh']) {
    a.equal(resolveHealthTimeout(script, '', {}).value, '180', script);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT=900\n', { HEALTH_TIMEOUT: '180' }).value, '900', script);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT=30\n', {}).value, '30', script);
    // Read as docker compose reads .env: an inline comment after whitespace is not part of the value, quoted or not
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT=900 # slow database update\n', {}).value, '900', script);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT=900\t#slow\r\n', {}).value, '900', script);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT="900" # slow\n', {}).value, '900', script);
    a.equal(resolveHealthTimeout(script, "HEALTH_TIMEOUT='600' # slow\n", {}).value, '600', script);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT=900#slow\n', { HEALTH_TIMEOUT: '240' }).value, '240',
      `${script}: no whitespace before # (compose keeps it in the value): not a number`);
    a.equal(resolveHealthTimeout(script, '# HEALTH_TIMEOUT=900\n', {}).value, '180', `${script}: a commented-out line`);
    // The other forms compose reads (supervisor/mode-guard.sh's _mode_env): "export ", blanks around =, KEY: v, the last line.
    a.equal(resolveHealthTimeout(script, 'export HEALTH_TIMEOUT=900\n', {}).value, '900', `${script}: export`);
    a.equal(resolveHealthTimeout(script, '  HEALTH_TIMEOUT = 900\n', {}).value, '900', `${script}: blanks`);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT: 900\n', {}).value, '900', `${script}: KEY: v`);
    a.equal(resolveHealthTimeout(script, 'HEALTH_TIMEOUT=900\nHEALTH_TIMEOUT=', {}).value, '180',
      `${script}: a last empty line with no newline after it`);
    for (const low of ['0', '29']) {
      const r = resolveHealthTimeout(script, `HEALTH_TIMEOUT=${low}\n`, { HEALTH_TIMEOUT: '240' });
      a.equal(r.value, '240', `${script}: ${low} falls through to the environment`);
      a.match(r.err, new RegExp(`HEALTH_TIMEOUT=${low} is not a whole number of seconds, at least 30; ignored`));
      a.equal(resolveHealthTimeout(script, `HEALTH_TIMEOUT=${low}\n`, {}).value, '180', `${script}: then the default`);
    }
  }
  const fs = require('fs'), path = require('path');
  const fn = (f) => /health_timeout\(\) \{[\s\S]*?\n\}\n/.exec(fs.readFileSync(path.join(__dirname, f), 'utf8'))[0];
  a.equal(fn('apply.sh'), fn('rollback.sh'), 'apply.sh and rollback.sh agree');
});

t.test('apply.sh: a failed health check rolls back with the same safe restore; a failed restore fails the apply loudly', () => {
  const fs = require('fs');
  let d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    let r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0' });
    a.equal(r.code, 1, r.all);
    // The first recreate is the new version's; the rollback's stop → restore → start follows it.
    const calls = d.calls(), firstUp = idx(calls, /^compose up /);
    assertSafeRestoreOrder(calls.slice(firstUp + 1), r.all);
    a.match(r.out, /PHASE rollback/);
    a.match(r.out, /PHASE failed/, 'still unhealthy after the rollback (curl stub always fails)');
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
    fs.rmSync(d.dir, { recursive: true, force: true });

    d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
    r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0', STUB_PSQL_FAIL: '1' });
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

t.test('apply.sh and rollback.sh: healthy means the edge stays up too; an edge the new release cannot start is rolled back', () => {
  const fs = require('fs');
  const base = 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n';
  // The new web image's edge doesn't start (a site.d rule it refuses, say), the old one does: rolled back, and the reason
  // names the edge and where to look.
  for (const edge of ['restarting', 'flapping', 'gone']) {
    const d = makeDeploy(base);
    try {
      const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_EDGE: edge, STUB_EDGE_BAD_WEB: '0.0.2' });
      a.equal(r.code, 1, edge + ': ' + r.all);
      a.match(r.err, /^ERR the edge \(caddy\) did not stay up.*docker compose logs caddy/m, edge);
      a.match(r.out, /PHASE rollback/);
      a.match(r.out, /PHASE rolled_back/, edge + ': the old edge starts: ' + r.all);
      const calls = d.calls();
      assertSafeRestoreOrder(calls.slice(idx(calls, /^compose up /) + 1), r.all);
      a.match(d.envFile(), /^WEB_VERSION=0\.0\.1$/m);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
  // An edge that stays up is healthy: the update is done (and the server's health alone is not enough: above).
  let d = makeDeploy(base);
  try {
    const r = runScript('apply.sh', applyArgs('0.0.2'), d.env);
    a.equal(r.code, 0, r.all);
    a.match(r.out, /PHASE done/);
    a.ok(d.calls().some((c) => c === 'compose ps -a -q caddy'), 'the edge was looked at');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  // rollback.sh: an edge that doesn't stay up after the rollback fails it, saying so.
  d = makeDeploy('SERVER_VERSION=0.0.2\nWEB_VERSION=0.0.2\nCURRENT_VERSION=0.0.2\n');
  try {
    fs.writeFileSync(require('path').join(d.backups, 'latest'), '20261101-120000\n');
    fs.writeFileSync(require('path').join(d.backups, '20261101-120000.env'), base);
    fs.writeFileSync(require('path').join(d.backups, '20261101-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
    const r = runScript('rollback.sh', [], { ...d.env, STUB_EDGE: 'restarting' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.err, /^ERR the edge \(caddy\) did not stay up/m);
    a.match(r.out, /PHASE failed/);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('apply.sh: healthy means the server still answers after the edge settles', () => {
  const fs = require('fs');
  const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_CURL_OK_TIMES: '1', HEALTH_TIMEOUT: '5', HEALTH_RETRY_WAIT: '0' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.err, /^ERR the server stopped answering /m);
    a.match(r.out, /PHASE rollback/);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  // An edge that stops after it looked steady, while the server is rechecked: not healthy.
  const d3 = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d3.env, STUB_EDGE: 'dies-late', HEALTH_RETRY_WAIT: '0' });
    a.notEqual(r.code, 0, r.all);
    a.match(r.err, /^ERR the edge \(caddy\) did not stay up \(exited /m);
    a.doesNotMatch(r.out, /PHASE done/);
  } finally { fs.rmSync(d3.dir, { recursive: true, force: true }); }
  // One slow answer in that recheck (the next one fine) is no failure: the update is done.
  const d2 = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d2.env, STUB_CURL_FAIL_AT: '2', HEALTH_TIMEOUT: '5', HEALTH_RETRY_WAIT: '0' });
    a.equal(r.code, 0, r.all);
    a.match(r.out, /PHASE done/);
  } finally { fs.rmSync(d2.dir, { recursive: true, force: true }); }
});

t.test('apply.sh: a failed pull changed no container, so it resets .env only: no stop, no restore, no downtime', () => {
  const fs = require('fs');
  const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
  try {
    const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_FAIL_RE: '^compose pull ' });
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
      const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, STUB_CURL_FAIL: '1', HEALTH_TIMEOUT: '0', ...knob });
      a.equal(r.code, 1, r.all);
      a.match(r.out, /PHASE failed/);
      a.match(d.envFile(), /^SERVER_VERSION=0\.0\.2@sha256:a{64}$/m, JSON.stringify(knob) + '\n' + r.all);
      a.match(d.envFile(), /^WEB_VERSION=0\.0\.2@sha256:b{64}$/m);
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
    // server.js handed apply.sh the signed manifest's digests, and those are what .env now runs.
    a.match(d.envFile(), /^SERVER_VERSION=0\.0\.2@sha256:a{64}$/m);
    a.match(d.envFile(), /^WEB_VERSION=0\.0\.2@sha256:b{64}$/m);
  });

// --- Images pinned by digest: apply.sh runs exactly the images the signed manifest names. ---
t.test('apply.sh pins server + web by digest (.env <version>@sha256:…), checks compose resolves to them and the pulled '
  + 'images carry them, then pins a release-following supervisor', () => {
  const fs = require('fs');
  const d = makeDeploy('IMAGE_OWNER=mdmesh-app\nSERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nSUPERVISOR_VERSION=latest\nCURRENT_VERSION=0.0.1\n');
  try {
    const r = runScript('apply.sh', applyArgs('0.0.2'), d.env);
    a.equal(r.code, 0, r.all);
    a.match(r.out, /^OK 0\.0\.2$/m);
    a.match(d.envFile(), /^SERVER_VERSION=0\.0\.2@sha256:a{64}$/m);
    a.match(d.envFile(), /^WEB_VERSION=0\.0\.2@sha256:b{64}$/m);
    a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.2$/m, 'CURRENT_VERSION stays a plain version (the supervisor compares it)');
    a.match(d.envFile(), /^SUPERVISOR_VERSION=0\.0\.2@sha256:c{64}$/m, ':latest becomes the release\'s signed supervisor');
    const calls = d.calls();
    const at = (re) => idx(calls, re);
    a.ok(at(/^compose config --images server caddy$/) > -1 && at(/^compose config --images/) < at(/ pg_dump /), calls.join('\n'));
    a.ok(at(new RegExp('^image inspect .* ' + PIN.server.replace(/[.]/g, '\\.') + '$')) > at(/^compose pull /), calls.join('\n'));
    a.ok(at(new RegExp('^image inspect .* ' + PIN.web.replace(/[.]/g, '\\.') + '$')) < at(/^compose up /), calls.join('\n'));
    a.equal(calls.filter((c) => /supervisor/.test(c) && /^compose (pull|up)/.test(c)).length, 0, 'the supervisor is never recreated');
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  // A supervisor version set by hand or by setup.sh (which builds it) is left alone; an earlier pin moves on; unset is pinned.
  for (const [before, after] of [['SUPERVISOR_VERSION=0.0.1\n', /^SUPERVISOR_VERSION=0\.0\.1$/m],
    ['SUPERVISOR_VERSION=0.0.1@sha256:' + 'd'.repeat(64) + '\n', /^SUPERVISOR_VERSION=0\.0\.2@sha256:c{64}$/m],
    ['', /^SUPERVISOR_VERSION=0\.0\.2@sha256:c{64}$/m]]) {
    const d2 = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n' + before);
    try {
      const r = runScript('apply.sh', applyArgs('0.0.2'), d2.env);
      a.equal(r.code, 0, r.all);
      a.match(d2.envFile(), after, before);
    } finally { fs.rmSync(d2.dir, { recursive: true, force: true }); }
  }
});

t.test('apply.sh refuses refs that are missing, malformed or under another owner, before anything changes', () => {
  const fs = require('fs');
  const ENV = 'IMAGE_OWNER=forkco\nSERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nSUPERVISOR_VERSION=latest\nCURRENT_VERSION=0.0.1\n';
  const cases = [
    [applyArgs('0.0.2'), /^ERR the signed release 0\.0\.2 pins its server image at ghcr\.io\/mdmesh-app\/mdmesh-server, but this install pulls ghcr\.io\/forkco\/mdmesh-server \(IMAGE_OWNER=forkco in \.env\).* Nothing was changed\.$/m],
    [['0.0.2'], /^ERR the signed release 0\.0\.2 names no digest for its server image .*Nothing was changed\.$/m],
    [applyArgs('0.0.2', { server: 'ghcr.io/forkco/mdmesh-server@sha256:' + 'a'.repeat(64), web: 'ghcr.io/forkco/mdmesh-web:0.0.2',
      supervisor: 'x' }), /^ERR the signed release 0\.0\.2 has no valid digest for its web image/m],
  ];
  for (const [args, want] of cases) {
    const d = makeDeploy(ENV);
    try {
      const r = runScript('apply.sh', args, d.env);
      a.equal(r.code, 1, r.all);
      a.match(r.err, want);
      a.match(r.out, /^PHASE failed$/m);
      a.doesNotMatch(r.out, /PHASE backup/);
      a.deepEqual(d.calls(), [], 'no docker call at all');
      a.equal(d.envFile(), ENV, '.env untouched');
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
  // The fork's own pinned images apply.
  const d = makeDeploy(ENV);
  try {
    const own = { server: 'ghcr.io/forkco/mdmesh-server@sha256:' + 'a'.repeat(64), web: 'ghcr.io/forkco/mdmesh-web@sha256:' + 'b'.repeat(64),
      supervisor: 'ghcr.io/forkco/mdmesh-supervisor@sha256:' + 'c'.repeat(64) };
    const r = runScript('apply.sh', applyArgs('0.0.2', own), d.env);
    a.equal(r.code, 0, r.all);
    a.match(d.envFile(), /^SERVER_VERSION=0\.0\.2@sha256:a{64}$/m);
  } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
});

t.test('apply.sh: a compose file that does not resolve to the pinned refs is refused before the backup; a pulled image '
  + 'without the signed digest resets .env only (no container changed)', () => {
  const fs = require('fs');
  // The compose check runs before the backup: a mismatch changes nothing at all.
  for (const [knob, want] of [
    [{ STUB_CONFIG_IMAGES: 'ghcr.io/mdmesh-app/mdmesh-server:0.0.2\nghcr.io/mdmesh-app/mdmesh-web:0.0.2' },
      /^ERR this install's compose file does not resolve to the signed image ghcr\.io\/mdmesh-app\/mdmesh-server:0\.0\.2@sha256:a{64}.*Nothing was changed\.$/m],
    [{ STUB_CONFIG_IMAGES: 'ghcr.io/mdmesh-app/mdmesh-server:0.0.2@sha256:' + 'a'.repeat(64) + '\nghcr.io/mdmesh-app/mdmesh-web:0.0.2' },
      /^ERR this install's compose file does not resolve to the signed image ghcr\.io\/mdmesh-app\/mdmesh-web:0\.0\.2@sha256:b{64}.*Nothing was changed\.$/m],
    [{ STUB_FAIL_RE: '^compose config ' }, /^ERR could not read the compose configuration/m]]) {
    const ENV = 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nSUPERVISOR_VERSION=latest\nCURRENT_VERSION=0.0.1\n';
    const d = makeDeploy(ENV);
    try {
      const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, ...knob });
      a.equal(r.code, 1, r.all);
      a.match(r.err, want);
      a.match(r.out, /^PHASE failed$/m);
      a.doesNotMatch(r.out, /PHASE backup/);
      a.deepEqual(d.calls(), ['compose config --images server caddy'], 'no dump, no pull');
      a.equal(require('fs').readdirSync(d.backups).length, 0, 'no backup, /backups/latest untouched');
      a.equal(d.envFile(), ENV);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
  const knobs = [
    [{ STUB_REPO_DIGESTS: 'ghcr.io/mdmesh-app/mdmesh-server@sha256:' + 'e'.repeat(64) },
      /^ERR the pulled image does not carry the signed digest ghcr\.io\/mdmesh-app\/mdmesh-server@sha256:a{64}, so it is not used$/m],
    // The server image is right and the web image isn't.
    [{ STUB_REPO_DIGESTS: PIN.server },
      /^ERR the pulled image does not carry the signed digest ghcr\.io\/mdmesh-app\/mdmesh-web@sha256:b{64}, so it is not used$/m],
    // docker image inspect fails: no image with that digest here.
    [{ STUB_FAIL_RE: '^image inspect ' },
      /^ERR the pulled image does not carry the signed digest ghcr\.io\/mdmesh-app\/mdmesh-server@sha256:a{64}, so it is not used$/m],
    [{ STUB_FAIL_RE: '^image inspect .*mdmesh-web@' },
      /^ERR the pulled image does not carry the signed digest ghcr\.io\/mdmesh-app\/mdmesh-web@sha256:b{64}, so it is not used$/m],
  ];
  for (const [knob, want] of knobs) {
    const d = makeDeploy('SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nSUPERVISOR_VERSION=latest\nCURRENT_VERSION=0.0.1\n');
    try {
      const r = runScript('apply.sh', applyArgs('0.0.2'), { ...d.env, ...knob });
      a.equal(r.code, 1, r.all);
      a.match(r.err, want);
      a.match(r.out, /PHASE rolled_back/);
      const calls = d.calls();
      a.equal(idx(calls, /^compose up /), -1, calls.join('\n'));
      a.equal(idx(calls, /^compose stop /), -1, calls.join('\n'));
      a.match(d.envFile(), /^SERVER_VERSION=0\.0\.1$/m);
      a.match(d.envFile(), /^WEB_VERSION=0\.0\.1$/m);
      a.match(d.envFile(), /^SUPERVISOR_VERSION=latest$/m);
      a.match(d.envFile(), /^CURRENT_VERSION=0\.0\.1$/m);
    } finally { fs.rmSync(d.dir, { recursive: true, force: true }); }
  }
});

t.test('a release without digests, under another owner or without images for this host: shown as blocked with the reason, '
  + 'not offered or auto-applied; Update refuses it; nothing runs',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async () => {
    const fs = require('fs');
    for (const [opts, envText, want, code] of [
      [{ version: '0.0.2', images: null, apk: Buffer.from('agent 0.0.2') }, '', /names its images by tag only/, 'digests'],
      [{ version: '0.0.2', apk: Buffer.from('agent 0.0.2') }, 'IMAGE_OWNER=forkco\n', /this install pulls ghcr\.io\/forkco\/mdmesh-server \(IMAGE_OWNER=forkco in \.env\)/, 'owner'],
      [{ version: '0.0.2', platforms: 'linux/s390x', apk: Buffer.from('agent 0.0.2') }, '', /has no images for this host \(linux\/[a-z0-9]+\), only for linux\/s390x/, 'platform'],
    ]) {
      const d = makeDeploy(envText + 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
      let gh = null, sup = null, authz = null;
      try {
        gh = await fakeGitHub(d.dir, opts);
        authz = await fakeAuthz();
        sup = await spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', AUTO_UPDATE: '1', SERVER_BASE: authz.base }, /supervisor on/, ['--require', gh.preload]);
        const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
        const st = await waitFor(async () => { const x = await status(); return x.checkedAt && x; }, 'the startup poll');
        a.equal(st.updateAvailable, false, 'not offered as an update');
        a.equal(st.latest, '0.0.2');
        a.match(st.blocked, want, 'the status says why');
        a.equal(st.blockedCode, code);
        // The running release (0.0.1) has no release here, so enrollment keeps the latest's agent, never a 404.
        a.equal(st.apk && st.apk.of, 'latest');
        a.deepEqual(Buffer.from(await (await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk`)).arrayBuffer()), opts.apk);
        await waitFor(async () => /\[apk\] running release v0\.0\.1 is unknown \(no such release\); offering the latest release's agent/.test(sup.log()), 'the fallback log line');
        // The status is what auto-apply acts on: no apply started (not just no log line yet).
        a.equal(st.apply, null, 'auto-apply leaves it alone');
        await waitFor(async () => /\[update\] not offered: /.test(sup.log()), 'the not-offered log line');
        a.doesNotMatch(sup.log(), /\[auto\]/, 'nor tries it');
        const r = await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN });
        a.equal(r.status, 400);
        a.match((await r.json()).error, want);
        a.deepEqual(d.calls(), [], 'apply.sh never ran');
      } finally {
        await stopChild(sup && sup.child); if (gh) await gh.close(); if (authz) await authz.close();
        fs.rmSync(d.dir, { recursive: true, force: true });
      }
    }
  });

/** A supervisor on this deployment against `gh` (a fakeGitHub), as Docker runs it; `env` adds to or overrides it. */
async function supervisorOn(d, gh, authz, env = {}) {
  const path = require('path');
  return spawnSupervisor(d.dir, { ...d.env, ...gh.env, APPLY_SUPPORTED: '1', SERVER_BASE: authz.base,
    APK_CACHE_DIR: path.join(d.dir, 'apk'), ...env }, /supervisor on/, ['--require', gh.preload]);
}
const envOf = (owner, current = '0.0.1') =>
  `IMAGE_OWNER=${owner}\nSERVER_VERSION=${current}\nWEB_VERSION=${current}\nCURRENT_VERSION=${current}\n`;
const NEW_AGENT = Buffer.from('agent 0.0.2'), RUNNING_AGENT = Buffer.from('agent 0.0.1');
async function statusOf(sup, since = 0) {
  return waitFor(async () => { const x = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
    return x.checkedAt && x.checkedAt > since && x; }, 'a poll');
}
async function agentOf(sup) {
  const r = await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk`);
  return r.status === 200 ? Buffer.from(await r.arrayBuffer()) : r.status;
}
/** Runs `body(d, gh, authz)` on a new deployment (.env `envText`) and fake GitHub (`ghOpts`), and cleans up. */
async function withDeploy(envText, ghOpts, body) {
  const fs = require('fs');
  const d = makeDeploy(envText);
  let gh = null, authz = null;
  try {
    gh = await fakeGitHub(d.dir, ghOpts);
    authz = await fakeAuthz();
    await body(d, gh, authz);
  } finally {
    if (gh) await gh.close(); if (authz) await authz.close();
    fs.rmSync(d.dir, { recursive: true, force: true });
  }
}

const OLDER = { version: '0.0.1', apk: RUNNING_AGENT, versionCode: 1 };
const GH = (older = {}) => ({ version: '0.0.2', apk: NEW_AGENT, older: { ...OLDER, ...older } });
const FAST = { POLL_INTERVAL_HOURS: '0.0003' };   // a poll (and a retry) about every second
const logCount = (sup, re) => (sup.log().match(new RegExp(re.source, 'g')) || []).length;

t.test('an agent is never offered ahead of the server: the running release\'s, blocked or not; the latest\'s once the '
  + 'server runs it', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('forkco'), GH(), async (d, gh, authz) => {
      // Each phase rewrites .env and restarts the supervisor (as docker compose up -d does), with the same APK cache.
      const phase = async (env, check) => {
        fs.writeFileSync(path.join(d.project, '.env'), env);
        const sup = await supervisorOn(d, gh, authz);
        try { await check(await statusOf(sup), sup); } finally { await stopChild(sup.child); }
      };
      await phase(envOf('forkco'), async (st, sup) => {   // blocked
        a.equal(st.blockedCode, 'owner');
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running']);
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      });
      await phase(envOf('mdmesh-app'), async (st, sup) => {   // installable, not installed yet
        a.equal(st.updateAvailable, true);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running'], 'update the server first');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      });
      await phase(envOf('mdmesh-app', '0.0.2'), async (st, sup) => {   // the server runs the latest
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'running']);
        a.deepEqual(await agentOf(sup), NEW_AGENT);
        a.equal(st.apkServed, true, 'this supervisor serves /files/agent.apk');
      });
      await phase(envOf('forkco'), async (st, sup) => {   // back on 0.0.1: the mirrored 0.0.2 isn't offered
        a.equal(st.apk && st.apk.version, '0.0.1');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      });
    });
  });

t.test('the running release\'s agent is kept on disk: a restart with GitHub down offers it; a tampered kept manifest '
  + 'or APK is refused and fetched again', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH({ unlisted: true }), async (d, gh, authz) => {
      const run = async (check) => { const sup = await supervisorOn(d, gh, authz); try { await check(sup); } finally { await stopChild(sup.child); } };
      await run(async (sup) => {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running']);
        await waitFor(async () => (await statusOf(sup)).apk.available, 'agent 0.0.1 mirrored');
      });
      a.ok(fs.existsSync(path.join(d.dir, 'apk', 'v0.0.1.manifest.json')), 'its manifest is kept');
      gh.control.down = true;
      await run(async (sup) => {
        const st = await statusOf(sup);
        a.match(st.error, /github 500/);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running'], 'from disk, with GitHub down');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      });
      // A kept manifest that no longer verifies: removed, nothing offered while GitHub is down, fetched again after.
      fs.writeFileSync(path.join(d.dir, 'apk', 'v0.0.1.manifest.json'), '{"version":"0.0.1","components":{"apk":{"versionCode":1,"sha256":"' + 'b'.repeat(64) + '"}}}');
      await run(async (sup) => {
        const st = await statusOf(sup);
        a.equal(st.apk, null);
        a.match(sup.log(), /the kept manifest of 0\.0\.1 is not usable \(minisign signature check failed/);
        a.ok(!fs.existsSync(path.join(d.dir, 'apk', 'v0.0.1.manifest.json')));
      });
      gh.control.down = false;
      const tags = gh.hits.tags;
      fs.writeFileSync(path.join(d.dir, 'apk', 'agent-1.apk'), 'tampered');
      await run(async (sup) => {
        const st = await statusOf(sup);
        a.equal(st.apk && st.apk.version, '0.0.1');
        a.equal(gh.hits.tags, tags + 1, 'looked up again');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT, 'the tampered APK is downloaded again, not served');
      });
    });
  });

t.test('a failed lookup of the running release (403, 5xx) offers nothing, never the latest, and is retried; a 404 means '
  + 'no such release, and the latest\'s agent is offered', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH({ unlisted: true }), async (d, gh, authz) => {
      gh.control.tagStatus = 403;   // a rate limit: GitHub never says 403 for a missing release
      const sup = await supervisorOn(d, gh, authz, FAST);
      try {
        const st = await statusOf(sup);
        a.equal(st.apk, null, 'not the latest release\'s agent');
        a.equal(await agentOf(sup), 404);
        await waitFor(async () => /could not be looked up yet; offering none, retrying/.test(sup.log()), 'the log line');
        gh.control.tagStatus = null;
        const ok = await waitFor(async () => { const x = await statusOf(sup); return x.apk && x; }, 'the retry');
        a.deepEqual([ok.apk.version, ok.apk.of], ['0.0.1', 'running']);
      } finally { await stopChild(sup.child); }
    });
    await withDeploy(envOf('mdmesh-app'), { version: '0.0.2', apk: NEW_AGENT }, async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz);   // no v0.0.1 release: the tag lookup answers 404
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'latest']);
        a.deepEqual(await agentOf(sup), NEW_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a running release whose manifest doesn\'t verify (another key) offers nothing, saying why',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH({ otherKey: true }), async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.equal(st.apk, null);
        a.equal(await agentOf(sup), 404);
        await waitFor(async () => /running release v0\.0\.1 has no verified agent \(manifest not verified: minisign signature check failed/.test(sup.log()), 'the log line');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('an unknown running version (a -dev build) offers the latest release\'s agent, logged once',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('forkco', '0.0.1-dev'), GH(), async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz, FAST);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'latest']);
        a.deepEqual(await agentOf(sup), NEW_AGENT);
        await statusOf(sup, st.checkedAt);
        a.equal(logCount(sup, /\[apk\] running release v0\.0\.1-dev is unknown \(not a published release version\)/), 1);
        a.equal(gh.hits.tags, 0, 'not looked up');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('the running release\'s agent is resolved once per version (from the list, no tag lookup)',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz, FAST);
      try {
        let st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running']);
        st = await statusOf(sup, st.checkedAt);
        st = await statusOf(sup, st.checkedAt);
        a.equal(gh.hits.tags, 0, 'found in the release list');
        a.equal(gh.hits.got.filter((x) => x === 'v0.0.1/manifest.json').length, 1, 'its manifest fetched once');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('the offer follows the running version at once (apply, rollback); ?v= serves an older verified agent, refuses '
  + 'one newer than the server (409), and an unknown one (404)', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async () => {
    const fs = require('fs'), path = require('path');
    // failAfter: polls after the first one fail, so only the offer on a version change can switch the agent.
    await withDeploy(envOf('mdmesh-app'), { ...GH(), failAfter: 1 }, async (d, gh, authz) => {
      fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
      const sup = await supervisorOn(d, gh, authz);
      const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
      const get = (q) => fetch(`http://127.0.0.1:${sup.port}/update/agent.apk${q}`);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running']);

        await waitFor(async () => (await status()).apk.available, 'agent 0.0.1 mirrored');
        // GitHub is gone from here on (agent downloads aside): only the manifest the first poll verified can make
        // 0.0.2's offer, in the very status that says the apply is done.
        gh.control.down = true;
        a.equal((await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN })).status, 202);
        const applied = await waitFor(async () => { const x = await status(); return x.apply && x.apply.phase === 'done' && x; }, 'the apply', 20000);
        a.equal(applied.current, '0.0.2');
        a.deepEqual(applied.apk && [applied.apk.version, applied.apk.of], ['0.0.2', 'running'], 'at once, with GitHub down');
        gh.control.down = false;
        a.deepEqual(await agentOf(sup), NEW_AGENT);
        a.deepEqual(Buffer.from(await (await get('?v=1')).arrayBuffer()), RUNNING_AGENT, 'an older rollout gets its own bytes');
        a.equal((await get('?v=7')).status, 404, 'an agent never seen');

        const rb = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
          headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
        a.equal(rb.status, 202, await rb.text());
        const back = await waitFor(async () => { const x = await status(); return x.apply && x.apply.phase === 'rolled_back' && x; }, 'the rollback', 20000);
        a.equal(back.current, '0.0.1');
        a.deepEqual(back.apk && [back.apk.version, back.apk.of], ['0.0.1', 'running'], 'at once');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
        const newer = await get('?v=999');
        a.equal(newer.status, 409, 'a rollout of 0.0.2 stops at the rolled-back server');
        a.match((await newer.json()).error, /agent 0\.0\.2 is newer than this server \(0\.0\.1\)/);
        // A cached file changed after it was checked is checked again before it's served, and downloaded again.
        fs.writeFileSync(path.join(d.dir, 'apk', 'agent-1.apk'), 'tampered while running');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a slow lookup for the version that was running can\'t replace the offer for the new one',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async () => {
    // 0.0.1 is only found by tag, slowly; the server is updated to 0.0.2 while that lookup is still on its way.
    await withDeploy(envOf('mdmesh-app'), { ...GH({ unlisted: true }), failAfter: 1 }, async (d, gh, authz) => {
      gh.control.tagDelayMs = 3000;
      const sup = await supervisorOn(d, gh, authz);
      const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
      try {
        // The first poll has verified 0.0.2 and is now waiting on 0.0.1's lookup: update while it does.
        await waitFor(async () => gh.hits.tags >= 1, 'the old lookup started');
        a.equal((await fetch(`http://127.0.0.1:${sup.port}/update/apply`, { method: 'POST', headers: ADMIN })).status, 202);
        await waitFor(async () => { const x = await status(); return x.apply && x.apply.phase === 'done' && x.apk && x.apk.version === '0.0.2'; }, 'the new offer', 20000);
        await new Promise((res) => setTimeout(res, 3500));   // the old lookup has answered by now
        const after = await status();
        a.deepEqual(after.apk && [after.apk.version, after.apk.of], ['0.0.2', 'running'], 'not replaced by 0.0.1\'s');
        a.deepEqual(await agentOf(sup), NEW_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('nothing in the offer path can stop the supervisor: with no temp directory it logs, offers nothing and keeps '
  + 'serving', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    const path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH({ unlisted: true }), async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz, { ...FAST, TMPDIR: path.join(d.dir, 'no-such-dir') });
      try {
        let st = await statusOf(sup);
        st = await statusOf(sup, st.checkedAt);   // a second poll: still up
        a.equal(st.apk, null);
        a.match(st.error, /manifest not verified: download or check failed/);
        a.equal((await fetch(`http://127.0.0.1:${sup.port}/healthz`)).status, 200);
        a.equal(sup.child.exitCode, null, 'still running');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a running release that was not published yet (404) is asked again each poll: once published, its agent is '
  + 'offered, not the newer latest', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      gh.control.hide.add('v0.0.1');
      const sup = await supervisorOn(d, gh, authz, FAST);
      try {
        let st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'latest'], 'unknown while unpublished');
        gh.control.hide.clear();
        st = await waitFor(async () => { const x = await statusOf(sup, st.checkedAt); return x.apk && x.apk.version === '0.0.1' && x; }, 'the published release');
        a.equal(st.apk.of, 'running');
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('?v= finds an agent kept on disk after a restart (a rollout started before an update); still 409 when newer than '
  + 'the server', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      const run = async (current, check) => {
        fs.writeFileSync(path.join(d.project, '.env'), envOf('mdmesh-app', current));
        const sup = await supervisorOn(d, gh, authz);
        try { await check(sup); } finally { await stopChild(sup.child); }
      };
      const mirrored = async (sup) => { await waitFor(async () => (await statusOf(sup)).apk.available, 'the agent mirrored'); };
      await run('0.0.1', mirrored);   // 0.0.1 runs: its agent is resolved, kept and mirrored
      await run('0.0.2', mirrored);   // updated to 0.0.2: the same for 0.0.2
      gh.control.down = true;         // restarts with GitHub down from here on
      await run('0.0.2', async (sup) => {
        await statusOf(sup);
        const r = await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk?v=1`);
        a.equal(r.status, 200);
        a.deepEqual(Buffer.from(await r.arrayBuffer()), RUNNING_AGENT, 'offline, from the kept manifest and cache');
      });
      await run('0.0.1', async (sup) => {   // rolled back: the kept 0.0.2 is newer than the server
        await statusOf(sup);
        a.equal((await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk?v=999`)).status, 409);
      });
    });
  });

t.test('a kept manifest is checked: another version\'s under this name is removed; minisign failing to run removes '
  + 'nothing', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app', '0.0.2'), GH(), async (d, gh, authz) => {
      const run = async (env, check) => { const sup = await supervisorOn(d, gh, authz, env); try { await check(sup); } finally { await stopChild(sup.child); } };
      await run({}, async (sup) => { await waitFor(async () => (await statusOf(sup)).apk, 'the offer'); });
      const kept = (v, ext) => path.join(d.dir, 'apk', `v${v}.${ext}`);
      a.ok(fs.existsSync(kept('0.0.2', 'manifest.json')));
      // minisign can't start (not on PATH): the kept manifest can't be checked, so nothing is offered, and it stays.
      gh.control.down = true;
      await run({ PATH: path.join(d.dir, 'empty-path') }, async (sup) => {
        const st = await statusOf(sup);
        a.equal(st.apk, null);
        await waitFor(async () => /the kept manifest of 0\.0\.2 can't be checked now/.test(sup.log()), 'the log line');
      });
      a.ok(fs.existsSync(kept('0.0.2', 'manifest.json')), 'not removed');
      await run({}, async (sup) => { a.equal((await statusOf(sup)).apk.version, '0.0.2', 'checked and used once minisign runs'); });
      // 0.0.2's signed manifest kept under 0.0.1's name: signed, but for another version — refused and removed.
      for (const ext of ['manifest.json', 'manifest.json.minisig', 'asset.json']) fs.copyFileSync(kept('0.0.2', ext), kept('0.0.1', ext));
      fs.writeFileSync(path.join(d.project, '.env'), envOf('mdmesh-app', '0.0.1'));
      await run({}, async (sup) => {
        const st = await statusOf(sup);
        a.equal(st.apk, null, 'not 0.0.2\'s agent, and GitHub is down');
        a.match(sup.log(), /the kept manifest of 0\.0\.1 is not usable \(manifest of another version\)/);
      });
      a.ok(!fs.existsSync(kept('0.0.1', 'manifest.json')));
    });
  });

t.test('after a rollback to a version not yet known, the old (newer) agent is dropped at once, and the running release '
  + 'is looked up once though two lookups start', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app', '0.0.2'), GH({ unlisted: true }), async (d, gh, authz) => {
      fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
      fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
      fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
      fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
      const sup = await supervisorOn(d, gh, authz);
      const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
      try {
        a.deepEqual((await statusOf(sup)).apk.version, '0.0.2');
        a.equal(gh.hits.tags, 0);
        gh.control.tagDelayMs = 2000;
        const rb = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
          headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
        a.equal(rb.status, 202, await rb.text());
        const back = await waitFor(async () => { const x = await status(); return x.apply && x.apply.phase === 'rolled_back' && x; }, 'the rollback', 20000);
        a.equal(back.current, '0.0.1');
        a.equal(back.apk, null, 'not 0.0.2\'s agent while 0.0.1\'s is looked up');
        a.equal((await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk`)).status, 404);
        const ok = await waitFor(async () => { const x = await status(); return x.apk && x; }, 'the running agent', 20000);
        a.deepEqual([ok.apk.version, ok.apk.of], ['0.0.1', 'running']);
        a.equal(gh.hits.tags, 1, 'the re-offer and the follow-up poll shared one lookup');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('native: a published agent newer than the server is removed when nothing replaces it',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy('', GH({ unlisted: true }), async (d, gh, authz) => {
      const pub = path.join(d.dir, 'files', 'agent.apk');
      fs.mkdirSync(path.dirname(pub), { recursive: true });
      fs.writeFileSync(pub, NEW_AGENT);   // the installer published 0.0.2's agent; the server runs 0.0.1
      gh.control.tagStatus = 403;         // 0.0.1's agent can't be looked up yet
      const sup = await supervisorOn(d, gh, authz, { APPLY_SUPPORTED: '0', CURRENT_VERSION: '0.0.1', PUBLISH_APK_TO: pub });
      try {
        const st = await statusOf(sup);
        a.equal(st.apk, null);
        a.equal(st.apkServed, false);
        a.ok(!fs.existsSync(pub), 'removed');
        a.match(sup.log(), /removed the published agent 0\.0\.2: it is newer than this server \(0\.0\.1\)/);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a server on the latest release takes its agent from the manifest the poll verified: no second download',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('mdmesh-app', '0.0.2'), GH(), async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'running']);
        a.equal(gh.hits.got.filter((x) => x === 'v0.0.2/manifest.json').length, 1);
        a.equal(gh.hits.tags, 0);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a running release whose manifest didn\'t verify is asked again each poll: once it does, its agent is offered',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH({ otherKey: true }), async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz, FAST);
      try {
        let st = await statusOf(sup);
        a.equal(st.apk, null, 'signed by another key: nothing');
        gh.control.otherKey.clear();   // re-signed with the release key
        st = await waitFor(async () => { const x = await statusOf(sup, st.checkedAt); return x.apk && x; }, 'the re-signed release');
        a.deepEqual([st.apk.version, st.apk.of], ['0.0.1', 'running']);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('?v= serves any verified agent while the running version is unknown (0.0.0): no 409',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      let sup = await supervisorOn(d, gh, authz);   // 0.0.1 runs: its agent is kept and mirrored
      try { await waitFor(async () => (await statusOf(sup)).apk.available, 'agent 0.0.1 mirrored'); } finally { await stopChild(sup.child); }
      fs.writeFileSync(path.join(d.project, '.env'), envOf('mdmesh-app', '0.0.0'));
      sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'latest']);
        const r = await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk?v=1`);   // 0.0.1: newer than 0.0.0, not offered
        a.equal(r.status, 200);
        a.deepEqual(Buffer.from(await r.arrayBuffer()), RUNNING_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('native, restarted while GitHub is down: a published agent newer than the server is removed (known from the '
  + 'kept store); one that isn\'t newer, or isn\'t a known agent (hosted by hand), stays',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy('', GH(), async (d, gh, authz) => {
      const pub = path.join(d.dir, 'files', 'agent.apk');
      fs.mkdirSync(path.dirname(pub), { recursive: true });
      const run = async (current, check) => {
        const sup = await supervisorOn(d, gh, authz, { APPLY_SUPPORTED: '0', CURRENT_VERSION: current, PUBLISH_APK_TO: pub });
        try { await check(sup); } finally { await stopChild(sup.child); }
      };
      // 0.0.1, then 0.0.2 ran here: both releases' manifests are kept.
      for (const v of ['0.0.1', '0.0.2']) await run(v, async (sup) => { await waitFor(async () => (await statusOf(sup)).apk.available, v); });
      gh.control.down = true;
      const published = async (current, bytes, expect) => {
        fs.writeFileSync(pub, bytes);
        await run(current, async (sup) => { const st = await statusOf(sup); a.equal(st.apk, null, current); });
        a.equal(fs.existsSync(pub), expect, `${current}: ${expect ? 'kept' : 'removed'}`);
      };
      await published('0.0.3', NEW_AGENT, true);                       // 0.0.2's agent on a 0.0.3 server: not newer
      await published('0.0.4', Buffer.from('hosted by hand'), true);   // not a known agent
      // A downgrade to a version never run here (nothing kept for it, GitHub down): 0.0.2's agent is newer, so it goes.
      await published('0.0.0-rc.1', NEW_AGENT, false);
    });
  });

t.test('an unreadable cached APK (a directory at its path) is not cached: the supervisor keeps running',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      let sup = await supervisorOn(d, gh, authz);
      try { await waitFor(async () => (await statusOf(sup)).apk.available, 'mirrored'); } finally { await stopChild(sup.child); }
      const p = path.join(d.dir, 'apk', 'agent-1.apk');
      fs.rmSync(p); fs.mkdirSync(p);   // at start-up the kept agent is offered, and its cached copy checked
      gh.control.down = true;
      sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.available], ['0.0.1', false]);
        a.equal(sup.child.exitCode, null, 'still running');
        a.equal((await fetch(`http://127.0.0.1:${sup.port}/healthz`)).status, 200);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a kept manifest whose kept asset entry doesn\'t fit it is removed and fetched again, not taken as "unknown"',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      let sup = await supervisorOn(d, gh, authz);
      try { await statusOf(sup); } finally { await stopChild(sup.child); }
      const asset = path.join(d.dir, 'apk', 'v0.0.1.asset.json');
      fs.writeFileSync(asset, JSON.stringify({ name: 'something-else.apk', url: 'https://x', browser_download_url: 'https://x' }));
      sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running'], 'not the latest\'s agent');
        a.match(sup.log(), /the kept manifest of 0\.0\.1 is not usable \(its kept asset entry doesn't fit it/);
        a.equal(JSON.parse(fs.readFileSync(asset, 'utf8')).name, 'mdmesh-agent.apk', 'kept again from GitHub');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('a failed lookup is retried within the retry interval, not only at the next poll',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH({ unlisted: true }), async (d, gh, authz) => {
      gh.control.tagStatus = 403;
      // A poll every 36 s, so a retry every 3.6 s: the agent must come from a retry, with no second poll.
      const sup = await supervisorOn(d, gh, authz, { POLL_INTERVAL_HOURS: '0.01' });
      try {
        const st = await statusOf(sup);
        a.equal(st.apk, null);
        gh.control.tagStatus = null;
        const ok = await waitFor(async () => { const x = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json(); return x.apk && x; }, 'the retry', 15000);
        a.deepEqual([ok.apk.version, ok.apk.of], ['0.0.1', 'running']);
        a.equal(gh.hits.releases, 1, 'no second poll');
      } finally { await stopChild(sup.child); }
    });
  });

t.test('the kept agent of the running release is offered at start-up, before the first poll answers',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      let sup = await supervisorOn(d, gh, authz);
      try { await waitFor(async () => (await statusOf(sup)).apk.available, 'mirrored'); } finally { await stopChild(sup.child); }
      gh.control.listDelayMs = 3000;
      sup = await supervisorOn(d, gh, authz);
      try {
        const st = await (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
        a.equal(st.checkedAt, null, 'the first poll hasn\'t answered');
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.1', 'running']);
        a.deepEqual(await agentOf(sup), RUNNING_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('?v= serves a kept agent newer than a running release that GitHub doesn\'t know (404): no 409',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 30000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app'), GH(), async (d, gh, authz) => {
      let sup = await supervisorOn(d, gh, authz);   // 0.0.1 ran here: its agent is kept
      try { await waitFor(async () => (await statusOf(sup)).apk.available, 'mirrored'); } finally { await stopChild(sup.child); }
      fs.writeFileSync(path.join(d.project, '.env'), envOf('mdmesh-app', '0.0.0-rc.1'));   // a release-shaped version, 404 by tag
      sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.deepEqual(st.apk && [st.apk.version, st.apk.of], ['0.0.2', 'latest']);
        a.match(sup.log(), /running release v0\.0\.0-rc\.1 is unknown \(no such release\)/);
        const r2 = await fetch(`http://127.0.0.1:${sup.port}/update/agent.apk?v=1`);
        a.equal(r2.status, 200);
        a.deepEqual(Buffer.from(await r2.arrayBuffer()), RUNNING_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('an agent whose download outlasts a rollback that makes it newer than the server is refused (409), not served',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 40000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy(envOf('mdmesh-app', '0.0.2'), GH(), async (d, gh, authz) => {
      fs.writeFileSync(path.join(d.backups, 'latest'), '20260927-120000\n');
      fs.writeFileSync(path.join(d.backups, '20260927-120000.env'), 'SERVER_VERSION=0.0.1\nWEB_VERSION=0.0.1\nCURRENT_VERSION=0.0.1\n');
      fs.writeFileSync(path.join(d.backups, '20260927-120000.sql'), 'SELECT 1;\n-- PostgreSQL database dump complete\n');
      fs.writeFileSync(path.join(d.dir, 'recovery.token'), 'tok123');
      gh.control.apkDelayMs = 4000;   // 0.0.2's agent takes a while to mirror
      const sup = await supervisorOn(d, gh, authz);
      const status = async () => (await fetch(`http://127.0.0.1:${sup.port}/update/status`)).json();
      try {
        a.equal((await statusOf(sup)).apk.version, '0.0.2');
        const download = fetch(`http://127.0.0.1:${sup.port}/update/agent.apk`);   // waits for the mirror
        const rb = await fetch(`http://127.0.0.1:${sup.port}/update/rollback`, { method: 'POST',
          headers: { 'X-MDMesh-Console': '1', 'X-Recovery-Token': 'tok123' } });
        a.equal(rb.status, 202, await rb.text());
        await waitFor(async () => { const x = await status(); return x.apply && x.apply.phase === 'rolled_back'; }, 'the rollback', 20000);
        const r = await download;
        a.equal(r.status, 409, 'the 0.0.2 agent is not sent to a 0.0.1 server');
        a.match((await r.json()).error, /agent 0\.0\.2 is newer than this server \(0\.0\.1\)/);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('native: a publish that failed (no files directory yet) is tried again at the next offer',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    const fs = require('fs'), path = require('path');
    await withDeploy('', GH(), async (d, gh, authz) => {
      const files = path.join(d.dir, 'files'), pub = path.join(files, 'agent.apk');
      fs.writeFileSync(files, 'a file where the directory should be');   // the copy can't be made
      const sup = await supervisorOn(d, gh, authz, { ...FAST, APPLY_SUPPORTED: '0', CURRENT_VERSION: '0.0.1', PUBLISH_APK_TO: pub });
      try {
        await waitFor(async () => /\[apk\] publish failed/.test(sup.log()), 'the failed publish');
        fs.rmSync(files); fs.mkdirSync(files);
        await waitFor(async () => fs.existsSync(pub), 'published at a later offer');
        a.deepEqual(fs.readFileSync(pub), RUNNING_AGENT);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('with no agent anywhere (the latest release has none, the running release has none), none is offered: '
  + 'apk is null, which the console\'s Enroll page warns about', { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async () => {
    await withDeploy(envOf('forkco'), { version: '0.0.2' }, async (d, gh, authz) => {
      const sup = await supervisorOn(d, gh, authz);
      try {
        const st = await statusOf(sup);
        a.equal(st.blockedCode, 'owner');
        a.equal(st.apk, null);
        a.equal(await agentOf(sup), 404);
      } finally { await stopChild(sup.child); }
    });
  });

t.test('restore_db: the lock bound is a fixed 60s; no environment value reaches the SQL (not even with a newline)', () => {
  const fs = require('fs');
  // The knob was never wired through compose, and a line-based check let "5s\n<anything>" through: it is gone.
  for (const val of ['5s', "5s\n'; DROP TABLE x; --", '0']) {
    const d = rollbackDeploy();
    try {
      const r = runScript('rollback.sh', [], { ...d.env, RESTORE_LOCK_TIMEOUT: val });
      a.equal(r.code, 0, r.all);
      const call = d.calls().find((c) => PSQL_RE.test(c));
      a.ok(call.includes("-c BEGIN -c SET lock_timeout = '60s' -c"), JSON.stringify(val) + ': ' + call);
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
    const r = cp.spawnSync('bash', ['-c', 'umask 022; exec bash "$0" "$@"', path.join(__dirname, 'apply.sh'), ...applyArgs('0.0.2')],
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
    + 'COPY public.t (x) FROM stdin;\nSET lock_timeout = 0;\nkeep me\n\\.\n\nSET lock_timeout = 0;\n'
    + '\n--\n-- PostgreSQL database dump complete\n--\n\n\\unrestrict abc123\n\n';
  const cases = [
    // [dump, expected psql stdin: the dump, then the COMMIT]
    [pre + 'SET lock_timeout = 0;\n' + body, pre + "SET lock_timeout = '60s';\n" + body + 'COMMIT;\n'],
    // No preamble line at all: nothing may be rewritten (the first whole-line match is then a data row).
    [pre + body, pre + body + 'COMMIT;\n'],
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
