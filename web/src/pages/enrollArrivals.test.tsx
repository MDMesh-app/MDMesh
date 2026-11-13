// Enroll: the device the admin just set up shows on the page as it enrolls and checks in, without leaving it.
// Through the real route, against a stubbed API.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ARRIVALS_POLL_MS, ARRIVALS_WATCH_MS } from '../enroll/arrivals';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const CREATED = 1_700_000_000_000;

function api(devices: () => unknown[]) {
  return (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    if (p === '/configurations/search') return [];
    if (p === '/agent/v1/token' && init?.method === 'POST')
      return { token: 'tok-1', createdAt: CREATED, expiresAt: Date.now() + 24 * 3600_000, maxUses: 1 };
    if (p === '/agent/v1/devices/enrolled') {
      const q = new URL(path, 'http://console').searchParams;
      expect(q.get('since')).toBe(String(CREATED));
      return { serverTime: Date.now(), devices: devices() };
    }
    return null;
  };
}

describe('Enroll: devices enrolled while the page is open', () => {
  it('lists a device that enrolled with the code, not one enrolled before it', async () => {
    stubApi(
      api(() => [
        { number: 'new-1', enrollTime: Date.now(), lastUpdate: Date.now() },
        { number: 'old-1', enrollTime: CREATED - 60_000, lastUpdate: Date.now() },
      ]),
    );
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));

    const link = await main.findByRole('link', { name: 'new-1' }, LOAD);
    expect(link.closest('li')?.textContent).toContain('Enrolled, getting its configuration');
    expect(main.queryByRole('link', { name: 'old-1' })).toBeNull();
  });

  it('says it is waiting while no device has enrolled', async () => {
    stubApi(api(() => []));
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));
    await waitFor(() => expect(main.getByText(/No device yet/)).toBeTruthy(), LOAD);
  });
});

describe('Enroll: what a screen reader hears about arrivals', () => {
  it('announces a new device once, in a live region apart from the list', async () => {
    stubApi(api(() => [{ number: 'new-1', enrollTime: Date.now(), lastUpdate: Date.now() }]));
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));

    const link = await main.findByRole('link', { name: 'new-1' }, LOAD);
    // The list is not live: a look every 5 s would re-read it.
    expect(link.closest('[aria-live], [role="status"], [role="alert"]')).toBeNull();
    expect(main.getByRole('list', { name: 'Enrolled while this page is open' })).toBeTruthy();
    expect(main.getByRole('heading', { level: 3, name: 'Enrolled while this page is open' })).toBeTruthy();
    await waitFor(
      () =>
        expect(main.getAllByRole('status').some((s) => s.textContent === 'new-1: Enrolled, getting its configuration….')).toBe(
          true,
        ),
      LOAD,
    );
  });
});

describe('Enroll: each piece of arrival news is read, repeats too', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  /** The announcements in the page's live regions right now. */
  const saidNodes = (): HTMLElement[] => [...screen.getByRole('main').querySelectorAll<HTMLElement>('[role="status"] p')];
  const said = (): Array<string | null> => saidNodes().map((p) => p.textContent);
  const poll = () =>
    act(async () => {
      await vi.advanceTimersByTimeAsync(ARRIVALS_POLL_MS);
    });
  const after30min = () =>
    act(async () => {
      vi.setSystemTime(Date.now() + ARRIVALS_WATCH_MS);
      // Two polls: a look still answering when the clock jumps schedules the next one only once it has its answer.
      await vi.advanceTimersByTimeAsync(ARRIVALS_POLL_MS);
      await vi.advanceTimersByTimeAsync(ARRIVALS_POLL_MS);
    });
  const STOP = 'Stopped looking for new devices after 30 minutes.';

  it('says it stopped looking, clears that on Look again with focus on the list, and says it again next time', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // Looks so far: the clock jumps only once the page is looking (its 30 minutes start with the first look).
    let looks = 0;
    stubApi(
      api(() => {
        looks += 1;
        return [];
      }),
    );
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));
    await waitFor(() => expect(main.getByText(/No device yet/)).toBeTruthy(), LOAD);
    await waitFor(() => expect(looks).toBeGreaterThan(0), LOAD);

    await after30min();
    await waitFor(() => expect(said()).toEqual([STOP]), LOAD);
    const first = saidNodes()[0];

    fireEvent.click(main.getByRole('button', { name: 'Look again' }));
    // Look again goes away: focus lands on the list's heading, not on the page.
    expect(document.activeElement).toBe(main.getByRole('heading', { level: 3, name: 'Enrolled while this page is open' }));
    // The old news is gone, so browse mode doesn't find a stop that no longer holds.
    await waitFor(() => expect(said()).toEqual([]), LOAD);
    await waitFor(() => expect(main.queryByRole('button', { name: 'Look again' })).toBeNull(), LOAD);
    const looked = looks;
    await waitFor(() => expect(looks).toBeGreaterThan(looked), LOAD);

    await after30min();
    await waitFor(() => expect(said()).toEqual([STOP]), LOAD);
    // A new node: a live region reads an addition, not a text that stayed the same.
    const second = saidNodes()[0];
    expect(second).not.toBe(first);

    // And it leaves the live region once read (the visible line under the heading stays).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(8_000);
    });
    expect(said()).toEqual([]);
    expect(main.getByRole('button', { name: 'Look again' })).toBeTruthy();
  });

  it('announces the same sentence twice when two looks bring the same news', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // Two devices both called "Scanner"; `checkedIn` of them have checked in.
    let checkedIn = 0;
    const enrolled = Date.now();
    stubApi(
      api(() => [
        { number: 's-1', description: 'Scanner', enrollTime: enrolled, lastUpdate: checkedIn >= 1 ? Date.now() : enrolled },
        { number: 's-2', description: 'Scanner', enrollTime: enrolled + 1, lastUpdate: checkedIn >= 2 ? Date.now() : enrolled + 1 },
      ]),
    );
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    await screen.findByRole('main', undefined, LOAD);
    // Both in one look: counted, not named one by one.
    await waitFor(() => expect(said()).toEqual(['2 devices enrolled.']), LOAD);

    checkedIn = 1;
    await poll();
    await waitFor(() => expect(said()).toContain('Scanner: Online.'), LOAD);
    checkedIn = 2;
    await poll();
    await waitFor(() => expect(said().filter((t) => t === 'Scanner: Online.')).toHaveLength(2), LOAD);
  });
});
