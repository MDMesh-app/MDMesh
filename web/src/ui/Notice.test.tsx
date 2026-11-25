import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Notice } from './Card';
import { render } from '../test/render';

describe('Notice', () => {
  it('is named by its title, or by aria-label when one is given', () => {
    render(
      <>
        <Notice icon={null} title="Missing security fixes">Text</Notice>
        <Notice icon={null} title="Shown title" aria-label="Given name">Text</Notice>
        <Notice icon={null} aria-label="Strip">Text</Notice>
      </>,
    );
    expect(screen.getByRole('region', { name: 'Missing security fixes' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Given name' }).classList.contains('v2-notice--titled')).toBe(true);
    expect(screen.getByRole('region', { name: 'Strip' }).classList.contains('v2-notice--titled')).toBe(false);
  });
});
