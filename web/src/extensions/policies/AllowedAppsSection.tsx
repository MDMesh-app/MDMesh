// The Allowed apps tab of a policy. Pick a host phone, scan it, tick the apps its group may use, turn the kiosk screen
// on: phones then open only the ticked apps, and every other app (including apps the host doesn't have) is blocked
// from opening. The ticked list is the configuration's own app list, so it is what the server sends phones.
import { useEffect, useMemo, useRef, useState, type Key } from 'react';
import {
  Badge,
  Button,
  Callout,
  Card,
  CardHeader,
  ChangedChip,
  EmptyState,
  FieldRow,
  IconApps,
  IconButton,
  IconPlus,
  IconSync,
  IconTrash,
  IconWarning,
  Input,
  RelativeTime,
  SearchInput,
  Segmented,
  Select,
  Table,
  Toggle,
  type Column,
} from '@mdmesh/console';
import { cachedIcons, fetchIcons, latestScan, scanNow } from './api';
import type { FleetDevice } from './fleet';
import { isPackageName, looksLikeDialer, newOnHost, type AppControl, type AppException, type AppMode, type ScannedApp } from './model';
import { errorText, plural } from './shared';

const MODE_HELP: Record<AppMode, string> = {
  off: 'Extra apps are not checked.',
  report: 'Extra apps show on Status. Nothing is removed.',
  remove: 'Extra apps show on Status with a Remove button. You decide, per app or per phone.',
  auto: 'Extra apps are uninstalled, with their data, after each app scan: by the server with the Policies plugin, otherwise while the Policies page is open. Exceptions are kept.',
};

/** The allowed apps being edited: package → name. */
export type AllowList = ReadonlyMap<string, string>;

type Filter = 'host' | 'new' | 'allowed' | 'all';

interface Row {
  pkg: string;
  label: string;
  onHost: boolean;
  system: boolean;
}

interface HostScan {
  apps: ScannedApp[];
  scannedAt?: number;
}

