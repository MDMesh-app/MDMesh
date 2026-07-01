import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from '../ui/theme';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import { API_BASE } from '../api/client';
import { fetchAuthOptions } from '../api/auth';
import { getUpdateStatus, setAutoUpdate, checkForUpdates, applyUpdate, type UpdateStatus } from '../api/updates';
import { RolloutPanel } from '../components/RolloutPanel';
import { orDash, fmtRelative } from '../ui/format';

const APP_VERSION = '0.1.0';
const DEFAULT_CONFIG_KEY = 'mdmesh-default-config';

type Conn = 'checking' | 'ok' | 'down';

export function SettingsPage() {
  const navigate = useNavigate();
  const { user, signOut } = useAuth();
  const { theme, setTheme, density, setDensity } = useTheme();
  const [configList, setConfigList] = useState<ConfigurationSummary[]>([]);
  const [conn, setConn] = useState<Conn>('checking');
  const [upd, setUpd] = useState<UpdateStatus | null>(null);
  const [autoSaving, setAutoSaving] = useState(false);
  const [autoErr, setAutoErr] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [applying, setApplying] = useState(false);
  const [updMsg, setUpdMsg] = useState<string | null>(null);
  const [defaultConfig, setDefaultConfig] = useState<string>(() => {
    try {
      return localStorage.getItem(DEFAULT_CONFIG_KEY) ?? '';
    } catch {
      return '';
    }
  });

  useEffect(() => {
    let cancelled = false;
    fetchAuthOptions()
      .then(() => !cancelled && setConn('ok'))
      .catch(() => !cancelled && setConn('down'));
    listConfigurations()
      .then((list) => {
        if (!cancelled)
          setConfigList([...list].sort((a, b) => a.name.localeCompare(b.name)));
      })
      .catch(() => undefined);
    getUpdateStatus()
      .then((x) => !cancelled && setUpd(x))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const checkNow = async () => {
    setChecking(true);
    setUpdMsg(null);
    const x = await checkForUpdates();
    setChecking(false);
    if (x) setUpd(x);
    else setUpdMsg('Could not check for updates.');
  };

  const applyNow = async () => {
    if (
      !window.confirm(
        `Update to v${upd?.latest}?\n\nThe server restarts briefly. The database is backed up first `
          + 'and the update rolls back automatically if it fails.',
      )
    )
      return;
    setApplying(true);
    setUpdMsg(null);
    const r = await applyUpdate();
    setApplying(false);
    if (!r.ok) setUpdMsg(r.error || 'Failed to start update.');
    else setUpdMsg('Update started — watch the banner at the top for live progress.');
  };

  const scrollToRollout = () =>
    document.getElementById('rollout-anchor')?.scrollIntoView({ behavior: 'smooth', block: 'start' });

  const toggleAuto = async (next: boolean) => {
    setAutoSaving(true);
    setAutoErr(null);
    const r = await setAutoUpdate(next);
    setAutoSaving(false);
    if (!r.ok) {
      setAutoErr(r.error || 'Could not save');
      return;
    }
    setUpd((p) => (p ? { ...p, auto: next } : p));
  };

  useEffect(() => {
    try {
      if (defaultConfig) localStorage.setItem(DEFAULT_CONFIG_KEY, defaultConfig);
      else localStorage.removeItem(DEFAULT_CONFIG_KEY);
    } catch {
      /* ignore */
    }
  }, [defaultConfig]);

  const connMeta: Record<Conn, { tone: string; label: string }> = {
    checking: { tone: 'idle', label: 'Checking…' },
    ok: { tone: 'ok', label: 'Connected' },
    down: { tone: 'alert', label: 'Unreachable' },
  };
  const cm = connMeta[conn];

  return (
    <AppShell title="Settings">
      <div className="page-head">
        <h1>Settings</h1>
      </div>

      <div className="settings">
        {/* Account & session */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Account</h2>
            <button className="btn btn-sm" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
          <div className="set-row">
            <span className="k">Signed in as</span>
            <span className="v">{orDash(user?.name || user?.login)}</span>
          </div>
          <div className="set-row">
            <span className="k">Login</span>
            <span className="v mono">{orDash(user?.login)}</span>
          </div>
          <div className="set-row">
            <span className="k">Email</span>
            <span className="v mono">{orDash(user?.email)}</span>
          </div>
          <div className="set-row">
            <span className="k">Role</span>
            <span className="v">{user?.superAdmin ? 'Super admin' : 'Admin'}</span>
          </div>
        </section>

        {/* Server & connection */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Server &amp; connection</h2>
          </div>
          <div className="set-row">
            <span className="k">Status</span>
            <span className="v">
              <span className="conn">
                <span className={`dot dot-${cm.tone}`} />
                {cm.label}
              </span>
            </span>
          </div>
          <div className="set-row">
            <span className="k">API base</span>
            <span className="v mono">{API_BASE || '(same origin)'}</span>
          </div>
          <div className="set-row">
            <span className="k">Console version</span>
            <span className="v mono">MDMesh {APP_VERSION}</span>
          </div>
        </section>

        {/* Updates */}
        {upd && (
          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title">Updates</h2>
            </div>
            <div className="set-row">
              <span className="k">Running version</span>
              <span className="v mono">{orDash(upd.current)}</span>
            </div>
            <div className="set-row">
              <span className="k">Latest available</span>
              <span className="v mono">
                {orDash(upd.latest)}
                {upd.latest && upd.verified ? ' ✓' : ''}
                {upd.channel ? ` (${upd.channel})` : ''}
              </span>
            </div>
            {upd.updateAvailable && upd.release?.notes && (
              <div className="set-row">
                <span className="k">
                  What&rsquo;s new
                  <small>Release notes for v{orDash(upd.latest)}.</small>
                </span>
                <span className="v">
                  <div className="whatsnew">{upd.release.notes}</div>
                  {upd.release.url && (
                    <a className="whatsnew-link" href={upd.release.url} target="_blank" rel="noreferrer">
                      Full release notes ↗
                    </a>
                  )}
                </span>
              </div>
            )}
            {upd.updateAvailable && (
              <div className="set-row">
                <span className="k">
                  Apply this release
                  <small>Server + console update now; agent APK rolls out to devices.</small>
                </span>
                <span className="v">
                  <div className="upd-actions">
                    <button
                      className="btn btn-sm btn-primary"
                      onClick={() => void applyNow()}
                      disabled={applying || !upd.verified}
                    >
                      {applying ? 'Starting…' : 'Update server + console'}
                    </button>
                    <button className="btn btn-sm" onClick={scrollToRollout}>
                      Roll out agent to devices ↓
                    </button>
                  </div>
                  {!upd.verified && (
                    <p className="au-note" style={{ color: 'var(--err)' }}>
                      Release signature not verified — apply is disabled.
                    </p>
                  )}
                </span>
              </div>
            )}
            <div className="set-row">
              <span className="k">
                Check for updates
                <small>
                  {upd.checkedAt ? `Last checked ${fmtRelative(upd.checkedAt)}.` : 'Not checked yet.'}
                </small>
                {updMsg && <p className="au-note">{updMsg}</p>}
              </span>
              <span className="v">
                <button className="btn btn-sm" onClick={() => void checkNow()} disabled={checking}>
                  {checking ? 'Checking…' : 'Check now'}
                </button>
              </span>
            </div>
            <div className="set-row auto-update-row">
              <span className="k">
                Automatic updates
                <small>Apply verified releases without a prompt.</small>
                <p className="au-note">
                  When on, the updater applies each verified release on its own — backing up the
                  database first and rolling back automatically if it fails. Leave off to review and
                  click Update each time.
                </p>
                {autoErr && <p className="au-note" style={{ color: 'var(--err)' }}>{autoErr}</p>}
              </span>
              <span className="v">
                <span className="seg">
                  <button
                    className={upd.auto ? 'on' : ''}
                    onClick={() => void toggleAuto(true)}
                    disabled={autoSaving}
                  >
                    On
                  </button>
                  <button
                    className={!upd.auto ? 'on' : ''}
                    onClick={() => void toggleAuto(false)}
                    disabled={autoSaving}
                  >
                    Off
                  </button>
                </span>
              </span>
            </div>
          </section>
        )}

        {/* Staged agent-APK rollout (renders itself only when there's an apk to roll out or an active rollout) */}
        <div id="rollout-anchor">
          <RolloutPanel />
        </div>

        {/* Enrollment defaults */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Enrollment defaults</h2>
            <button
              className="btn btn-sm btn-primary"
              onClick={() => navigate('/enroll')}
            >
              Enroll a device →
            </button>
          </div>
          <div className="set-row">
            <span className="k">
              Default configuration
              <small>Pre-selected when you enroll a new device.</small>
            </span>
            <span className="v">
              <select
                className="sel"
                value={defaultConfig}
                onChange={(e) => setDefaultConfig(e.target.value)}
              >
                <option value="">No default</option>
                {configList.map((c) => (
                  <option key={c.id} value={String(c.id)}>
                    {c.name}
                  </option>
                ))}
              </select>
            </span>
          </div>
          {configList.length === 0 && (
            <div className="set-row">
              <span className="k" style={{ fontWeight: 400 }}>
                No configurations are assigned to devices yet.
              </span>
            </div>
          )}
        </section>

        {/* Appearance */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Appearance</h2>
          </div>
          <div className="set-row">
            <span className="k">
              Theme
              <small>Switches with a smooth crossfade.</small>
            </span>
            <span className="v">
              <span className="seg">
                <button
                  className={theme === 'light' ? 'on' : ''}
                  onClick={() => setTheme('light')}
                >
                  Light
                </button>
                <button
                  className={theme === 'dark' ? 'on' : ''}
                  onClick={() => setTheme('dark')}
                >
                  Dark
                </button>
              </span>
            </span>
          </div>
          <div className="set-row">
            <span className="k">
              Density
              <small>Tighten spacing across the console.</small>
            </span>
            <span className="v">
              <span className="seg">
                <button
                  className={density === 'comfortable' ? 'on' : ''}
                  onClick={() => setDensity('comfortable')}
                >
                  Comfortable
                </button>
                <button
                  className={density === 'compact' ? 'on' : ''}
                  onClick={() => setDensity('compact')}
                >
                  Compact
                </button>
              </span>
            </span>
          </div>
        </section>
      </div>
    </AppShell>
  );
}
