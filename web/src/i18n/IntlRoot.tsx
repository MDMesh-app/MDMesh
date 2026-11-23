import { useEffect, useLayoutEffect, useState, type ReactNode } from 'react';
import { IntlProvider, type IntlConfig } from 'react-intl';
import { CATALOGS, directionOf, resolveLocale, SOURCE_LOCALE, storedLocale, type Catalog } from './locales';

interface Active {
  locale: string;
  messages?: Catalog;
}

// A message that can't be shown in the locale falls back to its English defaultMessage. Dev and tests say so;
// production stays quiet.
const onError: IntlConfig['onError'] = (err) => {
  if (import.meta.env.DEV) console.error(err);
};

/**
 * The console's react-intl provider, and `<html lang dir>` to match. English renders at once. Another locale renders
 * nothing until its catalog has loaded, so the page never shows English first and then switches; if the catalog
 * can't load, the console stays in English.
 */
export function IntlRoot({ children }: { children: ReactNode }) {
  const [wanted] = useState(() =>
    resolveLocale([SOURCE_LOCALE, ...Object.keys(CATALOGS)], storedLocale(), navigator.languages ?? []),
  );
  const [active, setActive] = useState<Active | null>(() => (wanted === SOURCE_LOCALE ? { locale: SOURCE_LOCALE } : null));

  useEffect(() => {
    if (wanted === SOURCE_LOCALE) return;
    let live = true;
    CATALOGS[wanted]().then(
      (messages) => live && setActive({ locale: wanted, messages }),
      (err: unknown) => {
        console.error(`Couldn't load the ${wanted} messages; showing English`, err);
        if (live) setActive({ locale: SOURCE_LOCALE });
      },
    );
    return () => {
      live = false;
    };
  }, [wanted]);

  // Before paint, so the first frame in a locale already carries its lang (for screen readers) and direction.
  useLayoutEffect(() => {
    if (!active) return;
    document.documentElement.lang = active.locale;
    document.documentElement.dir = directionOf(active.locale);
  }, [active]);

  if (!active) return null;
  return (
    <IntlProvider locale={active.locale} defaultLocale={SOURCE_LOCALE} messages={active.messages} onError={onError}>
      {children}
    </IntlProvider>
  );
}
