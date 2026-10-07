import { apiClient } from './client';

// Configurations list — see com.hmdm.rest.resource.ConfigurationResource#getAllConfigurations
//   GET /rest/private/configurations/search  ->  ConfigurationView[]
// The `qrCodeKey` identifies a configuration for the public QR endpoints
//   GET /rest/public/qr/{qrCodeKey}        (provisioning QR as PNG)
//   GET /rest/public/qr/json/{qrCodeKey}   (inner admin-extras bundle as JSON)

export interface ConfigurationSummary {
  id: number;
  name: string;
  /** Opaque key used to reference this configuration from the public QR routes. */
  qrCodeKey?: string;
  description?: string;
}

export async function listConfigurations(): Promise<ConfigurationSummary[]> {
  return apiClient.get<ConfigurationSummary[]>('/private/configurations/search');
}

// An app assigned to a configuration (Configuration.applications entry). action:
// 0 = hide, 1 = install, 2 = remove.
export interface ConfigApp {
  id: number;
  applicationId?: number;
  name?: string;
  pkg?: string;
  version?: string;
  action?: number;
  showIcon?: boolean;
  remove?: boolean;
  system?: boolean;
  /** The version this config is pinned to; preserved across a save so the
   *  server doesn't silently bump the app to its latest version. */
  usedVersionId?: number;
}

// Full configuration. Field access is by key (the editor is schema-driven), so we
// keep a permissive index signature alongside the well-known fields. The whole object
// is round-tripped on save (the server replaces all columns), so never send a partial.
export interface Configuration {
  id?: number;
  name: string;
  description?: string;
  qrCodeKey?: string;
  applications?: ConfigApp[];
  [key: string]: unknown;
}

/** Full configurations (every field) — same endpoint as the list. */
export async function getConfigurations(): Promise<Configuration[]> {
  return apiClient.get<Configuration[]>('/private/configurations/search');
}

/** One full configuration. */
export async function getConfiguration(id: number): Promise<Configuration> {
  return apiClient.get<Configuration>(`/private/configurations/${id}`);
}

// The app↔configuration matrix for one config. The server (GET
// /configurations/applications/{id}) returns EVERY library app carrying a
// `selected` flag plus its per-config columns (action, usedVersionId, showIcon…).
// The list endpoint (/configurations/search) does NOT include applications, so
// the editor must fetch the assigned set here — otherwise it opens blank and a
// save would wipe the config's apps.
interface ConfigAppRow extends ConfigApp {
  selected?: boolean;
}

/** The apps currently assigned to a configuration (selected rows only). */
export async function getConfigurationApps(configId: number): Promise<ConfigApp[]> {
  const rows = await apiClient.get<ConfigAppRow[]>(
    `/private/configurations/applications/${configId}`,
  );
  return rows
    .filter((a) => a.selected)
    .map((a) => ({
      id: a.id,
      applicationId: a.applicationId,
      name: a.name,
      pkg: a.pkg,
      version: a.version,
      action: a.action,
      showIcon: a.showIcon,
      remove: a.remove,
      system: a.system,
      usedVersionId: a.usedVersionId,
    }));
}

/** Create (id null) or update (id set). Returns the saved configuration. */
export async function saveConfiguration(config: Configuration): Promise<Configuration> {
  return apiClient.put<Configuration>('/private/configurations', config);
}

export async function deleteConfiguration(id: number): Promise<void> {
  await apiClient.del(`/private/configurations/${id}`);
}

export async function copyConfiguration(
  id: number,
  name: string,
  description?: string,
): Promise<void> {
  await apiClient.put('/private/configurations/copy', { id, name, description });
}
