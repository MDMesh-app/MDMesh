import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { CommandHistoryItem, QueueCommandRequest } from '../api/commands';
import { forceSync } from '../api/commands';
import type { AppInfo } from '../api/deviceApps';
import {
  Button,
  Callout,
  Card,
  CardHeader,
  Checkbox,
  EmptyState,
  IconApps,
  Modal,
  IconDownload,
  IconSync,
  IconWarning,
  RelativeTime,
  SearchInput,
  Table,
  type Column,
} from '../ui';
import { appsCsv, byLabel, filterApps, latestScan, scanProgress, appChange, appResultPending } from '../data/deviceApps';
import { DASH } from './deviceDetailFormat';

/** How long Scan now waits for the device, as the kiosk picker does (api/deviceApps runForResult). */
const SCAN_TIMEOUT_MS = 75_000;

/** The page's queue: toasts the outcome, nudges the device and refreshes the polled history. */
type Queue = (
  label: string,
  request: () => QueueCommandRequest,
  onQueued?: (cmdId: number | string | undefined) => void,
) => Promise<boolean>;

export interface AppScanState {
  /** Queued and not answered yet (and not timed out). */
  scanning: boolean;
  /** Why the last Scan now gave no list. */
  error: string | null;
  start: () => void;
}

/**
 * Scan now: queues apps.scan through the page's queue and follows it in the page's polled history, so there is no
 * second poll. Kept by the page, so a scan in flight survives a tab change; a different device forgets it.
 */
export function useAppScan(number: string | undefined, history: readonly CommandHistoryItem[], queue: Queue, canEdit = true): AppScanState {
  const [queueing, setQueueing] = useState(false);
  const [queued, setQueued] = useState<string | null>(null);
  const [timedOut, setTimedOut] = useState(false);
  const seq = useRef(0);
  const inFlight = useRef(false);
  const attempted = useRef<string | null>(null);
  const nextSync = useRef(0);

  useEffect(() => {
    seq.current++;
    inFlight.current = false;
    attempted.current = null;
    nextSync.current = 0;
    setQueueing(false);
    setQueued(null);
    setTimedOut(false);
  }, [number]);

  const progress = queued ? scanProgress(history, queued, true) : null;
  const waiting = progress?.state === 'pending';
  useEffect(() => {
    if (!waiting) return;
    const t = setTimeout(() => setTimedOut(true), SCAN_TIMEOUT_MS);
    return () => clearTimeout(t);
  }, [queued, waiting]);

  const start = useCallback(async () => {
    if (!canEdit || inFlight.current || (waiting && !timedOut)) return;
    inFlight.current = true;
    const mine = ++seq.current;
    setQueueing(true);
    setQueued(null);
    setTimedOut(false);
    try {
      await queue(
        'App scan',
        () => ({ type: 'apps.scan', payload: JSON.stringify({ includeAll: true }) }),
        (cmdId) => {
          if (mine === seq.current && cmdId != null) setQueued(String(cmdId));
        },
      );
    } finally {
      if (mine === seq.current) {
        setQueueing(false);
        inFlight.current = false;
      }
    }
  }, [queue, canEdit, waiting, timedOut]);

  useEffect(() => {
    if (!number || !canEdit || document.hidden) return;
    const saved = latestScan(history, true);
    const after = saved?.freshnessAt ?? saved?.scannedAt ?? 0;
    const changed = appChange(history, after);
    if (changed && String(changed.id) !== attempted.current && !queueing && !waiting) {
      attempted.current = String(changed.id);
      void start();
    }
    const controller = new AbortController();
    if (appResultPending(history, after, queued) && Date.now() >= nextSync.current) {
      nextSync.current = Date.now() + 15000;
      void forceSync(number, controller.signal).catch(() => undefined);
    }
    return () => controller.abort();
  }, [number, history, canEdit, queued, waiting, queueing, start]);

  return {
    scanning: queueing || (waiting && !timedOut),
    error: progress?.state === 'failed' ? progress.reason : waiting && timedOut ? 'Timed out waiting for the device (is it online?)' : null,
    start: () => void start(),
  };
}

const COLUMNS: ReadonlyArray<Column<AppInfo>> = [
  { key: 'app', header: 'App', cell: (a) => a.label },
  { key: 'pkg', header: 'Package', cell: (a) => a.pkg, mono: true },
  {
    key: 'version',
    header: 'Version',
    cell: (a) => `${a.versionName ?? DASH}${a.versionCode != null ? ` (${a.versionCode})` : ''}`,
    mono: true,
  },
  { key: 'type', header: 'Type', cell: (a) => (a.system ? 'System' : 'User') },
];

