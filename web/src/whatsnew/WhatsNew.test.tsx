// The What's new dialog in the console: once per version per browser after sign-in, never on a first visit or over
// the signed-out pages, and again from Settings. The bundled notes are this file's (the virtual module, mocked).
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { ROLES, renderInConsole, stubApi } from '../test/console';
import { parseNotes } from './changelog';
import { NotesView } from './NotesView';
import { readHistoryAgain, seenKey } from './seen';

const BODY = [
  'Upgrading',
  '- Update the server, then roll out agent 0.4.0.',
  '',
  'Heads-up',
  '- The console is new.',
  '',
  'Fixes',
  '- Fixed a thing in **Settings**.',
  '',
  'Under the hood',
  '- The console has unit tests (`vitest`).',
  '- Dependency updates.',
  '',
  'New',
  '- A device’s open tab is in its address. See [Updates](/settings#updates) and [GitHub](https://github.com/MDMesh-app/MDMesh).',
].join('\n');

vi.mock('virtual:mdmesh-changelog', () => ({
  // 0.3.6's section is listed but doesn't load.
  versions: ['0.4.0', '0.3.7', '0.3.6'],
  hasUnreleased: false,
  load: async () => ({
    sections: {
      '0.4.0': { version: '0.4.0', date: '2026-11-04', body: BODY },
      '0.3.7': { version: '0.3.7', date: '2026-10-22', body: 'Fixes\n- Kiosk fixes.' },
    },
    unreleased: null,
  }),
}));

const status = (current: string) => ({
  current, latest: current, updateAvailable: false, verified: true, channel: 'stable', checkedAt: 0, error: null,
  apply: null, auto: false, apk: null, release: null,
});

/** This page load: what the browser held before it (history is read as the console starts), then the console at `path`. */
function visit(
  path: string,
  {
    before = {},
    current = '0.4.0',
    user = ROLES.superAdmin,
    override,
  }: {
    before?: Record<string, string>;
    current?: string;
    user?: typeof ROLES.superAdmin | null;
    /** An answer of its own for some calls; undefined for the usual one. */
    override?: (path: string) => Response | Promise<Response> | undefined;
  } = {},
) {
  for (const [k, v] of Object.entries(before)) localStorage.setItem(k, v);
  readHistoryAgain();
  const fetch = stubApi();
  const rest = fetch.getMockImplementation();
  fetch.mockImplementation(async (input, init) => {
    const own = override?.(String(input));
    if (own) return own;
    return String(input).startsWith('/update/status') ? new Response(JSON.stringify(status(current))) : rest!(input, init);
  });
  renderInConsole(<AppRoutes />, { path, user });
  return fetch;
}

const SIGNED_IN = { 'hmdm.admin.user': JSON.stringify(ROLES.superAdmin) };
const dialog = () => screen.queryByRole('dialog', { name: /What’s new/ });
/** Long enough for the status, the notes and a dialog that would open. */
const settle = () => waitFor(() => expect(screen.getByRole('main')).toBeTruthy()).then(() => new Promise((r) => setTimeout(r, 50)));

