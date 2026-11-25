// Settings → Agent rollout against a stubbed API: Finish instead of a promote that would reach nobody, and the
// "current on every device" note checked again while no rollout runs.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../App';
import { ROLES, renderInConsole, settled, stubApi } from '../../test/console';
import { SUMMARY_POLL_MS } from './RolloutSection';

const NOW = Date.now();
const counts = (c: Partial<Record<'total' | 'updated' | 'pending' | 'outstanding' | 'ineligible', number>>) => ({
  total: 0, updated: 0, pending: 0, outstanding: 0, ineligible: 0, ...c,
});
const canary = (others: number | undefined) => ({
  id: 7, targetVersion: '0.5.0', packageName: 'com.mdmesh.agent', apkVersionCode: 50, stage: 'canary',
  createdAt: NOW - 60_000, updatedAt: NOW,
  progress: { stage: 'canary', targetVersion: '0.5.0', canary: counts({ total: 2, updated: 1, outstanding: 1 }), fleet: null, others },
});
const STATUS = {
  current: '0.5.0', latest: '0.5.0', updateAvailable: false, verified: true, channel: 'stable', checkedAt: NOW, error: null,
  apply: null, auto: false, release: { notes: null, url: null, publishedAt: null },
  apk: { version: '0.5.0', versionCode: 50, sha256: 'a'.repeat(64), available: true },
};

function open(routes: (path: string, init?: RequestInit) => unknown, status: object = STATUS) {
  const fetch = stubApi((path, init) => routes(new URL(path, 'http://console').pathname.replace('/rest/private', ''), init));
  const rest = fetch.getMockImplementation();
  fetch.mockImplementation(async (input, init) =>
    String(input).startsWith('/update/status') ? new Response(JSON.stringify(status)) : rest!(input, init),
  );
  renderInConsole(<AppRoutes />, { path: '/settings', user: ROLES.admin });
  return fetch;
}

const main = () => within(screen.getByRole('main'));

afterEach(() => {
  vi.useRealTimers();
});

describe('Agent rollout', () => {
  it('offers Finish, not Promote, when every device is in the canary', async () => {
    const fetch = open((p) => (p === '/agent/v1/rollout/active' ? canary(0) : null));
    await main().findByText(/Updating the agent to/);
    await settled();
    expect(main().queryByRole('button', { name: 'Promote to everyone else' })).toBeNull();
    expect(main().queryByRole('button', { name: 'Cancel rollout' })).toBeNull();
    expect(main().getByText(/Every device is in the canary\. The rollout closes once/)).toBeTruthy();

    fireEvent.click(main().getByRole('button', { name: 'Finish rollout' }));
    const dialog = within(await screen.findByRole('alertdialog'));
    fireEvent.click(dialog.getByRole('button', { name: 'Finish rollout' }));
    await waitFor(() =>
      expect(fetch.mock.calls.some(([u, i]) => String(u).endsWith('/rollout/7/cancel') && i?.method === 'POST')).toBe(true),
    );
    await settled();
  });

  it.each([3, undefined])('keeps Promote and Cancel when others is %s', async (others) => {
    open((p) => (p === '/agent/v1/rollout/active' ? canary(others) : null));
    await main().findByText(/Updating the agent to/);
    await settled();
    expect(main().getByRole('button', { name: 'Promote to everyone else' })).toBeTruthy();
    expect(main().getByRole('button', { name: 'Cancel rollout' })).toBeTruthy();
  });

  it('brings the start offer back when a device on an older agent enrolls while Settings is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let complete = true;
    open((p) =>
      p === '/agent/v1/rollout/summary'
        ? { targetVersion: '0.5.0', progress: counts({ total: 2, updated: complete ? 2 : 1, outstanding: complete ? 0 : 1 }), complete }
        : null,
    );
    await main().findByText(/is current on every device that can update itself/);

    complete = false; // a device enrolled with agent 0.4.0
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SUMMARY_POLL_MS);
    });
    await main().findByText(/is ready to roll out/);
    expect(main().getByRole('button', { name: 'Roll out agent' })).toBeTruthy();
  });
});

describe('Agent rollout: the server first', () => {
  // As the updater reports a 0.5.0 server with 0.5.1 released: it offers 0.5.0's own agent, never 0.5.1's.
  const OLDER = { ...STATUS, latest: '0.5.1', updateAvailable: true,
    apk: { ...STATUS.apk, of: 'running' } };
  const summary = (complete: boolean) => (p: string) =>
    p === '/agent/v1/rollout/summary'
      ? { targetVersion: '0.5.0', progress: counts({ total: 2, updated: complete ? 2 : 1, outstanding: complete ? 0 : 1 }), complete }
      : null;

  it('offers only the running release’s agent, and says to update the server for the newer one', async () => {
    open(summary(false), OLDER);
    const label = await main().findByText(/is ready to roll out/);
    await settled();
    expect(label.textContent).toBe('Agent 0.5.0 is ready to roll out');
    expect(main().getByText(/Agent 0\.5\.1 comes with server 0\.5\.1: update the server first, then roll it out here\./)).toBeTruthy();
  });

  it('with every device on the running release’s agent, says the same', async () => {
    open(summary(true), OLDER);
    const line = await main().findByText(/is current on every device that can update itself/);
    expect(line.textContent).toContain('Agent 0.5.1 comes with server 0.5.1: update the server first');
  });

  it('says nothing about it when the server runs the latest release', async () => {
    open(summary(false));
    await main().findByText(/is ready to roll out/);
    await settled();
    expect(main().queryByText(/update the server first/)).toBeNull();
  });
});

describe('Agent rollout: a rollout newer than the server', () => {
  it('is marked stalled after the server was rolled back (the updater refuses its agent)', async () => {
    // A rollout of agent 0.5.0 started on a 0.5.0 server, which was then rolled back to 0.4.9.
    open((p) => (p === '/agent/v1/rollout/active' ? canary(3) : null), { ...STATUS, current: '0.4.9', apk: { ...STATUS.apk, version: '0.4.9', of: 'running' } });
    const note = await main().findByText(/This rollout is stalled/);
    await settled();
    expect(note.textContent).toBe(
      'This rollout is stalled: agent 0.5.0 is newer than the server (0.4.9), so the updater won’t give it to devices. Cancel the rollout, or update the server to 0.5.0.',
    );
  });
  it('is not marked on a server that runs the rollout’s release', async () => {
    const fetch = open((p) => (p === '/agent/v1/rollout/active' ? canary(3) : null));
    await main().findByText(/Updating the agent to/);
    // Only once the updater's status has loaded (the stall needs it) does its absence mean anything.
    await settled();
    expect(fetch.mock.calls.some(([u]) => String(u).startsWith('/update/status'))).toBe(true);
    expect(main().queryByText(/This rollout is stalled/)).toBeNull();
  });
});
