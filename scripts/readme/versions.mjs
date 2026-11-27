// README-only sample versions, from the git tags: the server runs the release before the newest, and the newest is
// available, signed; agents run the server's version, rolling out to the canaries; one older agent (LOBBY-PAD),
// which the sample sync data already marks as too old for configuration sync. Build numbers are release/version.sh's.
// The shared screenshot fixtures (scripts/shots) keep their own versions for the parity tests.
// Applied in place to the harness's shared `fx` before any page is opened (its REST answers read it per request).
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const run = (cmd, args) => execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8' }).trim();
const versionCode = (v) => Number(run(path.join(ROOT, 'release/version.sh'), [v]));

// Release tags only (vX.Y.Z, as release.yml requires), newest first.
const tags = run('git', ['tag', '--list', 'v*', '--sort=-v:refname']).split('\n').filter((t) => /^v\d+\.\d+\.\d+$/.test(t));
if (tags.length < 2) throw new Error('versions.mjs needs two vX.Y.Z release tags; fetch them (git fetch --tags)');
export const [AVAILABLE, RUNNING] = tags.map((t) => t.slice(1));
export const AGENT = { version: RUNNING, build: versionCode(RUNNING) };
const OLD_AGENT = '0.2.6';

export function applyReadmeVersions(fx) {
  const u = fx.updateStatus;
  u.current = RUNNING;
  u.latest = AVAILABLE;
  u.apk = { ...u.apk, version: AVAILABLE, versionCode: versionCode(AVAILABLE) };
  if (u.release) {
    u.release = {
      ...u.release,
      notes: u.release.notes.replace(/^v\S+/, `v${AVAILABLE}`),
      url: u.release.url.replace(/v[\d.]+$/, `v${AVAILABLE}`),
    };
  }
  const r = fx.activeRollout;
  r.targetVersion = AGENT.version;
  r.apkVersionCode = AGENT.build;
  if (r.progress) r.progress.targetVersion = AGENT.version;
  for (const d of fx.devices) d.launcherVersion = d.number === 'LOBBY-PAD' ? OLD_AGENT : AGENT.version;
  fx.state.agentVersion = AGENT.version;
  for (const a of fx.applications) {
    if (a.pkg === 'com.lunacy.mdm.agent') Object.assign(a, { version: AGENT.version, versionCode: AGENT.build });
  }
}

/** Version strings that must not appear on a README shot (the shared fixtures' own versions). */
export const STALE = /\b0\.1\.1[0-9]\b/;
