import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { createElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registryOf } from '../test/console';
import { AppErrorBoundary } from '../ui/AppErrorBoundary';
import { PageErrorBoundary } from '../ui/PageErrorBoundary';
import { ExtensionBoundary } from './ExtensionBoundary';
import type { SlotEntry } from './registry';
import { defineExtension, lazyComponent, type OverviewCardContribution } from './types';

let crash = true;
function Flaky() {
  if (crash) throw new Error('boom');
  return <p>Flaky works</p>;
}

const cards = defineExtension({
  id: 'cards',
  apiVersion: 1,
  title: 'Fleet cards',
  contributions: [
    { slot: 'overview.card', id: 'flaky', order: 1, placement: 'main', component: lazyComponent(async () => ({ Flaky }), 'Flaky') },
    {
      slot: 'overview.card',
      id: 'steady',
      order: 2,
      placement: 'aside',
      component: lazyComponent(async () => ({ Steady: () => <p>Steady works</p> }), 'Steady'),
    },
    {
      slot: 'overview.card',
      id: 'stale-chunk',
      order: 3,
      placement: 'aside',
      component: lazyComponent(() => Promise.reject(new TypeError('Failed to fetch dynamically imported module')), 'Gone'),
    },
  ],
});

const entries = registryOf(cards).entries('overview.card');
const entry = (id: string) => entries.find((e) => e.contribution.id === id) as SlotEntry<OverviewCardContribution>;

function renderCards(ids: string[], fallback?: 'callout' | 'page' | 'none') {
  return render(
    <>
      {ids.map((id) => (
        <ExtensionBoundary key={id} entry={entry(id)} fallback={fallback}>
          {createElement(entry(id).contribution.component)}
        </ExtensionBoundary>
      ))}
    </>,
  );
}

describe('ExtensionBoundary', () => {
  beforeEach(() => {
    crash = true;
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('replaces only the crashed contribution with a callout naming its extension', async () => {
    renderCards(['flaky', 'steady']);
    expect(await screen.findByText('Steady works')).toBeTruthy();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('The “Fleet cards” extension ran into a problem. Try again');
    expect(console.error).toHaveBeenCalledWith('[ext:cards/flaky] crashed', expect.any(Error), expect.anything());
  });

  it('renders the contribution again on Try again', async () => {
    renderCards(['flaky']);
    await screen.findByRole('alert');
    crash = false;
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Flaky works')).toBeTruthy();
  });

  it('offers a reload when the chunk is gone (the console was updated)', async () => {
    renderCards(['stale-chunk', 'steady']);
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('The “Fleet cards” extension couldn’t load. Reload');
    expect(screen.getByText('Steady works')).toBeTruthy();
  });

  it('shows the page-level card for a page', async () => {
    renderCards(['flaky'], 'page');
    expect((await screen.findByRole('alert')).textContent).toContain('This page ran into a problem');
  });

  it('shows nothing for a decoration', async () => {
    const { container } = renderCards(['flaky'], 'none');
    await act(async () => {});
    expect(container.innerHTML).toBe('');
  });

  it('does not name core in its callout', async () => {
    const core = { ...entry('flaky'), extension: { id: 'core', title: 'MDMesh', core: true } };
    render(<ExtensionBoundary entry={core}>{createElement(core.contribution.component)}</ExtensionBoundary>);
    expect((await screen.findByRole('alert')).textContent).toBe('This part of the page ran into a problem. Try again');
  });
});

describe('a thrown value that is falsy is still caught', () => {
  // `throw undefined` (or null, 0, '') must stop at the nearest boundary, not climb to the root.
  const thrower = (value: unknown) =>
    function Thrower(): never {
      throw value;
    };

  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it.each([[undefined], [null], [0], ['']])('ExtensionBoundary catches throw %j', async (value) => {
    const Thrower = thrower(value);
    render(
      <AppErrorBoundary>
        <ExtensionBoundary entry={entry('steady')}>
          <Thrower />
        </ExtensionBoundary>
      </AppErrorBoundary>,
    );
    expect((await screen.findByRole('alert')).textContent).toBe('The “Fleet cards” extension ran into a problem. Try again');
  });

  it.each([[undefined], [null]])('PageErrorBoundary catches throw %j', async (value) => {
    const Thrower = thrower(value);
    render(
      <AppErrorBoundary>
        <PageErrorBoundary>
          <Thrower />
        </PageErrorBoundary>
      </AppErrorBoundary>,
    );
    expect((await screen.findByRole('alert')).textContent).toContain('This page ran into a problem');
  });

  it('AppErrorBoundary catches throw null', async () => {
    const Thrower = thrower(null);
    render(
      <AppErrorBoundary>
        <Thrower />
      </AppErrorBoundary>,
    );
    expect((await screen.findByRole('alert')).textContent).toContain('Couldn’t load the console');
  });
});

describe('lazyComponent', () => {
  // React.lazy keeps a failed load, so "Try again" would only show the same error: a reload is the way out.
  it.each([
    ['its export is missing', lazyComponent(async () => ({}) as Record<'Renamed', () => null>, 'Renamed')],
    ['its module throws while it loads', lazyComponent(() => Promise.reject(new Error('module init failed')), 'Broken')],
  ])('offers a reload, not a retry, when %s', async (_, Broken) => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <>
        <ExtensionBoundary entry={entry('steady')}>
          <Broken />
        </ExtensionBoundary>
        <ExtensionBoundary entry={entry('steady')} fallback="page">
          <Broken />
        </ExtensionBoundary>
      </>,
    );
    const [callout, page] = await screen.findAllByRole('alert');
    expect(callout.textContent).toBe('The “Fleet cards” extension couldn’t load. Reload');
    expect(within(page).getByRole('button').textContent).toBe('Reload');
  });

  it('names a missing export instead of failing inside React', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Missing = lazyComponent(async () => ({}) as Record<'Renamed', () => null>, 'Renamed');
    render(
      <ExtensionBoundary entry={entry('steady')} fallback="page">
        <Missing />
      </ExtensionBoundary>,
    );
    await screen.findByRole('alert');
    expect(console.error).toHaveBeenCalledWith(
      '[ext:cards/steady] crashed',
      expect.objectContaining({ message: 'lazyComponent: the module has no export named "Renamed"' }),
      expect.anything(),
    );
  });
});
