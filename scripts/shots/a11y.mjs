// Accessibility sweep of the REAL built console against the shot fixtures: axe-core (WCAG 2.0/2.1/2.2 A + AA rules) on
// every route and the key states (dialogs and menus open, validation errors, empty and error answers, device tabs), in
// the light and dark themes at 1280 and 320 CSS px; plus reflow (no horizontal scroll at 320), page titles, small
// targets and a keyboard walk (Tab order, visible and unobscured focus, Escape and focus return in dialogs). The walk
// runs in both themes and every viewport, and Tabs through each page until focus wraps to its first stop (at most
// TAB_CAP presses).
//
//   node scripts/shots/a11y.mjs [--dist <dir>] [--out <dir>] [--only <substring>] [--quick] [--keys] [--shots]
//
// --quick: light theme at 1280 only. --keys: also the keyboard walk. --shots: a screenshot per state.
// Writes <out>/axe.json, <out>/summary.txt, <out>/failures.txt (and keys.json, aria/*.yml with --keys) and exits 1 on
// any failed check (see failures(): axe, setup, horizontal scroll, text spacing, page errors, keyboard).
// The build must exist (`npm run build` in web/; VITE_GALLERY=1 adds the component gallery to the sweep).
import { chromium } from 'playwright';
import { AxeBuilder } from '@axe-core/playwright';
import fs from 'node:fs';
import path from 'node:path';
import { DIST, fx, newContext, startServer } from './harness.mjs';

const arg = (k) => {
  const i = process.argv.indexOf(k);
  return i > 0 ? process.argv[i + 1] : undefined;
};
const flag = (k) => process.argv.includes(k);
const dist = arg('--dist') ?? DIST;
const out = path.resolve(arg('--out') ?? 'a11y-out');
const only = arg('--only');
fs.mkdirSync(out, { recursive: true });

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];
const env = (data, status = 'OK', message = null) => JSON.stringify({ status, message, data });

const SIGNED_OUT = null;
const RESET = { ...fx.user, passwordReset: true, passwordResetToken: 'a11y-token' };

const dialog = (page) => page.locator('dialog[open]').last();
/** Users & roles answers: the section shows only when the server says it manages users. */
const ROLE = { id: 2, name: 'Admin', superAdmin: false, permissions: [] };
const USERS = {
  '**/rest/private/users/management': () => env({ users: true, roles: true }),
  '**/rest/private/users/all': () => env([{ id: 1, login: 'admin', name: 'Operations', userRole: ROLE, allDevicesAvailable: true, allConfigAvailable: true, groups: [], configurations: [], editable: true }]),
  '**/rest/private/users/roles': () => env([ROLE]),
  '**/rest/private/roles/all': () => env([ROLE]),
  '**/rest/private/roles/permissions': () => env([]),
};
const click = async (page, role, name, opts = {}) => {
  await page.getByRole(role, { name, ...opts }).first().click();
  await page.waitForTimeout(350);
};

/**
 * The states: a route, who is signed in, optional REST overrides and a setup that drives the page into the state.
 * `mobile: false` skips it in the phone layout (≤ 860 px), `desktop: false` above it; `gallery` needs a VITE_GALLERY=1 build.
 */
