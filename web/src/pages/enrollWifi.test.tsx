// Enroll: a Wi-Fi the device can't join is left out of the code. The note saying so is read out as it appears and
// is the description of the fields it is about.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };

describe('Enroll: a Wi-Fi that stays out of the code', () => {
  it('says why in a live region, and describes the password and security fields with it', async () => {
    stubApi((path: string, init?: RequestInit): unknown => {
      const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
      if (p === '/configurations/search') return [];
      if (p === '/agent/v1/token' && init?.method === 'POST') return { token: 'tok-1', expiresAt: Date.now() + 3600_000 };
      return null;
    });
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));

    fireEvent.click(await main.findByRole('switch', { name: 'Join Wi-Fi during setup' }, LOAD));
    fireEvent.change(main.getByLabelText('Network name'), { target: { value: 'Office' } });

    const password = main.getByLabelText('Wi-Fi password');
    await waitFor(() => expect(password.getAttribute('aria-describedby')).toBe('v2-enroll-wifi-issue'), LOAD);
    const note = document.getElementById('v2-enroll-wifi-issue');
    expect(note?.textContent).toMatch(/Enter the Wi-Fi password/);
    expect(note?.closest('[role="status"]')).not.toBeNull();
    expect(main.getByLabelText('Security').getAttribute('aria-describedby')).toBe('v2-enroll-wifi-issue');

    fireEvent.change(password, { target: { value: 'long-enough-1' } });
    await waitFor(() => expect(password.getAttribute('aria-describedby')).toBeNull(), LOAD);
    // The same live region now carries the warning that the password is in the code.
    expect(document.getElementById('v2-enroll-wifi-issue')).toBeNull();
    expect(main.getByText(/The Wi-Fi password is written into the QR code/).closest('[role="status"]')).not.toBeNull();
  });
});
