// Enroll on a release build whose updater has no agent to offer (/files/agent.apk would answer 404): the page warns
// and shows no QR code, which would fail on the device. With an agent, the QR code shows as usual.
import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { UpdateStatus } from '../api/updates';

const LOAD = { timeout: 10_000 };
const STATUS = {
  current: '0.6.0', latest: '0.6.1', updateAvailable: false, verified: true, channel: 'stable', checkedAt: Date.now(),
  error: null, apply: null, auto: false, applySupported: true, release: null, blocked: 'r', blockedCode: 'owner',
} as unknown as UpdateStatus;

async function render(status: UpdateStatus) {
  vi.stubEnv('VITE_AGENT_CHECKSUM', 'P0s1coXolfDwvZD-X6DNHVMM2tVaSM9oiAl9UIMCxfw');
  vi.resetModules();   // EnrollPage reads the build's checksum when it loads
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://console').pathname;
    if (path === '/update/status') return new Response(JSON.stringify(status), { status: 200 });
    const p = path.replace('/rest/private', '');
    const data = p === '/agent/v1/token' && init?.method === 'POST'
      ? { token: 'tok-1', createdAt: Date.now(), expiresAt: Date.now() + 3600_000, maxUses: 1 }
      : p === '/configurations/search' ? [] : null;
    return new Response(JSON.stringify({ status: 'OK', message: null, data }), { status: 200 });
  }));
  // After the reset, so the page and the test's providers share one set of modules.
  const { AppRoutes } = await import('../App');
  const { renderInConsole } = await import('../test/console');
  renderInConsole(<AppRoutes />, { path: '/enroll' });
  return within(await screen.findByRole('main', undefined, LOAD));
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('Enroll: no agent to install', () => {
  it('warns, and shows no QR code', async () => {
    const main = await render({ ...STATUS, apk: null });
    expect(await main.findByText(/This server has no agent app to give new devices/, undefined, LOAD)).toBeTruthy();
    expect(main.getByText('No QR code: there is no agent to install yet.')).toBeTruthy();
    expect(main.queryByRole('img', { name: /Enrollment QR code/ })).toBeNull();
  });

  it('shows the QR code when the updater offers an agent (the running release’s, here)', async () => {
    const main = await render({
      ...STATUS, apk: { version: '0.6.0', versionCode: 60000, sha256: 'a'.repeat(64), available: true, of: 'running' },
    });
    expect(await main.findByText('0.6.0', undefined, LOAD)).toBeTruthy();
    expect(main.queryByText(/no agent app/)).toBeNull();
    expect(main.queryByText(/No QR code/)).toBeNull();
  });
});
