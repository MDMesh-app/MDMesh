// Runs the v2 parity tests: one script per page, each driving the built SPA against the shots fixtures and checking
// the behaviour the classic page has (every action, dialog, validation, empty/error state). Exit code 1 if any fails.
//
//   node scripts/shots/v2-parity/run.mjs                    # every page, against $TMPDIR/mdmesh-dist-v2 or web/dist
//   node scripts/shots/v2-parity/run.mjs --build            # build web/ first into a fresh temp dir (removed after)
//   node scripts/shots/v2-parity/run.mjs apps enroll        # some pages
//   node scripts/shots/v2-parity/run.mjs --dist <dir> [--out <dir>] [--verbose]
//   node scripts/shots/v2-parity/run.mjs --with-fixture extensions   # the extension slots only (below)
//
// --with-fixture adds the `extensions` test: it builds web/ with the test fixture extension (one contribution of
// every slot) into a temp dir of its own (../fixture-build.mjs; --fixture-dist <dir> reuses one), checks each slot in
// it, and checks that the build the other pages run against has none of the fixture. Without it nothing changes:
// the pages run against the normal build only, which never has the fixture.
// Tests run one after another (each starts its own static server and browser). --out keeps their screenshots
// (default: a temp dir per run). --verbose prints every check, not just the failing ones.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { DIST } from '../harness.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(__dirname, '../../../web');
const PAGES = ['overview', 'devices', 'device-detail', 'configurations', 'apps', 'enroll', 'settings', 'auth'];
/** Runs against a fixture build, only with --with-fixture. */
const FIXTURE_PAGE = 'extensions';
/** Text only the fixture extension has (web/src/ext/__fixtures__/demo): a normal build must hold none of it. */
const FIXTURE_MARKERS = ['Demo export', 'Saved under extensions.demo.'];

const argv = process.argv.slice(2);
const opt = (k) => {
  const i = argv.indexOf(k);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : undefined;
};
const valueArgs = new Set([opt('--dist'), opt('--out'), opt('--fixture-dist')].filter(Boolean));
const wanted = argv.filter((a) => !a.startsWith('--') && !valueArgs.has(a));
const withFixture = argv.includes('--with-fixture');
const unknown = wanted.filter((p) => !PAGES.includes(p) && p !== FIXTURE_PAGE);
if (unknown.length) {
  console.error(`unknown page(s): ${unknown.join(', ')}. Pages: ${PAGES.join(', ')}, and ${FIXTURE_PAGE} with --with-fixture`);
  process.exit(2);
}
if (wanted.includes(FIXTURE_PAGE) && !withFixture) {
  console.error(`${FIXTURE_PAGE} runs against a build with the fixture extension: pass --with-fixture`);
  process.exit(2);
}
const pages = wanted.length ? wanted.filter((p) => p !== FIXTURE_PAGE) : [...PAGES];
if (withFixture) pages.push(FIXTURE_PAGE);
const verbose = argv.includes('--verbose');
const tmp = process.env.TMPDIR || os.tmpdir();

let dist = opt('--dist');
let builtDir = null;
if (argv.includes('--build')) {
  builtDir = fs.mkdtempSync(path.join(tmp, 'v2-parity-dist-'));
  console.log(`building web/ into ${builtDir}…`);
  const r = spawnSync('npm', ['run', 'build', '--', '--outDir', builtDir, '--emptyOutDir'], { cwd: WEB, stdio: 'inherit' });
  if (r.status !== 0) process.exit(r.status ?? 1);
  dist = builtDir;
}
if (!dist) {
  const fallback = path.join(tmp, 'mdmesh-dist-v2');
  dist = fs.existsSync(path.join(fallback, 'index.html')) ? fallback : DIST;
}
if (!fs.existsSync(path.join(dist, 'index.html'))) {
  console.error(`${dist}/index.html not found: build first or pass --build.`);
  process.exit(1);
}
let fixtureDist = opt('--fixture-dist');
let fixtureBuiltDir = null;
if (withFixture && !fixtureDist) {
  fixtureBuiltDir = fs.mkdtempSync(path.join(tmp, 'v2-parity-fixture-dist-'));
  console.log(`building web/ with the fixture extension into ${fixtureBuiltDir}…`);
  const r = spawnSync(process.execPath, [path.join(__dirname, '../fixture-build.mjs'), fixtureBuiltDir], { stdio: 'inherit' });
  if (r.status !== 0) process.exit(r.status ?? 1);
  fixtureDist = fixtureBuiltDir;
}
const out = opt('--out') ?? fs.mkdtempSync(path.join(tmp, 'v2-parity-out-'));
const builtAt = fs.statSync(path.join(dist, 'index.html')).mtime;
console.log(`dist ${dist} (built ${builtAt.toISOString()}${builtDir ? '' : '; pass --build to test the current source'})\n` +
  `${fixtureDist ? `fixture dist ${fixtureDist}\n` : ''}out  ${out}\n`);

