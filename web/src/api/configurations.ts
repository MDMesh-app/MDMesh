import { ApiError, apiClient } from './client';

// Configurations list — see com.hmdm.rest.resource.ConfigurationResource#getAllConfigurations
//   GET /rest/private/configurations/search  ->  ConfigurationView[]
// The `qrCodeKey` identifies a configuration for the Headwind launcher's legacy public QR endpoints, which answer
// 404 unless the server runs with LEGACY_DEVICE_API=1 (the console draws the agent's enrollment QR itself):
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

/**
 * Each configuration's id and name: ConfigurationResource GET /list, which checks no permission. It lists what
 * /search lists (the same DAO call: the customer's configurations the user may access), so a role without
 * `configurations` can still pick one.
 */
export async function listConfigurationNames(): Promise<Pick<ConfigurationSummary, 'id' | 'name'>[]> {
  return apiClient.get<Pick<ConfigurationSummary, 'id' | 'name'>[]>('/private/configurations/list');
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
  // Headwind launcher layout for the app (configurationApplications columns). The console doesn't edit them, but
  // a save re-inserts every row from what it sends, so they must go back exactly as loaded.
  screenOrder?: number | null;
  keyCode?: number | null;
  bottom?: boolean | null;
  longTap?: boolean | null;
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
  /**
   * Server plugins' and extensions' data by namespace (an extension id, or `<id>.<name>`), each a JSON object. The
   * server keeps what is stored when a save leaves it out, and replaces it whole when a save carries it. Only
   * getConfiguration has it: list rows leave it out.
   */
  extensions?: Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * Every configuration's columns — same endpoint as the list. A row has no `files`, `applicationSettings`,
 * `applicationUsageParameters`, `extensions` or assigned `applications`, so never save one: edit from getConfiguration.
 */
export async function getConfigurations(): Promise<Configuration[]> {
  return apiClient.get<Configuration[]>('/private/configurations/search');
}

/**
 * The server's own base URL (its `base.url` setting), which the server puts on every configuration it
 * returns. null when there is no configuration to read it from.
 */
export async function getServerBaseUrl(): Promise<string | null> {
  const list = await getConfigurations();
  const u = list.map((c) => c.baseUrl).find((v): v is string => typeof v === 'string' && v.trim() !== '');
  return u ?? null;
}

/** One full configuration: every column plus its files, app settings and usage parameters (not its apps). */
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
  // The whole row minus the matrix's `selected` flag: the server re-inserts each assigned app from the saved
  // body, so any column dropped here (the launcher layout, or one a newer server adds) would be wiped on save.
  return rows.filter((a) => a.selected).map(({ selected: _selected, ...app }) => app);
}

/**
 * Create (id null) or update (id set). Returns the saved configuration.
 *
 * `config.extensions` is the extensions data as loaded (the server's GET, or a template's), and `changed` the
 * namespaces the editor changed. The server replaces all of `extensions` when a save carries it, so a save with changes
 * sends the loaded object with the changed namespaces on top: every other namespace, including those of extensions
 * this build lacks, goes back as stored. An update without changes leaves `extensions` out, so the server keeps what is
 * stored; a new configuration keeps the one it carries, so one made from a template takes the template's, as a copy
 * does.
 */
export async function saveConfiguration(config: Configuration, changed?: Readonly<Record<string, unknown>>): Promise<Configuration> {
  const { extensions: loaded, ...body } = config;
  const changes = changed && Object.keys(changed).length > 0;
  // The server replaces the whole object: changes without what they go on top of would wipe the rest.
  if (changes && config.id != null && loaded == null) {
    throw new Error('The configuration’s extensions data did not load, so it cannot be saved without losing it.');
  }
  const extensions = changes ? { ...loaded, ...changed } : config.id == null ? loaded : undefined;
  return apiClient.put<Configuration>('/private/configurations', extensions === undefined ? body : { ...body, extensions });
}

/** One reason the server refused a save's `extensions` (com.hmdm.extension.ExtensionError). */
export interface ExtensionError {
  /** The namespace it is about; null when it is about all of them (their total size). */
  namespace: string | null;
  /** A message key: `error.extensions.<code>` for core's checks, `error.extension.<namespace>.<code>` for a plugin's. */
  key: string;
  /** A sentence for people. */
  message: string;
}

/**
 * The problems of a save the server refused for its `extensions` (an ERROR whose message is the first one's key and
 * whose data lists them all), or null for any other failure.
 */
export function extensionErrorsOf(error: unknown): ExtensionError[] | null {
  if (!(error instanceof ApiError) || !Array.isArray(error.data) || error.data.length === 0) return null;
  const rows: ExtensionError[] = [];
  for (const e of error.data as unknown[]) {
    if (typeof e !== 'object' || e === null) continue;
    const { namespace, key, message } = e as Record<string, unknown>;
    if (typeof key !== 'string') continue;
    if (!key.startsWith('error.extension')) return null;
    // A plugin's Problem may come without a sentence: its key is better than nothing.
    const sentence = typeof message === 'string' && message ? message : key;
    rows.push({ namespace: typeof namespace === 'string' ? namespace : null, key, message: sentence });
  }
  return rows.length ? rows : null;
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