function downloadCsv(name: string, text: string) {
  const url = URL.createObjectURL(new Blob(['\uFEFF' + text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Installed apps from the latest full scan, falling back to a clearly labelled older launcher-only scan.
 * The existing page history drives refresh and action results. Scan now (`scan`, only for a role with edit_devices) asks the device again; Export CSV writes
 * every app, system ones included, for app audits.
 */
export function AppsTab({
  number,
  history,
  loaded,
  scan,
  queue,
}: {
  number: string;
  history: readonly CommandHistoryItem[];
  loaded: boolean;
  scan?: AppScanState;
  queue?: Queue;
}) {
  const titleId = useId();
  const [query, setQuery] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const latest = useMemo(() => latestScan(history, true), [history]);
  const [action, setAction] = useState<{ app: AppInfo; kind: 'uninstall' | 'disable' | 'enable' } | null>(null);
  const [sending, setSending] = useState(false);
  const [pending, setPending] = useState<{ id: string; pkg: string } | null>(null);
  const actionSeq = useRef(0);
  useEffect(() => {
    actionSeq.current++;
    setAction(null);
    setPending(null);
    setSending(false);
  }, [number]);
  const pendingCommand = pending ? history.find((c) => String(c.id) === pending.id) : null;
  const actionWaiting = !!pending && (!pendingCommand || ['pending', 'delivered', 'accepted'].includes(pendingCommand.status));
  async function confirmAction() {
    if (!action || !queue || sending) return;
    const current = action;
    const mine = actionSeq.current;
    setSending(true);
    try {
      await queue(
        `${current.kind === 'uninstall' ? 'Uninstall' : current.kind === 'disable' ? 'Disable' : 'Enable'} app`,
        () => ({
          type: current.kind === 'uninstall' ? 'app.uninstall' : 'app.setHidden',
          requiresCapability: current.kind === 'uninstall' ? 'app.silentUninstall' : 'app.setHidden',
          payload: JSON.stringify({
            packageName: current.app.pkg,
            ...(current.kind === 'uninstall' ? {} : { hidden: current.kind === 'disable' }),
          }),
        }),
        (id) => {
          if (mine === actionSeq.current) {
            setAction(null);
            if (id != null) setPending({ id: String(id), pkg: current.app.pkg });
          }
        },
      );
    } finally {
      if (mine === actionSeq.current) setSending(false);
    }
  }
  const columns: ReadonlyArray<Column<AppInfo>> = [
    ...COLUMNS,
    { key: 'state', header: 'State', cell: (a) => (a.hidden ? 'Disabled (hidden)' : 'Available') },
    ...(queue
      ? [
          {
            key: 'actions',
            header: 'Actions',
            cell: (a: AppInfo) => {
              const reason = !latest?.appActions ? 'Update the agent to manage apps safely.' : a.protectedReason;
              const busy = sending || scan?.scanning || actionWaiting;
              return (
                <div style={{ display: 'flex', gap: 8 }}>
                  <Button
                    size="sm"
                    variant="danger"
                    disabled={!!reason || !!a.system || busy}
                    title={reason || (a.system ? 'System apps cannot be uninstalled' : undefined)}
                    onClick={() => setAction({ app: a, kind: 'uninstall' })}
                  >
                    Uninstall
                  </Button>
                  <Button
                    size="sm"
                    disabled={(!a.hidden && !!reason) || !latest?.appActions || busy}
                    title={(!a.hidden ? reason : undefined) || 'Hide this app without deleting its data, or restore it.'}
                    onClick={() => setAction({ app: a, kind: a.hidden ? 'enable' : 'disable' })}
                  >
                    {a.hidden ? 'Enable' : 'Disable'}
                  </Button>
                </div>
              );
            },
          },
        ]
      : []),
  ];
  const all = useMemo(() => [...(latest?.apps ?? [])].sort(byLabel), [latest]);
  const shown = filterApps(all, query, showSystem);
  const systemHidden = !showSystem && all.some((a) => a.system);
  const counted = showSystem ? all.length : all.filter((a) => !a.system).length;

  let empty: { title: string; text?: string } | null = null;
  if (!loaded) empty = { title: 'Loading the last scan…' };
  else if (!latest) {
    empty = {
      title: 'No recent scan',
      text: `The device’s recent commands hold no finished app scan.${scan ? ' Scan now asks it for its apps.' : ''}`,
    };
  } else if (all.length === 0) {
    empty = {
      title: 'The last scan listed no apps',
      text: scan ? 'Give the device a moment after an update, then scan again.' : undefined,
    };
  }

  return (
    <Card padding="list" clip aria-labelledby={titleId} className="v2-device-apps">
      <CardHeader
        inset
        spacing={8}
        title="Installed apps"
        titleId={titleId}
        subtitle={
          latest?.includeAll
            ? 'All installed packages for the managed Android user, including hidden apps.'
            : 'Launcher apps from an older scan. Scan with an updated agent for the full inventory.'
        }
        actions={
          <>
            {latest ? (
              <span className="v2-device-meta">
                {counted} app{counted === 1 ? '' : 's'}
                {latest.scannedAt ? (
                  <>
                    {' · scanned '}
                    <RelativeTime value={latest.scannedAt} />
                  </>
                ) : null}
              </span>
            ) : null}
            <Button
              size="md"
              icon={<IconDownload size={16} />}
              disabled={all.length === 0}
              title="All apps, system ones included"
              onClick={() => downloadCsv(`apps-${number}.csv`, appsCsv(all))}
            >
              Export CSV
            </Button>
            {scan ? (
              <Button size="md" icon={<IconSync />} disabled={scan.scanning} onClick={scan.start}>
                {scan.scanning ? 'Scanning…' : 'Scan now'}
              </Button>
            ) : null}
          </>
        }
      />
      {pending ? (
        <div className="v2-device-apps__body">
          <Callout
            tone={pendingCommand && ['failed', 'unsupported', 'expired'].includes(pendingCommand.status) ? 'alert' : 'info'}
            role="status"
          >
            {pending.pkg}: {pendingCommand?.status ?? 'pending'}
            {pendingCommand?.detail ? ` — ${pendingCommand.detail}` : ''}
          </Callout>
        </div>
      ) : null}
      {scan?.error || scan?.scanning || all.length ? (
        <div className="v2-device-apps__body">
          {scan?.error ? (
            <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
              {scan.error}
            </Callout>
          ) : null}
          {scan?.scanning ? (
            <p className="v2-device-meta" role="status">
              Asking the device for its apps…
            </p>
          ) : null}
          {all.length ? (
            <div className="v2-device-apps__tools">
              <SearchInput
                className="v2-device-apps__search"
                aria-label="Search apps"
                placeholder="Search name or package"
                value={query}
                onChange={setQuery}
              />
              <Checkbox label="Show system apps" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} />
            </div>
          ) : null}
        </div>
      ) : null}
      {empty ? (
        <div className="v2-device-cmds__empty">
          <EmptyState bare icon={<IconApps />} title={empty.title}>
            {empty.text ?? null}
          </EmptyState>
        </div>
      ) : (
        <Table
          aria-label="Installed apps"
          columns={columns}
          rows={shown}
          rowKey={(a) => a.pkg}
          edge={24}
          minWidth={820}
          empty={`No apps match.${systemHidden ? ' System apps are hidden.' : ''}`}
        />
      )}
      {action ? (
        <Modal
          open
          title={`${action.kind === 'uninstall' ? 'Uninstall' : action.kind === 'disable' ? 'Disable' : 'Enable'} ${action.app.label}?`}
          description={`Device ${number} · ${action.app.pkg}`}
          onClose={() => {
            if (!sending) setAction(null);
          }}
          role="alertdialog"
          footer={
            <>
              <Button disabled={sending} onClick={() => setAction(null)}>
                Cancel
              </Button>
              <Button variant={action.kind === 'uninstall' ? 'danger' : 'primary'} disabled={sending} onClick={() => void confirmAction()}>
                Confirm
              </Button>
            </>
          }
        >
          <p>
            {action.kind === 'uninstall'
              ? 'This removes the app and its local data. An Install assignment in the configuration may install it again.'
              : action.kind === 'disable'
                ? 'This hides the app and prevents its use. Its package and data stay on the device; Enable restores it.'
                : 'This makes the hidden app available again without deleting its data.'}
          </p>
        </Modal>
      ) : null}
    </Card>
  );
}
