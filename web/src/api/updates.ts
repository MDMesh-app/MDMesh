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
  verified: boolean;
  channel: string | null;
  checkedAt: number | null;
  error: string | null;
  apply: ApplyState | null;
  auto: boolean;
  /** The mirrored agent APK for the latest verified release (for device rollouts), or null. */
  apk: { version: string; versionCode: number; sha256: string; available: boolean } | null;
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