const STATES = [
  // ---- signed out ----
  { name: 'login', route: '/login', user: SIGNED_OUT },
  {
    name: 'login-errors',
    route: '/login',
    user: SIGNED_OUT,
    setup: async (p) => {
      await p.click('button[type=submit]');
      await p.waitForTimeout(300);
    },
  },
  {
    name: 'login-wrong-password',
    route: '/login',
    user: SIGNED_OUT,
    rest: { '**/rest/public/auth/login': () => env(null, 'ERROR', 'error.login') },
    setup: async (p) => {
      await p.fill('input[autocomplete=username]', 'admin');
      await p.fill('input[autocomplete=current-password]', 'nope');
      await p.click('button[type=submit]');
      await p.waitForTimeout(500);
    },
  },
  { name: 'set-password', route: '/set-password', user: RESET },
  {
    name: 'set-password-errors',
    route: '/set-password',
    user: RESET,
    setup: async (p) => {
      const inputs = p.locator('input[autocomplete=new-password]');
      await inputs.nth(0).fill('short');
      await inputs.nth(1).fill('different');
      await inputs.nth(1).blur();
      await p.waitForTimeout(300);
    },
  },
  // ---- overview ----
  { name: 'overview', route: '/' },
  {
    name: 'overview-empty',
    route: '/',
    rest: { '**/rest/private/devices/search': () => env({ configurations: [], devices: { items: [], totalItemsCount: 0 } }) },
  },
  {
    name: 'overview-error',
    route: '/',
    rest: { '**/rest/private/devices/search': () => env(null, 'ERROR', 'error.internal') },
  },
  // ---- devices ----
  { name: 'devices', route: '/devices' },
  { name: 'devices-cards', route: '/devices?view=cards' },
  {
    name: 'devices-empty',
    route: '/devices',
    rest: { '**/rest/private/devices/search': () => env({ configurations: [], devices: { items: [], totalItemsCount: 0 } }) },
  },
  {
    name: 'devices-error',
    route: '/devices',
    rest: { '**/rest/private/devices/search': () => env(null, 'ERROR', 'error.internal') },
  },
  {
    name: 'devices-row-menu',
    route: '/devices',
    setup: (p) => click(p, 'button', 'More actions for KIOSK-FRONT'),
  },
  {
    name: 'devices-delete-dialog',
    route: '/devices',
    setup: async (p) => {
      await click(p, 'button', 'More actions for KIOSK-FRONT');
      await click(p, 'menuitem', 'Delete…');
    },
  },
  {
    name: 'devices-bulk-modal',
    route: '/devices',
    setup: async (p) => {
      await p.locator('input[type=checkbox][aria-label^="Select "]').nth(1).check();
      await p.waitForTimeout(200);
      await click(p, 'button', 'Run action');
    },
  },
  // ---- device detail ----
  { name: 'device', route: '/devices/WH-SCAN-07' },
  { name: 'device-tab-control', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'tab', 'Control') },
  { name: 'device-tab-apps', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'tab', /^Apps/) },
  { name: 'device-tab-location', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'tab', 'Location') },
  { name: 'device-tab-telemetry', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'tab', 'Telemetry') },
  { name: 'device-tab-activity', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'tab', /Activity/) },
  { name: 'device-rename', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'button', 'Rename device') },
  { name: 'device-alert-dialog', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'button', 'Send alert…') },
  { name: 'device-factory-reset', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'button', 'Factory reset…') },
  { name: 'device-more-menu', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'button', 'More actions') },
  { name: 'device-kiosk-dialog', route: '/devices/WH-SCAN-07', setup: (p) => click(p, 'button', 'Custom kiosk…') },
  { name: 'device-not-found', route: '/devices/NOPE' },
  // ---- configurations ----
  { name: 'configurations', route: '/configurations' },
  {
    name: 'configurations-menu',
    route: '/configurations',
    setup: (p) => click(p, 'button', 'More for Field Ops'),
  },
  {
    name: 'configurations-new-dialog',
    route: '/configurations',
    setup: (p) => click(p, 'button', 'New configuration'),
  },
  {
    name: 'configurations-delete-dialog',
    route: '/configurations',
    setup: async (p) => {
      await click(p, 'button', 'More for Field Ops');
      await click(p, 'menuitem', /Delete/);
    },
  },
  { name: 'config-editor', route: '/configurations/1' },
  {
    name: 'config-editor-dirty',
    route: '/configurations/1',
    setup: async (p) => {
      await p.getByRole('radiogroup', { name: 'Bluetooth' }).getByRole('radio', { name: 'Block' }).click();
      await p.getByRole('region', { name: 'Unsaved changes' }).waitFor({ timeout: 5000 });
    },
  },
  {
    name: 'config-editor-add-apps',
    route: '/configurations/1',
    setup: (p) => click(p, 'button', 'Add apps'),
  },
  {
    name: 'config-editor-leave-guard',
    route: '/configurations/1',
    mobile: false,
    setup: async (p) => {
      await p.getByRole('radiogroup', { name: 'Bluetooth' }).getByRole('radio', { name: 'Block' }).click();
      await p.getByRole('region', { name: 'Unsaved changes' }).waitFor({ timeout: 5000 });
      await p.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: /Devices/ }).click();
      await p.locator('dialog[open]').waitFor({ timeout: 5000 });
    },
  },
  // ---- apps ----
  { name: 'apps-library', route: '/apps' },
  { name: 'apps-custom', route: '/apps?source=custom' },
  { name: 'apps-fdroid', route: '/apps?source=fdroid' },
  { name: 'apps-deploy-dialog', route: '/apps', setup: (p) => click(p, 'button', 'Deploy Chrome') },
  { name: 'apps-checksum-dialog', route: '/apps', setup: (p) => click(p, 'button', 'Add checksum') },
  {
    name: 'apps-custom-errors',
    route: '/apps?source=custom',
    setup: async (p) => {
      await p.getByRole('radio', { name: 'Use a link' }).click();
      await p.waitForTimeout(200);
      await p.getByLabel('Link to the APK').fill('not a link');
      await p.getByLabel('Link to the APK').blur();
      await p.waitForTimeout(300);
    },
  },
  // ---- enroll ----
  { name: 'enroll', route: '/enroll' },
  { name: 'enroll-token', route: '/enroll', setup: (p) => click(p, 'tab', 'Token') },
  { name: 'enroll-fullscreen', route: '/enroll', setup: (p) => click(p, 'button', 'Full screen') },
  {
    // Devices enrolled while the page is open, one in each state (#188): setting up, online, silent, offline.
    name: 'enroll-arrivals',
    route: '/enroll',
    rest: {
      '**/rest/private/agent/v1/devices/enrolled*': () => {
        const t = Date.now();
        const server = t + 15 * 60_000;
        return env({
          serverTime: server,
          devices: [
            { number: 'WH-SCAN-21', description: 'Dock 4 scanner', enrollTime: server - 2 * 60_000, lastUpdate: server - 2 * 60_000 },
            { number: 'WH-SCAN-20', enrollTime: server - 5 * 60_000, lastUpdate: server - 30_000 },
            { number: 'WH-SCAN-19', enrollTime: t, lastUpdate: t },
            { number: 'WH-SCAN-18', enrollTime: t, lastUpdate: t + 60_000 },
          ],
        });
      },
    },
    setup: (p) => p.getByRole('list', { name: 'Enrolled while this page is open' }).getByRole('link').nth(3).waitFor({ timeout: 8000 }),
  },
  {
    // A Wi-Fi the device can't join (#188): left out of the code, with the reason.
    name: 'enroll-wifi-issue',
    route: '/enroll',
    setup: async (p) => {
      const sw = p.getByRole('switch', { name: 'Join Wi-Fi during setup' });
      if ((await sw.getAttribute('aria-checked')) !== 'true') await sw.click();
      await p.getByLabel('Network name').fill('Office');
      await p.getByLabel('Security').selectOption('WPA');
      await p.getByLabel('Wi-Fi password').fill('short');
      await p.locator('#v2-enroll-wifi-issue').waitFor({ timeout: 5000 });
    },
  },
  // ---- settings ----
  { name: 'settings', route: '/settings' },
  { name: 'settings-change-password', route: '/settings', setup: (p) => click(p, 'button', /Change password/) },
  {
    name: 'settings-change-password-errors',
    route: '/settings',
    setup: async (p) => {
      await click(p, 'button', /Change password/);
      const pw = dialog(p).locator('input[type=password]');
      await pw.nth(1).fill('short');
      await pw.nth(2).fill('different');
      await pw.nth(2).blur();
      await p.waitForTimeout(300);
    },
  },
  {
    name: 'settings-new-user',
    route: '/settings',
    rest: USERS,
    setup: async (p) => {
      await p.locator('#users button:has-text("New user"), button:has-text("New user")').first().click();
      await p.waitForTimeout(400);
    },
  },
  {
    name: 'settings-rollout-dialog',
    route: '/settings',
    setup: async (p) => {
      await p.locator('#agent-rollout button:has-text("Cancel rollout")').click();
      await p.waitForTimeout(400);
    },
  },
  {
    name: 'settings-update-dialog',
    route: '/settings',
    // Only an admin who runs the install may apply an update (v0.3.8).
    user: { ...fx.user, deploymentAdmin: true },
    setup: async (p) => {
      await p.locator('#updates button:has-text("Update server and console")').click();
      await p.waitForTimeout(400);
    },
  },
  // ---- shell ----
  {
    name: 'mobile-drawer',
    route: '/devices',
    desktop: false,
    setup: (p) => click(p, 'button', 'Open navigation'),
  },
  { name: 'gallery', route: '/_gallery', gallery: true },
];

