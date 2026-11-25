// The device page's Control tab: Clear PIN lockout (queued as kiosk.clearPinLockout, gated on the agent's version and
// capability), and Retry apply, which asks the server to send the configuration now instead of after the hour it
// waits after a failure. Rendered through the real routes against a stubbed API.
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { ROLES, renderInConsole, settled, stubApi } from '../test/console';

const DEVICE = { id: 7, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: Date.now() };

function openControl({
  agentVersion = '0.5.0' as string | null,
  failed = false,
  retry = { result: 'queued', connected: true } as Record<string, unknown>,
  user = ROLES.superAdmin,
} = {}) {
  const fetch = stubApi((path, init) => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    const post = init?.method === 'POST';
    if (p === '/devices/search') return { devices: { items: [DEVICE], totalItemsCount: 1 }, configurations: {} };
    if (p === `/devices/number/${DEVICE.number}`) return DEVICE;
    if (p === `/agent/v1/devices/${DEVICE.number}/state`) {
      return { battery: 80, charging: false, locked: false, kioskActive: true, agentVersion, updatedAt: Date.now() };
    }
    if (p === `/agent/v1/devices/${DEVICE.number}/configStatus` && failed) {
      return {
        configurationId: 3, currentRevision: 'b'.repeat(64), appliedRevision: 'a'.repeat(64), inSync: false, supported: true,
        lastCommand: { id: 5, status: 'failed', detail: 'policies.camera: failed', completedAt: Date.now() },
      };
    }
    if (p === `/agent/v1/devices/${DEVICE.number}/commands`) return post ? { id: 42, status: 'pending' } : [];
    if (p === `/agent/v1/devices/${DEVICE.number}/retryConfig` && post) return retry;
    if (p === `/agent/v1/devices/${DEVICE.number}/sync` && post) return { connected: true };
    return null;
  });
  renderInConsole(<AppRoutes />, { user, path: `/devices/${DEVICE.number}?tab=control` });
  return fetch;
}

const posts = (fetch: ReturnType<typeof stubApi>, suffix: string) =>
  fetch.mock.calls.filter(([input, init]) => String(input).endsWith(suffix) && init?.method === 'POST');

const toastText = () =>
  Array.from(screen.getByRole('region', { name: 'Notifications' }).querySelectorAll('.toast'))
    .map((t) => t.textContent)
    .join(' | ');

describe('Clear PIN lockout', () => {
  it('queues kiosk.clearPinLockout, gated on the agent capability that handles it', async () => {
    const fetch = openControl();
    const button = (await screen.findByRole('button', { name: 'Clear PIN lockout' })) as HTMLButtonElement;
    await waitFor(() => expect(button.disabled).toBe(false));
    fireEvent.click(button);
    await waitFor(() => expect(posts(fetch, `/devices/${DEVICE.number}/commands`)).toHaveLength(1));
    expect(JSON.parse(String(posts(fetch, `/devices/${DEVICE.number}/commands`)[0][1]?.body))).toEqual({
      type: 'kiosk.clearPinLockout',
      requiresCapability: 'device.kioskClearPinLockout',
    });
    await settled();
  });

  it('is unavailable on an agent before 0.5.0, and says why', async () => {
    openControl({ agentVersion: '0.4.2' });
    await screen.findByText(/Clear PIN lockout needs agent 0\.5\.0 or later/);
    await settled();
    expect((screen.getByRole('button', { name: 'Clear PIN lockout' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('Retry apply', () => {
  it('asks the server to send the configuration now, not just for a check-in', async () => {
    const fetch = openControl({ failed: true });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry apply' }));
    await waitFor(() => expect(posts(fetch, `/devices/${DEVICE.number}/retryConfig`)).toHaveLength(1));
    expect(posts(fetch, `/devices/${DEVICE.number}/sync`)).toHaveLength(0);
    await waitFor(() => expect(toastText()).toContain('Configuration sent again'));
    await settled();
  });

  it('says when the device is not connected, so it applies at its next check-in', async () => {
    openControl({ failed: true, retry: { result: 'queued', connected: false } });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry apply' }));
    await waitFor(() => expect(toastText()).toContain('isn’t connected right now'));
    await settled();
  });

  it('says when there was nothing to send', async () => {
    openControl({ failed: true, retry: { result: 'inSync', connected: true } });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry apply' }));
    await waitFor(() => expect(toastText()).toContain('Already applied'));
    await settled();
  });
});
