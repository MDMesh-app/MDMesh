// Which language the console speaks. English is the source: its text is the `defaultMessage` beside each use in
// code, so it needs no catalog and renders synchronously. 0.6.0 ships no other locale. In dev, `npm run i18n:pseudo`
// compiles the en-XA pseudo-locale (accented and padded English) into ./pseudo/, and setting `mdmesh-locale` to
// `en-XA` in localStorage shows it: any text that stays plain English was never extracted.
import type { IntlConfig } from 'react-intl';

export const SOURCE_LOCALE = 'en';
export const LOCALE_KEY = 'mdmesh-locale';

export type Catalog = NonNullable<IntlConfig['messages']>;

/** Locale code → its catalog, loaded on demand. Production builds carry none yet; dev adds the pseudo-locale. */
export const CATALOGS: Readonly<Record<string, () => Promise<Catalog>>> = import.meta.env.DEV
  ? Object.fromEntries(
      Object.entries(import.meta.glob<Catalog>('./pseudo/*.json', { import: 'default' })).map(([path, load]) => [
        path.slice('./pseudo/'.length, -'.json'.length),
        load,
      ]),
    )
  : {};

/**
 * The locale to show: the stored preference if it is available, else the first browser language available (exactly,
 * or by its language: en-GB → en), else English.
 */
export function resolveLocale(
  available: readonly string[],
  preference: string | null,
  browser: readonly string[],
): string {
  const find = (code: string) => available.find((a) => a.toLowerCase() === code.toLowerCase());
  if (preference && find(preference)) return find(preference) as string;
  for (const lang of browser) {
    const match = find(lang) ?? find(lang.split('-')[0]);
    if (match) return match;
  }
  return SOURCE_LOCALE;
}

export function storedLocale(): string | null {
  try {
    return localStorage.getItem(LOCALE_KEY);
  } catch {
    return null;
  }
}
