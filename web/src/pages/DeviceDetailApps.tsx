import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { CommandHistoryItem, QueueCommandRequest } from '../api/commands';
import type { AppInfo } from '../api/deviceApps';
import {
  Button,
  Callout,
  Card,
  CardHeader,
  Checkbox,
  EmptyState,
  IconApps,
  IconDownload,
  IconSync,
  IconWarning,
  RelativeTime,
  SearchInput,
  Table,
  type Column,
} from '../ui';
import { appsCsv, byLabel, filterApps, latestScan, scanProgress } from '../data/deviceApps';
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
export function useAppScan(number: string | undefined, history: readonly CommandHistoryItem[], queue: Queue): AppScanState {
  const [queueing, setQueueing] = useState(false);
  const [queued, setQueued] = useState<string | null>(null);
  const [timedOut, setTimedOut] = useState(false);
  const seq = useRef(0);

  useEffect(() => {
    seq.current++;
    setQueueing(false);
    setQueued(null);
    setTimedOut(false);
  }, [number]);

  const progress = queued ? scanProgress(history, queued) : null;
  const waiting = progress?.state === 'pending';
  useEffect(() => {
    if (!waiting) return;
    const t = setTimeout(() => setTimedOut(true), SCAN_TIMEOUT_MS);
    return () => clearTimeout(t);
  }, [queued, waiting]);

  async function start() {
    const mine = ++seq.current;
    setQueueing(true);
    setQueued(null);
    setTimedOut(false);
    try {
      await queue('App scan', () => ({ type: 'apps.scan' }), (cmdId) => {
        if (mine === seq.current && cmdId != null) setQueued(String(cmdId));
      });
    } finally {
      if (mine === seq.current) setQueueing(false);
    }
  }

  return {
    scanning: queueing || (waiting && !timedOut),
    error:
      progress?.state === 'failed'
        ? progress.reason
        : waiting && timedOut
          ? 'Timed out waiting for the device (is it online?)'
          : null,
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
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * The device's installed apps from its latest apps.scan in the polled history: the apps with a launcher icon, as the
 * agent reports them. Scan now (`scan`, only for a role with edit_devices) asks the device again; Export CSV writes
 * every app, system ones included, for app audits.
 */
export function AppsTab({
  number,
  history,
  loaded,
  scan,
}: {
  number: string;
  history: readonly CommandHistoryItem[];
  loaded: boolean;
  scan?: AppScanState;
}) {
  const titleId = useId();
  const [query, setQuery] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const latest = useMemo(() => latestScan(history), [history]);
  const all = useMemo(() => [...(latest?.apps ?? [])].sort(byLabel), [latest]);
  const shown = filterApps(all, query, showSystem);
  const systemHidden = !showSystem && all.some((a) => a.system);

  let empty: { title: string; text?: string } | null = null;
  if (!loaded) empty = { title: 'Loading the last scan…' };
  else if (!latest) {
    empty = {
      title: 'No recent scan',
      text: `The device’s recent commands hold no finished app scan.${scan ? ' Scan now asks it for its apps.' : ''}`,
    };
  } else if (all.length === 0) {
    empty = { title: 'The last scan listed no apps', text: scan ? 'Give the device a moment after an update, then scan again.' : undefined };
  }

  return (
    <Card padding="list" clip aria-labelledby={titleId} className="v2-device-apps">
      <CardHeader
        inset
        spacing={8}
        title="Installed apps"
        titleId={titleId}
        subtitle="The apps with a launcher icon, from the device’s latest scan."
        actions={
          <>
            {latest ? (
              <span className="v2-device-meta">
                {all.length} app{all.length === 1 ? '' : 's'}
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
          columns={COLUMNS}
          rows={shown}
          rowKey={(a) => a.pkg}
          edge={24}
          minWidth={560}
          empty={`No apps match.${systemHidden ? ' System apps are hidden.' : ''}`}
        />
      )}
    </Card>
  );
}