describe('What’s new: when it opens by itself', () => {
  it('opens once after an upgrade (this browser used the console before), and not again', async () => {
    visit('/devices', { before: SIGNED_IN });
    const d = await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 });
    expect(within(d).getByText('Released 2026-11-04.')).toBeTruthy();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBe('seen');
    fireEvent.click(within(d).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(dialog()).toBeNull());

    // The next page load, same browser.
    cleanup();
    visit('/devices');
    await settle();
    expect(dialog()).toBeNull();
  });

  it('opens after an upgrade from a version that already had it', async () => {
    visit('/', { before: { [seenKey('0.3.7')]: 'seen' }, user: ROLES.admin });
    expect(await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 })).toBeTruthy();
  });

  it('stays closed on a version this browser has seen', async () => {
    visit('/', { before: { ...SIGNED_IN, [seenKey('0.4.0')]: 'seen' } });
    await settle();
    expect(dialog()).toBeNull();
  });

  it('stays closed on a first visit (a fresh install, a new browser), and remembers the version as seen', async () => {
    visit('/');
    await waitFor(() => expect(localStorage.getItem(seenKey('0.4.0'))).toBe('seen'));
    await settle();
    expect(dialog()).toBeNull();
  });

  it('stays closed for a version this console has no notes for', async () => {
    visit('/', { before: SIGNED_IN, current: '0.9.9' });
    await settle();
    expect(dialog()).toBeNull();
    expect(localStorage.getItem(seenKey('0.9.9'))).toBeNull();
  });

  it('opens after an upgrade from a v0.3 tab that lost its sign-in when the server restarted', async () => {
    // What a v0.3.9 console leaves in the browser: it writes its theme and density on every load, and its sign-in.
    localStorage.setItem('mdmesh-theme', 'light');
    localStorage.setItem('mdmesh-density', 'comfortable');
    localStorage.setItem('hmdm.admin.user', JSON.stringify(ROLES.superAdmin));
    // The update restarts the server, the open v0.3.9 tab's next poll gets 401, and that console deletes its sign-in.
    localStorage.removeItem('hmdm.admin.user');
    // The admin reloads the tab: 0.4.0 loads signed out.
    visit('/devices', {
      user: null,
      override: (path) =>
        path.includes('/public/auth/login')
          ? new Response(JSON.stringify({ status: 'OK', message: null, data: ROLES.superAdmin }))
          : undefined,
    });
    expect(await screen.findByRole('button', { name: 'Sign in' }, { timeout: 3000 })).toBeTruthy();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBeNull();
    fireEvent.change(screen.getByLabelText('Login'), { target: { value: 'admin' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 })).toBeTruthy();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBe('seen');
  });

  it('counts the v0.3 density key as history, but not the theme key this console writes itself', async () => {
    visit('/', { before: { 'mdmesh-density': 'compact' } });
    expect(await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 })).toBeTruthy();
    cleanup();
    localStorage.clear();
    // A browser whose first visit (the sign-in page, say) was this console's: only the theme key, so still a first visit.
    visit('/', { before: { 'mdmesh-theme': 'system' } });
    await waitFor(() => expect(localStorage.getItem(seenKey('0.4.0'))).toBe('seen'));
    await settle();
    expect(dialog()).toBeNull();
  });

  it('waits for the server to accept a stored sign-in: an expired session opens it only after signing in again', async () => {
    let expired = true;
    visit('/devices', {
      before: SIGNED_IN,
      // The refusal comes late, after the version and the notes: the dialog must not have opened (or been marked) meanwhile.
      override: (path) =>
        expired && path.includes('/private/')
          ? new Promise<Response>((r) => setTimeout(() => r(new Response('', { status: 401 })), 150))
          : path.includes('/public/auth/login')
            ? new Response(JSON.stringify({ status: 'OK', message: null, data: ROLES.superAdmin }))
            : undefined,
    });
    // The shell mounts from the stored sign-in, the session is refused, and the sign-in page replaces it.
    expect(await screen.findByRole('button', { name: 'Sign in' }, { timeout: 3000 })).toBeTruthy();
    expect(dialog()).toBeNull();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBeNull();

    expired = false;
    fireEvent.change(screen.getByLabelText('Login'), { target: { value: 'admin' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 })).toBeTruthy();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBe('seen');
  });

  it('doesn’t decide while an update is still applying', async () => {
    const apply = { phase: 'pull', fromVersion: '0.3.7', toVersion: '0.4.0', trigger: 'manual', startedAt: 0, finishedAt: null, error: null };
    visit('/', {
      before: SIGNED_IN,
      override: (path) => (path.startsWith('/update/status') ? new Response(JSON.stringify({ ...status('0.4.0'), apply })) : undefined),
    });
    await settle();
    expect(dialog()).toBeNull();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBeNull();
  });

  it('opens by itself once per page load, even when the server’s version changes after', async () => {
    visit('/settings', {
      before: SIGNED_IN,
      current: '0.3.7',
      // A later answer (Check now) reports another version this console has notes for.
      override: (path) => (path.startsWith('/update/check') ? new Response(JSON.stringify(status('0.4.0'))) : undefined),
    });
    const first = await screen.findByRole('dialog', { name: 'What’s new in 0.3.7' }, { timeout: 3000 });
    fireEvent.click(within(first).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(dialog()).toBeNull());
    fireEvent.click(await screen.findByRole('button', { name: 'Check now' }, { timeout: 3000 }));
    // Settings' link follows the new version; the dialog stays closed, and 0.4.0 is still unseen for the next load.
    const link = await screen.findByRole('button', { name: 'What’s new' });
    await waitFor(() => expect(link.closest('dd')?.textContent).toContain('0.4.0'));
    await settle();
    expect(dialog()).toBeNull();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBeNull();
    // It still opens by hand.
    fireEvent.click(link);
    expect(await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' })).toBeTruthy();
  });

  it('notes that didn’t load don’t use up the page load’s one opening', async () => {
    visit('/settings', {
      before: SIGNED_IN,
      current: '0.3.6',
      override: (path) => (path.startsWith('/update/check') ? new Response(JSON.stringify(status('0.4.0'))) : undefined),
    });
    await settle();
    expect(dialog()).toBeNull();
    expect(localStorage.getItem(seenKey('0.3.6'))).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: 'Check now' }, { timeout: 3000 }));
    expect(await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 })).toBeTruthy();
  });

  it.each(['/login', '/set-password'])('never opens over %s', async (path) => {
    visit(path, { before: SIGNED_IN, user: null });
    await new Promise((r) => setTimeout(r, 100));
    expect(dialog()).toBeNull();
    expect(localStorage.getItem(seenKey('0.4.0'))).toBeNull();
  });
});

