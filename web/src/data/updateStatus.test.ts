import { describe, expect, it } from 'vitest';
import type { UpdateStatus } from '../api/updates';
import {
  agentAfterServerUpdate,
  rolloutAheadOfServer,
  agentUnavailable,
  blockedShort,
  blockedText,
  joinVersions,
  missedSecurity,
  missedSecurityLink,
  missedSecurityShort,
  missedSecurityText,
  unverifiedRelease,
  updatesNavMeta,
  updateOffer,
  upToDate,
} from './updateStatus';

const status = (over: Partial<UpdateStatus> = {}): UpdateStatus => ({
  current: '0.1.12',
  latest: '0.1.15',
  updateAvailable: true,
  verified: true,
  channel: 'stable',
  checkedAt: 0,
  error: null,
  apply: null,
  auto: false,
  apk: null,
  release: { notes: null, url: 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.1.15', publishedAt: null },
  missedSecurity: ['0.1.13'],
  ...over,
});

describe('missedSecurity', () => {
  it('reads the supervisor’s list while an update is on offer', () => {
    expect(missedSecurity(status())).toEqual(['0.1.13']);
    expect(missedSecurity(status({ missedSecurity: ['0.1.13', '0.1.14'] }))).toEqual(['0.1.13', '0.1.14']);
  });
  it('is empty with nothing on offer, from an older supervisor, or for anything but versions', () => {
    expect(missedSecurity(status({ updateAvailable: false }))).toEqual([]);
    expect(missedSecurity(status({ latest: null }))).toEqual([]);
    expect(missedSecurity(status({ missedSecurity: undefined }))).toEqual([]);
    expect(missedSecurity(status({ missedSecurity: [] }))).toEqual([]);
    expect(missedSecurity(status({ missedSecurity: 'x' as unknown as string[] }))).toEqual([]);
    expect(missedSecurity(status({ missedSecurity: ['<b>', 1, '0.1.13'] as unknown as string[] }))).toEqual(['0.1.13']);
    expect(missedSecurity(null)).toEqual([]);
  });
});

describe('copy', () => {
  it('joins one, two and three versions', () => {
    expect(joinVersions(['0.1.13'])).toBe('0.1.13');
    expect(joinVersions(['0.1.13', '0.1.14'])).toBe('0.1.13 and 0.1.14');
    expect(joinVersions(['0.1.12', '0.1.13', '0.1.14'])).toBe('0.1.12, 0.1.13 and 0.1.14');
  });
  it('words the sidebar and Overview line', () => {
    expect(missedSecurityShort(['0.1.13'])).toBe('Includes security fixes from 0.1.13 that this server is missing.');
    expect(missedSecurityShort(['0.1.13', '0.1.14'])).toBe(
      'Includes security fixes from 0.1.13 and 0.1.14 that this server is missing.',
    );
    expect(missedSecurityShort(['0.1.12', '0.1.13', '0.1.14'])).toBe(
      'Includes security fixes from 0.1.12, 0.1.13 and 0.1.14 that this server is missing.',
    );
  });
  it('words the Updates notice', () => {
    expect(missedSecurityText(['0.1.13'], '0.1.12', '0.1.15')).toBe(
      '0.1.13 fixed security issues this server doesn’t have yet. It runs 0.1.12, and updating to 0.1.15 includes those fixes.',
    );
    expect(missedSecurityText(['0.1.13', '0.1.14'], '0.1.12', '0.1.15')).toBe(
      '0.1.13 and 0.1.14 fixed security issues this server doesn’t have yet. It runs 0.1.12, and updating to 0.1.15 includes those fixes.',
    );
  });
});

describe('missedSecurityLink', () => {
  const url = 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.1.15';
  it('links one missed release to its own page', () => {
    expect(missedSecurityLink(['0.1.13'], url)).toEqual({
      href: 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.1.13',
      label: 'What 0.1.13 fixed',
    });
  });
  it('links several to the releases list', () => {
    expect(missedSecurityLink(['0.1.13', '0.1.14'], url)).toEqual({
      href: 'https://github.com/MDMesh-app/MDMesh/releases',
      label: 'Release notes',
    });
  });
  it('follows a fork’s repository', () => {
    expect(missedSecurityLink(['0.1.13'], 'https://github.com/acme/mdm/releases/tag/v0.1.15')?.href).toBe(
      'https://github.com/acme/mdm/releases/tag/v0.1.13',
    );
  });
  it('has no link without an https release page', () => {
    expect(missedSecurityLink(['0.1.13'], 'http://github.com/MDMesh-app/MDMesh/releases/tag/v0.1.15')).toBeNull();
    expect(missedSecurityLink(['0.1.13'], 'javascript:alert(1)')).toBeNull();
    expect(missedSecurityLink(['0.1.13'], 'https://github.com/MDMesh-app/MDMesh/pull/1')).toBeNull();
    // Only github.com, the host the supervisor's release feed (api.github.com) links to.
    expect(missedSecurityLink(['0.1.13'], 'https://evil.example/MDMesh-app/MDMesh/releases/tag/v0.1.15')).toBeNull();
    expect(missedSecurityLink(['0.1.13'], 'https://github.com.evil.example/a/b/releases/tag/v0.1.15')).toBeNull();
    expect(missedSecurityLink(['0.1.13'], 'https://u:p@github.com/a/b/releases/tag/v0.1.15')).toBeNull();
    expect(missedSecurityLink(['0.1.13'], 'https://github.com:8443/a/b/releases/tag/v0.1.15')).toBeNull();
    expect(missedSecurityLink(['0.1.13'], null)).toBeNull();
    expect(missedSecurityLink([], url)).toBeNull();
  });
});

describe('updatesNavMeta (Settings section list)', () => {
  const apply = (phase: string) => ({ phase, fromVersion: '0.1.12', toVersion: '0.1.15', trigger: 'manual' as const, startedAt: 0, finishedAt: null, error: null });
  it('says "Security fixes" in amber when the update skips security releases', () => {
    expect(updatesNavMeta(status())).toEqual({ meta: 'Security fixes', metaTone: 'warn' });
  });
  it('says "1 ready" otherwise, and nothing with nothing on offer', () => {
    expect(updatesNavMeta(status({ missedSecurity: [] }))).toEqual({ meta: '1 ready', metaTone: 'ok' });
    expect(updatesNavMeta(status({ updateAvailable: false }))).toEqual({});
    expect(updatesNavMeta(null)).toEqual({});
  });
  it('puts a running or failed update first', () => {
    expect(updatesNavMeta(status({ apply: apply('pull') }))).toEqual({ meta: 'Updating', metaTone: 'warn' });
    expect(updatesNavMeta(status({ apply: apply('rolled_back') }))).toEqual({ meta: 'Failed', metaTone: 'alert' });
    expect(updatesNavMeta(status({ apply: apply('done') }))).toEqual({ meta: 'Security fixes', metaTone: 'warn' });
  });
});

describe('a release this server can’t install (blocked)', () => {
  const blocked = (over: Partial<UpdateStatus> = {}) =>
    status({ updateAvailable: false, blocked: 'the signed release 0.1.15 has no images …', blockedCode: 'platform', ...over });
  it('is still shown, with the security fixes it leaves missing (the release’s own included, from the supervisor)', () => {
    expect(updateOffer(blocked({ missedSecurity: ['0.1.13', '0.1.15'] }))).toEqual({
      kind: 'blocked', latest: '0.1.15', missed: ['0.1.13', '0.1.15'],
    });
    expect(updateOffer(blocked({ missedSecurity: [] }))).toEqual({ kind: 'blocked', latest: '0.1.15', missed: [] });
    expect(missedSecurity(blocked())).toEqual(['0.1.13']);
    expect(upToDate(blocked())).toBe(false);
    expect(updateOffer(status())?.kind).toBe('ready');
    expect(updateOffer(status({ updateAvailable: false }))).toBeNull();
  });
  it('says plainly when it leaves security fixes missing, and its cause otherwise', () => {
    expect(blockedShort(blocked(), ['0.1.13', '0.1.15'])).toBe(
      'This server is missing security fixes from 0.1.13 and 0.1.15, and can’t install them itself.',
    );
    expect(blockedShort(blocked(), [])).toBe('Its images don’t include this server’s platform.');
    expect(blockedShort(blocked({ blockedCode: 'owner' }), [])).toBe('Its images are published under another owner.');
    expect(blockedShort(blocked({ blockedCode: undefined }), [])).toBe('This server can’t install it itself.');
    expect(missedSecurityText(['0.1.15'], '0.1.12', '0.1.15', true)).toBe(
      '0.1.15 fixed security issues this server doesn’t have yet. It runs 0.1.12, and can’t install 0.1.15 itself (below).',
    );
  });
  it('gives the next step for each cause, and the supervisor’s words for an unknown one', () => {
    expect(blockedText(blocked())).toMatch(/^Its images don’t include this server’s platform\. This server keeps running 0\.1\.12\./);
    expect(blockedText(blocked({ blockedCode: 'owner' }))).toMatch(/Set GITHUB_REPO or IMAGE_OWNER/);
    expect(blockedText(blocked({ blockedCode: 'digests' }))).toMatch(/installed by hand/);
    expect(blockedText(blocked({ blockedCode: null }))).toBe('The updater says: the signed release 0.1.15 has no images ….');
  });
  it('nav meta: amber with security fixes missing, quiet otherwise', () => {
    expect(updatesNavMeta(blocked())).toEqual({ meta: 'Security fixes', metaTone: 'warn' });
    expect(updatesNavMeta(blocked({ missedSecurity: [] }))).toEqual({ meta: 'Can’t install' });
  });
});

describe('upToDate / unverifiedRelease', () => {
  it('a newer release found unverified is not "the latest release"', () => {
    const s = status({ updateAvailable: false, verified: false, missedSecurity: [] });
    expect(unverifiedRelease(s)).toBe(true);
    expect(upToDate(s)).toBe(false);
    expect(upToDate(status({ updateAvailable: false, latest: '0.1.12', missedSecurity: [] }))).toBe(true);
    expect(upToDate(null)).toBe(false);
  });
});

describe('upToDate / unverifiedRelease: failed checks and older releases', () => {
  it('a failed check is never "the latest release"', () => {
    expect(upToDate(status({ updateAvailable: false, latest: null, error: 'github 500', missedSecurity: [] }))).toBe(false);
    expect(upToDate(status({ updateAvailable: false, latest: '0.1.12', error: 'not polled yet', missedSecurity: [] }))).toBe(true);
  });
  it('only a newer unverified release counts as found', () => {
    expect(unverifiedRelease(status({ current: '0.1.16', updateAvailable: false, verified: false }))).toBe(false);
    expect(unverifiedRelease(status({ current: '0.1.12', updateAvailable: false, verified: false }))).toBe(true);
    expect(unverifiedRelease(status({ current: 'latest', updateAvailable: false, verified: false }))).toBe(false);
  });
  it('names this server’s platform when the supervisor reports it', () => {
    const b = status({ updateAvailable: false, blocked: 'r', blockedCode: 'platform', platform: 'linux/arm64' });
    expect(blockedShort(b, [])).toBe('Its images don’t include this server’s platform (linux/arm64).');
    expect(blockedText(b)).toMatch(/^Its images don’t include this server’s platform \(linux\/arm64\)\. /);
  });
});

describe('agentUnavailable (Enroll warns instead of showing a QR code)', () => {
  it('only once the updater has checked, on an install it serves the agent for, with none to offer', () => {
    expect(agentUnavailable(status({ apk: null }))).toBe(false);   // checkedAt 0: not checked yet
    expect(agentUnavailable(status({ apk: null, checkedAt: 1 }))).toBe(true);
    expect(agentUnavailable(status({ apk: null, checkedAt: 1, applySupported: false }))).toBe(false);
    // The updater says whether it serves /files/agent.apk (0.6.0+): a source build doesn't update itself but serves it.
    expect(agentUnavailable(status({ apk: null, checkedAt: 1, applySupported: false, apkServed: true }))).toBe(true);
    expect(agentUnavailable(status({ apk: null, checkedAt: 1, applySupported: true, apkServed: false }))).toBe(false);
    const apk = { version: '0.1.12', versionCode: 1, sha256: 'a', available: false, of: 'running' as const };
    expect(agentUnavailable(status({ apk, checkedAt: 1 }))).toBe(false);
    expect(agentUnavailable(null)).toBe(false);
  });
});

describe('agentAfterServerUpdate (Rollout: update the server first)', () => {
  const apk = (version: string) => ({ version, versionCode: 1, sha256: 'a', available: true });
  it('names the newer release while the offered agent is the running release’s', () => {
    expect(agentAfterServerUpdate(status({ current: '0.1.12', latest: '0.1.15', apk: apk('0.1.12') }))).toBe('0.1.15');
    expect(agentAfterServerUpdate(status({ current: '0.1.12', latest: '0.1.15', apk: null }))).toBe('0.1.15');
  });
  it('is null on the latest release, when the latest agent is offered anyway, or with versions it can’t compare', () => {
    expect(agentAfterServerUpdate(status({ current: '0.1.15', latest: '0.1.15', apk: apk('0.1.15') }))).toBeNull();
    expect(agentAfterServerUpdate(status({ current: '0.1.16', latest: '0.1.15' }))).toBeNull();
    expect(agentAfterServerUpdate(status({ current: '0.6.0-dev', latest: '0.1.15', apk: apk('0.1.15') }))).toBeNull();
    expect(agentAfterServerUpdate(status({ current: '0.1.12', latest: null }))).toBeNull();
  });
});

describe('rolloutAheadOfServer', () => {
  it('names the server version when the rollout’s agent is newer than it', () => {
    expect(rolloutAheadOfServer('0.1.15', status({ current: '0.1.12' }))).toBe('0.1.12');
    expect(rolloutAheadOfServer('0.1.12', status({ current: '0.1.12' }))).toBeNull();
    expect(rolloutAheadOfServer('0.1.11', status({ current: '0.1.12' }))).toBeNull();
    expect(rolloutAheadOfServer('0.1.15', status({ current: '0.6.0-dev' }))).toBeNull();
    expect(rolloutAheadOfServer(null, status())).toBeNull();
    expect(rolloutAheadOfServer('0.1.15', null)).toBeNull();
  });
});
