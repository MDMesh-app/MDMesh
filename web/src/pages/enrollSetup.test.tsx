// Enroll: the Setup fact sums up what the chosen configuration adds to the code, keeps doing so while its other Extra
// QR parameters can't be read, and says when Wi-Fi entries stay out of the code.
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, settled, stubApi } from '../test/console';
import { chooseOption } from '../test/select';

const LOAD = { timeout: 10_000 };

const CLUB = {
  id: 5,
  name: 'Club',
  mobileEnrollment: true,
  qrParameters:
    '"android.app.extra.PROVISIONING_LOCALE": "fr_FR",\n\n' +
    '"android.app.extra.PROVISIONING_WIFI_SSID": "other",\n"x.y": tru',
};

function serve(config: Record<string, unknown>) {
  stubApi((path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    if (p === '/configurations/search') return [config];
    if (p === '/agent/v1/token' && init?.method === 'POST') return { token: 'tok-1', expiresAt: Date.now() + 3600_000 };
    return null;
  });
}

async function setupFact(): Promise<string> {
  const main = within(await screen.findByRole('main', undefined, LOAD));
  const select = await main.findByRole('combobox', { name: 'Configuration' }, LOAD);
  await waitFor(() => chooseOption(select, 'Club'), LOAD);
  let text = '';
  await waitFor(() => {
    const label = main.getAllByText('Setup').find((el) => el.tagName !== 'OPTION');
    text = label?.parentElement?.textContent ?? '';
    expect(text).toMatch(/fr_FR/);
  }, LOAD);
  return text;
}

describe('Enroll: the Setup fact', () => {
  it('keeps the settings in view while the other entries are not valid JSON', async () => {
    serve(CLUB);
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const text = await setupFact();
    expect(text).toMatch(/fr_FR · mobile data/);
    expect(text).toMatch(/other Extra QR parameters left out: invalid JSON/);
    await settled();
  });

  it('says how many Wi-Fi entries stay out of the code, and how many entries go in unchecked', async () => {
    serve({
      ...CLUB,
      qrParameters: CLUB.qrParameters.replace('"x.y": tru', '"android.app.extra.PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT": true,\n"android.app.extra.PROVISIONING_FUTURE": true,\n"x.y": 1'),
    });
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const text = await setupFact();
    expect(text).toMatch(/2 other entries \(1 not checked by MDMesh\) · 1 Wi-Fi entry left out: .* · 1 entry left out: not a provisioning entry/);
    await settled();
  });
});

describe('Enroll: a configuration that adds nothing', () => {
  it('still shows the Setup fact, saying nothing is added', async () => {
    serve({ id: 5, name: 'Club', qrParameters: null, mobileEnrollment: false, wifiSSID: '' });
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));
    const select = await main.findByRole('combobox', { name: 'Configuration' }, LOAD);
    await waitFor(() => chooseOption(select, 'Club'), LOAD);
    await waitFor(() => {
      const label = main.getAllByText('Setup').find((el) => el.tagName !== 'OPTION');
      expect(label?.parentElement?.textContent).toMatch(/Nothing added/);
    }, LOAD);
    await settled();
  });
});
