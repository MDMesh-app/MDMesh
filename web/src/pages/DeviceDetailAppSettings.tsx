// The device page's Application settings (Apps tab): for each app the device's configuration installs, the
// configuration's values (read only) and this device's overrides, which win. Save replaces the device's rows (the
// server wakes the device); Discard goes back to the loaded ones. Values can be secrets: never in a toast or a log.
import { useEffect, useId, useMemo, useState } from 'react';
import { getConfigurationApps, type ConfigApp, type Configuration } from '../api/configurations';
import { getDeviceAppSettings, saveDeviceAppSettings } from '../api/devices';
import {
  appSettingsOf,
  appSettingsProblems,
  appSettingsSignature,
  newAppSetting,
  settingValueText,
  type AppSetting,
} from '../data/managedAppSettings';
import { Badge, Button, Callout, Card, CardHeader, EmptyState, IconWarning, LinkButton, SkeletonRows, useLeaveGuard, useToast } from '../ui';
import { AppSettingRows } from './configurations/AppSettingRows';

/** Rows that differ between two lists, the larger side: an edit, an add or a remove each count one. */
function changeCount(before: AppSetting[], after: AppSetting[]): number {
  const a = appSettingsSignature(before).split('\n').filter(Boolean);
  const b = appSettingsSignature(after).split('\n').filter(Boolean);
  const inA = new Set(a);
  const inB = new Set(b);
  return Math.max(a.filter((x) => !inB.has(x)).length, b.filter((x) => !inA.has(x)).length);
}

const nameOf = (a: ConfigApp) => a.name ?? a.pkg ?? `#${a.id}`;