const VIEWPORTS = flag('--quick')
  ? [{ name: '1280', width: 1280, height: 800 }]
  : [
      { name: '1280', width: 1280, height: 800 },
      { name: '640', width: 640, height: 800, lightOnly: true }, // 1280 at 200% (1.4.4)
      { name: '320', width: 320, height: 640 }, // 1280 at 400% (1.4.10)
    ];
const THEMES = flag('--quick') ? ['light'] : ['light', 'dark'];

async function openState(browser, base, state, theme, vp) {
  const user = 'user' in state ? state.user : fx.user;
  const context = await newContext(browser, user, {
    theme,
    viewport: { width: vp.width, height: vp.height },
    deviceScaleFactor: 1,
    blockGoogleFonts: true,
  });
  for (const [pattern, body] of Object.entries(state.rest ?? {})) {
    await context.route(pattern, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: body(route) }));
  }
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(base + state.route, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(900);
  let setupError = null;
  if (state.setup) {
    try {
      await state.setup(page);
    } catch (e) {
      setupError = String(e.message).split('\n')[0];
    }
  }
  return { context, page, errors, setupError };
}

/** Facts axe doesn't check: horizontal scroll, title, lang, small targets, focusable things with no name. */
async function pageFacts(page) {
  return page.evaluate(() => {
    const doc = document.documentElement;
    const interactive = Array.from(
      document.querySelectorAll('a[href], button, input:not([type=hidden]), select, textarea, [role=button], [role=tab], [role=menuitem], [role=radio], [role=switch], [role=checkbox]'),
    ).filter((el) => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && !el.closest('[inert]');
    });
    const describe = (el) => {
      const label = el.getAttribute('aria-label') || el.textContent?.trim().slice(0, 40) || el.getAttribute('name') || '';
      return `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? '.' + el.className.split(' ')[0] : ''} "${label}"`;
    };
    // 2.5.8: under 24×24 (inline links in text are exempt: skip <a> inside a <p>/<li> text run).
    const small = interactive
      .filter((el) => !(el.tagName === 'A' && el.closest('p, li, dd, td') && !el.className))
      .map((el) => ({ el, r: el.getBoundingClientRect() }))
      .filter(({ r }) => r.width < 24 || r.height < 24)
      .map(({ el, r }) => `${describe(el)} ${Math.round(r.width)}×${Math.round(r.height)}`);
    const under44 = interactive.filter((el) => {
      const r = el.getBoundingClientRect();
      return r.width < 44 || r.height < 44;
    }).length;
    // Content wider than the viewport, and which elements stick out.
    const overflowX = doc.scrollWidth > doc.clientWidth + 1;
    const wide = overflowX
      ? Array.from(document.querySelectorAll('body *'))
          .filter((el) => el.getBoundingClientRect().right > doc.clientWidth + 1 && !el.closest('[inert]'))
          .slice(0, 6)
          .map(describe)
      : [];
    return {
      title: document.title,
      lang: doc.lang,
      h1: Array.from(document.querySelectorAll('h1')).map((h) => h.textContent?.trim()),
      overflowX,
      scrollWidth: doc.scrollWidth,
      wide,
      small: small.slice(0, 30),
      smallCount: small.length,
      under44,
      interactive: interactive.length,
      liveRegions: document.querySelectorAll('[aria-live], [role=status], [role=alert]').length,
    };
  });
}

