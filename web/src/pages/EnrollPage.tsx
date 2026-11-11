import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { getConfigurations, type Configuration } from '../api/configurations';
import { mintEnrollToken, revokeAllEnrollTokens } from '../api/enroll';
import { getUpdateStatus, type UpdateStatus } from '../api/updates';
import { addressProblem, wifiProblem } from '../enroll/checks';
import { agentApkUrl, buildProvisioningPayload, serverBaseUrl, type WifiSecurity } from '../enroll/provisioning';
import {
  Button,
  Callout,
  Card,
  CardHeader,
  CopyField,
  Field,
  IconSync,
  IconWarning,
  Input,
  KeyValueList,
  MOBILE_QUERY,
  NumberedSteps,
  PageHeader,
  Select,
  TabPanel,
  Tabs,
  Toggle,
  useMediaQuery,
  useToast,
  type KeyValueItem,
  LinkButton,
  Modal,
} from '../ui';
// Not from the barrel: QrPanel pulls in `qrcode`, which stays in this page's chunk.
import { QrPanel } from '../ui/QrPanel';
import './EnrollPage.css';

/** This browser's default enrollment configuration (written by Settings; same key as the v0.3 console). */
const DEFAULT_CONFIG_KEY = 'mdmesh-default-config';
const SECURITY_VALUES: WifiSecurity[] = ['WPA', 'WEP', 'NONE', 'EAP'];
/**
 * The installers bake a verified release's signing checksum into the build. Without it, enroll/provisioning.ts
 * falls back to the debug agent's package and checksum (we only read the flag here; the payload is built there).
 */
const RELEASE_BUILD = !!(import.meta.env as Record<string, string | undefined>).VITE_AGENT_CHECKSUM;
const SERVER_SETTING = 'Server setting for new devices';
const METHOD_TABS = 'v2-enroll-method';

type Method = 'qr' | 'token';
interface Minted {
  value: string;
  expiresAt?: number;
  /** The devices it enrolls, as the server says (1 from a server whose tokens are all single-use). */
  maxUses: number;
  /** The server applied the chosen devices and validity; false: it predates them, and made a single-use 24 h code. */
  limitsApplied: boolean;
}

/**
 * How many devices one code enrolls: one, a code to send to a customer, or a batch set up together. A code for several
 * works for anyone who has it until it expires, so it is bounded both ways.
 */
const USES_OPTIONS = [1, 10, 25, 100];
/** How long a code is valid. */
const VALID_OPTIONS: { hours: number; label: string }[] = [
  { hours: 24, label: '24 hours' },
  { hours: 72, label: '3 days' },
  { hours: 168, label: '7 days' },
  { hours: 720, label: '30 days' },
];

function usesText(n: number): string {
  return n === 1 ? 'One device, then it stops working' : `Up to ${n} devices, then it stops working`;
}

/** Codes for a batch this page replaced that may still work: how many, and when the last of them expires. */
interface Replaced {
  count: number;
  until?: number;
}

/** What to say about the codes this page replaced. */
function replacedText(r: Replaced): string {
  if (r.count === 1) {
    return `The code for a batch you replaced still works ${r.until ? `until ${fmtExpiry(r.until)}` : 'until it expires'}, for anyone who has it.`;
  }
  return `The ${r.count} codes for a batch you replaced still work ${r.until ? `until ${fmtExpiry(r.until)}` : 'until they expire'}, for anyone who has them.`;
}

/** Why stopping the codes failed, in words. */
function revokeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.httpStatus === 404 || err.httpStatus === 405)
      return "This server can't stop codes. Update it, or wait for the codes to expire.";
    if (err.message === 'error.permission.denied')
      return "Your account can't stop codes: it needs permission to edit devices.";
    return err.message || "Couldn't stop the codes.";
  }
  return "Couldn't stop the codes.";
}

function readDefault(): string {
  try {
    return localStorage.getItem(DEFAULT_CONFIG_KEY) ?? '';
  } catch {
    return '';
  }
}