export function DeviceAppSettings({
  deviceId,
  configuration,
  configurationId,
  canEdit,
}: {
  deviceId: number;
  /** The device's configuration as GET /configurations/{id} returns it (with its applicationSettings); null while it loads. */
  configuration: Configuration | null;
  configurationId: number | undefined;
  /** The device is not wiped (the card shows only for a role with edit_device_app_settings). */
  canEdit: boolean;
}) {
  const toast = useToast();
  const titleId = useId();
  const [apps, setApps] = useState<ConfigApp[] | null>(null);
  const [baseline, setBaseline] = useState<AppSetting[] | null>(null);
  const [draft, setDraft] = useState<AppSetting[]>([]);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setApps(null);
    setBaseline(null);
    setError(false);
    if (configurationId == null) return;
    let active = true;
    Promise.all([getConfigurationApps(configurationId), getDeviceAppSettings(deviceId)])
      .then(([assigned, rows]) => {
        if (!active) return;
        setApps(Array.isArray(assigned) ? assigned : []);
        setBaseline(rows);
        setDraft(rows);
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
    };
  }, [deviceId, configurationId, attempt]);

  const count = baseline ? changeCount(baseline, draft) : 0;
  const dirty = count > 0;
  const guard = useLeaveGuard(dirty, count);
  const installed = useMemo(() => (apps ?? []).filter((a) => (a.action ?? 1) === 1), [apps]);
  // Only the rows of the apps shown: a stored row of an app the configuration doesn't install is sent back as it is.
  const problems = useMemo(() => {
    const shown = new Set(installed.map((a) => a.id));
    return appSettingsProblems(draft).filter((p) => shown.has(draft[p.index].applicationId));
  }, [draft, installed]);
  const configured = appSettingsOf(configuration?.applicationSettings);

  if (configurationId == null) return null;

  const setRow = (index: number, row: AppSetting) => setDraft((rows) => rows.map((r, i) => (i === index ? row : r)));
  const removeRow = (index: number) => setDraft((rows) => rows.filter((_, i) => i !== index));
  const addRow = (row: AppSetting) => setDraft((rows) => [...rows, row]);

  async function save() {
    if (problems.length > 0) {
      const app = installed.find((a) => a.id === draft[problems[0].index]?.applicationId);
      toast.push(
        'err',
        `Fix ${problems.length === 1 ? '1 problem' : `${problems.length} problems`} before saving`,
        `${app ? nameOf(app) : 'An app'}: ${problems[0].message}`,
      );
      return;
    }
    setBusy(true);
    try {
      // The whole list goes back, rows this page doesn't show included: the server replaces the device's rows.
      await saveDeviceAppSettings(deviceId, draft);
      toast.push('ok', 'Application settings saved', 'The device gets them on its next check-in.');
      // Read them back for the ids the server gave new rows; what was sent stands if that read fails.
      const saved = await getDeviceAppSettings(deviceId).catch(() => draft);
      setBaseline(saved);
      setDraft(saved);
    } catch (e) {
      toast.push('err', 'Save failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  const loading = !error && (apps == null || baseline == null || configuration == null);
  return (
    <Card padding="list" aria-labelledby={titleId} className="v2-device-appsettings">
      <CardHeader
        inset
        spacing={8}
        title="Application settings"
        titleId={titleId}
        subtitle="What each app reads as its managed configuration. A value set on this device wins over the configuration’s."
        actions={
          dirty ? (
            <>
              <span className="v2-device-meta" aria-live="polite">
                {count === 1 ? '1 unsaved change' : `${count} unsaved changes`}
              </span>
              <Button size="sm" disabled={busy} onClick={() => setDraft(baseline ?? [])}>
                Discard
              </Button>
              <Button size="sm" variant="primary" disabled={busy} onClick={() => void save()}>
                {busy ? 'Saving…' : 'Save'}
              </Button>
            </>
          ) : null
        }
      />
      <div className="v2-device-appsettings__body">
        {error ? (
          <Callout tone="alert" icon={<IconWarning size={16} />}>
            The application settings did not load.{' '}
            <LinkButton tone="inline" onClick={() => setAttempt((n) => n + 1)}>
              Try again
            </LinkButton>
          </Callout>
        ) : loading ? (
          <SkeletonRows compact count={3} label="Loading the application settings" />
        ) : installed.length === 0 ? (
          <EmptyState bare title="No managed apps">
            The device’s configuration installs no apps.
          </EmptyState>
        ) : (
          installed.map((app) => {
            const fromConfig = configured.filter((r) => r.applicationId === app.id);
            const own = draft.flatMap((row, index) => (row.applicationId === app.id ? [{ index, row }] : []));
            const ownNames = new Set(own.map((e) => e.row.name));
            const configNames = new Set(fromConfig.map((r) => r.name));
            const appName = nameOf(app);
            return (
              <section key={app.id} className="v2-device-appsettings__app" aria-label={appName}>
                <h3 className="v2-device-appsettings__name">
                  {appName}
                  {app.pkg ? <span className="v2-device-appsettings__pkg"> {app.pkg}</span> : null}
                </h3>
                {!fromConfig.length && !own.length ? <p className="v2-device-meta">No settings.</p> : null}
                {fromConfig.length ? (
                  <table className="v2-device-appsettings__config" aria-label={`Configuration values of ${appName}`}>
                    <tbody>
                      {fromConfig.map((r) => {
                        const overridden = ownNames.has(r.name);
                        return (
                          <tr key={r.name} className={overridden ? 'is-overridden' : undefined}>
                            <th scope="row">{r.name}</th>
                            <td className="v2-device-appsettings__value">
                              {overridden ? <s>{settingValueText(r.type, r.value)}</s> : settingValueText(r.type, r.value)}
                            </td>
                            <td className="v2-device-appsettings__state">
                              {overridden ? (
                                <Badge tone="warn" size="xs">
                                  Overridden on this device
                                </Badge>
                              ) : canEdit ? (
                                <LinkButton
                                  tone="quiet"
                                  aria-label={`Override ${r.name}`}
                                  onClick={() => addRow({ ...newAppSetting(app.id), name: r.name, type: r.type, value: r.value, variable: !!r.variable })}
                                >
                                  Override
                                </LinkButton>
                              ) : (
                                <span className="v2-device-meta">From the configuration</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                ) : null}
                {own.length || canEdit ? (
                  <AppSettingRows
                    appName={appName}
                    entries={own.map((e) => ({
                      ...e,
                      note: configNames.has(e.row.name) ? 'Replaces the configuration’s value on this device.' : 'Only on this device.',
                    }))}
                    problems={problems}
                    readOnly={!canEdit || busy}
                    onChange={setRow}
                    onRemove={removeRow}
                    onAdd={() => addRow(newAppSetting(app.id))}
                    addLabel="Add device setting"
                  />
                ) : null}
              </section>
            );
          })
        )}
      </div>
      {guard.dialog}
    </Card>
  );
}