/** 1.4.12: the bookmarklet's spacing, then any element whose text no longer fits a box that hides overflow. */
async function textSpacing(page) {
  await page.addStyleTag({
    content: '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }',
  });
  await page.waitForTimeout(150);
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('body *'))
      .filter((el) => {
        if (!el.childNodes.length || el.closest('[inert], .v2-visually-hidden, svg')) return false;
        const hasText = Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim());
        if (!hasText) return false;
        const s = getComputedStyle(el);
        const clipsX = /hidden|clip/.test(s.overflowX) && el.scrollWidth > el.clientWidth + 1 && s.textOverflow !== 'ellipsis';
        const clipsY = /hidden|clip/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 1 && !s.webkitLineClamp?.match(/\d/);
        return clipsX || clipsY;
      })
      .slice(0, 10)
      .map((el) => `${el.tagName.toLowerCase()}.${String(el.className).split(' ')[0]} "${el.textContent.trim().slice(0, 30)}"`),
  );
}

async function sweep(browser, base) {
  const results = [];
  for (const theme of THEMES) {
    for (const vp of VIEWPORTS) {
      for (const state of STATES) {
        if (only && !state.name.includes(only)) continue;
        if (vp.lightOnly && theme !== 'light') continue;
        // The shell's phone layout (drawer, top bar) starts at 860px.
        if (vp.width <= 860 && state.mobile === false) continue;
        if (vp.width > 860 && state.desktop === false) continue;
        const { context, page, errors, setupError } = await openState(browser, base, state, theme, vp);
        if (state.gallery && !(await page.locator('h1').count())) {
          await context.close();
          continue;
        }
        let violations = [];
        try {
          const res = await new AxeBuilder({ page }).withTags(TAGS).analyze();
          violations = res.violations.map((v) => ({
            id: v.id,
            impact: v.impact,
            tags: v.tags.filter((t) => /^wcag\d/.test(t)),
            help: v.help,
            nodes: v.nodes.map((n) => ({ target: n.target.join(' '), summary: n.failureSummary?.split('\n').slice(1, 3).join(' ') })),
          }));
        } catch (e) {
          violations = [{ id: 'axe-error', impact: 'n/a', tags: [], help: String(e.message), nodes: [] }];
        }
        const facts = await pageFacts(page);
        if (flag('--shots')) {
          fs.mkdirSync(path.join(out, 'shots'), { recursive: true });
          await page.screenshot({ path: path.join(out, 'shots', `${state.name}-${theme}-${vp.name}.png`), fullPage: vp.width < 600 });
        }
        if (theme === 'light' && vp.name !== '640') facts.spacingClipped = await textSpacing(page);
        results.push({ state: state.name, route: state.route, theme, viewport: vp.name, setupError, pageErrors: errors, violations, facts });
        const n = violations.reduce((s, v) => s + v.nodes.length, 0);
        console.log(`${n ? 'FAIL' : 'ok  '} ${state.name.padEnd(34)} ${theme.padEnd(5)} ${vp.name.padStart(4)}  ${violations.map((v) => `${v.id}×${v.nodes.length}`).join(' ')}${setupError ? `  [setup: ${setupError}]` : ''}${facts.overflowX ? '  [h-scroll]' : ''}`);
        await context.close();
      }
    }
  }
  return results;
}

