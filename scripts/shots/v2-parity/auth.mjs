// Parity + fidelity checks for the v2 Sign in / Set password pages against a built dist (fixtures harness).
//   node scripts/shots/v2-parity/auth.mjs [--dist <dir>] [--out <dir>]
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { fx, newContext, startServer } from '../harness.mjs';
import { dist, outDir as out } from './lib.mjs';

const md5u = (s) => createHash('md5').update(s).digest('hex').toUpperCase();
const ok = (data) => JSON.stringify({ status: 'OK', message: null, data });
const { server, base } = await startServer(dist);
const browser = await chromium.launch();
let fails = 0;
const check = (cond, msg) => { console.log(`${cond ? 'PASS' : 'FAIL'} ${msg}`); if (!cond) fails++; };
const resetUser = { ...fx.user, passwordReset: true, passwordResetToken: 'tok-123' };

async function ctx(user, opts = {}) {
  const c = await newContext(browser, user, { theme: 'light', deviceScaleFactor: 1, blockGoogleFonts: true, ...opts });
  const errors = [];
  const page = await c.newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/fonts\.g/.test(m.location()?.url ?? '')) errors.push(m.text()); });
  return { c, page, errors };
}
const path = (page) => { const u = new URL(page.url()); return u.pathname + u.search + u.hash; };
const settle = (page) => page.waitForTimeout(400);

// ---------- fidelity shots ----------
{
  const { c, page } = await ctx(null, { viewport: { width: 1440, height: 860 } });
  await page.goto(base + '/login');
  await page.evaluate(() => history.replaceState({ usr: { sessionExpired: true }, key: 'x', idx: 0 }, '', '/login'));
  await page.reload();
  await page.waitForSelector('.v2-auth__card');
  await page.fill('input[autocomplete="username"]', 'admin');
  await page.fill('input[autocomplete="current-password"]', 'correct-horse');
  await page.evaluate(() => document.activeElement.blur());
  await page.evaluate(() => document.fonts.ready); await settle(page);
  await page.screenshot({ path: `${out}/login-expired-1440-light.png` });
  check(await page.getByText('Your session expired. Sign in again to pick up where you left off.').isVisible(), 'session-expired notice from router state');
  await c.close();
}
for (const theme of ['light', 'dark']) {
  const { c, page } = await ctx(resetUser, { viewport: { width: 1440, height: 860 }, theme });
  await page.goto(base + '/set-password');
  await page.waitForSelector('.v2-auth__card');
  await page.fill('input[name="new-password"]', 'harbour-lantern-42');
  await page.fill('input[name="confirm-password"]', 'harbour-lantern-4');
  await page.evaluate(() => document.activeElement.blur());
  await page.evaluate(() => document.fonts.ready); await settle(page);
  await page.screenshot({ path: `${out}/setpw-1440-${theme}.png` });
  await c.close();
}
{
  const { c, page } = await ctx(resetUser, { viewport: { width: 390, height: 844 } });
  await page.goto(base + '/set-password');
  await page.waitForSelector('.v2-auth__card'); await settle(page);
  await page.screenshot({ path: `${out}/setpw-390-light.png`, fullPage: true });
  const sw = await page.evaluate(() => document.documentElement.scrollWidth);
  check(sw <= 390, `set-password no horizontal scroll at 390 (${sw})`);
  await c.close();
}

