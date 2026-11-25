// #222: the agent reports `wipeNotStarted` when an accepted factory reset didn't reboot the device in time. It is a
// warning wherever device events show, like the kiosk's crash loop, never the plain info row.
import { screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { EVENT_LABELS, EVENT_VERBS } from '../data/deviceActivity';
import { IconAlert } from '../ui/icons';
import { AppRoutes } from '../App';
import { renderInConsole, settled, stubApi } from '../test/console';
import { EventIcon } from './DeviceDetailTabs';
import { eventTone } from './deviceDetailFormat';
import { eventView } from './overview/eventView';
import { render } from '../test/render';

/** What the agent sends with the event (agent AgentModule WIPE_NOT_STARTED_DETAIL). */
const DETAIL = "The device didn't reset within 10 minutes. It is back in use, and its configuration is applied again.";
const markup = (node: ReactNode) => render(<>{node}</>).container.innerHTML;

describe('wipeNotStarted event', () => {
  it('has a label and a verb', () => {
    expect(EVENT_LABELS.wipeNotStarted).toBe('Factory reset didn’t start');
    expect(EVENT_VERBS.wipeNotStarted).toBe('didn’t start its factory reset');
  });

  it('is a warning with the alert icon on the device page', () => {
    expect(eventTone('wipeNotStarted')).toBe('warn');
    expect(markup(<EventIcon type="wipeNotStarted" />)).toBe(markup(<IconAlert size={15} />));
  });

  it('is a warning with the alert icon on the Overview', () => {
    const view = eventView({ id: 1, type: 'wipeNotStarted', ts: 0, detail: 'x' }, {});
    expect(view.tone).toBe('warn');
    expect(view.what).toBe('didn’t start its factory reset');
    expect(view.detail).toBe('x');
    expect(markup(view.icon)).toBe(markup(<IconAlert size={15} />));
  });

  it('shows in the Overview’s recent activity with the agent’s detail', async () => {
    const now = Date.now();
    const device = { id: 1, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: now };
    stubApi((path) => {
      const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
      if (p === '/devices/search') return { devices: { items: [device], totalItemsCount: 1 }, configurations: {} };
      if (p === '/agent/v1/devices/WH-1/events') return [{ id: 9, type: 'wipeNotStarted', ts: now - 60_000, detail: DETAIL }];
      return null;
    });
    renderInConsole(<AppRoutes />, { path: '/' });
    const feed = within(await screen.findByRole('list', { name: 'Recent activity' }, { timeout: 10_000 }));
    const row = (await feed.findByText(DETAIL, undefined, { timeout: 10_000 })).closest('li')!;
    expect(row.textContent).toContain('WH-1 didn’t start its factory reset');
    expect(row.textContent).not.toContain('Front desk');
    await settled();
  });
});