/**
 * Most Tab presses per page in the keyboard walk. A walk ends when focus comes back to its first stop, so this only
 * cuts a page longer than any today (the config editor is the longest); a cut page says so in the log.
 */
const TAB_CAP = 400;

/**
 * Tab through a page, in each theme and viewport: what gets focus, in what order, whether the focus indicator shows
 * and is not covered.
 */
async function keyboardWalk(browser, base) {
  const walks = [];
  const routes = [
    ['login', '/login', SIGNED_OUT],
    ['overview', '/', fx.user],
    ['devices', '/devices', fx.user],
    ['device', '/devices/WH-SCAN-07', fx.user],
    ['configurations', '/configurations', fx.user],
    ['config-editor', '/configurations/1', fx.user],
    ['apps', '/apps', fx.user],
    ['enroll', '/enroll', fx.user],
    ['settings', '/settings', fx.user],
  ];
  fs.mkdirSync(path.join(out, 'aria'), { recursive: true });
  fs.mkdirSync(path.join(out, 'focus'), { recursive: true });
  for (const theme of THEMES) for (const vp of VIEWPORTS) {
    if (vp.lightOnly && theme !== 'light') continue;
    for (const [name, route, user] of routes) {
      if (only && !name.includes(only)) continue;
      const { context, page } = await openState(browser, base, { route, user }, theme, vp);
      if (vp.name === '1280' && theme === 'light') fs.writeFileSync(path.join(out, 'aria', `${name}.yml`), await page.locator('body').ariaSnapshot());
      await page.evaluate(() => document.activeElement instanceof HTMLElement && document.activeElement.blur());
      const steps = [];
      let wrapped = false;
      for (let i = 0; i < TAB_CAP; i++) {
        await page.keyboard.press('Tab');
        const info = await page.evaluate(() => {
          const el = document.activeElement;
          if (!el || el === document.body) return null;
          // Back at the first stop: the whole page has been walked.
          if (window.__a11yFirstStop === el) return { wrapped: true };
          window.__a11yFirstStop ??= el;
          const s = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          const cx = Math.min(Math.max(r.left + r.width / 2, 0), innerWidth - 1);
          const cy = Math.min(Math.max(r.top + r.height / 2, 0), innerHeight - 1);
          const top = document.elementFromPoint(cx, cy);
          const covered = !!top && top !== el && !el.contains(top) && !top.contains(el);
          const ring =
            (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) >= 1) ||
            (s.boxShadow && s.boxShadow !== 'none') ||
            (() => {
              const box = el.parentElement?.closest('.v2-search, .v2-lselect');
              if (!box) return false;
              const b = getComputedStyle(box);
              return b.outlineStyle !== 'none' && parseFloat(b.outlineWidth) >= 1;
            })();
          const name = (el.getAttribute('aria-label') || el.textContent || el.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 50);
          return {
            tag: el.tagName.toLowerCase(),
            role: el.getAttribute('role') || '',
            name,
            visibleRing: ring,
            inView: r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth,
            covered,
            coveredBy: covered ? `${top.tagName.toLowerCase()}.${String(top.className).split(' ')[0]}` : '',
          };
        });
        if (!info) {
          steps.push({ tag: 'body' });
          continue;
        }
        if (info.wrapped) {
          wrapped = true;
          break;
        }
        steps.push(info);
        if (steps.length > 3 && steps.length % 25 === 0) {
          await page.screenshot({ path: path.join(out, 'focus', `${name}-${theme}-${vp.name}-${steps.length}.png`) });
        }
      }
      walks.push({ page: name, theme, viewport: vp.name, steps, wrapped });
      const bad = steps.filter((s) => s.tag !== 'body' && (!s.visibleRing || s.covered || !s.inView));
      console.log(`keys ${name.padEnd(16)} ${theme.padEnd(5)} ${vp.name}: ${steps.length} stops${wrapped ? '' : ` (cut at ${TAB_CAP})`}, ${bad.length} without a visible/unobscured ring`);
      await context.close();
    }
  }

  // Dialogs, menus and the inline rename: the trigger opens it with focus inside, Escape closes it and focus returns
  // to the trigger. `open` is what the trigger opens (a dialog or menu unless said).
  const POPUP = 'dialog[open], [role=menu]';
  const dialogs = [
    ['/devices/WH-SCAN-07', 'button', 'Rename device', 'form.v2-device-rename'],
    ['/devices/WH-SCAN-07', 'button', 'Send alert…'],
    ['/devices', 'button', 'More actions for KIOSK-FRONT'],
    ['/configurations', 'button', 'New configuration'],
    ['/apps', 'button', 'Deploy Chrome'],
    ['/enroll', 'button', 'Full screen'],
    ['/settings', 'button', /Change password/],
  ];
  const escapes = [];
  for (const [route, role, label, open = POPUP] of dialogs) {
    const { context, page } = await openState(browser, base, { route }, 'light', VIEWPORTS[0]);
    const trigger = page.getByRole(role, { name: label }).first();
    let result;
    try {
      await trigger.focus();
      await page.keyboard.press('Enter');
      await page.waitForTimeout(400);
      const opened = await page.evaluate((sel) => !!document.querySelector(sel), open);
      const focusInside = await page.evaluate((sel) => !!document.activeElement?.closest(sel), open);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(400);
      const closed = await page.evaluate((sel) => !document.querySelector(sel), open);
      const returned = await trigger.evaluate((el) => el === document.activeElement);
      result = { route, label: String(label), opened, focusInside, closed, returned };
    } catch (e) {
      result = { route, label: String(label), error: String(e.message).split('\n')[0] };
    }
    escapes.push(result);
    console.log('esc', JSON.stringify(result));
    await context.close();
  }
  return { walks, escapes };
}