// ---------- sign in ----------
{
  const { c, page, errors } = await ctx(null);
  const posts = [];
  let mode = 'wrong';
  await c.route('**/rest/public/auth/login', async (r) => {
    posts.push(JSON.parse(r.request().postData()));
    if (mode === 'down') return r.abort();
    if (mode === 'wrong') return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message: null, data: null }) });
    if (mode === 'http500') return r.fulfill({ status: 500, body: '' });
    return r.fulfill({ status: 200, contentType: 'application/json', body: ok(fx.user) });
  });
  await page.goto(base + '/login');
  await page.waitForSelector('.v2-auth__card');
  check(await page.evaluate(() => document.activeElement?.getAttribute('autocomplete')) === 'username', 'login field autofocused');
  await page.click('button[type=submit]');
  await settle(page);
  check(posts.length === 0, 'empty form is not sent');
  await page.screenshot({ path: `${out}/login-empty.png` });
  check(await page.getByText('Enter your login.').isVisible() && await page.getByText('Enter your password.').isVisible(), 'empty-field errors shown');
  check(await page.evaluate(() => document.activeElement?.getAttribute('autocomplete')) === 'username', 'focus moves to first empty field');
  await page.fill('input[autocomplete="username"]', 'admin');
  check(!(await page.getByText('Enter your login.').isVisible()), 'field error clears on typing');
  await page.fill('input[autocomplete="current-password"]', 'S3cret pass');
  await page.click('button[aria-label="Show password"]');
  check(await page.getAttribute('input[autocomplete="current-password"]', 'type') === 'text', 'show password reveals');
  await page.click('button[aria-label="Show password"]');
  check(await page.getAttribute('input[autocomplete="current-password"]', 'type') === 'password', 'hide password again');
  await page.press('input[autocomplete="current-password"]', 'Enter');
  await page.waitForSelector('[role=alert]');
  check(posts.length === 1 && posts[0].login === 'admin' && posts[0].password === md5u('S3cret pass') && Object.keys(posts[0]).length === 2, 'POST body {login, MD5 upper} like classic');
  check((await page.textContent('[role=alert]')).includes('Wrong login or password.'), 'wrong password → generic message');
  await page.screenshot({ path: `${out}/login-error.png` });
  mode = 'http500';
  await page.click('button[type=submit]'); await settle(page);
  check((await page.textContent('[role=alert]')).includes('Wrong login or password.'), 'HTTP 500 → same generic message (classic)');
  mode = 'down';
  await page.click('button[type=submit]'); await settle(page);
  check((await page.textContent('[role=alert]')).includes('Cannot reach the server'), 'network failure → cannot reach');
  check(await page.isEnabled('button[type=submit]'), 'button re-enabled after failure');
  mode = 'ok';
  await page.click('button[type=submit]');
  await page.waitForURL('**/');
  check(path(page) === '/', 'success without from → / (overview)');
  check(await page.evaluate(() => JSON.parse(localStorage.getItem('hmdm.admin.user'))?.login) === 'admin', 'stored user hint written');
  check(errors.filter((e) => !/status of 500|ERR_FAILED/.test(e)).length === 0, `no console errors besides the induced 500/abort (sign in) ${errors.join(' | ')}`);
  await c.close();
}

// ---------- from= handling (open redirect) ----------
const cases = [
  ['/devices?view=cards#x', '/devices?view=cards#x'],
  ['/devices/12', '/devices/12'],
  ['//evil.example/', '/'],
  ['/\\evil.example/', '/'],
  ['https://evil.example/', '/'],
  ['javascript:alert(1)', '/'],
  ['/\t/evil.example/', '/'],
  ['/\n/evil.example/', '/'],
  [' /devices', '/'],
  ['/v2/settings#updates', '/settings#updates'],
  ['/login?from=/x', '/'],
  ['/set-password', '/'],
  ['', '/'],
];
for (const [from, want] of cases) {
  const { c, page } = await ctx(null);
  await page.goto(`${base}/login?from=${encodeURIComponent(from)}`);
  await page.waitForSelector('.v2-auth__card');
  await page.fill('input[autocomplete="username"]', 'admin');
  await page.fill('input[autocomplete="current-password"]', 'pw');
  await page.click('button[type=submit]');
  await page.waitForFunction(() => !location.pathname.startsWith('/login'), null, { timeout: 5000 }).catch(() => {});
  await settle(page);
  const origin = new URL(page.url()).origin;
  check(origin === base && path(page) === want, `from=${JSON.stringify(from)} → ${path(page)} (want ${want})`);
  await c.close();
}

