// Update status from the decoupled supervisor. NOT under /rest — it's served at the origin by Caddy
// (handle /update/* → supervisor), independent of the Java server.

/** Live state of an in-progress (or last) apply. Phases: authorizing→backup→pull→recreate→
 *  healthcheck→done; failures end at rollback→rolled_back or failed. */
export interface ApplyState {
  phase: string;
  fromVersion: string;
  toVersion: string;
  trigger: 'manual' | 'auto';
  startedAt: number;
  finishedAt: number | null;
  error: string | null;
}

export interface UpdateStatus {
  current: string;
  latest: string | null;
  updateAvailable: boolean;
  /** Why the newer verified release (`latest`) can't be installed by this updater, which then doesn't offer it, in
   *  the supervisor's words; `blockedCode` names the cause. Absent from a supervisor before 0.6.0. */
  blocked?: string | null;
  blockedCode?: 'platform' | 'owner' | 'digests' | null;
  /** This updater serves /files/agent.apk (Docker installs); false where a published copy is served (native).
   *  Absent from a supervisor before 0.6.0. */
  apkServed?: boolean;
  /** The platform this updater pulls images for (linux/amd64, linux/arm64). Absent from a supervisor before 0.6.0. */
  platform?: string;
  verified: boolean;
  channel: string | null;
  checkedAt: number | null;
  error: string | null;
  apply: ApplyState | null;
  auto: boolean;
  /** The release automatic updates will not apply again (its update failed or was rolled back), from the
   *  supervisor's auto.json `skipVersion`. A manual Update still applies it; a newer release still auto-applies. */
  autoSkipped?: string | null;
  /** False on deployments that can't self-apply (native installs) — hide apply/auto controls.
   *  Absent (older supervisor) means supported. */
  applySupported?: boolean;
  /** The agent APK the updater offers for enrollment and rollouts (see `of`), or null. */
  apk: {
    version: string;
    versionCode: number;
    sha256: string;
    available: boolean;
    /** Whose agent: the running release's, or the latest release's when the running version is unknown (a
     *  development build). An agent is never offered ahead of a server whose release is known (0.6.0+). */
    of?: 'latest' | 'running';
  } | null;
  /** The security releases the offered update skips over (newer than `current`, older than `latest`; `latest` too
   *  when it is `blocked`), oldest first, from the verified manifest's securityReleases. Empty when nothing is skipped or the manifest isn't verified;
   *  absent from a supervisor before 0.5.0. */
  missedSecurity?: string[];
  /** Release notes / link / date for the picked release, or null when there's no release. */
  release: { notes: string | null; url: string | null; publishedAt: string | null } | null;
}

/** Phases that mean an apply has settled and the UI should stop spinning. */
export const APPLY_TERMINAL = ['done', 'rolled_back', 'failed'];
export const isApplyTerminal = (p?: string | null): boolean => !!p && APPLY_TERMINAL.includes(p);

export async function getUpdateStatus(): Promise<UpdateStatus | null> {
  try {
    const r = await fetch('/update/status', { headers: { Accept: 'application/json' } });
    if (!r.ok) return null;
    return (await r.json()) as UpdateStatus;
  } catch {
    return null; // supervisor not reachable (e.g. dev without the stack) → no banner
  }
}

/** Force an on-demand poll of GitHub and return the refreshed status (authz'd + rate-limited server-side). */
export async function checkForUpdates(): Promise<UpdateStatus | null> {
  try {
    const r = await fetch('/update/check', {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'application/json', 'X-MDMesh-Console': '1' },
    });
    return r.ok ? ((await r.json()) as UpdateStatus) : null;
  } catch {
    return null;
  }
}

/** Kick off a one-click apply. credentials:'include' forwards JSESSIONID for the supervisor's authz. */
export async function applyUpdate(): Promise<{ ok: boolean; error?: string }> {
  try {
    const r = await fetch('/update/apply', { method: 'POST', credentials: 'include', headers: { Accept: 'application/json', 'X-MDMesh-Console': '1' } });
    if (r.status === 202) return { ok: true };
    const body = await r.json().catch(() => ({}));
    return { ok: false, error: (body as { error?: string }).error || `HTTP ${r.status}` };
  } catch (e) {
    return { ok: false, error: String((e as Error)?.message || e) };
  }
}

/** Toggle unattended ("automatic") updates. */
export async function setAutoUpdate(auto: boolean): Promise<{ ok: boolean; error?: string }> {
  try {
    const r = await fetch('/update/auto', {
      method: 'POST', credentials: 'include',
      headers: { 'content-type': 'application/json', Accept: 'application/json', 'X-MDMesh-Console': '1' },
      body: JSON.stringify({ auto }),
    });
    if (r.ok) return { ok: true };
    const body = await r.json().catch(() => ({}));
    return { ok: false, error: (body as { error?: string }).error || `HTTP ${r.status}` };
  } catch (e) {
    return { ok: false, error: String((e as Error)?.message || e) };
  }
}