/** Sentences for the token errors the server is known to send; anything else is shown as it comes (classic). */
function tokenError(err: unknown): string {
  if (err instanceof ApiError && err.httpStatus === 0) return 'Cannot reach the server.';
  if (err instanceof ApiError) {
    if (err.message === 'error.configuration.not.found')
      return "That configuration doesn't exist any more, or your account can't use it. Pick another one.";
    if (err.message === 'error.permission.denied')
      return "Your account can't enroll devices: it needs permission to edit devices.";
    if (err.message === 'error.agent.token.maxUses') return 'A code can enroll 1 to 1000 devices.';
    if (err.message === 'error.agent.token.validHours') return 'A code can be valid for 1 hour to 30 days.';
    return err.message || 'Failed to generate a token.';
  }
  return 'Failed to generate a token.';
}

/** Token expiry, short enough for the summary box: a token lives 30 days at most, so no year ("Oct 16, 1:54 PM"). */
function fmtExpiry(ms: number): string {
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

/** "in 7 days", "in 24 hours", "in 5 minutes", "in under a minute". */
function untilText(ms: number, now: number): string {
  const min = Math.floor((ms - now) / 60_000);
  if (min < 1) return 'in under a minute';
  if (min < 60) return `in ${min} minute${min === 1 ? '' : 's'}`;
  const hr = Math.round(min / 60);
  if (hr >= 48) return `in ${Math.round(hr / 24)} days`;
  return `in ${hr} hour${hr === 1 ? '' : 's'}`;
}

/** The clock, every `everyMs`, and also right at `at` (a code's expiry), so an expired code is never left up. */
function useNow(everyMs: number, at?: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(t);
  }, [everyMs]);
  useEffect(() => {
    if (at == null) return;
    const ms = at - Date.now();
    if (ms <= 0 || ms > 2 ** 31 - 1) return;
    const t = setTimeout(() => setNow(Date.now()), ms + 50);
    return () => clearTimeout(t);
  }, [at]);
  return now;
}

/** How long a configuration choice must settle before a token is minted for it (arrowing through the select). */
const MINT_SETTLE_MS = 300;

function qrSteps(wifiInCode: boolean): ReactNode[] {
  return [
    'Factory-reset the device, or start with one fresh from the box.',
    'On the first welcome screen, tap the same spot six times.',
    wifiInCode
      ? 'Scan this code when the camera opens.'
      : 'Scan this code when the camera opens. Connect to Wi-Fi if the device asks.',
    'Wait a few minutes while the agent installs. The device then appears under Devices.',
  ];
}