// ---------- ProtectedRoute ----------
{
  const { c, page } = await ctx(null);
  await page.goto(base + '/devices?view=cards');
  await page.waitForURL('**/login**');
  check(path(page) === '/login?from=%2Fdevices%3Fview%3Dcards', `v2 guard → ${path(page)}`);
  check(!(await page.getByText('Your session expired').isVisible()), 'no expiry notice on a plain signed-out visit');
  await page.goto(base + '/');
  await page.waitForURL('**/login');
  await page.waitForSelector('.v2-auth__card input[autocomplete="username"]', { timeout: 5000 }).catch(() => {});
  check(path(page) === '/login' && await page.locator('.v2-auth__card input[autocomplete="username"]').count() === 1, 'guard on / → /login without from');
  await page.goto(base + '/v2/devices/101?tab=x#y');
  await page.waitForURL('**/login**');
  check(path(page) === '/login?from=%2Fdevices%2F101%3Ftab%3Dx%23y', `old /v2 bookmark signed out → ${path(page)}`);
  await page.goto(base + '/dashboard');
  await page.waitForURL('**/login');
  check(path(page) === '/login', `old /dashboard signed out → ${path(page)}`);
  await c.close();
}
// ---------- old bookmarks: /v2/* and the v0.3 console's paths redirect (signed in) ----------
{
  const { c, page } = await ctx(fx.user);
  const legacy = [
    ['/v2', '/'],
    ['/v2/', '/'],
    ['/v2/overview', '/'],
    ['/v2/devices', '/devices'],
    ['/v2/devices/101?tab=location', '/devices/101?tab=location'],
    ['/v2/configurations/2', '/configurations/2'],
    ['/v2/apps?source=fdroid', '/apps?source=fdroid'],
    ['/v2/settings#agent-rollout', '/settings#agent-rollout'],
    ['/v2/login', '/login'],
    ['/dashboard', '/'],
    ['/configs', '/configurations'],
    ['/overview', '/'],
    ['/no-such-page', '/'],
    ['/v2//evil.example/x', '/'], // never a protocol-relative redirect
  ];
  for (const [from, want] of legacy) {
    await page.goto(base + from);
    await page.waitForSelector(want === '/login' ? '.v2-auth__card' : '.v2-shell');
    await page.waitForFunction((w) => location.pathname + location.search + location.hash === w, want, { timeout: 5000 }).catch(() => {});
    check(new URL(page.url()).origin === base && path(page) === want, `${from} → ${page.url()} (want ${want})`);
  }
  await c.close();
}
// session expiry (#64): API answers 401 on a signed-in v2 page
{
  const { c, page } = await ctx(fx.user);
  await page.goto(base + '/');
  await page.waitForSelector('.v2-shell'); await settle(page);
  await c.route('**/rest/**', (r) => r.fulfill({ status: 401, body: '' }));
  await page.goto(base + '/devices');
  await page.waitForURL('**/login**', { timeout: 8000 }).catch(() => {});
  check(path(page) === '/login?from=%2Fdevices', `expiry → ${path(page)}`);
  check(await page.getByText('Your session expired. Sign in again to pick up where you left off.').waitFor({ timeout: 5000 }).then(() => true, () => false), 'expiry notice shown');
  await c.unroute('**/rest/**');
  await c.route('**/rest/**', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: ok(fx.user) }));
  await page.fill('input[autocomplete="username"]', 'admin');
  await page.fill('input[autocomplete="current-password"]', 'pw');
  await page.click('button[type=submit]');
  await page.waitForURL('**/devices', { timeout: 5000 }).catch(() => {});
  check(path(page) === '/devices', 'returns to the page after re-sign-in');
  await c.close();
}
// sign out from the v2 sidebar
{
  const { c, page } = await ctx(fx.user);
  let loggedOut = false;
  await c.route('**/rest/public/auth/logout', (r) => { loggedOut = true; return r.fulfill({ status: 200, body: '' }); });
  await page.goto(base + '/apps');
  await page.waitForSelector('.v2-shell');
  await page.getByRole('button', { name: 'Sign out' }).first().click();
  await page.waitForURL('**/login**');
  check(loggedOut && path(page).startsWith('/login?from=%2Fapps'), `sign out → ${path(page)}`);
  check(await page.evaluate(() => localStorage.getItem('hmdm.admin.user')) === null, 'stored user cleared on sign out');
  await c.close();
}

