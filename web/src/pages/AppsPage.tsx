import { useEffect, useMemo, useRef, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import {
  listApplications,
  getVersions,
  uploadApk,
  uploadBundle,
  commitUpload,
  saveAndroidApplication,
  type Application,
  type BundleUploadResult,
} from '../api/applications';
import { searchFdroid, type FDroidApp } from '../api/fdroid';
import { DeployModal, type DeploySubject } from '../components/DeployModal';

type SourceId = 'library' | 'custom' | 'fdroid' | 'play';

interface Source {
  id: SourceId;
  label: string;
  enabled: boolean;
  tip: string;
}

const SOURCES: Source[] = [
  { id: 'library', label: 'Library', enabled: true, tip: 'Apps already uploaded to this MDMesh server.' },
  { id: 'custom', label: 'Custom APK', enabled: true, tip: 'Deploy any APK by file or URL — including APKMirror / APKPure downloads.' },
  { id: 'fdroid', label: 'F-Droid', enabled: true, tip: 'Search the F-Droid open-source catalogue and deploy straight from f-droid.org.' },
  { id: 'play', label: 'Play Store', enabled: false, tip: 'Download via a Google account (Aurora-style dispenser). Not built yet.' },
];

// APKMirror / APKPure have no usable API and forbid embedding — they're search
// shortcuts into the Custom APK flow (open the site, download, drop the file).
const EXT_SOURCES: { label: string; url: string }[] = [
  { label: 'APKMirror', url: 'https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=' },
  { label: 'APKPure', url: 'https://apkpure.com/search?q=' },
];

async function resolveApp(app: Application): Promise<DeploySubject> {
  let url = app.url;
  let versionCode = app.versionCode;
  let sha256: string | undefined;
  let partsJson: string | undefined = app.parts;
  try {
    const vs = await getVersions(app.id);
    const latest = [...vs]
      .filter((v) => v.url || v.parts) // a split-bundle version has parts but no single url
      .sort((a, b) => (b.versionCode ?? 0) - (a.versionCode ?? 0))[0];
    if (latest) {
      url = latest.url ?? url;
      versionCode = latest.versionCode ?? versionCode;
      sha256 = latest.apkHash || undefined;
      partsJson = latest.parts ?? partsJson;
    }
  } catch {
    /* fall back to the app's own fields */
  }
  let parts: { url: string; sha256?: string }[] | undefined;
  if (partsJson) {
    try {
      parts = (JSON.parse(partsJson) as { url: string; sha256?: string }[]).map((p) => ({ url: p.url, sha256: p.sha256 }));
    } catch {
      /* malformed parts — ignore, fall back to url */
    }
  }
  if (!url && !(parts && parts.length)) throw new Error('This app has no APK to deploy.');
  return { label: app.name, packageName: app.pkg, url: url ?? '', versionCode, sha256, applicationId: app.id, parts };
}

export function AppsPage() {
  const toast = useToast();
  const [source, setSource] = useState<SourceId>('library');
  const [deploy, setDeploy] = useState<DeploySubject | null>(null);

  return (
    <AppShell title="Apps">
      <div className="page-head">
        <h1>Apps</h1>
      </div>

      <span className="seg modes" role="tablist" aria-label="App source" style={{ marginBottom: 16 }}>
        {SOURCES.map((s) => (
          <span className="tip" key={s.id}>
            <button
              className={source === s.id ? 'on' : ''}
              disabled={!s.enabled}
              onClick={() => s.enabled && setSource(s.id)}
              role="tab"
              aria-selected={source === s.id}
              aria-describedby={`src-${s.id}`}
            >
              {s.label}
              {!s.enabled && <span className="src-soon">soon</span>}
            </button>
            <span className="tip-pop" role="tooltip" id={`src-${s.id}`}>
              {s.tip}
            </span>
          </span>
        ))}
      </span>

      {source === 'library' && (
        <LibrarySource onDeploy={(app) => {
          resolveApp(app)
            .then(setDeploy)
            .catch((e) => toast.push('err', 'Cannot deploy', e instanceof Error ? e.message : ''));
        }} />
      )}
      {source === 'custom' && <CustomSource onDeploy={setDeploy} />}
      {source === 'fdroid' && <FDroidSource onDeploy={setDeploy} />}

      {deploy && <DeployModal subject={deploy} onClose={() => setDeploy(null)} />}
    </AppShell>
  );
}

function AppIcon({ name, url }: { name: string; url?: string | null }) {
  const [broken, setBroken] = useState(false);
  if (url && !broken) {
    return (
      <img
        className="app-ic app-ic-img"
        src={url}
        alt=""
        loading="lazy"
        onError={() => setBroken(true)}
      />
    );
  }
  const ch = (name.trim()[0] ?? '?').toUpperCase();
  return <span className="app-ic" aria-hidden="true">{ch}</span>;
}

function LibrarySource({ onDeploy }: { onDeploy: (app: Application) => void }) {
  const [apps, setApps] = useState<Application[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState('');

  useEffect(() => {
    let cancelled = false;
    listApplications()
      .then((list) => !cancelled && setApps(list.filter((a) => (a.type ?? 'app') !== 'web')))
      .catch(() => !cancelled && (setApps([]), setError('Could not load the app library.')));
    return () => {
      cancelled = true;
    };
  }, []);

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!apps) return [];
    if (!needle) return apps;
    return apps.filter((a) => `${a.name} ${a.pkg}`.toLowerCase().includes(needle));
  }, [apps, q]);

  return (
    <>
      <div className="dv-search" style={{ width: 260, marginBottom: 16 }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4-4" />
        </svg>
        <input type="search" placeholder="Search apps" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {apps === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Loading apps…</div></div>
      ) : shown.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">No apps</span>
            {apps.length === 0 ? 'No apps are in the library yet.' : 'No apps match your search.'}
          </div>
        </div>
      ) : (
        <div className="app-grid">
          {shown.map((a) => (
            <div className="app-card" key={a.id}>
              <div className="app-top">
                <AppIcon name={a.name} />
                <div className="app-meta">
                  <div className="app-nm">{a.name}</div>
                  <div className="app-pkg mono">{a.pkg}</div>
                </div>
              </div>
              <div className="app-foot">
                <span className="app-ver">{a.version ? `v${a.version}` : '—'}</span>
                <button className="btn btn-sm btn-primary" onClick={() => onDeploy(a)}>
                  Deploy
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

// Split-APK bundle containers the /bundle endpoint unpacks into installable parts.
const BUNDLE_EXTS = ['.xapk', '.apks', '.apkm', '.zip'];
function isBundleName(n: string): boolean {
  const lower = n.toLowerCase();
  return BUNDLE_EXTS.some((e) => lower.endsWith(e));
}

function CustomSource({ onDeploy }: { onDeploy: (s: DeploySubject) => void }) {
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [pkg, setPkg] = useState('');
  const [vc, setVc] = useState('');
  const [sha, setSha] = useState('');
  const [bundle, setBundle] = useState<BundleUploadResult | null>(null);
  const [savedAppId, setSavedAppId] = useState<number | undefined>(undefined);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [savingToLibrary, setSavingToLibrary] = useState(false);
  const [dropped, setDropped] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function onFile(file: File) {
    setSavedAppId(undefined); // fresh file → drop any prior Library id
    if (isBundleName(file.name)) {
      await onBundle(file);
      return;
    }
    if (!file.name.toLowerCase().endsWith('.apk')) {
      toast.push('err', 'Not an APK', 'Drop an .apk, .xapk, .apks, .apkm or .zip file.');
      return;
    }
    setBundle(null);
    setUploading(true);
    setDropped(file.name);
    try {
      const up = await uploadApk(file);
      const fd = up.fileDetails;
      if (fd) {
        if (fd.name) setName(fd.name);
        if (fd.pkg) setPkg(fd.pkg);
        if (fd.versionCode) setVc(String(fd.versionCode));
      }
      try {
        const committed = await commitUpload(up.serverPath);
        if (committed.url) {
          setUrl(committed.url);
          // Auto-add to the Library so it shows up everywhere (incl. the kiosk app picker),
          // not just this one-off deploy. Non-fatal if it fails.
          if (fd?.pkg) {
            try {
              const saved = await saveAndroidApplication({
                name: fd.name || fd.pkg,
                pkg: fd.pkg,
                url: committed.url,
                version: fd.version,
                versionCode: fd.versionCode,
                type: 'app', // applications.type is NOT NULL — send it explicitly so the save can't fail
              });
              setSavedAppId(saved.id); // enables the deploy dialog's "Add to a configuration" tab
              toast.push('ok', 'APK ready', 'Hosted, added to your Library — review and deploy.');
            } catch {
              // Most likely it's already in the Library (same package+version). Reuse that entry so it
              // stays assignable to a configuration instead of silently dropping it.
              const existing = (await listApplications(fd.pkg).catch(() => [])).find((a) => a.pkg === fd.pkg);
              if (existing?.id) {
                setSavedAppId(existing.id);
                toast.push('ok', 'APK ready', 'Already in your Library — review and deploy.');
              } else {
                toast.push('ok', 'APK ready', 'Hosted — review and deploy. (Could not add to Library.)');
              }
            }
          } else {
            toast.push('ok', 'APK ready', 'Details filled in — review and deploy.');
          }
        } else {
          toast.push('ok', 'Details extracted', 'Couldn’t host the file — paste a URL to deploy.');
        }
      } catch {
        toast.push('ok', 'Details extracted', 'Couldn’t host the file — paste a URL to deploy.');
      }
    } catch (e) {
      toast.push('err', 'Upload failed', e instanceof Error ? e.message : '');
      setDropped(null);
    } finally {
      setUploading(false);
    }
  }

  // Split bundles (.xapk/.apks/.apkm/.zip): the server unpacks + hosts every part.
  // There's no single URL, so we hold the parts and deploy them together.
  async function onBundle(file: File) {
    setUploading(true);
    setDropped(file.name);
    try {
      const b = await uploadBundle(file);
      setBundle(b);
      setUrl('');
      setSha('');
      if (b.name) setName(b.name);
      if (b.packageName) setPkg(b.packageName);
      if (b.versionCode) setVc(String(b.versionCode));
      // Register in the Library so it shows in the config picker + is assignable to a configuration.
      // A single-part bundle (a universal.apk .apks) is an ordinary single-URL app; a multi-part bundle
      // stores its parts as a JSON string on the version.
      try {
        const version = b.version || String(b.versionCode); // applicationVersions.version is NOT NULL
        const saved = await saveAndroidApplication(
          b.parts.length === 1
            ? { name: b.name || b.packageName, pkg: b.packageName, url: b.parts[0].url, version, versionCode: b.versionCode, type: 'app' }
            : {
                name: b.name || b.packageName,
                pkg: b.packageName,
                version,
                versionCode: b.versionCode,
                type: 'app',
                parts: JSON.stringify(b.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name }))),
              },
        );
        setSavedAppId(saved.id);
      } catch {
        // Non-fatal: still deployable via push-now; may already be in the Library.
      }
      toast.push(
        'ok',
        'Bundle ready',
        `${b.parts.length} split${b.parts.length === 1 ? '' : 's'} hosted — added to your Library.`,
      );
    } catch (e) {
      // The server returns a clear message for encrypted .apkm / no-apks bundles.
      toast.push('err', 'Bundle upload failed', e instanceof Error ? e.message : '');
      setDropped(null);
    } finally {
      setUploading(false);
    }
  }

  function submit() {
    if (bundle) {
      onDeploy({
        label: name.trim() || bundle.name || 'Split bundle',
        packageName: (pkg.trim() || bundle.packageName),
        // No single URL for a bundle; parts carry the hosted splits.
        url: '',
        versionCode: vc ? Number(vc) : bundle.versionCode,
        parts: bundle.parts.map((p) => ({ url: p.url, sha256: p.sha256 })),
      });
      return;
    }
    if (!url.trim() || !pkg.trim()) {
      toast.push('err', 'Missing fields', 'APK URL and package name are required.');
      return;
    }
    onDeploy({
      label: name.trim() || 'Custom APK',
      packageName: pkg.trim(),
      url: url.trim(),
      versionCode: vc ? Number(vc) : undefined,
      sha256: sha.trim() || undefined,
      applicationId: savedAppId, // present once the APK is in the Library → enables "Add to a configuration"
    });
  }

  // Upload normally registers the app automatically. This is retained as a retry path for a
  // failed automatic registration or a URL entered by hand; never create a second Library row
  // after the upload has already supplied an id.
  async function saveToLibrary() {
    // A multi-part bundle has no single URL — its parts stand in for one.
    const isMultiPart = !!bundle && bundle.parts.length > 1;
    if (!pkg.trim() || (!url.trim() && !isMultiPart)) {
      toast.push('err', 'Missing fields', 'A package name and an APK URL (or a bundle) are required to add it to your Library.');
      return;
    }
    setSavingToLibrary(true);
    try {
      const saved = await saveAndroidApplication({
        name: name.trim() || pkg.trim(),
        pkg: pkg.trim(),
        url: isMultiPart ? undefined : url.trim(),
        versionCode: vc ? Number(vc) : undefined,
        type: 'app',
        parts: isMultiPart
          ? JSON.stringify(bundle!.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name })))
          : undefined,
      });
      setSavedAppId(saved.id);
      toast.push('ok', 'Added to Library', `${name.trim() || pkg.trim()} is in your Library — now assignable to a configuration.`);
    } catch (e) {
      const existing = (await listApplications(pkg.trim()).catch(() => [])).find((a) => a.pkg === pkg.trim());
      if (existing?.id) {
        setSavedAppId(existing.id);
        toast.push('ok', 'Already in Library', 'This app is already in your Library — you can assign it to a configuration.');
      } else {
        toast.push('err', 'Could not add to Library', e instanceof Error ? e.message : 'The server rejected the save.');
      }
    } finally {
      setSavingToLibrary(false);
    }
  }

  return (
    <div className="panel" style={{ maxWidth: 640 }}>
      <div className="panel-head">
        <h2 className="panel-title">Deploy a custom APK</h2>
        <div className="ext-search">
          <input
            className="input"
            style={{ width: 150, padding: '6px 10px' }}
            placeholder="find app…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {EXT_SOURCES.map((s) => (
            <button
              key={s.label}
              type="button"
              className="btn btn-sm"
              onClick={() => window.open(s.url + encodeURIComponent(query), '_blank', 'noopener')}
            >
              {s.label} ↗
            </button>
          ))}
        </div>
      </div>
      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div
          className={`dropzone ${dragging ? 'over' : ''} ${uploading ? 'busy' : ''}`}
          onClick={() => !uploading && fileRef.current?.click()}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            const f = e.dataTransfer.files?.[0];
            if (f) void onFile(f);
          }}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => e.key === 'Enter' && fileRef.current?.click()}
        >
          <input
            ref={fileRef}
            type="file"
            accept=".apk,.xapk,.apks,.apkm,.zip,application/vnd.android.package-archive"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) void onFile(f);
              e.target.value = '';
            }}
          />
          {uploading ? (
            <span className="dz-main"><span className="spin" /> Analyzing {dropped}…</span>
          ) : dropped ? (
            <span className="dz-main">
              ✓ {dropped}
              <span className="dz-sub">
                {bundle
                  ? `Split bundle · ${bundle.parts.length} part${bundle.parts.length === 1 ? '' : 's'} · drop another to replace`
                  : 'Drop another to replace'}
              </span>
            </span>
          ) : (
            <span className="dz-main">
              Drop an APK or split bundle here, or click to browse
              <span className="dz-sub">
                APK, or .xapk / .apks / .apkm / .zip — auto-fills package, version and hosted URL(s)
              </span>
            </span>
          )}
        </div>
        <p className="note" style={{ margin: 0 }}>
          Point the agent at any reachable APK, or drop a file to upload and host it here.
          Split bundles (<span className="mono">.xapk</span> / <span className="mono">.apks</span> /{' '}
          <span className="mono">.apkm</span> / <span className="mono">.zip</span>) are unpacked and
          installed as one session. Need an app from APKMirror or APKPure? Search above, download it,
          then drop it in — those are unofficial sources, at your own risk. Silent install needs
          Device Owner (the <span className="mono">silentInstall</span> capability).
        </p>
        {bundle ? (
          <label className="field">
            <span className="label">Split bundle</span>
            <input
              className="input mono"
              value={`${bundle.parts.length} part${bundle.parts.length === 1 ? '' : 's'}: ${bundle.parts.map((p) => p.name).join(', ')}`}
              readOnly
            />
          </label>
        ) : (
          <label className="field">
            <span className="label">APK URL *</span>
            <input className="input" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…/app.apk" />
          </label>
        )}
        <label className="field">
          <span className="label">Package name *</span>
          <input className="input mono" value={pkg} onChange={(e) => setPkg(e.target.value)} placeholder="com.example.app" />
        </label>
        <div style={{ display: 'flex', gap: 12 }}>
          <label className="field" style={{ flex: 1 }}>
            <span className="label">Version code</span>
            <input className="input" type="number" value={vc} onChange={(e) => setVc(e.target.value)} placeholder="optional" />
          </label>
          <label className="field" style={{ flex: 1 }}>
            <span className="label">Display name</span>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="optional" />
          </label>
        </div>
        {!bundle && (
          <label className="field">
            <span className="label">SHA-256 (base64)</span>
            <input className="input mono" value={sha} onChange={(e) => setSha(e.target.value)} placeholder="optional — integrity check" />
          </label>
        )}
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <button
            className="btn"
            onClick={() => void saveToLibrary()}
            disabled={uploading || savingToLibrary || !!savedAppId || !pkg.trim() || (!url.trim() && !(bundle && bundle.parts.length > 1))}
          >
            {savedAppId ? '✓ In Library' : savingToLibrary ? 'Adding…' : 'Add to Library'}
          </button>
          <button className="btn btn-primary" onClick={submit}>
            Deploy…
          </button>
          {savedAppId && <span className="note" style={{ margin: 0 }}>Saved — assignable to a configuration.</span>}
        </div>
      </div>
    </div>
  );
}

