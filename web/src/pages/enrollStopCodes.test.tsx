// Enroll: a new code doesn't stop the code for a batch it replaces, so the page says so and offers to stop every
// code; Stop all codes asks first, stops them on the server and makes a new code. Through the real route, against a
// stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const SLOW = 20_000;

/** Mints tokens tok-1, tok-2, … for the asked devices; `revoke` answers revoke-all. */
function api(revoke: unknown = { revoked: 2 }) {
  let n = 0;
  return (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    if (p === '/configurations/search') return [];
    if (p === '/agent/v1/token' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body ?? '{}')) as { maxUses?: number };
      n += 1;
      return { token: `tok-${n}`, expiresAt: Date.now() + 3 * 24 * 3600_000, maxUses: body.maxUses ?? 1 };
    }
    if (p === '/agent/v1/token/revoke-all' && init?.method === 'POST') return revoke;
    return null;
  };
}

const posts = (fetch: ReturnType<typeof stubApi>, suffix: string) =>
  fetch.mock.calls.filter(([input, init]) => String(input).endsWith(suffix) && init?.method === 'POST');
const main = () => within(screen.getByRole('main'));

async function openEnroll(fetch: ReturnType<typeof stubApi>) {
  renderInConsole(<AppRoutes />, { path: '/enroll' });
  await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(1), LOAD);
  await main().findByRole('button', { name: 'New code' }, LOAD);
}

async function chooseBatch(fetch: ReturnType<typeof stubApi>) {
  fireEvent.change(main().getByRole('combobox', { name: 'Devices' }), { target: { value: '25' } });
  await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(2), LOAD);
  await main().findByText('Up to 25 devices, then it stops working', undefined, LOAD);
}

describe('Enroll: codes for a batch', () => {
  it('says a replaced batch code still works, and stops every code on request', async () => {
    const fetch = stubApi(api());
    await openEnroll(fetch);
    await chooseBatch(fetch);
    expect(main().queryByText(/you replaced still works/)).toBeNull();

    fireEvent.click(main().getByRole('button', { name: 'New code' }));
    await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(3), LOAD);
    const notice = await main().findByText(/The code for a batch you replaced still works until/, undefined, LOAD);

    fireEvent.click(within(notice.closest('.v2-callout') ?? notice).getByRole('button', { name: 'Stop all codes' }));
    const dialog = within(await screen.findByRole('alertdialog', { name: 'Stop all enrollment codes?' }));
    expect(posts(fetch, '/agent/v1/token/revoke-all')).toHaveLength(0);
    fireEvent.click(dialog.getByRole('button', { name: 'Stop all codes' }));

    await waitFor(() => expect(posts(fetch, '/agent/v1/token/revoke-all')).toHaveLength(1), LOAD);
    expect(await screen.findByText('2 codes stopped working. A new code is ready.', undefined, LOAD)).toBeTruthy();
    // The shown code was stopped too: a new one is made for the same choices.
    await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(4), LOAD);
    expect(JSON.parse(String(posts(fetch, '/agent/v1/token')[3][1]?.body))).toMatchObject({ maxUses: 25 });
    await waitFor(() => expect(main().queryByText(/you replaced still works/)).toBeNull(), LOAD);
  }, SLOW);

  it('says nothing when the replaced code was single-use', async () => {
    const fetch = stubApi(api());
    await openEnroll(fetch);
    fireEvent.click(main().getByRole('button', { name: 'New code' }));
    await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(2), LOAD);
    await main().findByRole('button', { name: 'New code' }, LOAD);
    expect(main().queryByText(/you replaced still work/)).toBeNull();
  }, SLOW);

  it('counts every batch code a change of choice replaced', async () => {
    const fetch = stubApi(api());
    await openEnroll(fetch);
    await chooseBatch(fetch);
    fireEvent.change(main().getByRole('combobox', { name: 'Devices' }), { target: { value: '100' } });
    await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(3), LOAD);
    fireEvent.change(main().getByRole('combobox', { name: 'Devices' }), { target: { value: '10' } });
    await waitFor(() => expect(posts(fetch, '/agent/v1/token')).toHaveLength(4), LOAD);
    expect(await main().findByText(/The 2 codes for a batch you replaced still work until/, undefined, LOAD)).toBeTruthy();
  }, SLOW);

  it('says so when the server cannot stop codes, and keeps the dialog open', async () => {
    const fetch = stubApi(api());
    const answer = fetch.getMockImplementation()!;
    fetch.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) =>
      String(input).endsWith('/token/revoke-all') ? new Response('Not Found', { status: 404 }) : answer(input, init),
    );
    await openEnroll(fetch);
    fireEvent.click(main().getByRole('button', { name: 'Stop all codes' }));
    const dialog = within(await screen.findByRole('alertdialog', { name: 'Stop all enrollment codes?' }));
    fireEvent.click(dialog.getByRole('button', { name: 'Stop all codes' }));
    expect(await dialog.findByText(/This server can.t stop codes/, undefined, LOAD)).toBeTruthy();
    expect(posts(fetch, '/agent/v1/token')).toHaveLength(1);
    vi.unstubAllGlobals();
  }, SLOW);
});
