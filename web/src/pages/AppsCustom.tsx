import { useId, useState, type ReactNode } from 'react';
import {
  commitUpload,
  listApplications,
  saveAndroidApplication,
  uploadApk,
  uploadBundle,
  type BundleUploadResult,
} from '../api/applications';
import {
  Badge,
  Button,
  ButtonAnchor,
  Callout,
  Card,
  CardHeader,
  Field,
  FileDropZone,
  FileReadPanel,
  IconCheck,
  IconDownload,
  IconPlus,
  IconWarning,
  Input,
  Segmented,
  Stepper,
  useToast,
} from '../ui';
import { EXT_SOURCES, SHA256_HEX, isBundleName, isHostedHere, type DeploySubject } from '../data/apps';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { usePermission } from '../ext/permissions';
import { FILE_ACCEPT, FILE_EXTS, errorText, formatBytes } from './appsModel';

type Mode = 'upload' | 'link';

interface FileInfo {
  name: string;
  size: number;
  /** Set once the server has read the file. */
  read: boolean;
  /** Why the file could not be used (alert panel). */
  error?: string;
}

/**
 * Custom APK: upload a file (APK or split bundle) or use a link, check the details, add it to the library
 * and/or deploy it. Behaviour is the classic CustomSource's: an upload is hosted and registered in the library at
 * once; "Add to library" is the manual path for links and the retry when that registration failed.
 */
