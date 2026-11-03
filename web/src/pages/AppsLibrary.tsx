import { useMemo, useState, type ReactNode } from 'react';
import {
  deleteApplication,
  getAppConfigLinks,
  getVersions,
  setVersionSha256,
  type Application,
  type ApplicationVersion,
} from '../api/applications';
import { useAuth } from '../auth/AuthContext';
import {
  AppTile,
  Badge,
  Button,
  ButtonLink,
  Callout,
  Card,
  EmptyState,
  Field,
  IconAlert,
  IconApps,
  IconDownload,
  IconWarning,
  Input,
  LabelledSelect,
  Menu,
  Modal,
  Notice,
  SearchInput,
  Table,
  useMediaQuery,
  useToast,
  type SortDir,
} from '../ui';
import { SHA256_HEX, targetVersion } from '../data/apps';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { withCoreAppsParts } from '../ext/core/lists';
import { ORDER } from '../ext/core/order';
import { useListColumns, useRowActionHelpers, useRowMenu, type PlacedColumn } from '../ext/hosts/lists';
import { usePermission } from '../ext/permissions';
import {
  SOURCE_LABEL,
  errorText,
  missingChecksum,
  sourceOf,
  type LibrarySource as SourceKind,
  type LibraryState,
  type Sources,
  type UsedIn,
} from './appsModel';

type SourceFilter = 'all' | SourceKind;

const NARROW = '(max-width: 900px)';

/** The empty library's help: only the ways in and the deploying the role has. */
function emptyLibraryText(sources: Sources, mayUpload: boolean, mayDeploy: boolean): string {
  const custom = mayUpload ? 'Upload an APK or link to one' : 'Link to an APK';
  const ways = sources.fdroid ? `${custom}, or pick an app from F-Droid. ` : sources.custom ? `${custom}. ` : '';
  return ways + (mayDeploy ? 'Apps in the library can be added to a configuration or pushed straight to devices.' : '');
}

/**
 * Library: every deployable app in the library as a table (cards ≤ 900px), with the missing-checksum
 * notice, Deploy, and the "…" menu (SHA-256…, Remove from library).
 */
