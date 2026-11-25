import { screen } from '@testing-library/react';
import { createContext, useContext, type ReactNode } from 'react';
import { FormattedMessage } from 'react-intl';
import { describe, expect, it } from 'vitest';
import { render } from './render';

const Theme = createContext('none');

describe('render', () => {
  it('renders inside IntlRoot, so a react-intl message renders', () => {
    render(<FormattedMessage id="test.hello" defaultMessage="Hello" />);
    expect(screen.getByText('Hello')).toBeTruthy();
  });

  it('keeps a wrapper the test passes, inside IntlRoot', () => {
    const Themed = () => <FormattedMessage id="test.theme" defaultMessage="Theme: {name}" values={{ name: useContext(Theme) }} />;
    const wrapper = ({ children }: { children: ReactNode }) => <Theme.Provider value="dark">{children}</Theme.Provider>;
    render(<Themed />, { wrapper });
    expect(screen.getByText('Theme: dark')).toBeTruthy();
  });
});