export function AppsCustom({
  tabs,
  baseUrl,
  onDeploy,
  onLibraryChanged,
}: {
  tabs: ReactNode;
  baseUrl: string | null;
  /** Left out for a role that may not deploy at all (neither push to devices nor add to a configuration). */
  onDeploy?: (s: DeploySubject) => void;
  /** A library entry was created or found (refresh the list and the tab count). */
  onLibraryChanged: () => void;
}) {
  const toast = useToast();
  const headId = useId();
  // Uploading needs edit_files and adding to the library edit_applications (ext/core/access.ts). Without edit_files
  // only the link is offered; without edit_applications, an upload is hosted but not added.
  const canUpload = usePermission(CORE_ACTION_ACCESS.uploadFile);
  const canAdd = usePermission(CORE_ACTION_ACCESS.addApp);
  const [mode, setMode] = useState<Mode>(canUpload ? 'upload' : 'link');
  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [pkg, setPkg] = useState('');
  const [pkgFromFile, setPkgFromFile] = useState(false);
  const [version, setVersion] = useState('');
  const [vc, setVc] = useState('');
  const [sha, setSha] = useState('');
  const [shaTouched, setShaTouched] = useState(false);
  // The URL of the file uploaded here: the server hashes it itself. Any other URL needs its SHA-256 entered, since
  // the agent only installs an APK whose hash it can verify.
  const [hostedUrl, setHostedUrl] = useState('');
  const [bundle, setBundle] = useState<BundleUploadResult | null>(null);
  const [savedAppId, setSavedAppId] = useState<number | undefined>(undefined);
  const [uploading, setUploading] = useState(false);
  const [savingToLibrary, setSavingToLibrary] = useState(false);
  const [file, setFile] = useState<FileInfo | null>(null);

  const isMultiPart = !!bundle && bundle.parts.length > 1;
  /** The single URL this app installs from: the hosted upload, a single-part bundle's part, or the typed link. */
  const effectiveUrl = mode === 'link' ? url.trim() : bundle ? (bundle.parts.length === 1 ? bundle.parts[0].url : '') : hostedUrl;
  // A URL this console didn't just upload, and not one MDMesh hosts: the admin must supply the APK's SHA-256.
  const externalUrl = mode === 'link' && effectiveUrl !== '' && effectiveUrl !== hostedUrl && !isHostedHere(effectiveUrl, baseUrl);
  const shaTrim = sha.trim();
  const shaInvalid = shaTrim !== '' && !SHA256_HEX.test(shaTrim);
  const shaMissing = externalUrl && shaTrim === '';
  const fileRead = !!file?.read;
  const hasSource = mode === 'upload' ? fileRead : url.trim() !== '';
  const hasInstallable = isMultiPart || effectiveUrl !== '';
  const ready = hasInstallable && pkg.trim() !== '' && !shaInvalid && !shaMissing;
  const step = savedAppId ? 3 : !hasSource ? 0 : ready ? 2 : 1;
  const hostFailed = mode === 'upload' && fileRead && !bundle && !hostedUrl;

  function resetFileState() {
    setSavedAppId(undefined);
    setHostedUrl('');
    setBundle(null);
    setFile(null);
    setPkgFromFile(false);
  }

  function switchMode(next: Mode) {
    if (next === mode) return;
    if (next === 'link') {
      // Keep what the file told us; a hosted single APK keeps its link (and its library entry).
      // A file MDMesh read but couldn't host also keeps its details: the admin links to the same APK instead.
      const keepLink = !bundle && hostedUrl !== '';
      setUrl(keepLink ? hostedUrl : '');
      if (!keepLink) {
        setSavedAppId(undefined);
        setVersion('');
        if (!hostFailed) {
          setName('');
          setPkg('');
          setVc('');
        }
      }
      setBundle(null);
      setFile(null);
      setPkgFromFile(false);
    } else {
      resetFileState();
      setName('');
      setPkg('');
      setVc('');
      setUrl('');
      setSha('');
      setShaTouched(false);
      setVersion('');
    }
    setMode(next);
  }

  async function onFile(f: File) {
    setSavedAppId(undefined); // fresh file: drop any prior Library id
    setHostedUrl('');
    setSha('');
    setShaTouched(false);
    if (isBundleName(f.name)) {
      await onBundle(f);
      return;
    }
    if (!f.name.toLowerCase().endsWith('.apk')) {
      toast.push('err', 'Not an APK', 'Drop an .apk, .xapk, .apks, .apkm or .zip file.');
      setBundle(null);
      setFile({ name: f.name, size: f.size, read: false, error: 'Not an Android app. Choose an .apk or a split bundle.' });
      return;
    }
    setBundle(null);
    setUploading(true);
    setFile({ name: f.name, size: f.size, read: false });
    try {
      const up = await uploadApk(f);
      const fd = up.fileDetails;
      setVersion(fd?.version ?? ''); // per file: never carry the previous file's version over (#69)
      if (fd) {
        if (fd.name) setName(fd.name);
        if (fd.pkg) setPkg(fd.pkg);
        setPkgFromFile(!!fd.pkg);
        setVc(fd.versionCode ? String(fd.versionCode) : '');
      }
      setFile({ name: f.name, size: f.size, read: true });
      try {
        const committed = await commitUpload(up.serverPath);
        if (committed.url) {
          setUrl(committed.url);
          setHostedUrl(committed.url);
          // Add to the Library at once so it shows up everywhere (incl. the kiosk app picker), not just this one-off
          // deploy. Non-fatal if it fails.
          if (fd?.pkg && canAdd) {
            try {
              const saved = await saveAndroidApplication({
                name: fd.name || fd.pkg,
                pkg: fd.pkg,
                url: committed.url,
                version: fd.version,
                versionCode: fd.versionCode,
                type: 'app', // applications.type is NOT NULL: send it explicitly so the save can't fail
              });
              setSavedAppId(saved.id); // enables the deploy dialog's "Add to a configuration"
              onLibraryChanged();
              toast.push('ok', 'APK ready', 'Hosted, added to your Library — review and deploy.');
            } catch {
              // Most likely it's already in the Library (same package+version). Reuse that entry so it stays
              // assignable to a configuration instead of silently dropping it (#60: never a second row).
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
      toast.push('err', 'Upload failed', errorText(e));
      setFile(null);
    } finally {
      setUploading(false);
    }
  }

  // Split bundles (.xapk/.apks/.apkm/.zip): the server unpacks and hosts every part. There's no single URL, so we
  // hold the parts and deploy them together.
  async function onBundle(f: File) {
    setUploading(true);
    setFile({ name: f.name, size: f.size, read: false });
    try {
      const b = await uploadBundle(f);
      setBundle(b);
      setUrl('');
      setSha('');
      if (b.name) setName(b.name);
      if (b.packageName) setPkg(b.packageName);
      setPkgFromFile(!!b.packageName);
      const ver = b.version || String(b.versionCode); // applicationVersions.version is NOT NULL
      setVersion(ver);
      setVc(b.versionCode ? String(b.versionCode) : '');
      setFile({ name: f.name, size: f.size, read: true });
      // Register in the Library so it shows in the config picker and is assignable to a configuration. A
      // single-part bundle (a universal.apk .apks) is an ordinary single-URL app; a multi-part bundle stores its
      // parts as a JSON string on the version.
      if (canAdd) {
        try {
          const saved = await saveAndroidApplication(
            b.parts.length === 1
              ? { name: b.name || b.packageName, pkg: b.packageName, url: b.parts[0].url, version: ver, versionCode: b.versionCode, type: 'app' }
              : {
                  name: b.name || b.packageName,
                  pkg: b.packageName,
                  version: ver,
                  versionCode: b.versionCode,
                  type: 'app',
                  parts: JSON.stringify(b.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name }))),
                },
          );
          setSavedAppId(saved.id);
          onLibraryChanged();
        } catch {
          // Non-fatal: still deployable via push-now; may already be in the Library.
        }
      }
      const splits = `${b.parts.length} split${b.parts.length === 1 ? '' : 's'} hosted`;
      toast.push('ok', 'Bundle ready', canAdd ? `${splits} — added to your Library.` : `${splits}.`);
    } catch (e) {
      // The server returns a clear message for encrypted .apkm / no-apks bundles.
      toast.push('err', 'Bundle upload failed', errorText(e));
      setFile(null);
    } finally {
      setUploading(false);
    }
  }

  /** The SHA-256 rule; returns the hash to send (undefined for a hosted file), or null after a toast. */
  function checkedSha(): string | undefined | null {
    setShaTouched(true);
    if (shaInvalid) {
      toast.push('err', 'Invalid SHA-256', 'Enter the APK’s SHA-256 as 64 hex characters.');
      return null;
    }
    if (shaMissing) {
      toast.push('err', 'SHA-256 required', 'This APK isn’t hosted by MDMesh: enter its SHA-256 so devices can verify it, or upload the file.');
      return null;
    }
    return shaTrim ? shaTrim.toLowerCase() : undefined;
  }

  /** The deploy subject for the form, or null after a toast. */
  function subjectFor(applicationId: number | undefined, checked: string | undefined): DeploySubject {
    if (bundle) {
      return {
        label: name.trim() || bundle.name || 'Split bundle',
        packageName: pkg.trim() || bundle.packageName,
        url: '', // no single URL for a bundle; parts carry the hosted splits
        versionCode: vc ? Number(vc) : bundle.versionCode,
        parts: bundle.parts.map((p) => ({ url: p.url, sha256: p.sha256 })),
        applicationId,
      };
    }
    return {
      label: name.trim() || 'Custom APK',
      packageName: pkg.trim(),
      url: effectiveUrl,
      versionCode: vc ? Number(vc) : undefined,
      sha256: checked,
      applicationId, // present once the APK is in the Library: enables "Add to a configuration"
    };
  }

  /** Validate for deploy; returns the checked hash (undefined = none needed) or null after a toast. */
  function validateDeploy(): { sha: string | undefined } | null {
    if (bundle) return { sha: undefined };
    if (!effectiveUrl || !pkg.trim()) {
      toast.push('err', 'Missing fields', 'APK URL and package name are required.');
      return null;
    }
    const checked = checkedSha();
    if (checked === null) return null;
    return { sha: checked };
  }

  // The manual path for links, and the retry for a failed automatic registration. Never creates a second Library
  // row after the upload has already supplied an id. Returns the library id, or undefined.
  async function saveToLibrary(): Promise<number | undefined> {
    if (savedAppId) return savedAppId;
    if (!pkg.trim() || (!effectiveUrl && !isMultiPart)) {
      toast.push('err', 'Missing fields', 'A package name and an APK URL (or a bundle) are required to add it to your Library.');
      return undefined;
    }
    const checked = isMultiPart ? undefined : checkedSha();
    if (checked === null) return undefined;
    setSavingToLibrary(true);
    try {
      const saved = await saveAndroidApplication({
        name: name.trim() || pkg.trim(),
        pkg: pkg.trim(),
        url: isMultiPart ? undefined : effectiveUrl,
        sha256: checked,
        version: version.trim() || undefined,
        versionCode: vc ? Number(vc) : undefined,
        type: 'app',
        parts:
          isMultiPart && bundle
            ? JSON.stringify(bundle.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name })))
            : undefined,
      });
      setSavedAppId(saved.id);
      onLibraryChanged();
      toast.push('ok', 'Added to Library', `${name.trim() || pkg.trim()} is in your Library — now assignable to a configuration.`);
      return saved.id;
    } catch (e) {
      const existing = (await listApplications(pkg.trim()).catch(() => [])).find((a) => a.pkg === pkg.trim());
      if (existing?.id) {
        setSavedAppId(existing.id);
        toast.push('ok', 'Already in Library', 'This app is already in your Library — you can assign it to a configuration.');
        return existing.id;
      }
      toast.push('err', 'Could not add to Library', e instanceof Error ? e.message : 'The server rejected the save.');
      return undefined;
    } finally {
      setSavingToLibrary(false);
    }
  }

  async function addAndDeploy() {
    const v = validateDeploy();
    if (!v) return;
    // Not in the library yet: add it first, if the role may. Without it, the dialog still opens for pushing to devices.
    const id = savedAppId ?? (canAdd ? await saveToLibrary() : undefined);
    onDeploy?.(subjectFor(id, v.sha));
  }

  const chooseAnother = (choose: () => void) => (
    <Button size="md" disabled={uploading} onClick={choose}>
      Choose another file
    </Button>
  );

  const fileDetail = file
    ? [
        'Read successfully',
        bundle ? `split bundle with ${bundle.parts.length} part${bundle.parts.length === 1 ? '' : 's'}` : 'APK',
        formatBytes(file.size),
      ]
        .filter(Boolean)
        .join(' · ')
    : '';

  const versionText = [version, vc ? `(code ${vc})` : ''].filter(Boolean).join(' ') || '—';
  const saved = !!savedAppId;
  const addDisabled = uploading || savingToLibrary || saved || !pkg.trim() || !hasInstallable;
  const deployDisabled = uploading || savingToLibrary || !hasSource;

  return (
    <>
      <div className="v2-apps-toolbar">{tabs}</div>

      <div className="v2-apps-custom">
        <Card as="section" padding="roomy" gap={18} className="v2-apps-custom__main" aria-labelledby={headId}>
          <CardHeader
            titleId={headId}
            title="Add your own app"
            size={18}
            className="v2-apps-custom__head"
            actions={<Stepper steps={['File', 'Check details', 'Add']} current={step} />}
          />

          {canUpload ? (
            <Segmented<Mode>
              aria-label="Where the app comes from"
              className="v2-apps-custom__mode"
              value={mode}
              onChange={switchMode}
              disabled={uploading || savingToLibrary}
              options={[
                { value: 'upload', label: 'Upload a file' },
                { value: 'link', label: 'Use a link' },
              ]}
            />
          ) : null}

          {mode === 'upload' ? (
            <>
              <FileDropZone
                accept={FILE_ACCEPT}
                onFile={(f) => void onFile(f)}
                busy={uploading}
                busyLabel={`Reading ${file?.name ?? 'the file'}…`}
                title="Drop an APK or a split bundle here, or choose one"
                hint="MDMesh reads the package name, version and checksum from the file."
                buttonLabel="Choose a file"
                result={
                  file
                    ? (choose) =>
                        file.error ? (
                          <FileReadPanel tone="alert" name={file.name} detail={file.error} action={chooseAnother(choose)} />
                        ) : (
                          <FileReadPanel name={file.name} detail={fileDetail} action={chooseAnother(choose)} />
                        )
                    : undefined
                }
              />
              {hostFailed ? (
                <Callout tone="warn" icon={<IconWarning size={16} />}>
                  <span className="v2-apps-calloutline">
                    MDMesh read the file but couldn’t host it. Use a link to the APK to deploy it.
                    <Button size="sm" onClick={() => switchMode('link')}>
                      Use a link
                    </Button>
                  </span>
                </Callout>
              ) : null}
            </>
          ) : null}

          {mode === 'link' || fileRead ? (
            <div className="v2-apps-custom__fields">
              {mode === 'link' ? (
                <Field
                  className="v2-apps-custom__wide"
                  label="Link to the APK"
                  help="Devices download the file from this address, so it must be reachable from them."
                >
                  <Input
                    type="url"
                    value={url}
                    placeholder="https://…/app.apk"
                    onChange={(e) => {
                      setUrl(e.target.value);
                      setVersion(''); // a hand-typed URL isn't the analysed file any more (#69)
                      if (savedAppId && e.target.value.trim() !== hostedUrl) setSavedAppId(undefined);
                    }}
                  />
                </Field>
              ) : null}
              <Field label="Display name" help={saved ? 'In the library under this name.' : 'What admins see in MDMesh.'}>
                <Input value={name} readOnly={saved} onChange={(e) => setName(e.target.value)} placeholder={pkg || 'Optional'} />
              </Field>
              <Field label="Package name" help={pkgFromFile ? 'Read from the file.' : 'Required, e.g. com.example.app.'}>
                <Input
                  mono
                  value={pkg}
                  readOnly={pkgFromFile || saved}
                  onChange={(e) => setPkg(e.target.value)}
                  placeholder="com.example.app"
                  spellCheck={false}
                />
              </Field>
              {mode === 'upload' ? (
                <Field label="Version" help="Read from the file.">
                  <Input mono value={versionText} readOnly />
                </Field>
              ) : (
                <Field label="Version code" help="Optional. The versionCode from the app’s manifest.">
                  <Input mono type="number" inputMode="numeric" value={vc} readOnly={saved} onChange={(e) => setVc(e.target.value)} />
                </Field>
              )}
              {mode === 'upload' ? (
                <Field
                  label="SHA-256 checksum"
                  helpTone="ok"
                  help={bundle ? '✓ Worked out for each part of the bundle.' : '✓ Worked out by MDMesh, which hosts the file.'}
                >
                  <Input
                    value={bundle ? `${bundle.parts.length} part${bundle.parts.length === 1 ? '' : 's'}, one checksum each` : 'Worked out by MDMesh'}
                    readOnly
                  />
                </Field>
              ) : (
                <Field
                  label="SHA-256 checksum"
                  help={
                    externalUrl
                      ? 'Required: devices check the download against it. 64 hex characters.'
                      : effectiveUrl
                        ? 'Not needed: MDMesh hosts this file and works it out itself.'
                        : '64 hex characters. Devices check the download against it.'
                  }
                  error={
                    shaTouched && shaInvalid
                      ? 'Enter the SHA-256 as 64 hex characters (0-9, a-f).'
                      : shaTouched && shaMissing
                        ? 'Enter the checksum, or upload the file instead.'
                        : undefined
                  }
                >
                  <Input
                    mono
                    value={sha}
                    readOnly={saved}
                    onChange={(e) => setSha(e.target.value)}
                    onBlur={() => shaTrim !== '' && setShaTouched(true)}
                    autoComplete="off"
                    spellCheck={false}
                  />
                </Field>
              )}
            </div>
          ) : null}

          <div className="v2-apps-custom__foot">
            {canAdd ? (
              <Button
                size="hero"
                variant="primary"
                icon={saved ? <IconCheck size={15} /> : <IconPlus size={15} />}
                disabled={addDisabled}
                onClick={() => void saveToLibrary()}
              >
                {saved ? 'In library' : savingToLibrary ? 'Adding…' : 'Add to library'}
              </Button>
            ) : null}
            {onDeploy ? (
              <Button size="hero" icon={<IconDownload size={15} />} disabled={deployDisabled} onClick={() => void addAndDeploy()}>
                {saved || !canAdd ? 'Deploy…' : 'Add and deploy…'}
              </Button>
            ) : null}
            <span className="v2-apps-custom__hint">
              {saved
                ? 'It’s in the library. Nothing reaches devices until it is deployed or added to a configuration.'
                : 'Nothing reaches devices until it is deployed or added to a configuration.'}
            </span>
          </div>
        </Card>

        <aside className="v2-apps-custom__side" aria-label="Help">
          <Card padding="side" gap={10}>
            <h2 className="v2-apps-sidecard__title">Files MDMesh can read</h2>
            <div className="v2-apps-sidecard__exts">
              {FILE_EXTS.map((e) => (
                <Badge key={e} tone="neutral" size="md" square mono>
                  {e}
                </Badge>
              ))}
            </div>
            <p className="v2-apps-sidecard__text">Split bundles are unpacked on the server. Devices install every part together.</p>
          </Card>
          <Card padding="side" gap={10}>
            <h2 className="v2-apps-sidecard__title">Using a link</h2>
            <p className="v2-apps-sidecard__text">
              Devices download the file themselves. You’ll need its SHA-256 checksum so they can check the download wasn’t
              changed.
            </p>
          </Card>
          <Card padding="side" gap={10}>
            <h2 className="v2-apps-sidecard__title">Need an APK?</h2>
            <p className="v2-apps-sidecard__text">
              Search a mirror for the package name. Only use files whose signature matches the developer’s.
            </p>
            <Input
              size="sm"
              aria-label="Search a mirror for"
              placeholder={pkg || 'App or package name'}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <div className="v2-apps-sidecard__links">
              {EXT_SOURCES.map((s) => (
                <ButtonAnchor
                  key={s.label}
                  size="sm"
                  href={s.url + encodeURIComponent(query.trim() || pkg.trim())}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {s.label} ↗
                </ButtonAnchor>
              ))}
            </div>
          </Card>
        </aside>
      </div>
    </>
  );
}