export function AppsLibrary({
  tabs,
  library,
  sources,
  onDeploy,
  deployingId,
  usedIn,
}: {
  tabs: ReactNode;
  library: LibraryState;
  /** The source tabs shown: the empty library links to these only. */
  sources: Sources;
  /** "Used in" per app (useUsedIn, kept by the page across tab switches). */
  usedIn: UsedIn;
  /** Left out for a role that may not deploy at all (neither edit_devices nor edit_applications): no Deploy. */
  onDeploy?: (app: Application) => void;
  /** The app whose deploy subject is being resolved. */
  deployingId: number | null;
}) {
  const { apps, error, baseUrl, reload, setApps } = library;
  const toast = useToast();
  const { user } = useAuth();
  const narrow = useMediaQuery(NARROW);
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<SourceFilter>('all');
  const [removing, setRemoving] = useState<number | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<Application | null>(null);
  const [shaFor, setShaFor] = useState<{ app: Application; version: ApplicationVersion } | null>(null);
  // Only extension columns sort (core's never did): null until one is picked.
  const [sort, setSort] = useState<{ key: string; dir: SortDir } | null>(null);
  const extensionColumns = useListColumns('apps');
  const listHelpers = useRowActionHelpers(reload);

  const shown = useMemo(() => {
    if (!apps) return [];
    const needle = q.trim().toLowerCase();
    const matching = apps.filter(
      (a) =>
        (filter === 'all' || sourceOf(a, baseUrl).kind === filter) &&
        (!needle || `${a.name} ${a.pkg}`.toLowerCase().includes(needle)),
    );
    const byExtension = sort && extensionColumns.compare(sort.key, sort.dir);
    return byExtension ? [...matching].sort(byExtension) : matching;
  }, [apps, q, filter, baseUrl, sort, extensionColumns]);

  const missing = useMemo(() => (apps ?? []).filter((a) => missingChecksum(a, baseUrl)), [apps, baseUrl]);

  // The row menu's entry and the missing-checksum notice open the same dialog, so both follow this one check.
  const maySetChecksum = usePermission(CORE_ACTION_ACCESS.setAppChecksum);
  const mayUpload = usePermission(CORE_ACTION_ACCESS.uploadFile);
  const canEditSha = (a: Application) => maySetChecksum && !!a.url && !sourceOf(a, baseUrl).hosted && !a.commonApplication;
  const canRemove = (a: Application) => !a.commonApplication || !!user?.superAdmin;

  // An APK MDMesh doesn't host installs only with a known SHA-256: set it on the version the app installs.
  async function editSha256(app: Application) {
    try {
      const version = targetVersion(app, await getVersions(app.id));
      if (!version?.url) {
        toast.push('err', 'No single APK', 'This app has no single-APK version to set a SHA-256 on.');
        return;
      }
      setShaFor({ app, version });
    } catch (e) {
      toast.push('err', 'Could not load the app versions', errorText(e));
    }
  }

  // #66: removing a library record never uninstalls the app; refuse while a configuration still uses it.
  async function askRemove(app: Application) {
    try {
      const links = await getAppConfigLinks(app.id);
      const active = links.filter((l) => l.id != null);
      if (active.length > 0) {
        const names = active.map((l) => l.configurationName || `Configuration ${l.configurationId}`);
        toast.push(
          'err',
          'App is still in use',
          `Remove it from ${names.join(', ')} before deleting the Library record. This does not uninstall the app from devices.`,
        );
        return;
      }
      setConfirmRemove(app);
    } catch (e) {
      toast.push('err', 'Remove failed', errorText(e));
    }
  }

  async function remove(app: Application) {
    setConfirmRemove(null);
    setRemoving(app.id);
    try {
      await deleteApplication(app.id);
      setApps((cur) => cur.filter((a) => a.id !== app.id));
      toast.push('ok', 'Removed from Library', `${app.name} — devices keep the installed app.`);
    } catch (e) {
      const msg = errorText(e);
      toast.push('err', 'Remove failed', /config\.reference\.exists/.test(msg) ? 'A configuration still uses this app.' : msg);
    } finally {
      setRemoving(null);
    }
  }

  // The "…" menu comes from the registry: core's entries (ext/core/lists.tsx) pick these out of the helpers.
  const helpers = withCoreAppsParts(listHelpers, {
    row: {
      checksum: (a) => (canEditSha(a) ? { key: 'sha', label: 'SHA-256 checksum…', onSelect: () => void editSha256(a) } : null),
      remove: (a) =>
        canRemove(a)
          ? {
              key: 'remove',
              label: removing === a.id ? 'Removing…' : 'Remove from library…',
              textValue: 'Remove from library',
              danger: true,
              disabled: removing != null,
              onSelect: () => void askRemove(a),
            }
          : null,
    },
  });
  const rowMenu = useRowMenu('apps', helpers);
  // Without Deploy and any menu entry for the role, the table has no Actions column (as on Devices).
  const hasActions = !!onDeploy || rowMenu.available;

  const actions = (a: Application) => {
    const items = rowMenu.items(a);
    return (
      <div className="v2-apps-lib__actions">
        {onDeploy ? (
          <Button
            size="xs"
            icon={<IconDownload size={14} />}
            onClick={() => onDeploy(a)}
            // Not disabled while it opens: focus must stay on the button so the dialog can return it there.
            aria-busy={deployingId === a.id || undefined}
            aria-label={`Deploy ${a.name}`}
          >
            {deployingId === a.id ? 'Opening…' : 'Deploy'}
          </Button>
        ) : null}
        {items.length > 0 ? <Menu label={`More for ${a.name}`} items={items} /> : <span className="v2-apps-lib__menugap" aria-hidden="true" />}
      </div>
    );
  };

  const appCell = (a: Application) => (
    <div className="v2-apps-lib__app">
      <AppTile name={a.name} size={38} tone={sourceOf(a, baseUrl).kind === 'fdroid' ? 'neutral' : 'ink'} />
      <span className="v2-apps-lib__names">
        <span className="v2-apps-lib__name">{a.name}</span>
        <span className="v2-apps-lib__pkg">{a.pkg}</span>
      </span>
    </div>
  );

  const sourceCell = (a: Application) => {
    const s = sourceOf(a, baseUrl);
    return (
      <div className="v2-apps-lib__src">
        <span>{SOURCE_LABEL[s.kind]}</span>
        {s.note ? <span className="v2-apps-lib__srcnote">{s.note}</span> : null}
      </div>
    );
  };

  const checksumCell = (a: Application) =>
    missingChecksum(a, baseUrl) ? (
      <Badge
        tone="warn"
        size="md"
        title="This APK isn't hosted by MDMesh and has no SHA-256, so devices refuse to install it."
      >
        Missing
      </Badge>
    ) : (
      <Badge
        tone="ok"
        size="md"
        title={
          sourceOf(a, baseUrl).hosted
            ? 'MDMesh hosts this file and works out its SHA-256 itself; devices check the download against it.'
            : 'Devices check the download against the stored SHA-256.'
        }
      >
        ✓ Verified
      </Badge>
    );

  const usedCell = (a: Application) => {
    const u = usedIn[a.id];
    if (u === undefined) return <span className="v2-apps-lib__muted">…</span>;
    if (u === 'error') return <span className="v2-apps-lib__muted">—</span>;
    if (u.length === 0) return <span className="v2-apps-lib__muted">Not used yet</span>;
    const head = u.slice(0, 2).join(', ');
    return (
      <span title={u.join(', ')}>
        {head}
        {u.length > 2 ? <span className="v2-apps-lib__muted"> +{u.length - 2}</span> : null}
      </span>
    );
  };

  // Core's columns are the page's own; extension columns take their places among them (ORDER.appsColumn).
  const C = ORDER.appsColumn;
  const coreColumns: PlacedColumn<Application>[] = [
    { key: 'app', order: C.app, header: 'App', cell: appCell, className: 'v2-apps-lib__colapp' },
    { key: 'version', order: C.version, header: 'Version', mono: true, cell: (a) => a.version || '—' },
    { key: 'source', order: C.source, header: 'Source', cell: sourceCell },
    { key: 'checksum', order: C.checksum, header: 'Checksum', cell: checksumCell },
    { key: 'used', order: C.usedIn, header: 'Used in', cell: usedCell },
  ];
  const columns = extensionColumns.place(
    coreColumns,
    hasActions ? [{ key: 'actions', header: 'Actions', headerHidden: true, shrink: true, cell: actions }] : [],
  );
  const onSort = (key: string) =>
    setSort((cur) => ({ key, dir: cur?.key === key && cur.dir === 'ascending' ? 'descending' : 'ascending' }));

  const first = missing[0];
  const filtering = q.trim() !== '' || filter !== 'all';

  return (
    <>
      <div className="v2-apps-toolbar">
        {tabs}
        <span className="v2-apps-toolbar__spacer" />
        <SearchInput
          className="v2-apps-lib__search"
          aria-label="Search the library"
          placeholder="Search name or package"
          value={q}
          onChange={setQ}
        />
        <LabelledSelect label="Source" value={filter} onChange={(e) => setFilter(e.target.value as SourceFilter)}>
          <option value="all">All</option>
          <option value="uploaded">{SOURCE_LABEL.uploaded}</option>
          <option value="link">{SOURCE_LABEL.link}</option>
          <option value="bundle">{SOURCE_LABEL.bundle}</option>
          <option value="fdroid">{SOURCE_LABEL.fdroid}</option>
        </LabelledSelect>
      </div>

      {error ? (
        <Callout tone="alert" role="alert" icon={<IconAlert size={16} />}>
          <span className="v2-apps-calloutline">
            {error}
            <Button size="sm" onClick={reload}>
              Retry
            </Button>
          </span>
        </Callout>
      ) : null}

      {first ? (
        <Notice
          aria-label="Missing checksum"
          icon={<IconWarning size={16} />}
          className="v2-apps-lib__notice"
          action={
            canEditSha(first) ? (
              <Button size="md" onClick={() => void editSha256(first)}>
                Add checksum
              </Button>
            ) : undefined
          }
        >
          <span>
            {missing.length === 1 ? (
              <>
                <strong>{first.name} has no SHA-256 checksum.</strong> It downloads from a link, and devices only install
                a linked file they can check against its checksum.{maySetChecksum ? ' Add the checksum before deploying it.' : ''}
              </>
            ) : (
              <>
                <strong>
                  {first.name} and {missing.length - 1} other app{missing.length === 2 ? '' : 's'} have no SHA-256
                  checksum.
                </strong>{' '}
                They download from links, and devices only install a linked file they can check against its checksum.
                {maySetChecksum ? ' Add the checksums before deploying them.' : ''}
              </>
            )}
          </span>
        </Notice>
      ) : null}

      {apps === null ? (
        <Card aria-busy="true">
          <p className="v2-apps-loading">Loading apps…</p>
        </Card>
      ) : apps.length === 0 && !error ? (
        <Card>
          <EmptyState
            icon={<IconApps />}
            title="No apps in the library yet"
            action={
              sources.custom || sources.fdroid ? (
                <>
                  {sources.custom ? (
                    <ButtonLink to="?source=custom" size="lg">
                      Add your own app
                    </ButtonLink>
                  ) : null}
                  {sources.fdroid ? (
                    <ButtonLink to="?source=fdroid" size="lg">
                      Browse F-Droid
                    </ButtonLink>
                  ) : null}
                </>
              ) : undefined
            }
          >
            {emptyLibraryText(sources, mayUpload, !!onDeploy) || null}
          </EmptyState>
        </Card>
      ) : apps.length === 0 ? null : narrow ? (
        shown.length === 0 ? (
          <Card>
            <p className="v2-apps-loading">{filtering ? 'No apps match your search.' : 'No apps in the library yet.'}</p>
          </Card>
        ) : (
          <ul className="v2-apps-libcards" aria-label="Library">
            {shown.map((a) => (
              <li key={a.id} className="v2-apps-libcard">
                {appCell(a)}
                <dl className="v2-apps-libcard__facts">
                  <div>
                    <dt>Version</dt>
                    <dd className="v2-mono">{a.version || '—'}</dd>
                  </div>
                  <div>
                    <dt>Source</dt>
                    <dd>{sourceCell(a)}</dd>
                  </div>
                  <div>
                    <dt>Checksum</dt>
                    <dd>{checksumCell(a)}</dd>
                  </div>
                  <div>
                    <dt>Used in</dt>
                    <dd>{usedCell(a)}</dd>
                  </div>
                </dl>
                {hasActions ? actions(a) : null}
              </li>
            ))}
          </ul>
        )
      ) : (
        <Card padding="list" clip aria-label="Library">
          <Table
            aria-label="Library"
            columns={columns}
            rows={shown}
            rowKey={(a) => a.id}
            edge={20}
            minWidth={900}
            empty={filtering ? 'No apps match your search.' : 'No apps in the library yet.'}
            sort={{ key: sort?.key ?? '', dir: sort?.dir ?? 'ascending', onSort }}
            className="v2-apps-lib__table"
          />
        </Card>
      )}

      {shaFor ? (
        <Sha256Dialog
          app={shaFor.app}
          version={shaFor.version}
          onClose={() => setShaFor(null)}
          onSaved={() => {
            const id = shaFor.app.id;
            setShaFor(null);
            setApps((cur) => cur.map((a) => (a.id === id ? { ...a, hasApkHash: true } : a)));
          }}
        />
      ) : null}

      <Modal
        open={confirmRemove !== null}
        onClose={() => setConfirmRemove(null)}
        role="alertdialog"
        size="sm"
        title={confirmRemove ? `Remove ${confirmRemove.name} from the library?` : ''}
        footer={
          <>
            <Button size="lg" onClick={() => setConfirmRemove(null)}>
              Cancel
            </Button>
            <Button size="lg" variant="danger" onClick={() => confirmRemove && void remove(confirmRemove)}>
              Remove from library
            </Button>
          </>
        }
      >
        <p className="v2-apps-modaltext">
          This deletes its Library record and the APK files MDMesh hosts for it, except files another Library entry
          still uses. It does not uninstall the app from enrolled devices.
        </p>
      </Modal>
    </>
  );
}

