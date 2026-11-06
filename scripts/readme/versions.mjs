// README-only sample versions. The shared screenshot fixtures (scripts/shots) keep their own versions for the
// parity tests; for the README, the console should read as the current release era instead:
//   server running 0.4.0, a signed 0.5.0 available; agents on 0.4.0 (build 400), rolling out to the canaries;
//   one older agent (LOBBY-PAD), which the sample sync data already marks as too old for configuration sync.
// Applied in place to the harness's shared `fx` before any page is opened (its REST answers read it per request).
export const RUNNING = '0.4.0';
export const AVAILABLE = '0.5.0';
export const AGENT = { version: '0.4.0', build: 400 };
const OLD_AGENT = '0.2.6';

export function applyReadmeVersions(fx) {
  const u = fx.updateStatus;
  u.current = RUNNING;
  u.latest = AVAILABLE;
  u.apk = { ...u.apk, version: AVAILABLE, versionCode: 500 };
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