/** Enroll. Classic: pages/EnrollPage.tsx. */
export function EnrollPage() {
  const toast = useToast();
  const mobile = useMediaQuery(MOBILE_QUERY);

  const [method, setMethod] = useState<Method>('qr');
  const [defaultId] = useState(readDefault);
  const [cfgId, setCfgId] = useState(defaultId);
  const [uses, setUses] = useState(1);
  const [validHours, setValidHours] = useState(24);
  /** null while loading. */
  const [configs, setConfigs] = useState<Configuration[] | null>(null);
  const [configsFailed, setConfigsFailed] = useState(false);

  const [wifiOn, setWifiOn] = useState(false);
  const [wifiSsid, setWifiSsid] = useState('');
  const [wifiPass, setWifiPass] = useState('');
  const [wifiSec, setWifiSec] = useState<WifiSecurity>('WPA');
  /** The configuration the Wi-Fi fields were filled from (null: nothing saved there, or typed by hand). */
  const [wifiFrom, setWifiFrom] = useState<string | null>(null);

  const [token, setToken] = useState<Minted | null>(null);
  const [minting, setBusy] = useState(false);
  /** The first code waits for the configuration list, so a stored default that is gone is never minted for. */
  const [mintReady, setMintReady] = useState(false);
  const busy = minting || !mintReady;
  const [tokError, setTokError] = useState<string | null>(null);
  const mintSeq = useRef(0);
  const minted = useRef(false);
  /** The code last shown, to tell whether a new one replaces a code for a batch that still works. */
  const shown = useRef<Minted | null>(null);
  const [replaced, setReplaced] = useState<Replaced | null>(null);
  const [stopOpen, setStopOpen] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [stopError, setStopError] = useState<string | null>(null);
  const now = useNow(30_000, token?.expiresAt);

  const [update, setUpdate] = useState<UpdateStatus | null>(null);

  const loadConfigs = useCallback(() => {
    setConfigsFailed(false);
    setConfigs(null);
    getConfigurations()
      .then((list) => setConfigs([...list].sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => {
        setConfigs([]);
        setConfigsFailed(true);
      });
  }, []);

  useEffect(() => {
    loadConfigs();
  }, [loadConfigs]);

  // The mirrored agent's version, for the footer. Only meaningful when the code points at the release mirror.
  useEffect(() => {
    if (!RELEASE_BUILD) return;
    let cancelled = false;
    void getUpdateStatus().then((s) => {
      if (!cancelled) setUpdate(s);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const selected = useMemo(() => configs?.find((c) => String(c.id) === cfgId) ?? null, [configs, cfgId]);

  // A stored default that is no longer in the list (deleted, or not usable by this account) falls back to the
  // server setting instead of minting against a dead id, and is forgotten so the next visit doesn't fall back again.
  const stale = !!configs && !configsFailed && !!cfgId && !selected;
  useEffect(() => {
    if (!stale) return;
    try {
      if (localStorage.getItem(DEFAULT_CONFIG_KEY) === cfgId) localStorage.removeItem(DEFAULT_CONFIG_KEY);
    } catch {
      /* storage unavailable */
    }
    setCfgId('');
  }, [stale, cfgId]);
  // Once the list has answered (or failed) and the choice is a real one, codes can be minted.
  useEffect(() => {
    if (configs && !stale) setMintReady(true);
  }, [configs, stale]);

  // Choosing a configuration fills the Wi-Fi fields from its saved enrollment Wi-Fi (still editable). The server
  // setting keeps what is there, but it no longer came from a configuration.
  useEffect(() => {
    if (!selected) {
      if (configs) setWifiFrom(null);
      return;
    }
    const ssid = typeof selected.wifiSSID === 'string' ? selected.wifiSSID : '';
    const sec = typeof selected.wifiSecurityType === 'string' ? selected.wifiSecurityType : '';
    setWifiSsid(ssid);
    setWifiPass(typeof selected.wifiPassword === 'string' ? selected.wifiPassword : '');
    setWifiSec((SECURITY_VALUES as string[]).includes(sec) ? (sec as WifiSecurity) : 'WPA');
    setWifiOn(!!ssid.trim());
    setWifiFrom(ssid.trim() ? selected.name : null);
  }, [selected, configs]);

  /**
   * Mint a token for `maxUses` devices, valid `hours`, bound to configuration `id` ('' = none: the server's setting
   * for new devices). The latest call wins. `keep` leaves the current code up while a new one for the SAME choices is
   * minted ("New code"); changing a choice clears it, so a code never carries a token for other choices.
   */
  const generate = useCallback(async (id: string, keep: boolean, maxUses: number, hours: number) => {
    const seq = ++mintSeq.current;
    const n = Number(id);
    // A new code doesn't stop the one it replaces. For a batch that one keeps working for anyone who has it: say so,
    // and offer to stop it (a single-use code is less of a worry, and the page never showed it to anyone else).
    const prev = shown.current;
    shown.current = null;
    if (prev && prev.maxUses > 1 && !(prev.expiresAt && prev.expiresAt <= Date.now())) {
      setReplaced((r) => ({
        count: (r?.count ?? 0) + 1,
        until: Math.max(r?.until ?? 0, prev.expiresAt ?? 0) || undefined,
      }));
    }
    setBusy(true);
    setTokError(null);
    if (!keep) setToken(null);
    try {
      const res = await mintEnrollToken(Number.isFinite(n) && n > 0 ? n : undefined, { maxUses, validHours: hours });
      if (seq !== mintSeq.current) return;
      const applied = typeof res.maxUses === 'number';
      const devices = typeof res.maxUses === 'number' ? res.maxUses : 1;
      if (res.token) {
        const next = { value: res.token, expiresAt: res.expiresAt, maxUses: devices, limitsApplied: applied };
        shown.current = next;
        setToken(next);
      } else setTokError('The server did not return a token.');
    } catch (err) {
      if (seq === mintSeq.current) setTokError(tokenError(err));
    } finally {
      if (seq === mintSeq.current) setBusy(false);
    }
  }, []);

  // The token binds the device to the configuration and its limits server-side: mint again whenever a choice changes.
  // The first code is minted at once; a later choice waits until it settles, so arrowing through a select mints one
  // token (each lives until it expires), not one per option passed. The old code goes at once: it is for other choices.
  useEffect(() => {
    if (!mintReady) return;
    if (minted.current) {
      ++mintSeq.current; // drop an answer still on its way for the previous choice
      setToken(null);
      setTokError(null);
      setBusy(true);
    }
    const t = setTimeout(
      () => {
        minted.current = true;
        void generate(cfgId, false, uses, validHours);
      },
      minted.current ? MINT_SETTLE_MS : 0,
    );
    return () => clearTimeout(t);
  }, [cfgId, uses, validHours, generate, mintReady]);

  const newCode = () => void generate(cfgId, true, uses, validHours);

  /** Stop every code that still works, then make a new one for the current choices (the shown one stopped too). */
  const stopAll = async () => {
    setStopping(true);
    setStopError(null);
    try {
      const res = await revokeAllEnrollTokens();
      shown.current = null;
      setReplaced(null);
      setStopOpen(false);
      const n = typeof res.revoked === 'number' ? res.revoked : null;
      toast.push(
        'ok',
        'Codes stopped',
        n === null ? 'Every enrollment code stopped working. A new code is ready.' : `${n} code${n === 1 ? '' : 's'} stopped working. A new code is ready.`,
      );
      void generate(cfgId, false, uses, validHours);
    } catch (err) {
      setStopError(revokeError(err));
    } finally {
      setStopping(false);
    }
  };
  const openStop = () => {
    setStopError(null);
    setStopOpen(true);
  };

  const givesName = selected?.name ?? (cfgId ? `Configuration #${cfgId}` : SERVER_SETTING);
  // What the code does, not what was asked: an older server ignores both choices and makes a single-use 24 h code.
  const limitsIgnored = !!token && !token.limitsApplied && (uses !== 1 || validHours !== 24);
  let devicesHelp: string;
  if (limitsIgnored) {
    devicesHelp =
      'This server makes single-use codes, valid for 24 hours, whatever is chosen here. Update it to set up a batch with one code.';
  } else if (uses === 1) {
    devicesHelp = 'One device, then the code stops working: the one to send a customer.';
  } else {
    devicesHelp = `Anyone with this code can enroll up to ${uses} devices until it expires, or until you stop all codes. Share it only with whoever sets them up.`;
  }
  const ssid = wifiSsid.trim();
  const wifiIssue = wifiOn ? wifiProblem({ ssid: wifiSsid, password: wifiPass, security: wifiSec }) : null;
  const wifiInCode = wifiOn && !!ssid && !wifiIssue;
  // The code carries this page's address (see enroll/provisioning.ts); compare it with the server's own setting, which
  // every configuration carries.
  const serverSetting = useMemo(
    () => configs?.map((c) => c.baseUrl).find((v): v is string => typeof v === 'string' && v.trim() !== '') ?? null,
    [configs],
  );
  const address = useMemo(() => addressProblem(serverBaseUrl(), serverSetting), [serverSetting]);
  const expired = !!token?.expiresAt && token.expiresAt <= now;
  const payload = useMemo(
    () =>
      token
        ? buildProvisioningPayload(
            token.value,
            wifiInCode ? { ssid: wifiSsid, password: wifiPass, security: wifiSec } : undefined,
          )
        : null,
    [token, wifiInCode, wifiSsid, wifiPass, wifiSec],
  );

  let expiresValue: ReactNode = busy ? 'Making a code…' : '—';
  if (token?.expiresAt) {
    expiresValue = expired ? (
      <span className="v2-enroll__expired">Expired {fmtExpiry(token.expiresAt)}</span>
    ) : (
      `${fmtExpiry(token.expiresAt)} (${untilText(token.expiresAt, now)})`
    );
  }
  const facts = (withWifi: boolean): KeyValueItem[] => [
    { key: 'gives', label: 'Gives', value: <span className="v2-enroll__gives">{givesName}</span> },
    ...(withWifi && wifiOn
      ? [{ key: 'wifi', label: 'Joins Wi-Fi', value: wifiIssue ? 'Not in the code (see Wi-Fi above)' : ssid || 'No network name yet' }]
      : []),
    { key: 'use', label: 'Use', value: usesText(token?.maxUses ?? uses) },
    { key: 'expires', label: 'Expires', value: expiresValue },
  ];

  const agentVersion = RELEASE_BUILD && update?.apk?.available ? update.apk.version : null;

  const configHelp = configsFailed ? (
    <>
      Couldn&rsquo;t load the configurations, so the code uses the server&rsquo;s setting for new devices.{' '}
      <LinkButton tone="inline" onClick={loadConfigs}>
        Retry
      </LinkButton>
    </>
  ) : (
    'New devices start with this configuration. The code and token are tied to it, so pick before you share them.'
  );

  const chooseCard = (
    <Card key="choose" padding="roomy" gap={16} aria-labelledby="v2-enroll-choose" className="v2-enroll__choose">
      <CardHeader number={1} size={17} title="Choose what the device gets" titleId="v2-enroll-choose" />
      <Field label="Configuration" help={configHelp}>
        <Select size="lg" value={cfgId} onChange={(e) => setCfgId(e.target.value)} disabled={configs === null}>
          {configs === null ? (
            <option value={cfgId}>Loading configurations…</option>
          ) : (
            <>
              <option value="">{SERVER_SETTING}</option>
              {configsFailed && cfgId ? <option value={cfgId}>{`Configuration #${cfgId}`}</option> : null}
              {configs.map((c) => (
                <option key={String(c.id)} value={String(c.id)}>
                  {c.name}
                  {String(c.id) === defaultId ? ' (default)' : ''}
                </option>
              ))}
            </>
          )}
        </Select>
      </Field>

      <div className="v2-enroll__limits">
        <Field label="Devices" help={devicesHelp}>
          <Select value={String(uses)} onChange={(e) => setUses(Number(e.target.value))}>
            {USES_OPTIONS.map((n) => (
              <option key={n} value={String(n)}>
                {n === 1 ? 'One device' : `Up to ${n} devices`}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Valid for">
          <Select value={String(validHours)} onChange={(e) => setValidHours(Number(e.target.value))}>
            {VALID_OPTIONS.map((o) => (
              <option key={o.hours} value={String(o.hours)}>
                {o.label}
              </option>
            ))}
          </Select>
        </Field>
      </div>

      <div className="v2-enroll__wifi">
        <div className="v2-enroll__wifirow">
          <div className="v2-enroll__wifitext">
            <span id="v2-enroll-wifi" className="v2-enroll__wifilabel">
              Join Wi-Fi during setup
            </span>
            <span id="v2-enroll-wifi-help" className="v2-enroll__help">
              Only needed if the device can&rsquo;t reach the server on mobile data.
            </span>
          </div>
          <Toggle
            checked={wifiOn}
            onChange={setWifiOn}
            showState={false}
            aria-labelledby="v2-enroll-wifi"
            aria-describedby="v2-enroll-wifi-help"
          />
        </div>
        {wifiOn ? (
          <>
            <div className="v2-enroll__wifigrid">
              <Field label="Network name" size="sm">
                <Input
                  value={wifiSsid}
                  onChange={(e) => setWifiSsid(e.target.value)}
                  placeholder="Office-WiFi"
                  autoComplete="off"
                  spellCheck={false}
                />
              </Field>
              <Field label="Security" size="sm">
                <Select value={wifiSec} onChange={(e) => setWifiSec(e.target.value as WifiSecurity)}>
                  <option value="WPA">WPA / WPA2</option>
                  <option value="WEP">WEP</option>
                  <option value="NONE">Open</option>
                  {wifiSec === 'EAP' ? <option value="EAP">EAP (from the configuration)</option> : null}
                </Select>
              </Field>
              {wifiSec !== 'NONE' ? (
                <Field label="Wi-Fi password" size="sm" className="v2-enroll__wide">
                  <Input
                    type="password"
                    value={wifiPass}
                    onChange={(e) => setWifiPass(e.target.value)}
                    autoComplete="new-password"
                  />
                </Field>
              ) : null}
            </div>
            {wifiIssue ? (
              <Callout tone="warn" icon={<IconWarning size={16} />} role="status">
                {wifiIssue}
              </Callout>
            ) : wifiSec !== 'NONE' ? (
              <Callout tone="warn" icon={<IconWarning size={16} />}>
                The Wi-Fi password is written into the QR code. Anyone who photographs the code can read it.
              </Callout>
            ) : null}
            <span className="v2-enroll__help">
              {wifiFrom ? `Filled in from ${wifiFrom}. ` : ''}Changes here only affect this code.
            </span>
          </>
        ) : null}
      </div>
    </Card>
  );

  const qrPanel =
    payload && !expired ? (
      <QrPanel
        value={payload}
        label={`Enrollment QR code for ${givesName}`}
        onNewCode={newCode}
        newCodeBusy={busy}
        fullScreenTitle={`Scan to enroll: ${givesName}`}
        fullScreenNote={`${(token?.maxUses ?? 1) === 1 ? 'Single-use' : `For up to ${token?.maxUses} devices`}.${
          token?.expiresAt ? ` Expires ${fmtExpiry(token.expiresAt)}.` : ''
        }`}
      />
    ) : (
      <div className="v2-qr">
        <div className="v2-qr__frame v2-enroll__qrwait" role="status">
          {busy ? 'Making a code…' : expired ? 'This code has expired.' : 'No code yet.'}
        </div>
        <div className="v2-qr__actions">
          <Button size="lg" icon={<IconSync size={14} />} onClick={newCode} disabled={busy}>
            {busy ? 'Making a code…' : 'New code'}
          </Button>
        </div>
      </div>
    );

  const enrollCard = (
    <Card key="enroll" padding="roomy" gap={16} aria-labelledby="v2-enroll-device" className="v2-enroll__device">
      <CardHeader
        number={2}
        size={17}
        title="Enroll the device"
        titleId="v2-enroll-device"
        actions={
          <Tabs
            size="md"
            idBase={METHOD_TABS}
            aria-label="Method"
            value={method}
            onChange={setMethod}
            items={[
              { key: 'qr', label: 'QR code' },
              { key: 'token', label: 'Token' },
            ]}
          />
        }
      />
      {address ? (
        <Callout tone={address.tone} icon={<IconWarning size={16} />} role="status">
          {address.text}
        </Callout>
      ) : null}
      {tokError ? (
        <Callout tone="alert" role="alert">
          {tokError}{' '}
          <LinkButton tone="inline" onClick={newCode} disabled={busy}>
            Try again
          </LinkButton>
        </Callout>
      ) : null}
      {expired && !busy ? (
        <Callout tone="warn" icon={<IconWarning size={16} />} role="status">
          This code has expired. Make a new one before you enroll a device.
        </Callout>
      ) : null}
      {replaced && !(replaced.until && replaced.until <= now) ? (
        <Callout tone="warn" icon={<IconWarning size={16} />} role="status">
          {replacedText(replaced)}{' '}
          <LinkButton tone="inline" onClick={openStop}>
            Stop all codes
          </LinkButton>
        </Callout>
      ) : null}
      <TabPanel idBase={METHOD_TABS} tabKey={method} className="v2-enroll__panel">
        {method === 'qr' ? (
          <div className="v2-enroll__cols">
            {qrPanel}
            <div className="v2-enroll__info">
              <KeyValueList variant="facts" items={facts(true)} />
              <NumberedSteps steps={qrSteps(wifiInCode)} />
            </div>
          </div>
        ) : (
          <div className="v2-enroll__token">
            {token && !expired ? (
              <Field label="Enrollment token">
                <CopyField
                  value={token.value}
                  onCopied={() => toast.push('ok', 'Token copied', 'Enrollment token copied to clipboard.')}
                />
              </Field>
            ) : (
              <p className="v2-enroll__tokenwait" role="status">
                {busy ? 'Making a token…' : expired ? 'This token has expired.' : 'No token yet.'}
              </p>
            )}
            <div>
              <Button size="lg" icon={<IconSync size={14} />} onClick={newCode} disabled={busy}>
                {busy && token ? 'Making a token…' : 'New token'}
              </Button>
            </div>
            <KeyValueList variant="facts" items={facts(false)} />
            <p className="v2-enroll__note">
              For headless or scripted provisioning: pass it as{' '}
              <span className="v2-mono">com.mdmesh.ENROLL_TOKEN</span>, with{' '}
              <span className="v2-mono">com.mdmesh.SERVER_URL</span> set to the server below. Scanning the QR code is
              the usual way.
            </p>
          </div>
        )}
      </TabPanel>
      <div className="v2-enroll__stop">
        <span className="v2-enroll__help">
          A code shared further than meant? Stop every code that still works; enrolled devices stay enrolled.
        </span>
        <Button size="sm" variant="danger" onClick={openStop}>
          Stop all codes
        </Button>
      </div>
      {!RELEASE_BUILD ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          No verified agent release was found when this console was built, so the code expects the debug build of
          the agent. Host a debug agent APK at the Agent address below, or re-run the installer once a release is
          published.
        </Callout>
      ) : null}
      <dl className="v2-enroll__foot">
        <div>
          <dt>Server</dt>
          <dd className="v2-mono">{serverBaseUrl()}</dd>
        </div>
        <div>
          <dt>Agent</dt>
          <dd className="v2-mono">{agentApkUrl()}</dd>
        </div>
        {agentVersion ? (
          <div>
            <dt>Version</dt>
            <dd className="v2-mono">{agentVersion}</dd>
          </div>
        ) : null}
      </dl>
    </Card>
  );

  return (
    <>
      <PageHeader title="Enroll a device" subtitle="Set up a new or factory-reset Android device so MDMesh manages it." />
      {/* QR first on phones: the person is usually holding the device to scan. */}
      <div className="v2-enroll">{mobile ? [enrollCard, chooseCard] : [chooseCard, enrollCard]}</div>
      <Modal
        open={stopOpen}
        size="sm"
        role="alertdialog"
        title="Stop all enrollment codes?"
        description="Every code that still works stops now: this one, codes you replaced, and codes other admins made, printed or shared ones too. Devices already enrolled stay enrolled. A new code is made for your choices."
        onClose={() => {
          if (!stopping) setStopOpen(false);
        }}
        footer={
          <>
            <Button size="lg" disabled={stopping} onClick={() => setStopOpen(false)}>
              Cancel
            </Button>
            <Button size="lg" variant="danger" disabled={stopping} onClick={() => void stopAll()}>
              {stopping ? 'Stopping…' : 'Stop all codes'}
            </Button>
          </>
        }
      >
        {stopError ? (
          <Callout tone="alert" role="alert">
            {stopError}
          </Callout>
        ) : null}
      </Modal>
    </>
  );
}