/** Store the SHA-256 of the version an app installs (classic Sha256Modal). */
function Sha256Dialog({
  app,
  version,
  onClose,
  onSaved,
}: {
  app: Application;
  version: ApplicationVersion;
  onClose: () => void;
  onSaved: () => void;
}) {
  const toast = useToast();
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const trimmed = value.trim();
  const valid = SHA256_HEX.test(trimmed);

  async function save() {
    if (!valid) return;
    setBusy(true);
    try {
      await setVersionSha256(version.id, trimmed.toLowerCase());
      toast.push('ok', 'SHA-256 saved', `${app.name} can now be installed; devices verify the APK against it.`);
      onSaved();
    } catch (e) {
      toast.push('err', 'Could not save the SHA-256', errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onClose={() => !busy && onClose()}
      dismissOnBackdrop={false}
      title={`SHA-256 checksum for ${app.name}`}
      description="Devices check the downloaded file against it before they install."
      footer={
        <>
          <Button size="lg" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button size="lg" variant="primary" type="submit" form="v2-apps-sha" disabled={busy || !valid}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <form
        id="v2-apps-sha"
        className="v2-apps-shaform"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <p className="v2-apps-shaform__url">{version.url}</p>
        <Field
          label="SHA-256 (64 hex characters)"
          error={trimmed !== '' && !valid ? 'Enter the SHA-256 as 64 hex characters (0-9, a-f).' : undefined}
          help={version.apkHash ? 'A checksum is already stored; this one replaces it.' : undefined}
        >
          <Input mono value={value} onChange={(e) => setValue(e.target.value)} autoComplete="off" spellCheck={false} />
        </Field>
      </form>
    </Modal>
  );
}
