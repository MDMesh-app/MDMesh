// render() from Testing Library, inside the console's IntlRoot: a component whose text is a react-intl message needs
// the provider, as it has in the console. Component tests import render from here; renderInConsole (test/console.tsx)
// renders whole pages inside the same providers.
import { render as renderBare, type RenderOptions } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { IntlRoot } from '../i18n/IntlRoot';

export function render(ui: ReactElement, options: RenderOptions = {}) {
  const Inner = options.wrapper;
  const wrapper = ({ children }: { children: ReactNode }) => <IntlRoot>{Inner ? <Inner>{children}</Inner> : children}</IntlRoot>;
  return renderBare(ui, { ...options, wrapper });
}