describe('What’s new: the dialog', () => {
  it('opens again from Settings, beside the version, and gives focus back to the link', async () => {
    visit('/settings', { before: { ...SIGNED_IN, [seenKey('0.4.0')]: 'seen' } });
    const link = await screen.findByRole('button', { name: 'What’s new' }, { timeout: 3000 });
    expect(link.closest('dd')?.textContent).toContain('0.4.0');
    link.focus();
    fireEvent.click(link);
    const d = await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' });
    // New first, then Heads-up, Upgrading and Fixes; Under the hood behind its toggle.
    expect(within(d).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['New', 'Heads-up', 'Upgrading', 'Fixes']);
    // One label, open or not: aria-expanded says which.
    const more = within(d).getByRole('button', { name: 'Under the hood (2)' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(more);
    expect(within(d).getByRole('heading', { name: 'Under the hood' })).toBeTruthy();
    expect(within(d).getByRole('button', { name: 'Under the hood (2)' })).toBe(more);
    expect(more.getAttribute('aria-expanded')).toBe('true');
    expect(within(d).getByText('vitest').tagName).toBe('CODE');
    expect(within(d).getByText('Settings').tagName).toBe('STRONG');
    expect(within(d).getByRole('link', { name: 'Full release notes on GitHub ↗' }).getAttribute('href')).toBe(
      'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.4.0',
    );
    // Focus starts at the top of the notes (a long release opens at its title), inside the dialog.
    expect(d.contains(document.activeElement)).toBe(true);
    expect(document.activeElement?.className).toContain('v2-whatsnew__start');
    fireEvent.click(within(d).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(dialog()).toBeNull());
    expect(document.activeElement).toBe(link);
  });

  it('has no link in Settings for a version without notes', async () => {
    visit('/settings', { before: SIGNED_IN, current: '0.9.9' });
    const server = await screen.findByRole('region', { name: 'Server' }, { timeout: 3000 });
    await within(server).findByText('0.9.9', undefined, { timeout: 3000 });
    expect(screen.queryByRole('button', { name: 'What’s new' })).toBeNull();
  });

  it('closes when a link into the console is followed, and goes there', async () => {
    // jsdom doesn't scroll; the shell scrolls a #hash target into view.
    Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, value: () => {} });
    try {
      visit('/', { before: SIGNED_IN });
      const d = await screen.findByRole('dialog', { name: 'What’s new in 0.4.0' }, { timeout: 3000 });
      fireEvent.click(within(d).getByRole('link', { name: 'Updates' }));
      await waitFor(() => expect(dialog()).toBeNull());
      expect(await screen.findByRole('heading', { name: 'Settings', level: 1 }, { timeout: 3000 })).toBeTruthy();
    } finally {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    }
  });
});

describe('NotesView', () => {
  function show(body: string, onNavigate = () => {}) {
    const router = createMemoryRouter([{ path: '*', element: <NotesView notes={parseNotes(body)} onNavigate={onNavigate} /> }]);
    return render(<RouterProvider router={router} />);
  }

  it('renders only safe links: http(s) in a new tab, console paths in place, anything else as text', () => {
    const onNavigate = vi.fn();
    show(
      'New\n- [GitHub](https://github.com/MDMesh-app/MDMesh), [Updates](/settings#updates), [bad](javascript:void0), [file](RELEASING.md), <b>not bold</b>',
      onNavigate,
    );
    const gh = screen.getByRole('link', { name: 'GitHub' });
    expect(gh.getAttribute('href')).toBe('https://github.com/MDMesh-app/MDMesh');
    expect(gh.getAttribute('target')).toBe('_blank');
    expect(gh.getAttribute('rel')).toBe('noopener noreferrer');
    expect(screen.getByRole('link', { name: 'Updates' }).getAttribute('href')).toBe('/settings#updates');
    expect(screen.queryByRole('link', { name: 'bad' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'file' })).toBeNull();
    expect(screen.getByRole('listitem').textContent).toContain('bad, file, <b>not bold</b>');
    expect(document.querySelector('b')).toBeNull();
    // Ctrl/Cmd-click opens a new tab and leaves the dialog open here.
    const updates = screen.getByRole('link', { name: 'Updates' });
    updates.addEventListener('click', (e) => e.preventDefault()); // jsdom can't open the tab
    fireEvent.click(updates, { ctrlKey: true });
    fireEvent.click(updates, { metaKey: true });
    expect(onNavigate).not.toHaveBeenCalled();
    fireEvent.click(updates);
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it('shows Under the hood without a toggle when it is all there is', () => {
    show('Under the hood\n- Tests.');
    expect(screen.getByRole('heading', { name: 'Under the hood' })).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
