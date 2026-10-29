// The What's new notes as a Vite plugin (vite.config.ts): CHANGELOG.md, read at build time.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Plugin } from 'vite';
import { RELEASE_VERSION, releaseSection, releaseVersions, unreleasedSection } from './src/whatsnew/changelog.ts';

/**
 * The What's new dialog's notes: the repository's CHANGELOG.md, read at build time (docker/web.Dockerfile copies it
 * next to web/). `virtual:mdmesh-changelog` holds the versions with a section, and loads the sections themselves as
 * a chunk of their own (`virtual:mdmesh-changelog/notes`), cut by release/changelog-section.sh's rules
 * (src/whatsnew/changelog.ts). Unreleased is in dev builds only (`vite`, and tests). Types: src/whatsnew/virtual.d.ts.
 * A production build fails without CHANGELOG.md; a dev server or test run only warns. `file` is for tests.
 */
export function changelogNotes(
  appVersion: string | undefined,
  file = fileURLToPath(new URL('../CHANGELOG.md', import.meta.url)),
): Plugin {
  const INDEX = 'virtual:mdmesh-changelog';
  const NOTES = 'virtual:mdmesh-changelog/notes';
  let dev = true;
  return {
    name: 'mdmesh-changelog',
    configResolved(config) {
      dev = !config.isProduction;
    },
    resolveId(id) {
      return id === INDEX || id === NOTES ? `\0${id}` : null;
    },
    load(id) {
      if (id !== `\0${INDEX}` && id !== `\0${NOTES}`) return null;
      let text = '';
      if (existsSync(file)) {
        this.addWatchFile(file);
        text = readFileSync(file, 'utf8');
      } else if (!dev) {
        // A release image without its notes would show What's new to no one, silently: refuse to build it.
        this.error(`${file} not found: a production build needs it for What's new (docker/web.Dockerfile copies it next to web/)`);
      } else {
        this.warn(`${file} not found: the console runs without What's new notes`);
      }
      const versions = releaseVersions(text);
      const unreleased = dev ? unreleasedSection(text) : null;
      if (id === `\0${INDEX}`) {
        const v = appVersion?.replace(/^v/, '');
        if (!dev && v && RELEASE_VERSION.test(v) && !versions.includes(v)) {
          this.warn(`CHANGELOG.md has no section for VITE_APP_VERSION ${v}: What's new shows nothing for it`);
        }
        return [
          `export const versions = ${JSON.stringify(versions)};`,
          `export const hasUnreleased = ${unreleased !== null};`,
          `export const load = () => import(${JSON.stringify(NOTES)});`,
        ].join('\n');
      }
      const sections = Object.fromEntries(versions.map((v) => [v, releaseSection(text, v)]));
      return `export const sections = ${JSON.stringify(sections)};\nexport const unreleased = ${JSON.stringify(unreleased)};`;
    },
  };
}