export function AllowedAppsSection({
  readOnly,
  canAct,
  phones,
  hostChoices,
  host,
  storedHost,
  onHost,
  hostSeen,
  onHostSeen,
  allow,
  storedAllow,
  onAllow,
  kiosk,
  storedKiosk,
  onKiosk,
  pin,
  storedPin,
  onPin,
  control,
  storedControl,
  onControl,
}: {
  readOnly: boolean;
  /** May queue commands (scan, icons). */
  canAct: boolean;
  /** The policy's phones. */
  phones: FleetDevice[];
  /** Phones the host may be picked from: the policy's own, or every phone when it has none. */
  hostChoices: FleetDevice[];
  host: string | undefined;
  storedHost: string | undefined;
  onHost: (number: string | undefined) => void;
  /** The host's apps at the last review; undefined before the first. */
  hostSeen: readonly string[] | undefined;
  /** Mark the host's current apps as reviewed (a policy change, saved with the save bar). */
  onHostSeen: (pkgs: string[]) => void;
  allow: AllowList;
  storedAllow: AllowList;
  onAllow: (next: AllowList) => void;
  kiosk: boolean;
  storedKiosk: boolean;
  onKiosk: (on: boolean) => void;
  pin: string;
  storedPin: string;
  onPin: (pin: string) => void;
  control: AppControl;
  storedControl: AppControl;
  onControl: (next: AppControl) => void;
}) {
  const [scan, setScan] = useState<HostScan | null>(null);
  const [scanState, setScanState] = useState<{ kind: 'idle' | 'loading' | 'scanning' | 'icons' } | { kind: 'error'; message: string }>({ kind: 'idle' });
  const [icons, setIcons] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState<Filter>('allowed');
  const [q, setQ] = useState('');
  const [pkg, setPkg] = useState('');
  const [exDevice, setExDevice] = useState('');
  const [exPkg, setExPkg] = useState('');
  const abort = useRef<AbortController | null>(null);

  const hostDevice = hostChoices.find((d) => d.device.number === host) ?? phones.find((d) => d.device.number === host);
  const phoneName = (n: string) =>
    [...phones, ...hostChoices].find((d) => d.device.number === n)?.device.description || n;

  // The host's saved scan, as soon as a host is chosen (no command is sent for it).
  useEffect(() => {
    abort.current?.abort();
    setScan(null);
    setScanState({ kind: 'idle' });
    if (!host) return;
    let on = true;
    setScanState({ kind: 'loading' });
    latestScan(host)
      .then((r) => {
        if (!on) return;
        setScan(r);
        setScanState({ kind: 'idle' });
        if (r) {
          setIcons((prev) => ({ ...prev, ...cachedIcons(r.apps.map((a) => a.pkg)) }));
          setFilter('host');
        }
      })
      .catch((e: unknown) => on && setScanState({ kind: 'error', message: errorText(e, 'Could not read its last scan.') }));
    return () => {
      on = false;
    };
  }, [host]);

  useEffect(() => () => abort.current?.abort(), []);

  async function scanHost() {
    if (!host) return;
    abort.current?.abort();
    const ctl = new AbortController();
    abort.current = ctl;
    setScanState({ kind: 'scanning' });
    try {
      const apps = await scanNow(host, ctl.signal);
      setScan({ apps, scannedAt: Date.now() });
      setFilter('host');
      const have = cachedIcons(apps.map((a) => a.pkg));
      setIcons((prev) => ({ ...prev, ...have }));
      const need = apps.map((a) => a.pkg).filter((p) => !have[p]);
      if (need.length) {
        setScanState({ kind: 'icons' });
        await fetchIcons(host, need, (got) => setIcons((prev) => ({ ...prev, ...got })), ctl.signal).catch(() => undefined);
      }
      setScanState({ kind: 'idle' });
    } catch (e) {
      if (!ctl.signal.aborted) setScanState({ kind: 'error', message: errorText(e, 'The scan failed.') });
    }
  }

  const rows = useMemo<Row[]>(() => {
    const out = new Map<string, Row>();
    for (const a of scan?.apps ?? []) out.set(a.pkg, { pkg: a.pkg, label: a.label || a.pkg, onHost: true, system: a.system === true });
    for (const [p, name] of [...storedAllow, ...allow]) {
      if (!out.has(p)) out.set(p, { pkg: p, label: name || p, onHost: false, system: false });
    }
    return [...out.values()].sort((x, y) => x.label.localeCompare(y.label));
  }, [scan, allow, storedAllow]);

  // Apps new on the host since the last review: on it, not allowed, not seen then.
  const fresh = useMemo(() => newOnHost(scan?.apps ?? [], new Set(allow.keys()), hostSeen), [scan, allow, hostSeen]);
  const freshPkgs = useMemo(() => new Set(fresh.map((a) => a.pkg)), [fresh]);
  const reviewedAll = !!scan && !!hostSeen && scan.apps.every((a) => hostSeen.includes(a.pkg));

  const shown = useMemo(() => {
    const term = q.trim().toLowerCase();
    return rows.filter((r) => {
      if (filter === 'host' && !r.onHost) return false;
      if (filter === 'new' && !freshPkgs.has(r.pkg)) return false;
      if (filter === 'allowed' && !allow.has(r.pkg)) return false;
      return !term || r.label.toLowerCase().includes(term) || r.pkg.toLowerCase().includes(term);
    });
  }, [rows, filter, q, allow, freshPkgs]);

  const onHostCount = rows.filter((r) => r.onHost).length;
  const hostAllowed = rows.filter((r) => r.onHost && allow.has(r.pkg)).length;
  const listChanged = !sameList(allow, storedAllow);
  const noDialer = kiosk && allow.size > 0 && ![...allow.keys()].some(looksLikeDialer);
  const pkgOk = isPackageName(pkg);
  const busyScan = scanState.kind === 'scanning' || scanState.kind === 'icons';

  const setAllowed = (r: Row, on: boolean, base: Map<string, string>) => {
    if (on) base.set(r.pkg, r.label);
    else base.delete(r.pkg);
  };
  const toggle = (r: Row) => {
    const next = new Map(allow);
    setAllowed(r, !allow.has(r.pkg), next);
    onAllow(next);
  };

  const columns: Column<Row>[] = [
    {
      key: 'app',
      header: 'App',
      cell: (r) => (
        <span className="x-policies-appcell">
          <span className="x-policies-appicon" aria-hidden="true">
            {icons[r.pkg] ? <img src={icons[r.pkg]} alt="" /> : r.label.slice(0, 1).toUpperCase()}
          </span>
          <span className="x-policies-cell">
            <span className="x-policies-strong">{r.label}</span>
            <span className="x-policies-help x-policies-mono">{r.pkg}</span>
          </span>
        </span>
      ),
    },
    {
      key: 'host',
      header: 'On host',
      cell: (r) => (r.onHost ? 'Yes' : <span className="x-policies-muted">No</span>),
    },
    {
      key: 'kind',
      header: 'Type',
      cell: (r) => (r.onHost ? (r.system ? 'Preinstalled' : 'Installed') : <span className="x-policies-muted">—</span>),
    },
    {
      key: 'state',
      header: 'On the kiosk screen',
      cell: (r) =>
        allow.has(r.pkg) ? (
          <Badge tone="ok" size="sm">
            Allowed
          </Badge>
        ) : (
          <span className="x-policies-muted">Blocked</span>
        ),
    },
  ];

  const exceptionColumns: Column<AppException>[] = [
    { key: 'phone', header: 'Phone', cell: (e) => <span className="x-policies-strong">{phoneName(e.device)}</span> },
    { key: 'app', header: 'May keep', mono: true, cell: (e) => e.pkg },
    { key: 'note', header: 'Why', cell: (e) => e.note ?? <span className="x-policies-muted">—</span> },
    ...(!readOnly
      ? [
          {
            key: 'remove',
            header: 'Remove',
            headerHidden: true,
            shrink: true,
            cell: (e: AppException) => (
              <IconButton
                label="Remove exception"
                size="sm"
                onClick={() => onControl({ ...control, exceptions: control.exceptions.filter((x) => x !== e) })}
              >
                <IconTrash />
              </IconButton>
            ),
          },
        ]
      : []),
  ];

  const changedChip = (on: boolean) => (on ? <ChangedChip /> : undefined);

  return (
    <>
      <Card>
        <CardHeader
          title={<span className="x-policies-inline">Host phone {changedChip(host !== storedHost)}</span>}
          subtitle="A phone with every app this policy's phones need, installed and set up. Its scan lists the apps you can allow."
          level={3}
        />
        <div className="x-policies-addrow">
          <Select
            value={host ?? ''}
            disabled={readOnly}
            onChange={(e) => onHost(e.target.value || undefined)}
            aria-label="Host phone"
          >
            <option value="">Choose a phone…</option>
            {hostChoices.map((d) => (
              <option key={d.device.id} value={d.device.number}>
                {d.device.description || d.device.number}
              </option>
            ))}
          </Select>
          <Button icon={<IconSync />} disabled={!host || !canAct || busyScan} onClick={() => void scanHost()}>
            {scanState.kind === 'scanning' ? 'Scanning…' : scanState.kind === 'icons' ? 'Getting icons…' : 'Scan host now'}
          </Button>
        </div>
        <p className="x-policies-help is-below">
          {!host ? (
            'Pick the phone you set up as the model for this policy.'
          ) : scanState.kind === 'loading' ? (
            'Reading its last scan…'
          ) : scanState.kind === 'scanning' ? (
            'Asking the phone for its apps. This takes up to a minute; the phone must be online.'
          ) : scanState.kind === 'icons' ? (
            'Fetching the app icons from the phone…'
          ) : scan ? (
            <>
              {plural(scan.apps.length, 'app')} with an icon, scanned {scan.scannedAt ? <RelativeTime value={scan.scannedAt} style="long" /> : 'earlier'}
              {hostDevice && !phones.some((d) => d.device.number === host) ? '. This phone is not on this policy.' : '.'}
            </>
          ) : (
            'This phone has never been scanned. Scan it now (it must be online).'
          )}
        </p>
        {scanState.kind === 'error' ? <p className="x-policies-error">{scanState.message}</p> : null}
      </Card>

      <Card padding="none" clip>
        <div className="x-policies-cardhead">
          <CardHeader
            title={<span className="x-policies-inline">Allowed apps {changedChip(listChanged)}</span>}
            subtitle={
              scan
                ? `${plural(allow.size, 'app')} allowed · ${hostAllowed} of ${onHostCount} host apps ticked. Ticked apps open on the kiosk screen; every other app is blocked.`
                : `${plural(allow.size, 'app')} allowed. Ticked apps open on the kiosk screen; every other app is blocked.`
            }
            level={3}
            actions={
              !readOnly && scan && !reviewedAll ? (
                <Button size="sm" onClick={() => onHostSeen(scan.apps.map((a) => a.pkg))}>
                  Mark reviewed
                </Button>
              ) : undefined
            }
          />
          {fresh.length ? (
            <Callout tone="info" icon={<IconApps size={16} />}>
              {plural(fresh.length, 'new app')} on the host since the last review:{' '}
              {fresh.slice(0, 5).map((a) => a.label || a.pkg).join(', ')}
              {fresh.length > 5 ? ` and ${fresh.length - 5} more` : ''}. They stay blocked unless you tick them.
            </Callout>
          ) : null}
          <div className="x-policies-toolbar x-policies-toolbar--below">
            <Segmented<Filter>
              aria-label="Show"
              size="sm"
              value={filter}
              onChange={setFilter}
              options={[
                { value: 'host', label: `On host${scan ? ` (${onHostCount})` : ''}`, disabled: !scan },
                ...(fresh.length ? [{ value: 'new' as const, label: `New on host (${fresh.length})` }] : []),
                { value: 'allowed', label: `Allowed (${allow.size})` },
                { value: 'all', label: 'All' },
              ]}
            />
            <SearchInput className="x-policies-toolbar__search" value={q} onChange={setQ} placeholder="Search apps" aria-label="Search apps" />
          </div>
        </div>
        <Table<Row>
          aria-label="Apps"
          columns={columns}
          rows={shown}
          rowKey={(r) => r.pkg}
          edge={20}
          minWidth={620}
          selection={
            readOnly
              ? undefined
              : {
                  selected: new Set<Key>(shown.filter((r) => allow.has(r.pkg)).map((r) => r.pkg)),
                  onToggle: (_k, r) => toggle(r),
                  onToggleAll: (select) => {
                    const next = new Map(allow);
                    for (const r of shown) setAllowed(r, select, next);
                    onAllow(next);
                  },
                  rowLabel: (r) => `Allow ${r.label}`,
                  allLabel: 'Allow every app shown',
                }
          }
          empty={
            <EmptyState icon={<IconApps />} title={filter === 'allowed' ? 'No app is allowed yet' : 'No apps to show'} bare>
              {filter === 'allowed' && !scan ? 'Choose a host phone and scan it, then tick the apps to allow.' : null}
            </EmptyState>
          }
        />
        {!readOnly ? (
          <div className="x-policies-cardfoot">
            <div className="x-policies-addrow">
              <Input
                mono
                value={pkg}
                tone={pkg && (!pkgOk || allow.has(pkg.trim())) ? 'invalid' : 'default'}
                onChange={(e) => setPkg(e.target.value)}
                placeholder="com.example.app"
                aria-label="Package name to allow"
              />
              <Button
                icon={<IconPlus />}
                disabled={!pkgOk || allow.has(pkg.trim())}
                onClick={() => {
                  const p = pkg.trim();
                  onAllow(new Map(allow).set(p, rows.find((r) => r.pkg === p)?.label ?? p));
                  setPkg('');
                }}
              >
                Allow by package name
              </Button>
            </div>
            <p className="x-policies-help">For an app the host doesn&rsquo;t have. Apps not ticked stay blocked on every phone, whether the host has them or not.</p>
            {pkg && !pkgOk ? <p className="x-policies-error">Enter a package name, like com.company.app.</p> : null}
          </div>
        ) : null}
      </Card>

      <Card>
        <CardHeader
          title="Kiosk screen"
          subtitle="The allowed list only works while kiosk is on: it is what blocks every other app from opening."
          level={3}
        />
        <FieldRow
          label="Kiosk mode"
          help={
            kiosk
              ? 'On: phones show only the allowed apps, and the home, settings and other apps are locked.'
              : 'Off: phones use their normal home screen and every app opens.'
          }
          adornment={changedChip(kiosk !== storedKiosk)}
          divider
        >
          {({ labelId }) => <Toggle aria-labelledby={labelId} checked={kiosk} disabled={readOnly} onChange={onKiosk} showState />}
        </FieldRow>
        <FieldRow
          label="Exit PIN"
          help="Asked for when someone leaves kiosk on the phone. Choose a PIN only your staff know."
          adornment={changedChip(pin !== storedPin)}
          divider
          disabled={!kiosk}
        >
          {({ labelId }) => (
            <Input
              aria-labelledby={labelId}
              type="password"
              autoComplete="new-password"
              inputMode="numeric"
              value={pin}
              maxLength={32}
              disabled={readOnly || !kiosk}
              tone={kiosk && !pin ? 'warn' : 'default'}
              onChange={(e) => onPin(e.target.value)}
            />
          )}
        </FieldRow>
        {kiosk && !pin ? (
          <Callout tone="warn" icon={<IconWarning size={16} />}>
            There is no exit PIN. Phones on agent 0.3.5 or older let anyone leave kiosk without a prompt. Set one before
            turning kiosk on.
          </Callout>
        ) : null}
        {noDialer ? (
          <Callout tone="warn" icon={<IconWarning size={16} />}>
            None of the allowed apps looks like a phone app, so users won&rsquo;t be able to make calls. Tick the phone
            (dialer) app of the host if they need to.
          </Callout>
        ) : null}
        {kiosk && allow.size === 0 ? (
          <Callout tone="warn" icon={<IconWarning size={16} />}>
            No app is allowed: phones would show an empty kiosk screen.
          </Callout>
        ) : null}
      </Card>

      <Card>
        <CardHeader
          title="Extra apps"
          subtitle="Apps installed on a phone that aren't allowed. Kiosk already stops them opening; this decides whether they are also listed or removed. Apps the phone marks as system can't be removed and aren't listed."
          level={3}
        />
        <FieldRow
          label="When an extra app is found"
          help={MODE_HELP[control.mode]}
          adornment={changedChip(control.mode !== storedControl.mode)}
          divider
        >
          {({ labelId }) => (
            <Segmented<AppMode>
              aria-labelledby={labelId}
              wrap
              disabled={readOnly}
              value={control.mode}
              onChange={(mode) => onControl({ ...control, mode })}
              options={[
                { value: 'off', label: 'Off' },
                { value: 'report', label: 'Report only' },
                { value: 'remove', label: 'One-click remove' },
                { value: 'auto', label: 'Auto-remove' },
              ]}
            />
          )}
        </FieldRow>
        <FieldRow
          label="Tell the user"
          help="Show an alert on the phone when an app is removed from it."
          adornment={changedChip(control.notify !== storedControl.notify)}
          divider
          disabled={control.mode === 'off' || control.mode === 'report'}
        >
          {({ labelId }) => (
            <Toggle
              aria-labelledby={labelId}
              checked={control.notify}
              disabled={readOnly || control.mode === 'off' || control.mode === 'report'}
              onChange={(notify) => onControl({ ...control, notify })}
              showState
            />
          )}
        </FieldRow>
        {control.mode === 'auto' ? (
          <Callout tone="warn" icon={<IconWarning size={16} />}>
            Auto-remove uninstalls every user-installed app that isn&rsquo;t allowed, with its data, on every phone of
            this policy. Try Report only first and check Status.
          </Callout>
        ) : null}
      </Card>

      <Card padding="none" clip>
        <div className="x-policies-cardhead">
          <CardHeader
            title={
              <span className="x-policies-inline">
                Exceptions {changedChip(JSON.stringify(control.exceptions) !== JSON.stringify(storedControl.exceptions))}
              </span>
            }
            subtitle="A phone that may keep an extra app: it is neither listed nor removed there. To let it open the app too, allow the app."
            level={3}
          />
        </div>
        <Table<AppException>
          aria-label="Exceptions"
          columns={exceptionColumns}
          rows={control.exceptions}
          rowKey={(e) => `${e.device}:${e.pkg}`}
          edge={20}
          empty={
            <EmptyState icon={<IconApps />} title="No exceptions" bare>
              Add one here, or with Let it keep on Status.
            </EmptyState>
          }
        />
        {!readOnly && phones.length ? (
          <div className="x-policies-cardfoot">
            <div className="x-policies-addrow">
              <Select value={exDevice} onChange={(e) => setExDevice(e.target.value)} aria-label="Phone">
                <option value="">Phone…</option>
                {phones.map((d) => (
                  <option key={d.device.id} value={d.device.number}>
                    {d.device.description || d.device.number}
                  </option>
                ))}
              </Select>
              <Input mono value={exPkg} onChange={(e) => setExPkg(e.target.value)} placeholder="com.example.app" aria-label="Package it may keep" />
              <Button
                icon={<IconPlus />}
                disabled={!exDevice || !isPackageName(exPkg) || control.exceptions.some((x) => x.device === exDevice && x.pkg === exPkg.trim())}
                onClick={() => {
                  onControl({ ...control, exceptions: [...control.exceptions, { pkg: exPkg.trim(), device: exDevice }] });
                  setExPkg('');
                }}
              >
                Add exception
              </Button>
            </div>
          </div>
        ) : null}
      </Card>
    </>
  );
}

/** Whether two allowed lists hold the same packages. */
export function sameList(a: AllowList, b: AllowList): boolean {
  if (a.size !== b.size) return false;
  for (const k of a.keys()) if (!b.has(k)) return false;
  return true;
}

