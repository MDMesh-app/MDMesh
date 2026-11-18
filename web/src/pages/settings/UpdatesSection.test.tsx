// Settings' release notes come from the release feed at runtime, so only https links render as links (as the
// release URL next to them): http, paths in the console and anything else show as their label.
import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { UpdateStatus } from '../../api/updates';
import { renderInConsole, stubApi } from '../../test/console';
import { UpdatesSection } from './UpdatesSection';

const links = (n: string) =>
  `[${n}a](https://x.example/${n}) [${n}b](http://x.example/${n}) [${n}c](/settings) [${n}d](javascript:alert(1))`;

const NOTES = [
  'v0.3.8',
  '',
  `Headline ${links('h')}.`,
  '',
  `More ${links('p')}.`,
  '',
  'Fixes',
  `- Item ${links('i')}.`,
  `  - Nested ${links('n')}.`,
].join('\n');

const STATUS = {
  current: '0.3.7', latest: '0.3.8', updateAvailable: true, verified: true, channel: 'stable', checkedAt: Date.now(),
  error: null, apply: null, auto: false, apk: null,
  release: { notes: NOTES, url: 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.3.8', publishedAt: null },
} as unknown as UpdateStatus;

describe('Settings release notes: links from the feed', () => {
  it('render only https links as links, in the headline, a paragraph, an item and a nested item', async () => {
    stubApi();
    const sup = { status: STATUS, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
    const notes = within((await screen.findByText(/^Headline/)).closest('.v2-relnotes') as HTMLElement);
    for (const at of ['h', 'p', 'i', 'n']) {
      expect(notes.getByRole('link', { name: `${at}a` }).getAttribute('href')).toBe(`https://x.example/${at}`);
      for (const other of ['b', 'c']) {
        expect(notes.queryByRole('link', { name: `${at}${other}` })).toBeNull();
        expect(notes.getByText(`${at}${other}`)).toBeTruthy();
      }
      // A javascript: address is never a link (the parser leaves it as the text it was written as).
      expect(notes.queryByRole('link', { name: new RegExp(`${at}d`) })).toBeNull();
    }
    const box = (await screen.findByText(/^Headline/)).closest('.v2-relnotes') as HTMLElement;
    expect([...box.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual(
      ['h', 'p', 'i', 'n'].map((at) => `https://x.example/${at}`),
    );
  });
});

describe('Settings → Updates: missing security fixes', () => {
  const render = (over: Partial<UpdateStatus>) => {
    stubApi();
    const status = { ...STATUS, current: '0.3.6', ...over } as UpdateStatus;
    const sup = { status, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
  };

  it('names the skipped release, links its page, and keeps the update below', async () => {
    render({ missedSecurity: ['0.3.7'] });
    const notice = await screen.findByRole('region', { name: 'Missing security fixes' });
    expect(notice.textContent).toContain(
      '0.3.7 fixed security issues this server doesn’t have yet. It runs 0.3.6, and updating to 0.3.8 includes those fixes.',
    );
    expect(within(notice).getByRole('link', { name: 'What 0.3.7 fixed ↗' }).getAttribute('href')).toBe(
      'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.3.7',
    );
    expect(notice.classList.contains('v2-notice--warn')).toBe(true);
    // Above the ready release, whose Update button is still there.
    const ready = screen.getByText(/is ready/).closest('.v2-settings-upd') as HTMLElement;
    expect(notice.compareDocumentPosition(ready) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Update server and console' })).toBeTruthy();
  });

  it('names several and links the releases list', async () => {
    render({ current: '0.3.5', missedSecurity: ['0.3.6', '0.3.7'] });
    const notice = await screen.findByRole('region', { name: 'Missing security fixes' });
    expect(notice.textContent).toContain('0.3.6 and 0.3.7 fixed security issues');
    expect(within(notice).getByRole('link', { name: 'Release notes ↗' }).getAttribute('href')).toBe(
      'https://github.com/MDMesh-app/MDMesh/releases',
    );
  });

  it('is absent when nothing is skipped or the supervisor predates the field', async () => {
    render({ missedSecurity: [] });
    await screen.findByText(/is ready/);
    expect(screen.queryByRole('region', { name: 'Missing security fixes' })).toBeNull();
  });
});
