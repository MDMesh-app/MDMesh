// #222: the agent reports `wipeNotStarted` when an accepted factory reset didn't reboot the device in time. It is a
// warning wherever device events show, like the kiosk's crash loop, never the plain info row.
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { EVENT_LABELS, EVENT_VERBS } from '../data/deviceActivity';
import { IconAlert } from '../ui/icons';
import { EventIcon } from './DeviceDetailTabs';
import { eventTone } from './deviceDetailFormat';
import { eventView } from './overview/eventView';

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
    const view = eventView({ id: 1, type: 'wipeNotStarted', ts: 0, detail: 'x' }, { id: 1, number: 'WH-1' }, {});
    expect(view.tone).toBe('warn');
    expect(view.what).toBe('didn’t start its factory reset');
    expect(markup(view.icon)).toBe(markup(<IconAlert size={15} />));
  });
});
