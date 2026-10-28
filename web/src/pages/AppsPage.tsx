import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import type { Application } from '../api/applications';
import { IconAndroid, IconLibrary, IconUpload, LinkTabs, PageHeader, useToast } from '../ui';
import { AppsCustom } from './AppsCustom';
import { canDeploy, resolveApp, type DeployModes, type DeploySubject } from '../data/apps';
import { DeployModal } from '../ui/DeployModal';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { usePermission } from '../ext/permissions';
import { paths } from '../routes';
import { AppsFdroid } from './AppsFdroid';
import { AppsLibrary } from './AppsLibrary';
import { errorText, useLibrary, useUsedIn, type Sources } from './appsModel';
import './AppsPage.css';

type SourceId = 'library' | 'custom' | 'fdroid';

/** The tab a link asks for; the Library when it asks for none, or for one the role doesn't see. */
function sourceFrom(param: string | null, sources: Sources): SourceId {
  return (param === 'custom' || param === 'fdroid') && sources[param] ? param : 'library';
}

/**
 * Apps (library, custom APK, F-Droid). Classic: pages/AppsPage.tsx + components/DeployModal.tsx.
 * The source is a deep link (`?source=custom|fdroid`); the library list is loaded once here, because the Library
 * tab shows its count on every source and F-Droid marks apps already in the library.
 */
export function AppsPage() {
  const [params] = useSearchParams();
  const toast = useToast();
  const library = useLibrary();
  // Deploy pushes the app to devices or adds it to a configuration, each behind the permission its endpoint checks
  // (ext/core/access.ts): it shows when the role may do either, with the modes it may use.
  const deployModes: DeployModes = {
    devices: usePermission(CORE_ACTION_ACCESS.editDevices),
    configuration: usePermission(CORE_ACTION_ACCESS.addToConfiguration),
  };
  const mayDeploy = deployModes.devices || deployModes.configuration;
  // Custom APK and F-Droid add to the library or deploy. A role that may do neither doesn't get the tabs: an upload
  // alone is hosted, but reaches neither the library nor a device.
  const canAdd = usePermission(CORE_ACTION_ACCESS.addApp);
  const sources: Sources = { custom: canAdd || mayDeploy, fdroid: canAdd || mayDeploy };
  const source = sourceFrom(params.get('source'), sources);
  const [deploy, setDeploy] = useState<DeploySubject | null>(null);
  const [deployingId, setDeployingId] = useState<number | null>(null);
  const { usedIn, invalidate: invalidateUsedIn } = useUsedIn(library.apps, source === 'library');
  // Bumped when the source tab changes, so a Deploy still resolving on the Library tab doesn't open its dialog
  // over another tab.
  const deploySeq = useRef(0);

  useEffect(() => {
    deploySeq.current++;
    setDeployingId(null);
  }, [source]);

  function deployLibraryApp(app: Application) {
    if (deployingId != null) return; // one at a time
    const seq = ++deploySeq.current;
    setDeployingId(app.id);
    resolveApp(app)
      .then((subject) => {
        if (seq === deploySeq.current) setDeploy(subject);
      })
      .catch((e) => {
        if (seq === deploySeq.current) toast.push('err', 'Cannot deploy', errorText(e));
      })
      .finally(() => {
        if (seq === deploySeq.current) setDeployingId(null);
      });
  }

  // A role refresh (on tab focus) can leave no way to deploy the open subject: the dialog closes.
  const deployOpen = deploy != null && canDeploy(deployModes, deploy);
  useEffect(() => {
    if (deploy && !deployOpen) setDeploy(null);
  }, [deploy, deployOpen]);

  // A subject outside the library can only be pushed to devices.
  function openDeploy(subject: DeploySubject) {
    if (canDeploy(deployModes, subject)) setDeploy(subject);
  }

  function assigned(applicationId: number) {
    invalidateUsedIn(applicationId);
    library.reload(); // an app that was unused is now in use (deletionProhibited)
  }

  const tabs = (
    <LinkTabs
      aria-label="App source"
      className="v2-apps-tabs"
      items={[
        {
          key: 'library',
          to: paths.apps,
          label: 'Library',
          icon: <IconLibrary />,
          count: library.apps ? library.apps.length : undefined,
          active: source === 'library',
        },
        ...(sources.custom
          ? [{ key: 'custom', to: `${paths.apps}?source=custom`, label: 'Custom APK', icon: <IconUpload />, active: source === 'custom' }]
          : []),
        ...(sources.fdroid
          ? [{ key: 'fdroid', to: `${paths.apps}?source=fdroid`, label: 'F-Droid', icon: <IconAndroid />, active: source === 'fdroid' }]
          : []),
      ]}
    />
  );

  return (
    <>
      <PageHeader title="Apps" subtitle="Apps in the library can be added to a configuration or pushed straight to devices." />

      {source === 'library' ? (
        <AppsLibrary
          tabs={tabs}
          library={library}
          sources={sources}
          onDeploy={mayDeploy ? deployLibraryApp : undefined}
          deployingId={deployingId}
          usedIn={usedIn}
        />
      ) : source === 'custom' ? (
        <AppsCustom
          tabs={tabs}
          baseUrl={library.baseUrl}
          onDeploy={mayDeploy ? openDeploy : undefined}
          onLibraryChanged={library.reload}
        />
      ) : (
        <AppsFdroid
          tabs={tabs}
          library={library.apps}
          deployModes={deployModes}
          onDeploy={mayDeploy ? openDeploy : undefined}
          onLibraryChanged={library.reload}
        />
      )}

      {deploy && deployOpen ? <DeployModal subject={deploy} modes={deployModes} onClose={() => setDeploy(null)} onAssigned={assigned} /> : null}
    </>
  );
}
