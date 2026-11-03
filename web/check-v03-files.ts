// The build fails while a v0.3 console file that 0.4.0 removed or replaced is still in the tree. A fork that merges
// 0.4.0 can keep one without noticing: git leaves a file the fork changed and 0.4.0 deleted ("deleted by them"), and
// nothing imports it any more. A kept styles.css even builds green and drops every fork style. Each line says where
// the file's content belongs now (docs/UPGRADING.md, "v0.4.0 for forks", has the full map).
//
// Runs before `tsc -b` in `npm run build` (node check-v03-files.ts), so it is the first error a fork sees, and in
// `npm test` (check-v03-files.test.ts), which CI's web job runs too.
//
// The list is every file under web/src that 0.4.0 deleted or renamed, from
// `git diff --name-status --diff-filter=DR v0.3.9...v0.4.0 -- web/`. A file a fork added under components/, which
// git's rename detection may move into ui/ during the merge, is not detected here: it can't be told apart from a
// file a fork adds to ui/ on purpose. UPGRADING's "After the merge" checklist covers it.
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** v0.3 file (under web/src/) → where its content belongs in 0.4.0. */
export const V03_FILES: Readonly<Record<string, string>> = {
  'styles.css':
    'tokens.css and one CSS file per page; your own styles go in your extension, with the design tokens and x-<id>- classes',
  'pages/DashboardPage.tsx': 'pages/OverviewPage.tsx and pages/overview/; add a card with overview.card',
  'components/ActionConsole.tsx': 'pages/DeviceDetailControl.tsx (Control tab); add to the device page with device.tab',
  'components/AppPicker.tsx': 'pages/ConfigEditorPage.tsx and pages/configurations/; add fields with config.section',
  'components/BulkActionModal.tsx': 'ui/BulkModals.tsx; add a bulk action with list.bulkAction',
  'components/BulkKioskModal.tsx': 'ui/BulkModals.tsx',
  'components/ConfigStatusCard.tsx': 'pages/DeviceDetailControl.tsx; add to the device page with device.tab or device.railGroup',
  'components/DeployModal.tsx': "ui/DeployModal.tsx; add an app action with list.rowAction (list: 'apps')",
  'components/EventTimeline.tsx': 'pages/DeviceDetailTabs.tsx (Activity tab) and data/deviceActivity.ts; add a tab with device.tab',
  'components/KioskChangeConfirm.tsx': 'pages/ConfigEditorPage.tsx and pages/configurations/; add fields with config.section',
  'components/KioskEnterModal.tsx': 'pages/DeviceKioskDialog.tsx',
  'components/LocationMap.tsx': 'ui/LocationMap.tsx (moved)',
  'components/LocationPanel.tsx': 'pages/DeviceDetailTabs.tsx (Location tab); add a tab with device.tab',
  'components/QrCanvas.tsx': 'ui/QrCode.tsx and ui/QrPanel.tsx',
  'components/ReloadPrompt.tsx': 'ui/ReloadPrompt.tsx',
  'components/RolloutPanel.tsx': 'pages/settings/RolloutSection.tsx; add a Settings section with settings.section',
  'components/SyncBar.tsx': 'pages/configurations/sync.ts',
  'components/TelemetryCard.tsx': 'pages/DeviceDetailTabs.tsx (Telemetry tab); add a tab with device.tab',
  'components/UpdateBanner.tsx': 'ui/Sidebar.tsx (the update card) and pages/settings/UpdatesSection.tsx',
  'ui/Wordmark.tsx': 'ui/Sidebar.tsx (Wordmark)',
  'ui/status.ts': 'data/presence.ts (isOnline) and ui/Status.tsx (status pills)',
};

/** The v0.3 files still under `<web>/src`, as `web/src/...` paths, in the list's order. */
export function findV03Files(web: string): string[] {
  return Object.keys(V03_FILES)
    .filter((f) => existsSync(join(web, 'src', f)))
    .map((f) => `web/src/${f}`);
}

/** The build's error: one line per file, or null when there is none. */
export function describeV03Files(found: readonly string[]): string | null {
  if (found.length === 0) return null;
  return [
    'These v0.3 console files were removed or replaced in 0.4.0. Move what you changed in them, then delete them',
    '(see docs/UPGRADING.md, v0.4.0 for forks):',
    ...found.map((f) => `- ${f}: now ${V03_FILES[f.slice('web/src/'.length)]}`),
  ].join('\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const message = describeV03Files(findV03Files(fileURLToPath(new URL('.', import.meta.url))));
  if (message) {
    console.error(message);
    process.exit(1);
  }
}