/** The files of a build that hold fixture-only text: none for a normal build. */
function fixtureFiles(dir) {
  const files = fs.readdirSync(dir, { recursive: true }).filter((f) => /\.(js|html|css)$/.test(f));
  return files.filter((f) => {
    const text = fs.readFileSync(path.join(dir, f), 'utf8');
    return FIXTURE_MARKERS.some((m) => text.includes(m));
  });
}

function runOne(page) {
  return new Promise((resolve) => {
    const started = Date.now();
    const outDir = path.join(out, page);
    fs.mkdirSync(outDir, { recursive: true });
    const served = page === FIXTURE_PAGE ? fixtureDist : dist;
    const child = spawn(process.execPath, [path.join(__dirname, `${page}.mjs`), '--dist', served, '--out', outDir], {
      cwd: path.resolve(__dirname, '../../..'),
      env: { ...process.env, V2_PARITY_DIST: served, V2_PARITY_OUT: outDir },
    });
    let text = '';
    child.stdout.on('data', (d) => (text += d));
    child.stderr.on('data', (d) => (text += d));
    child.on('close', (code) => {
      const lines = text.split('\n');
      const pass = lines.filter((l) => /^(PASS|ok)\b/.test(l.trim())).length;
      const fail = lines.filter((l) => /^(FAIL|not ok)\b/.test(l.trim())).length;
      resolve({ page, code, pass, fail, text, secs: ((Date.now() - started) / 1000).toFixed(0) });
    });
  });
}

const results = [];
if (withFixture) {
  // The fixture build has it, and the build the pages run against doesn't.
  const leaked = fixtureFiles(dist);
  const present = fixtureFiles(fixtureDist).length > 0;
  const code = leaked.length === 0 && present ? 0 : 1;
  const text = `${leaked.length ? `FAIL ${dist} holds fixture text in ${leaked.join(', ')}` : 'PASS the normal build has none of the fixture'}\n` +
    `${present ? 'PASS' : 'FAIL'} the fixture build has it`;
  results.push({ page: 'no-fixture', code, pass: code ? 0 : 2, fail: code, text });
  console.log(`${code ? 'FAIL' : 'PASS'}  ${'no-fixture'.padEnd(15)} ${dist} has none of the fixture, the fixture build has it`);
  if (code || verbose) console.log(text.replace(/^/gm, '    ') + '\n');
}
for (const page of pages) {
  const r = await runOne(page);
  results.push(r);
  const status = r.code === 0 ? 'PASS' : 'FAIL';
  console.log(`${status}  ${page.padEnd(15)} ${String(r.pass).padStart(3)} passed, ${r.fail} failed  (${r.secs}s)`);
  if (verbose || r.code !== 0) {
    const shown = verbose ? r.text : r.text.split('\n').filter((l) => !/^\s*(PASS|ok)\b/.test(l)).join('\n');
    console.log(shown.replace(/^/gm, '    ').trimEnd() + '\n');
  }
}
if (builtDir) fs.rmSync(builtDir, { recursive: true, force: true });
if (fixtureBuiltDir) fs.rmSync(fixtureBuiltDir, { recursive: true, force: true });
const failed = results.filter((r) => r.code !== 0);
console.log(`\n${results.length - failed.length}/${results.length} pages passed${failed.length ? ` (failed: ${failed.map((r) => r.page).join(', ')})` : ''}`);
process.exit(failed.length ? 1 : 0);
