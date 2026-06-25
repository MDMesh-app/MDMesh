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
