// Settings' release notes come from the release feed at runtime, so only https links render as links (as the
// release URL next to them): http, paths in the console and anything else show as their label.
import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { UpdateStatus } from '../../api/updates';
import { renderInConsole, settled, stubApi } from '../../test/console';
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
    await settled();
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
  const render = async (over: Partial<UpdateStatus>) => {
    stubApi();
    const status = { ...STATUS, current: '0.3.6', ...over } as UpdateStatus;
    const sup = { status, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
    await settled();
  };

  it('names the skipped release, links its page, and keeps the update below', async () => {
    await render({ missedSecurity: ['0.3.7'] });
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
    await render({ current: '0.3.5', missedSecurity: ['0.3.6', '0.3.7'] });
    const notice = await screen.findByRole('region', { name: 'Missing security fixes' });
    expect(notice.textContent).toContain('0.3.6 and 0.3.7 fixed security issues');
    expect(within(notice).getByRole('link', { name: 'Release notes ↗' }).getAttribute('href')).toBe(
      'https://github.com/MDMesh-app/MDMesh/releases',
    );
  });

  it('is absent when nothing is skipped or the supervisor predates the field', async () => {
    await render({ missedSecurity: [] });
    await screen.findByText(/is ready/);
    expect(screen.queryByRole('region', { name: 'Missing security fixes' })).toBeNull();
  });
});

describe('Settings → Updates: a release this updater cannot install', () => {
  const REASON =
    'the signed release 0.3.8 has no images for this host (linux/arm64), only for linux/amd64, so it cannot be applied';
  const render = async (over: Partial<UpdateStatus>, base: UpdateStatus = STATUS) => {
    stubApi();
    const status = { ...base, ...over } as UpdateStatus;
    const sup = { status, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
    await settled();
  };

  it('says so once, with the next step; no Update button and no "latest release" line', async () => {
    await render({ updateAvailable: false, blocked: REASON, blockedCode: 'platform' });
    const text = (await screen.findByText(/can’t be installed on this server/)).textContent;
    expect(text).toMatch(/^MDMesh 0\.3\.8 can’t be installed on this server\. Its images don’t include this server’s platform\./);
    expect(text).toMatch(/native install/);
    expect(screen.queryByRole('button', { name: 'Update server and console' })).toBeNull();
    expect(screen.queryByText(/is the latest release/)).toBeNull();
    expect(screen.queryByText(/is ready/)).toBeNull();
  });

  it('is quiet (info) unless it leaves security fixes missing (warn), as in the sidebar and on the Overview', async () => {
    await render({ updateAvailable: false, blocked: REASON, blockedCode: 'platform', missedSecurity: [] });
    const quiet = (await screen.findByText(/can’t be installed on this server/)).closest('.v2-callout') as HTMLElement;
    expect(quiet.classList.contains('v2-callout--info')).toBe(true);
  });

  it('is amber when it leaves security fixes missing', async () => {
    await render({ updateAvailable: false, blocked: REASON, blockedCode: 'owner', missedSecurity: ['0.3.8'], current: '0.3.6' });
    const loud = (await screen.findByText(/can’t be installed on this server/)).closest('.v2-callout') as HTMLElement;
    expect(loud.classList.contains('v2-callout--warn')).toBe(true);
  });

  it('keeps the missing-security notice, saying the release can’t be installed here', async () => {
    await render({ updateAvailable: false, blocked: REASON, blockedCode: 'owner', missedSecurity: ['0.3.8'], current: '0.3.6' });
    const notice = await screen.findByRole('region', { name: 'Missing security fixes' });
    expect(notice.textContent).toContain('0.3.8 fixed security issues this server doesn’t have yet. It runs 0.3.6, and can’t install 0.3.8 itself (below).');
    expect(screen.getByText(/Set GITHUB_REPO or IMAGE_OWNER/)).toBeTruthy();
  });

  it('is absent when the supervisor predates the field (no blocked key at all)', async () => {
    const { blocked: _b, blockedCode: _c, ...older } = { ...STATUS, updateAvailable: false, latest: '0.3.7' } as UpdateStatus;
    expect('blocked' in older).toBe(false);
    await render({}, older as UpdateStatus);
    await screen.findByText(/is the latest release/);
    expect(screen.queryByText(/can’t be installed/)).toBeNull();
  });
});

describe('Settings → Updates: an unverified newer release', () => {
  it('is not also called "the latest release"', async () => {
    stubApi();
    const status = { ...STATUS, updateAvailable: false, verified: false } as UpdateStatus;
    const sup = { status, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
    await settled();
    await screen.findByText(/its signature couldn’t be verified/);
    expect(screen.queryByText(/is the latest release/)).toBeNull();
  });
});

describe('Settings → Updates: the new release’s agent', () => {
  it('names the new release’s agent, not the one the updater offers now (the running release’s)', async () => {
    stubApi();
    // The updater offers 0.3.7's agent (the server runs 0.3.7); 0.3.8 is ready.
    const status = { ...STATUS, apk: { version: '0.3.7', versionCode: 307, sha256: 'a', available: true, of: 'running' } } as UpdateStatus;
    const sup = { status, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
    await settled();
    const line = await screen.findByText(/The release carries agent/);
    expect(line.textContent).toBe('The release carries agent 0.3.8. Once the server runs it, roll it out to devices.');
  });
});

describe('Settings → Updates: the new release’s agent when the updater already offers it', () => {
  it('says nothing about waiting for the server (Agent rollout offers it now)', async () => {
    stubApi();
    // The running release is unknown, so the updater offers the latest release's agent (0.3.8) now.
    const status = { ...STATUS, apk: { version: '0.3.8', versionCode: 308, sha256: 'a', available: true, of: 'latest' } } as UpdateStatus;
    const sup = { status, loaded: true, reachable: true, refresh: async () => {}, setStatus: () => {}, patch: () => {} };
    renderInConsole(<UpdatesSection id="updates" sup={sup} rolloutHref="#agent-rollout" />, { path: '/settings' });
    await settled();
    await screen.findByText(/is ready/);
    expect(screen.queryByText(/The release carries agent/)).toBeNull();
  });
});
