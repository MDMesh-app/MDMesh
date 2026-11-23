// Which notes a console shows (bundle.ts), and the seen marks when the browser's storage refuses (seen.ts).
import { describe, expect, it, vi } from 'vitest';
import { releaseNotesUrl, UNRELEASED, whatsNewTarget } from './bundle';
import { isSeen, markSeen, readHistoryAgain, whatsNewOnSignIn } from './seen';

const bundled = vi.hoisted(() => ({ hasUnreleased: false }));
vi.mock('virtual:mdmesh-changelog', () => ({
  versions: ['0.4.0', '0.3.7'],
  get hasUnreleased() {
    return bundled.hasUnreleased;
  },
  load: async () => ({ sections: {}, unreleased: null }),
}));

describe('whatsNewTarget', () => {
  it('takes the server’s version first, then the build’s, the first one this build has notes for', () => {
    expect(whatsNewTarget(['0.4.0', '0.3.7'])).toEqual({ id: '0.4.0', version: '0.4.0' });
    expect(whatsNewTarget(['0.9.9', '0.3.7'])).toEqual({ id: '0.3.7', version: '0.3.7' });
    // The supervisor unreachable: the build's version (VITE_APP_VERSION).
    expect(whatsNewTarget([undefined, '0.3.7'])).toEqual({ id: '0.3.7', version: '0.3.7' });
    expect(whatsNewTarget([null, null])).toBeNull();
  });

  it('reads a "v" prefix, and takes only release versions', () => {
    expect(whatsNewTarget(['v0.4.0'])).toEqual({ id: '0.4.0', version: '0.4.0' });
    expect(whatsNewTarget(['0.4.0-rc.1', 'dev', '0.0.0'])).toBeNull();
  });

  it('shows Unreleased in a dev build that has it, whatever the version', () => {
    bundled.hasUnreleased = true;
    try {
      expect(whatsNewTarget(['0.4.0'])).toBe(UNRELEASED);
      expect(whatsNewTarget([])).toBe(UNRELEASED);
    } finally {
      bundled.hasUnreleased = false;
    }
  });

  it('links the GitHub release of a version, and the changelog for Unreleased', () => {
    expect(releaseNotesUrl({ id: '0.4.0', version: '0.4.0' })).toBe('https://github.com/MDMesh-app/MDMesh/releases/tag/v0.4.0');
    expect(releaseNotesUrl(UNRELEASED)).toBe('https://github.com/MDMesh-app/MDMesh/blob/main/CHANGELOG.md');
  });
});

describe('seen marks without storage', () => {
  it('never opens by itself and never throws when localStorage refuses', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    readHistoryAgain();
    expect(isSeen('0.4.0')).toBe(false);
    expect(whatsNewOnSignIn('0.4.0')).toBe('fresh');
    expect(() => markSeen('0.4.0')).not.toThrow();
  });

  it('doesn’t open by itself when storage reads but won’t write, and remembers seen for the page load', () => {
    // A browser with history (the stored sign-in reads fine) whose storage refuses every write.
    localStorage.setItem('hmdm.admin.user', '{}');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    readHistoryAgain();
    // Opening it would happen again on every page load, since "seen" can't be kept: it is remembered instead.
    expect(whatsNewOnSignIn('0.4.0')).toBe('fresh');
    markSeen('0.4.0');
    expect(isSeen('0.4.0')).toBe(true);
    expect(whatsNewOnSignIn('0.4.0')).toBe('seen');
    // Memory is this page load's only: the next one starts without it.
    readHistoryAgain();
    expect(isSeen('0.4.0')).toBe(false);
  });

  it('opens by itself with storage that writes, after an upgrade', () => {
    localStorage.setItem('hmdm.admin.user', '{}');
    readHistoryAgain();
    expect(whatsNewOnSignIn('0.4.0')).toBe('show');
  });
});
