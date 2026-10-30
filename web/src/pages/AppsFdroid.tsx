import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { listApplications, saveAndroidApplication, type Application } from '../api/applications';
import { searchFdroid, type FDroidApp } from '../api/fdroid';
import {
  AppTile,
  Button,
  Callout,
  Card,
  EmptyState,
  IconAlert,
  IconCheck,
  IconInfo,
  IconSearch,
  IconWarning,
  SearchInput,
  ShowMore,
  useToast,
} from '../ui';
import { canDeploy, type DeployModes, type DeploySubject } from '../data/apps';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { usePermission } from '../ext/permissions';
import { errorText } from './appsModel';

/** The server caps a search at 100 results; one request fetches them all, Show more pages through them. */
const FETCH_LIMIT = 100;
const PAGE = 12;

/**
 * F-Droid: search the catalogue the server caches, add an app to the library or deploy it straight from
 * f-droid.org. Apps without a SHA-256 in the index can't be deployed (devices could not verify them).
 */
export function AppsFdroid({
  tabs,
  library,
  deployModes,
  onDeploy,
  onLibraryChanged,
}: {
  tabs: ReactNode;
  /** Deployable library apps (null while loading): drives "In library". */
  library: Application[] | null;
  deployModes: DeployModes;
  /** Left out for a role that may not deploy at all; an app is offered Deploy when the role has a mode for it. */
  onDeploy?: (s: DeploySubject) => void;
  onLibraryChanged: () => void;
}) {
  const toast = useToast();
  const [q, setQ] = useState('');
  const [apps, setApps] = useState<FDroidApp[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [shown, setShown] = useState(PAGE);
  const [tick, setTick] = useState(0);
  const [adding, setAdding] = useState<Set<string>>(() => new Set());
  /** Packages added here, until the library list catches up. */
  const [added, setAdded] = useState<Map<string, number>>(() => new Map());
  // Adding to the library needs edit_applications (ext/core/access.ts).
  const canAdd = usePermission(CORE_ACTION_ACCESS.addApp);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    const t = setTimeout(
      () => {
        setSearching(true);
        searchFdroid(q, FETCH_LIMIT)
          .then((r) => {
            if (cancelled) return;
            setApps(r);
            setShown(PAGE);
          })
          .catch(() => {
            if (cancelled) return;
            setApps([]);
            setError('Could not reach the F-Droid catalogue.');
          })
          .finally(() => !cancelled && setSearching(false));
      },
      q ? 350 : 0,
    );
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [q, tick]);

  const byPkg = useMemo(() => {
    const m = new Map<string, Application>();
    for (const a of library ?? []) if (!m.has(a.pkg)) m.set(a.pkg, a);
    return m;
  }, [library]);

  /** The library id to deploy with: only when the library entry is this exact catalogue version. */
  function libraryIdFor(a: FDroidApp): number | undefined {
    const lib = byPkg.get(a.packageName);
    if (lib) return lib.versionCode === a.versionCode ? lib.id : undefined;
    return added.get(a.packageName);
  }

  /** Pushing works for any catalogue app; adding to a configuration only for one already in the library. */
  const deployable = (a: FDroidApp) => !!onDeploy && canDeploy(deployModes, { applicationId: libraryIdFor(a) });

  function deploy(a: FDroidApp) {
    if (!a.sha256 || !onDeploy) return; // no checksum, or the role may not: the action isn't offered
    onDeploy({
      label: a.name,
      packageName: a.packageName,
      url: a.apkUrl,
      versionCode: a.versionCode,
      // The hex sha256 from the F-Droid index pins the APK the admin picked.
      sha256: a.sha256,
      applicationId: libraryIdFor(a),
    });
  }

  // Same as the Custom APK link path: a library entry pointing at f-droid.org, pinned by the index's SHA-256.
  async function addToLibrary(a: FDroidApp) {
    if (!a.sha256) return;
    setAdding((s) => new Set(s).add(a.packageName));
    try {
      const saved = await saveAndroidApplication({
        name: a.name || a.packageName,
        pkg: a.packageName,
        url: a.apkUrl,
        sha256: a.sha256.toLowerCase(),
        version: a.versionName || String(a.versionCode), // applicationVersions.version is NOT NULL
        versionCode: a.versionCode,
        type: 'app',
      });
      setAdded((m) => new Map(m).set(a.packageName, saved.id));
      toast.push('ok', 'Added to Library', `${a.name} is in your Library — now assignable to a configuration.`);
      onLibraryChanged();
    } catch (e) {
      const existing = (await listApplications(a.packageName).catch(() => [])).find((x) => x.pkg === a.packageName);
      if (existing?.id) {
        toast.push('ok', 'Already in Library', `${a.name} is already in your Library.`);
        onLibraryChanged();
      } else {
        toast.push('err', 'Could not add to Library', errorText(e) || 'The server rejected the save.');
      }
    } finally {
      setAdding((s) => {
        const n = new Set(s);
        n.delete(a.packageName);
        return n;
      });
    }
  }

  const visible = apps ? apps.slice(0, shown) : [];
  const capped = !!apps && apps.length >= FETCH_LIMIT && shown >= apps.length;

  return (
    <>
      <div className="v2-apps-toolbar">
        {tabs}
        <span className="v2-apps-toolbar__spacer" />
        <SearchInput
          className="v2-apps-fd__search"
          aria-label="Search F-Droid"
          placeholder="Search the F-Droid catalogue"
          value={q}
          onChange={setQ}
        />
      </div>

      <p className="v2-apps-fd__info">
        <IconInfo size={15} />
        <span>
          {q.trim() ? 'Matches from the f-droid.org catalogue.' : 'The f-droid.org catalogue, A to Z.'} Devices download the app
          straight from f-droid.org, and MDMesh checks it against the catalogue’s checksum.
        </span>
      </p>

      {error ? (
        <Callout tone="alert" role="alert" icon={<IconAlert size={16} />}>
          <span className="v2-apps-calloutline">
            {error}
            <Button size="sm" onClick={() => setTick((t) => t + 1)}>
              Retry
            </Button>
          </span>
        </Callout>
      ) : null}

      {apps === null ? (
        <Card aria-busy="true">
          <p className="v2-apps-loading">Searching F-Droid…</p>
        </Card>
      ) : apps.length === 0 ? (
        <Card>
          <EmptyState icon={<IconSearch />} title="No results">
            {error ? 'The server could not load the catalogue.' : 'No apps match your search.'}
          </EmptyState>
        </Card>
      ) : (
        <>
          <ul className="v2-apps-fd__grid" aria-label="F-Droid apps" aria-busy={searching || undefined}>
            {visible.map((a) => {
              const inLib = byPkg.has(a.packageName) || added.has(a.packageName);
              const busy = adding.has(a.packageName);
              return (
                <li key={a.packageName} className="v2-apps-fd__card">
                  <div className="v2-apps-fd__body">
                    <AppTile name={a.name} size={44} src={a.iconUrl ?? undefined} />
                    <div className="v2-apps-fd__text">
                      <h3 className="v2-apps-fd__name">{a.name}</h3>
                      <span className="v2-apps-fd__pkg" title={a.packageName}>
                        {a.packageName}
                      </span>
                      {a.summary ? <p className="v2-apps-fd__summary">{a.summary}</p> : null}
                    </div>
                  </div>
                  <div className="v2-apps-fd__foot">
                    <span className="v2-apps-fd__ver">{a.versionName || a.versionCode}</span>
                    {!a.sha256 ? (
                      <>
                        {inLib ? (
                          <span className="v2-apps-fd__inlib">
                            <IconCheck size={13} />
                            In library
                          </span>
                        ) : null}
                        <span className="v2-apps-fd__blocked">
                          <IconWarning size={14} />
                          No checksum in the catalogue, can’t deploy
                        </span>
                      </>
                    ) : inLib ? (
                      <>
                        <span className="v2-apps-fd__inlib">
                          <IconCheck size={13} />
                          In library
                        </span>
                        {deployable(a) ? (
                          <Button size="xs" onClick={() => deploy(a)} aria-label={`Deploy ${a.name}`}>
                            Deploy
                          </Button>
                        ) : null}
                      </>
                    ) : (
                      <>
                        {canAdd ? (
                          <Button size="xs" disabled={busy} onClick={() => void addToLibrary(a)} aria-label={`Add ${a.name} to library`}>
                            {busy ? 'Adding…' : 'Add to library'}
                          </Button>
                        ) : null}
                        {deployable(a) ? (
                          <Button size="xs" variant="primary" onClick={() => deploy(a)} aria-label={`Deploy ${a.name}`}>
                            Deploy
                          </Button>
                        ) : null}
                      </>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
          {shown < apps.length ? (
            <ShowMore onClick={() => setShown((n) => n + PAGE)} />
          ) : capped ? (
            <p className="v2-apps-fd__capped">Showing the first {FETCH_LIMIT} matches. Search to narrow them down.</p>
          ) : null}
        </>
      )}
    </>
  );
}
