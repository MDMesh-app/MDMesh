import { useId, useMemo, useState } from 'react';
import type { CommandHistoryItem } from '../api/commands';
import type { AppInfo } from '../api/deviceApps';
import {
  Button,
  Card,
  CardHeader,
  Checkbox,
  EmptyState,
  IconApps,
  IconDownload,
  RelativeTime,
  SearchInput,
  Table,
  type Column,
} from '../ui';
import { appsCsv, byLabel, filterApps, latestScan } from '../data/deviceApps';
import { DASH } from './deviceDetailFormat';

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
 * agent reports them. Export CSV writes every app, system ones included, for app audits.
 */
export function AppsTab({
  number,
  history,
  loaded,
}: {
  number: string;
  history: readonly CommandHistoryItem[];
  loaded: boolean;
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
      text: `The device’s recent commands hold no finished app scan.`,
    };
  } else if (all.length === 0) {
    empty = { title: 'The last scan listed no apps' };
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
          </>
        }
      />
      {all.length ? (
        <div className="v2-device-apps__body">
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