function FDroidSource({ onDeploy }: { onDeploy: (s: DeploySubject) => void }) {
  const [q, setQ] = useState('');
  const [apps, setApps] = useState<FDroidApp[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    const t = setTimeout(
      () => {
        searchFdroid(q, 60)
          .then((r) => !cancelled && setApps(r))
          .catch(() => {
            if (cancelled) return;
            setApps([]);
            setError('Could not reach the F-Droid catalogue.');
          });
      },
      q ? 350 : 0,
    );
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [q]);

  function deploy(a: FDroidApp) {
    onDeploy({
      label: a.name,
      packageName: a.packageName,
      url: a.apkUrl,
      versionCode: a.versionCode,
      // F-Droid publishes a HEX sha256; the agent's expected format isn't confirmed
      // (the server stores base64), so omit it for now — HTTPS covers transit integrity.
      sha256: undefined,
    });
  }

  return (
    <>
      <div className="dv-search" style={{ width: 320, marginBottom: 16 }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4-4" />
        </svg>
        <input type="search" placeholder="Search F-Droid (e.g. firefox, keepass)" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {apps === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Searching F-Droid…</div></div>
      ) : apps.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">No results</span>
            {error ? 'The server could not load the catalogue.' : 'No apps match your search.'}
          </div>
        </div>
      ) : (
        <div className="app-grid">
          {apps.map((a) => (
            <div className="app-card" key={a.packageName}>
              <div className="app-top">
                <AppIcon name={a.name} url={a.iconUrl} />
                <div className="app-meta">
                  <div className="app-nm">{a.name}</div>
                  <div className="app-pkg mono">{a.packageName}</div>
                </div>
              </div>
              {a.summary && <div className="app-sum">{a.summary}</div>}
              <div className="app-foot">
                <span className="app-ver">{a.versionName ? `v${a.versionName}` : `v${a.versionCode}`}</span>
                <button className="btn btn-sm btn-primary" onClick={() => deploy(a)}>
                  Deploy
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      <p className="note" style={{ marginTop: 14 }}>
        Apps are downloaded by the device directly from f-droid.org. The device must be able to reach it.
      </p>
    </>
  );
}
