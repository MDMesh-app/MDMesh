import { useEffect, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { IconCopy } from '../ui/icons';
import { useToast } from '../ui/toast';
import { mintEnrollToken } from '../api/enroll';
import { ApiError } from '../api/client';
import { fmtDateTime } from '../ui/format';
import { QrCanvas } from '../components/QrCanvas';
import { buildProvisioningPayload, serverBaseUrl, agentApkUrl, type WifiSecurity } from '../enroll/provisioning';

const STEPS = [
  { title: 'Start from a factory-reset device', sub: 'On the first "Hi there" welcome screen, don\'t sign in yet.' },
  { title: 'Tap the screen 6 times', sub: 'This opens the QR provisioning scanner. Connect to Wi-Fi if asked.' },
  { title: 'Scan this code', sub: 'Android downloads the MDMesh agent and sets it as device owner.' },
  { title: 'Wait for enrollment', sub: 'The device appears in Devices after its first check-in.' },
];

type Mode = 'qr' | 'token';

export function EnrollPage() {
  const toast = useToast();
  const [mode, setMode] = useState<Mode>('qr');
  const [token, setToken] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<number | undefined>();
  const [busy, setBusy] = useState(false);
  const [tokError, setTokError] = useState<string | null>(null);
  const [wifiSsid, setWifiSsid] = useState('');
  const [wifiPass, setWifiPass] = useState('');
  const [wifiSec, setWifiSec] = useState<WifiSecurity>('WPA');

  async function generate() {
    setBusy(true);
    setTokError(null);
    try {
      const res = await mintEnrollToken();
      if (res.token) {
        setToken(res.token);
        setExpiresAt(res.expiresAt);
      } else {
        setTokError('The server did not return a token.');
      }
    } catch (err) {
      if (err instanceof ApiError && err.httpStatus === 0) setTokError('Cannot reach the server.');
      else if (err instanceof ApiError) setTokError(err.message || 'Failed to generate a token.');
      else setTokError('Failed to generate a token.');
    } finally {
      setBusy(false);
    }
  }

  // Mint a token up front so the QR is ready on load.
  useEffect(() => { void generate(); /* eslint-disable-next-line */ }, []);

  async function copy() {
    if (!token) return;
    try {
      await navigator.clipboard.writeText(token);
      toast.push('ok', 'Token copied', 'Enrollment token copied to clipboard.');
    } catch {
      toast.push('err', 'Copy failed', 'Select and copy the token manually.');
    }
  }

  return (
    <AppShell title="Enroll">
      <div className="enroll">
        <div className="enroll-top">
          <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>
            Enroll a device
          </h1>
          <div className="sp" />
          <span className="seg" role="tablist" aria-label="Enrollment method">
            <button className={mode === 'qr' ? 'on' : ''} onClick={() => setMode('qr')}>Scan QR</button>
            <button className={mode === 'token' ? 'on' : ''} onClick={() => setMode('token')}>Token</button>
          </span>
        </div>

        {mode === 'qr' && (
          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title">Scan to enroll</h2>
              <button className="btn btn-sm" onClick={() => void generate()} disabled={busy}>
                {busy ? 'Generating…' : 'New code'}
              </button>
            </div>
            {tokError && <div className="banner banner-alert">{tokError}</div>}
            <div className="qr-layout">
              <div>
                <div className="qr-frame">
                  {token ? (
                    <QrCanvas
                      text={buildProvisioningPayload(
                        token,
                        wifiSsid.trim() ? { ssid: wifiSsid, password: wifiPass, security: wifiSec } : undefined,
                      )}
                      size={320}
                    />
                  ) : (
                    <div className="empty"><span className="spin" /> Preparing…</div>
                  )}
                </div>
                <div className="qr-cap">
                  Single-use{expiresAt ? ` · expires ${fmtDateTime(expiresAt)}` : ''}
                  {wifiSsid.trim() ? ` · joins Wi-Fi “${wifiSsid.trim()}”` : ''}
                </div>
                <details className="wifi-block">
                  <summary>Pre-connect Wi-Fi during setup (optional)</summary>
                  <div className="wifi-fields">
                    <label>
                      Network name (SSID)
                      <input value={wifiSsid} onChange={(e) => setWifiSsid(e.target.value)} placeholder="Office-WiFi" />
                    </label>
                    <label>
                      Security
                      <select className="sel" value={wifiSec} onChange={(e) => setWifiSec(e.target.value as WifiSecurity)}>
                        <option value="WPA">WPA / WPA2</option>
                        <option value="WEP">WEP</option>
                        <option value="NONE">Open (no password)</option>
                      </select>
                    </label>
                    {wifiSec !== 'NONE' && (
                      <label>
                        Password
                        <input type="password" value={wifiPass} onChange={(e) => setWifiPass(e.target.value)} autoComplete="off" />
                      </label>
                    )}
                    <p className="note">
                      The device joins this network during provisioning (before it downloads the agent).
                      Heads-up: the password is embedded in the QR — only show it to people you trust to enroll.
                    </p>
                  </div>
                </details>
              </div>
              <ol className="qr-steps" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                {STEPS.map((s, i) => (
                  <li className="qr-step" key={i}>
                    <span className="step-n">{i + 1}</span>
                    <span className="st-tx">{s.title}<span className="sub">{s.sub}</span></span>
                  </li>
                ))}
              </ol>
            </div>
            <p className="note" style={{ padding: '0 20px 16px' }}>
              Server <span className="mono">{serverBaseUrl()}</span> · agent{' '}
              <span className="mono">{agentApkUrl()}</span>. Host the agent APK at that URL.
            </p>
          </section>
        )}

        {mode === 'token' && (
          <section className="panel enroll-wrap">
            <div className="panel-head">
              <h2 className="panel-title">Enrollment token</h2>
              <button className="btn btn-primary btn-sm" onClick={() => void generate()} disabled={busy}>
                {busy ? 'Generating…' : token ? 'Generate another' : 'Generate token'}
              </button>
            </div>
            <div style={{ padding: 20 }}>
              {tokError && <div className="banner banner-alert">{tokError}</div>}
              {token ? (
                <>
                  <div className="token-box">
                    <span className="tok">{token}</span>
                    <button className="btn btn-sm btn-ghost" onClick={() => void copy()} aria-label="Copy token">
                      <IconCopy className="ico" />
                    </button>
                  </div>
                  <p className="note">
                    Single-use token{expiresAt ? `, expires ${fmtDateTime(expiresAt)}` : ''}. For headless
                    or scripted provisioning — embed it as{' '}
                    <span className="mono">com.mdmesh.ENROLL_TOKEN</span> (with{' '}
                    <span className="mono">com.mdmesh.SERVER_URL</span>). The QR is the usual path.
                  </p>
                </>
              ) : (
                <p className="note">For headless or scripted provisioning. For the usual flow, scan the QR.</p>
              )}
            </div>
          </section>
        )}
      </div>
    </AppShell>
  );
}
