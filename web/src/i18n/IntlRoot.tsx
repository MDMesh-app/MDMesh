import { useEffect, useLayoutEffect, useState, type ReactNode } from 'react';
import { IntlProvider } from 'react-intl';
import { CATALOGS, resolveLocale, SOURCE_LOCALE, storedLocale, type Catalog } from './locales';

interface Active {
  locale: string;
  messages?: Catalog;
}

/**
 * The console's react-intl provider, and `<html lang>` to match. English renders at once. Another locale renders
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
    CATALOGS[wanted]().then(
      (messages) => setActive({ locale: wanted, messages }),
      (err: unknown) => {
        console.error(`Couldn't load the ${wanted} messages; showing English`, err);
        setActive({ locale: SOURCE_LOCALE });
      },
    );
  }, [wanted]);

  // Before paint, so the first frame in a locale already carries its lang (for screen readers).
  useLayoutEffect(() => {
    if (active) document.documentElement.lang = active.locale;
  }, [active]);

  if (!active) return null;
  return (
    <IntlProvider locale={active.locale} defaultLocale={SOURCE_LOCALE} messages={active.messages}>
      {children}
    </IntlProvider>
  );
}
