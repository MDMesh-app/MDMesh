// @vitest-environment node
// The message catalog, checked with FormatJS's own CLI:
// - locales/en.json is what `npm run i18n:extract` writes from the code today, so an edited or new defaultMessage
//   can't reach translators without its catalog entry;
// - every message has an explicit, stable id (`area.topic.purpose`): copy edits keep the id, and a message written
//   without one (FormatJS would hash its text) fails;
// - a translation (locales/<code>.json, same shape as en.json) may not add ids English doesn't have or change a
//   message's structure (its {placeholders}, plural and select arms, <tags>): `formatjs verify` fails the build.
//   Missing translations never fail: those messages show in English.
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';

const WEB = resolve(import.meta.dirname, '../..');
const LOCALES = join(import.meta.dirname, 'locales');
const FORMATJS = join(WEB, 'node_modules/@formatjs/cli/bin/formatjs');
/** Lower-camel segments, at least two: `devices.list.empty`, `error.agent.command.wiped`. */
const ID = /^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9]+)+$/;

type Messages = Record<string, { defaultMessage: string; description?: string }>;

const tmp = mkdtempSync(join(tmpdir(), 'mdmesh-i18n-'));
afterAll(() => rmSync(tmp, { recursive: true, force: true }));

function formatjs(...args: string[]) {
  const run = spawnSync(process.execPath, [FORMATJS, ...args], { cwd: WEB, encoding: 'utf8' });
  return { ok: run.status === 0, output: `${run.stdout}${run.stderr}` };
}

/** The structural problems of the translations at `files` against `source` (en.json); '' when there are none. */
function verify(source: string, files: readonly string[]): string {
  const run = formatjs('verify', '--source-locale', 'en', '--extra-keys', '--structural-equality', source, ...files);
  return run.ok ? '' : run.output;
}

const read = (path: string): Messages => JSON.parse(readFileSync(path, 'utf8'));

describe('the message catalog', () => {
  it('locales/en.json is what `npm run i18n:extract` writes', () => {
    const out = join(tmp, 'extracted.json');
    const run = formatjs(
      'extract', 'src/**/*.{ts,tsx}', '--ignore', 'src/**/*.test.*', '--ignore', 'src/**/*.d.ts', '--out-file', out, '--throws',
    );
    expect(run.ok, run.output).toBe(true);
    // Line endings aside (.gitattributes keeps LF; a checkout made before that may still have CRLF).
    const text = (path: string) => readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
    const stale = text(out) !== text(join(LOCALES, 'en.json'));
    expect(stale, 'web/src/i18n/locales/en.json is out of date: run `npm run i18n:extract` in web/ and commit it').toBe(false);
  });

  it('gives every message an explicit id', () => {
    const bad = Object.keys(read(join(LOCALES, 'en.json'))).filter((id) => !ID.test(id));
    expect(bad, 'give these messages an id like `devices.list.empty`').toEqual([]);
  });

  it('accepts the translations: no extra ids, the same structure as English', () => {
    const translations = readdirSync(LOCALES).filter((f) => f.endsWith('.json') && f !== 'en.json');
    if (translations.length === 0) return;
    expect(verify(join(LOCALES, 'en.json'), translations.map((f) => join(LOCALES, f)))).toBe('');
  });
});

describe('the translation checks', () => {
  const source: Messages = {
    'devices.bulk.wipe': { defaultMessage: 'Wipe {count, plural, one {# device} other {# devices}}?' },
    'devices.list.empty': { defaultMessage: 'No devices yet' },
  };
  const write = (name: string, messages: Messages) => {
    const path = join(tmp, name);
    writeFileSync(path, JSON.stringify(messages));
    return path;
  };
  const en = write('en.json', source);

  it('fails a translation that drops a placeholder or adds an id', () => {
    const dropped = write('ru.json', { 'devices.bulk.wipe': { defaultMessage: 'Стереть устройства?' } });
    expect(verify(en, [dropped])).toContain('devices.bulk.wipe');
    const extra = write('de.json', { 'devices.list.gone': { defaultMessage: 'Weg' } });
    expect(verify(en, [extra])).toContain('devices.list.gone');
  });

  it('passes a translation with messages missing', () => {
    const ru: Messages = {
      'devices.bulk.wipe': { defaultMessage: 'Стереть {count, plural, one {# устройство} few {# устройства} many {# устройств} other {# устройства}}?' },
    };
    expect(verify(en, [write('ru.json', ru)])).toBe('');
  });
});
