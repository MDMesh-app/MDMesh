// The console's licence files as a Vite plugin (vite.config.ts): what a build ships under /licenses/, and the licence
// policy for the packages it bundles (docs/licenses/policy.md).
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import satisfies from 'spdx-satisfies';
import type { Plugin } from 'vite';

/** Where Vite's own build.license writes the bundled packages and their licences (vite.config.ts sets it). */
export const THIRD_PARTY = 'licenses/THIRD-PARTY.json';

const WEB = dirname(fileURLToPath(import.meta.url));
const ROOT = join(WEB, '..');
const repo = (path: string) => join(ROOT, path);

/** The SPDX licence ids in docs/licenses/allowed.txt: one per line, `#` starts a comment. */
export function readAllowed(file = repo('docs/licenses/allowed.txt')): string[] {
  return readFileSync(file, 'utf8')
    .split('\n')
    .map((line) => line.replace(/#.*/, '').trim())
    .filter(Boolean);
}

/** One entry of Vite's build.license JSON. */
export interface BundledPackage {
  name: string;
  version: string;
  identifier?: string;
  text?: string;
}

/** The packages whose licence (an SPDX expression, `(WTFPL OR MIT)` say) the allowed licences don't satisfy. */
export function violations(packages: readonly BundledPackage[], allowed: readonly string[]): string[] {
  return packages.flatMap(({ name, version, identifier }) => {
    if (!identifier) return [`${name}@${version}: no licence in its package.json`];
    let ok = false;
    try {
      ok = satisfies(identifier, allowed);
    } catch {
      // Not an SPDX expression ("SEE LICENSE IN …", a misspelt id): a person has to read it.
    }
    return ok ? [] : [`${name}@${version}: ${identifier}`];
  });
}

/**
 * Adds LICENSE.txt and NOTICE.txt from the repository root to the build's licenses/ folder, next to the THIRD-PARTY.json
 * Vite writes there, and checks that JSON against docs/licenses/allowed.txt. A package under another licence fails
 * the build where CI is set (CI's web job), and only warns elsewhere (an image build, a native install), so building
 * an install never stops on licence policy. docker/web.Dockerfile copies LICENSE, NOTICE and docs/licenses next to
 * web/. `files` and `enforce` are for tests.
 */
export function licences(
  files = { licence: repo('LICENSE'), notice: repo('NOTICE'), allowed: repo('docs/licenses/allowed.txt') },
  enforce = !!process.env.CI && !/^(false|0)$/i.test(process.env.CI),
): Plugin {
  return {
    name: 'mdmesh-licences',
    apply: 'build',
    generateBundle: {
      // After vite:license has emitted THIRD-PARTY.json.
      order: 'post',
      handler(_, bundle) {
        for (const [file, fileName] of [
          [files.licence, 'licenses/LICENSE.txt'],
          [files.notice, 'licenses/NOTICE.txt'],
        ]) {
          if (!existsSync(file)) this.error(`${file} not found: the console ships it at /${fileName}`);
          this.emitFile({ type: 'asset', fileName, source: readFileSync(file, 'utf8') });
        }
        const report = bundle[THIRD_PARTY];
        if (report?.type !== 'asset') this.error(`${THIRD_PARTY} is missing: vite.config.ts must set build.license`);
        const packages = JSON.parse(String(report.source)) as BundledPackage[];
        // Vite lists the packages of JS modules only: add those whose files CSS loads (the fonts).
        for (const file of Object.values(bundle).flatMap((out) => (out.type === 'asset' ? out.originalFileNames : []))) {
          const dir = /^node_modules\/(@[^/]+\/)?[^/]+/.exec(file)?.[0];
          if (!dir) continue;
          const read = (name: string) => readFileSync(join(WEB, dir, name), 'utf8');
          const { name, version, license } = JSON.parse(read('package.json')) as Record<string, string>;
          if (packages.some((p) => p.name === name)) continue;
          packages.push({ name, version, identifier: license, text: read('LICENSE').trim() });
        }
        report.source = JSON.stringify(packages, null, 2);
        const bad = violations(packages, readAllowed(files.allowed));
        if (bad.length === 0) return;
        const message =
          `the console bundles packages under licences docs/licenses/allowed.txt doesn't allow ` +
          `(docs/licenses/policy.md says what to do):\n  ${bad.join('\n  ')}`;
        if (enforce) this.error(message);
        this.warn(message);
      },
    },
  };
}
