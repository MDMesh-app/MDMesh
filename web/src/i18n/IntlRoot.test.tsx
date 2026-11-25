import { render, screen, waitFor } from '@testing-library/react';
import { FormattedMessage } from 'react-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntlRoot } from './IntlRoot';
import { LOCALE_KEY, resolveLocale } from './locales';

// Two catalogs that load when asked: `xx` (a test locale) and `ar`, which fails to load.
let release: (messages: Record<string, string>) => void = () => {};
vi.mock('./locales', async (actual) => ({
  ...(await actual<typeof import('./locales')>()),
  CATALOGS: {
    xx: () => new Promise((resolve) => (release = resolve)),
    ar: () => Promise.reject(new Error('offline')),
  },
}));

const Hello = () => <FormattedMessage id="test.hello" defaultMessage="Hello" />;

// Not set, so a test sees only what IntlRoot sets.
afterEach(() => {
  document.documentElement.lang = '';
});

describe('resolveLocale', () => {
  const available = ['en', 'ru', 'pt-BR'];

  it('takes the stored preference first, then the browser languages, then English', () => {
    expect(resolveLocale(available, 'ru', ['pt-BR'])).toBe('ru');
    expect(resolveLocale(available, 'de', ['de-DE', 'pt-br'])).toBe('pt-BR');
    expect(resolveLocale(available, null, ['ru-RU'])).toBe('ru');
    expect(resolveLocale(available, null, ['de', 'fr'])).toBe('en');
  });
});

describe('IntlRoot', () => {
  it('renders English at once and sets <html lang>', () => {
    render(<IntlRoot><Hello /></IntlRoot>);
    expect(screen.getByText('Hello')).toBeTruthy();
    expect(document.documentElement.lang).toBe('en');
  });

  it('shows another locale only once its catalog has loaded, never English first', async () => {
    localStorage.setItem(LOCALE_KEY, 'xx');
    const { container } = render(<IntlRoot><Hello /></IntlRoot>);
    expect(container.textContent).toBe('');
    release({ 'test.hello': 'Hallo' });
    expect(await screen.findByText('Hallo')).toBeTruthy();
    expect(screen.queryByText('Hello')).toBeNull();
    expect(document.documentElement.lang).toBe('xx');
  });

  it('stays in English when the catalog fails to load', async () => {
    localStorage.setItem(LOCALE_KEY, 'ar');
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<IntlRoot><Hello /></IntlRoot>);
    expect(await screen.findByText('Hello')).toBeTruthy();
    await waitFor(() => expect(document.documentElement.lang).toBe('en'));
    expect(error).toHaveBeenCalled();
  });
});