// ---------- first-login flow ----------
{
  const { c, page, errors } = await ctx(null);
  const resets = []; let logouts = 0; let resetMode = 'fail';
  await c.route('**/rest/public/auth/login', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: ok(resetUser) }));
  await c.route('**/rest/public/auth/logout', (r) => { logouts++; return r.fulfill({ status: 200, body: '' }); });
  await c.route('**/rest/public/passwordReset/reset', (r) => {
    resets.push(JSON.parse(r.request().postData()));
    return resetMode === 'fail'
      ? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message: 'error.user.not.found', data: null }) })
      : r.fulfill({ status: 200, contentType: 'application/json', body: ok({}) });
  });
  await page.goto(`${base}/login?from=${encodeURIComponent('/settings')}`);
  await page.fill('input[autocomplete="username"]', 'admin');
  await page.fill('input[autocomplete="current-password"]', 'admin');
  await page.click('button[type=submit]');
  await page.waitForURL('**/set-password**');
  check(path(page) === '/set-password?from=%2Fsettings', `passwordReset → ${path(page)}`);
  await page.waitForSelector('.v2-rules');
  {
    const stored = JSON.parse(await page.evaluate(() => localStorage.getItem('hmdm.admin.user')) ?? 'null');
    check(stored?.passwordReset === true && !('passwordResetToken' in stored), 'reset token kept in memory, not in localStorage');
  }
  check((await page.textContent('.v2-auth__sub')).includes('admin'), 'subtitle names the account');
  const submit = page.locator('button[type=submit]');
  check(await submit.isDisabled(), 'Set password disabled while empty');
  await page.fill('input[name="new-password"]', 'short');
  check(await page.locator('.v2-rules li.is-alert').count() === 1, 'length rule fails for 5 chars');
  await page.fill('input[name="new-password"]', 'longenough1');
  await page.fill('input[name="confirm-password"]', 'longenough');
  check(await page.getAttribute('input[name="confirm-password"]', 'aria-invalid') === 'true', 'mismatch marks Type it again invalid');
  check(await submit.isDisabled(), 'disabled while mismatched');
  await page.fill('input[name="confirm-password"]', 'longenough1');
  check(await page.locator('.v2-rules li.is-ok').count() === 2 && await submit.isEnabled(), 'both rules met → enabled');
  check((await page.getAttribute('input[name="new-password"]', 'aria-describedby')) === 'v2-setpw-rules', 'inputs described by the rule list');
  await page.press('input[name="confirm-password"]', 'Enter');
  await page.waitForSelector('[role=alert]');
  check((await page.textContent('[role=alert]')).includes('Could not set the password. Please try again.'), 'server failure message');
  resetMode = 'ok';
  await submit.click();
  await page.waitForURL('**/login**');
  check(resets.at(-1).passwordResetToken === 'tok-123' && resets.at(-1).newPassword === md5u('longenough1'), 'reset body {token, MD5 upper} like classic');
  check(logouts === 1, 'signed out after the reset');
  check(path(page) === '/login?from=%2Fsettings', `after reset → ${path(page)}`);
  check(await page.getByText('Password updated. Sign in with your new password.').waitFor({ timeout: 5000 }).then(() => true, () => false), 'password-changed notice');
  check(errors.length === 0, `no console errors (first login) ${errors.join(' | ')}`);
  await c.close();
}
{
  const { c, page } = await ctx(fx.user);
  await page.goto(base + '/set-password');
  await page.waitForURL('**/login');
  check(path(page) === '/login', 'set-password without a flagged session → sign in');
  await c.close();
}
{
  const { c, page } = await ctx(resetUser);
  let logouts = 0;
  // An older console stored the token: it still works once, and the stored copy is rewritten without it.
  await page.goto(base + '/set-password');
  await page.waitForSelector('.v2-rules');
  {
    const stored = JSON.parse(await page.evaluate(() => localStorage.getItem('hmdm.admin.user')) ?? 'null');
    check(stored?.passwordReset === true && !('passwordResetToken' in stored), 'a stored reset token is honoured once, then scrubbed');
  }
  await c.route('**/rest/public/auth/logout', (r) => { logouts++; return r.fulfill({ status: 200, body: '' }); });
  await page.goto(base + '/set-password');
  await page.getByRole('link', { name: 'Back to sign in' }).click();
  await page.waitForURL('**/login');
  check(logouts === 1 && await page.evaluate(() => localStorage.getItem('hmdm.admin.user')) === null, 'Back to sign in signs out first');
  await c.close();
}
// footer: server unreachable
{
  const { c, page } = await ctx(null);
  await c.route('**/rest/public/auth/options', (r) => r.abort());
  await c.route('**/update/**', (r) => r.abort());
  await page.goto(base + '/login');
  await page.waitForSelector('.v2-auth-conn__dot.is-down');
  check(await page.getByText('Can’t reach the server').isVisible(), 'footer shows unreachable');
  check(!(await page.getByText(/^MDMesh /).isVisible().catch(() => false)), 'no version without data');
  await c.close();
}
{
  const { c, page } = await ctx(null);
  await page.goto(base + '/login');
  await page.waitForSelector('.v2-auth-conn__dot.is-ok');
  check(await page.getByText(`MDMesh ${fx.updateStatus.current}`).isVisible(), 'footer version from /update/status');
  await c.close();
}

await browser.close();
server.close();
console.log(fails ? `${fails} FAILED` : 'ALL PASS');
process.exit(fails ? 1 : 0);
