// Builds web/ with the test fixture extension (web/src/ext/__fixtures__/demo, one contribution of every slot) found
// as if it were a fork's web/src/extensions/demo/, into a directory of its own. Nothing in web/src changes: a Vite
// plugin of this build only adds the fixtures' folder to ext/load.ts's glob, so `npm run build` never has them.
//
//   node scripts/shots/fixture-build.mjs <outDir>
//
// v2-parity/run.mjs --with-fixture builds with it, and v2-parity/extensions.mjs checks each slot in the result.
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(__dirname, '../../web');

// ext/load.ts's discovery, as written there. If it changes, this build says so instead of building without the fixture.
const GLOB = "import.meta.glob<{ default?: unknown }>('../extensions/*/index.{ts,tsx}', { eager: true })";
// The fixture folders join the glob under the path a fork's folder would have, so load.ts reads their ids from
// their folder names as it does for extensions/<id>/.
const WITH_FIXTURES =
  "Object.fromEntries(Object.entries(import.meta.glob<{ default?: unknown }>(['../extensions/*/index.{ts,tsx}', " +
  "'./__fixtures__/*/index.ts'], { eager: true })).map(([p, m]) => [p.replace(/^\\.\\/__fixtures__\\//, '../extensions/'), m]))";

// One per build: it fails the build when ext/load.ts was never transformed (renamed or moved), which would otherwise
// build without the fixture and say nothing.
function fixturePlugin() {
  let extended = false;
  return {
    name: 'mdmesh-shots-fixture-extensions',
    enforce: 'pre',
    transform(code, id) {
      if (!id.split('?')[0].endsWith('/src/ext/load.ts')) return null;
      if (!code.includes(GLOB)) throw new Error('fixture-build: web/src/ext/load.ts no longer holds the glob this build extends');
      extended = true;
      return code.replace(GLOB, WITH_FIXTURES);
    },
    buildEnd(error) {
      if (!error && !extended) throw new Error('fixture-build: the build never loaded web/src/ext/load.ts, so it has no fixture');
    },
  };
}

export async function buildWithFixture(outDir) {
  const vite = await import(pathToFileURL(path.join(WEB, 'node_modules/vite/dist/node/index.js')).href);
  // As `npm run build` runs it: from web/, with web/vite.config.ts (and any web/.env).
  process.chdir(WEB);
  await vite.build({
    root: WEB,
    configFile: path.join(WEB, 'vite.config.ts'),
    plugins: [fixturePlugin()],
    logLevel: 'warn',
    build: { outDir, emptyOutDir: true },
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const outDir = process.argv[2];
  if (!outDir) {
    console.error('usage: node scripts/shots/fixture-build.mjs <outDir>');
    process.exit(2);
  }
  await buildWithFixture(path.resolve(outDir));
  console.log(`built web/ with the fixture extension into ${path.resolve(outDir)}`);
}