function summarize(results) {
  const byRule = new Map();
  for (const r of results) {
    for (const v of r.violations) {
      const k = v.id;
      const e = byRule.get(k) ?? { id: k, impact: v.impact, tags: v.tags, help: v.help, states: new Set(), nodes: 0, targets: new Set() };
      e.states.add(`${r.state}/${r.theme}/${r.viewport}`);
      e.nodes += v.nodes.length;
      for (const n of v.nodes) e.targets.add(n.target);
      byRule.set(k, e);
    }
  }
  const lines = [];
  for (const e of [...byRule.values()].sort((a, b) => b.nodes - a.nodes)) {
    lines.push(`${e.id} [${e.impact}] ${e.tags.join(',')} — ${e.help}: ${e.nodes} nodes in ${e.states.size} states`);
    for (const t of [...e.targets].slice(0, 12)) lines.push(`    ${t}`);
  }
  const hscroll = results.filter((r) => r.facts.overflowX).map((r) => `${r.state}/${r.theme}/${r.viewport} (${r.facts.scrollWidth}px: ${r.facts.wide.join('; ')})`);
  const titles = [...new Set(results.map((r) => `${r.route} -> "${r.facts.title}"`))];
  const small = results.filter((r) => r.theme === 'light' && r.facts.smallCount).map((r) => `${r.state}/${r.viewport}: ${r.facts.smallCount} — ${r.facts.small.slice(0, 8).join(', ')}`);
  const spacing = results.filter((r) => r.facts.spacingClipped?.length).map((r) => `${r.state}/${r.viewport}: ${r.facts.spacingClipped.join(', ')}`);
  const setup = results.filter((r) => r.setupError).map((r) => `${r.state}/${r.theme}/${r.viewport}: ${r.setupError}`);
  return [
    `states run: ${results.length}`,
    `axe violations: ${[...byRule.values()].reduce((s, e) => s + e.nodes, 0)} nodes, ${byRule.size} rules`,
    '',
    ...lines,
    '',
    'horizontal scroll:',
    ...hscroll.map((l) => '  ' + l),
    '',
    'titles:',
    ...titles.map((l) => '  ' + l),
    '',
    'targets under 24×24:',
    ...small.map((l) => '  ' + l),
    '',
    'text spacing (1.4.12) clipped:',
    ...spacing.map((l) => '  ' + l),
    '',
    'setup errors:',
    ...setup.map((l) => '  ' + l),
  ].join('\n');
}

