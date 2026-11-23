// The sidebar's update card and the Overview's Server card when the offered update skips security releases: both
// amber, in the same words, and the plain "verified" wording otherwise.
import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { UpdateStatus } from '../api/updates';
import { SupervisorProvider } from '../data/useSupervisor';
import { ServerCard } from '../pages/overview/ServerCard';
import { ROLES, renderInConsole } from '../test/console';
import { Sidebar } from './Sidebar';

const STATUS: UpdateStatus = {
  current: '0.1.12', latest: '0.1.15', updateAvailable: true, verified: true, channel: 'stable', checkedAt: 0,
  error: null, apply: null, auto: false, apk: null, applySupported: true, missedSecurity: ['0.1.13'],
  release: { notes: null, url: 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.1.15', publishedAt: null },
};
const LINE = 'Includes security fixes from 0.1.13 that this server is missing.';

function stub(status: UpdateStatus) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const body = String(input).includes('/update/status') ? status : { status: 'OK', message: null, data: null };
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
}
const sidebar = (status: UpdateStatus, user = ROLES.superAdmin) => {
  stub(status);
  renderInConsole(<SupervisorProvider><Sidebar /></SupervisorProvider>, { user });
};

afterEach(() => vi.unstubAllGlobals());

describe('sidebar update card', () => {
  it('is amber and names the skipped release, without calling the offered one a security release', async () => {
    sidebar(STATUS);
    const card = (await screen.findByText(LINE)).closest('.v2-updcard') as HTMLElement;
    expect(card.querySelector('.v2-updcard__title')?.textContent).toBe('Update 0.1.15');
    expect(card.querySelector('.v2-updcard__dot.is-warn')).toBeTruthy();
  });
  it('still tells someone who can’t apply it who can', async () => {
    sidebar(STATUS, ROLES.user);
    expect(await screen.findByText(`${LINE} An admin can apply it.`)).toBeTruthy();
  });
  it('reads as before when nothing is skipped', async () => {
    sidebar({ ...STATUS, missedSecurity: [] });
    const card = (await screen.findByText(/Verified release/)).closest('.v2-updcard') as HTMLElement;
    expect(card.querySelector('.v2-updcard__title')?.textContent).toBe('Server update 0.1.15');
    expect(card.querySelector('.v2-updcard__dot.is-ok')).toBeTruthy();
  });
});

describe('Overview Server card', () => {
  it('says the same in amber instead of the green verified line', () => {
    stub(STATUS);
    renderInConsole(<ServerCard status={STATUS} />);
    const line = screen.getByText(LINE).closest('.v2-ov-srv__line') as HTMLElement;
    expect(line.classList.contains('is-warn')).toBe(true);
    expect(screen.queryByText(/Signature verified/)).toBeNull();
  });
  it('keeps the verified line when nothing is skipped', () => {
    stub(STATUS);
    renderInConsole(<ServerCard status={{ ...STATUS, missedSecurity: undefined }} />);
    expect(screen.getByText(/Signature verified/)).toBeTruthy();
    expect(screen.queryByText(LINE)).toBeNull();
  });
});

describe('a release this server can’t install (blocked)', () => {
  const BLOCKED: UpdateStatus = { ...STATUS, updateAvailable: false, blocked: 'r', blockedCode: 'owner', missedSecurity: [] };
  const SEC = 'This server is missing security fixes from 0.1.13 and 0.1.15, and can’t install them itself.';
  it('sidebar: quiet with its cause, amber when security fixes are missing', async () => {
    sidebar(BLOCKED);
    const card = (await screen.findByText('Its images are published under another owner.')).closest('.v2-updcard') as HTMLElement;
    expect(card.querySelector('.v2-updcard__title')?.textContent).toBe('0.1.15 can’t be installed');
    // Neutral, not the green of a verified update nor amber.
    const dot = card.querySelector('.v2-updcard__dot') as HTMLElement;
    expect(dot.classList.contains('is-idle')).toBe(true);
    expect(dot.classList.contains('is-warn') || dot.classList.contains('is-ok')).toBe(false);
    vi.unstubAllGlobals();
  });
  it('sidebar: says which security fixes are missing, in amber', async () => {
    sidebar({ ...BLOCKED, missedSecurity: ['0.1.13', '0.1.15'] });
    const card = (await screen.findByText(SEC)).closest('.v2-updcard') as HTMLElement;
    expect(card.querySelector('.v2-updcard__dot.is-warn')).toBeTruthy();
  });
  it('Overview: "can’t be installed here", quiet or amber the same way', () => {
    stub(BLOCKED);
    const { unmount } = renderInConsole(<ServerCard status={BLOCKED} />);
    expect(screen.getByText('0.1.15 can’t be installed here')).toBeTruthy();
    const line = screen.getByText('Its images are published under another owner.').closest('.v2-ov-srv__line') as HTMLElement;
    expect(line.classList.contains('is-warn')).toBe(false);
    expect(screen.queryByText(/Up to date/)).toBeNull();
    unmount();
    renderInConsole(<ServerCard status={{ ...BLOCKED, missedSecurity: ['0.1.13', '0.1.15'] }} />);
    expect((screen.getByText(SEC).closest('.v2-ov-srv__line') as HTMLElement).classList.contains('is-warn')).toBe(true);
  });
});

describe('Overview Server card: no "Up to date" while a newer release can’t be trusted or the check failed', () => {
  it('says a newer unverified release was found', () => {
    // As the supervisor sends it: a manifest that didn't verify sets the check's error too.
    const st: UpdateStatus = { ...STATUS, updateAvailable: false, verified: false, missedSecurity: [],
      error: 'manifest not verified: minisign signature check failed (details in the supervisor log)' };
    stub(st);
    renderInConsole(<ServerCard status={st} />);
    expect(screen.getByText('0.1.15 was found, but its signature couldn’t be verified.')).toBeTruthy();
    expect(screen.queryByText(/The last update check failed/)).toBeNull();
    expect(screen.queryByText(/Up to date/)).toBeNull();
  });
  it('an unverified release older than the running one is just a failed check', () => {
    const st: UpdateStatus = { ...STATUS, current: '0.1.16', updateAvailable: false, verified: false, missedSecurity: [],
      error: 'manifest not verified: minisign signature check failed (details in the supervisor log)' };
    stub(st);
    renderInConsole(<ServerCard status={st} />);
    expect(screen.getByText('The last update check failed.')).toBeTruthy();
  });
  it('a plain check failure still says so', () => {
    const st: UpdateStatus = { ...STATUS, latest: null, updateAvailable: false, verified: false, missedSecurity: [], error: 'github 500' };
    stub(st);
    renderInConsole(<ServerCard status={st} />);
    expect(screen.getByText('The last update check failed.')).toBeTruthy();
  });
});