/**
 * Known, accepted exceptions to the checks below, as `<state>/<theme>/<viewport>: <check>` (none today). An entry
 * names the state precisely, so a new failure elsewhere still fails the run.
 */
const EXCEPTIONS = new Set([]);

/**
 * Everything that fails the run: axe violations; a state whose setup failed (never checked); and the layout and
 * keyboard checks the sweep measures: horizontal scroll (1.4.10), text clipped by WCAG text spacing (1.4.12), an
 * uncaught page error, and with --keys a Tab stop without a visible, in-view, uncovered ring (2.4.7/2.4.11) or a
 * dialog/menu/inline editor whose trigger doesn't open it with focus inside, that Escape doesn't close, or that
 * doesn't return focus to its trigger.
 */
function failures(results, keys) {
  const out = [];
  const add = (where, check) => {
    if (!EXCEPTIONS.has(`${where}: ${check}`)) out.push(`${where}: ${check}`);
  };
  for (const r of results) {
    const where = `${r.state}/${r.theme}/${r.viewport}`;
    for (const v of r.violations) add(where, `axe ${v.id} ×${v.nodes.length}`);
    if (r.setupError) add(where, `setup failed: ${r.setupError}`);
    if (r.facts.overflowX) add(where, `horizontal scroll (${r.facts.scrollWidth}px)`);
    if (r.facts.spacingClipped?.length) add(where, `text spacing clips: ${r.facts.spacingClipped.join(', ')}`);
    for (const e of r.pageErrors) add(where, `page error: ${e}`);
  }
  for (const w of keys?.walks ?? []) {
    const bad = w.steps.filter((s) => s.tag !== 'body' && (!s.visibleRing || s.covered || !s.inView));
    for (const s of bad) add(`keys ${w.page}/${w.theme}/${w.viewport}`, `focus not visible or covered: ${s.tag} "${s.name}"`);
  }
  for (const e of keys?.escapes ?? []) {
    const ok = !e.error && e.opened && e.focusInside && e.closed && e.returned;
    if (!ok) {
      const facts = `opened=${e.opened} focusInside=${e.focusInside} closed=${e.closed} returned=${e.returned}`;
      add(`esc ${e.route}`, `${e.label}: ${e.error ?? facts}`);
    }
  }
  return out;
}

const { server, base } = await startServer(dist);
const browser = await chromium.launch();
try {
  const results = await sweep(browser, base);
  fs.writeFileSync(path.join(out, 'axe.json'), JSON.stringify(results, null, 1));
  const summary = summarize(results);
  fs.writeFileSync(path.join(out, 'summary.txt'), summary);
  let keys = null;
  if (flag('--keys')) {
    keys = await keyboardWalk(browser, base);
    fs.writeFileSync(path.join(out, 'keys.json'), JSON.stringify(keys, null, 1));
  }
  const problems = failures(results, keys);
  fs.writeFileSync(path.join(out, 'failures.txt'), problems.join('\n'));
  console.log('\n' + summary.split('\n').slice(0, 3).join('\n') + `\n→ ${out}`);
  if (problems.length) console.error(`\n${problems.length} failed checks:\n` + problems.map((l) => '  ' + l).join('\n'));
  process.exitCode = problems.length ? 1 : 0;
} finally {
  await browser.close();
  server.close();
}
