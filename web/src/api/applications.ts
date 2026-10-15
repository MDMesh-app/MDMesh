import { apiClient } from './client';

// Applications library — see com.hmdm.rest.resource.ApplicationResource (@Path /private/applications).
// All responses use the {status,data} envelope (apiClient unwraps to data).

export interface Application {
  id: number;
  name: string;
  pkg: string;
  version?: string;
  versionCode?: number;
  url?: string;
  apkHash?: string;
  /** Input only: hex SHA-256 of an APK at a URL not hosted by MDMesh (stored as the version's apkHash). */
  sha256?: string;
  icon?: string;
  iconId?: number;
  /** "app" | "web" */
  type?: string;
  system?: boolean;
  latestVersion?: number;
  runAfterInstall?: boolean;
  /** Library list only: whether the latest version has a stored APK SHA-256. */
  hasApkHash?: boolean;
  /** Owned by the master customer; only a super-admin can delete it. */
  commonApplication?: boolean;
  /** Split-bundle parts as a raw JSON string `[{url,sha256,name}]` (server stores it as text);
   *  present only for multi-part bundles. Use JSON.parse to read, JSON.stringify to send. */
  parts?: string;
}

export interface ApplicationVersion {
  id: number;
  applicationId: number;
  version?: string;
  versionCode?: number;
  url?: string;
  apkHash?: string;
  arch?: string | null;
  /** Split-bundle parts as a raw JSON string `[{url,sha256,name}]`; present only for bundle versions. */
  parts?: string;
}

// ApplicationConfigurationLink — the app↔configuration matrix.
// action: 0 = hide, 1 = install, 2 = remove.
export interface AppConfigLink {
  id?: number;
  configurationId: number;
  configurationName?: string;
  applicationId: number;
  applicationName?: string;
  action: number;
  showIcon?: boolean;
  remove?: boolean;
  notify?: boolean;
}

export interface LinkConfigurationsToAppRequest {
  applicationId: number;
  configurations: AppConfigLink[];
}

// Library apps fall into three buckets. "uploaded" = the APKs/apps an admin added
// (non-system); "system" = the large pre-seeded OS app list; "web" = web-app shortcuts.
export type AppCategory = 'uploaded' | 'system' | 'web';

export function appCategory(a: Application): AppCategory {
  if ((a.type ?? 'app') === 'web') return 'web';
  if (a.system) return 'system';
  return 'uploaded';
}

/** Create (id absent) or update an Android application in the Library. */
export async function saveAndroidApplication(app: Partial<Application>): Promise<Application> {
  return apiClient.put<Application>('/private/applications/android', app);
}

export async function listApplications(value?: string): Promise<Application[]> {
  const v = value?.trim();
  const path = v
    ? `/private/applications/search/${encodeURIComponent(v)}`
    : '/private/applications/search';
  return apiClient.get<Application[]>(path);
}

export async function getVersions(appId: number): Promise<ApplicationVersion[]> {
  return apiClient.get<ApplicationVersion[]>(`/private/applications/${appId}/versions`);
}

/** Store the hex SHA-256 of a version's APK (needed before devices install an APK MDMesh doesn't host). */
export async function setVersionSha256(versionId: number, sha256: string): Promise<void> {
  await apiClient.put(`/private/applications/versions/${versionId}/sha256`, { sha256 });
}

/** Remove a Library record and its server-hosted APK files (unless another entry shares them). This never sends an uninstall command. */
export async function deleteApplication(appId: number): Promise<void> {
  await apiClient.del(`/private/applications/${appId}`);
}

export async function getAppConfigLinks(appId: number): Promise<AppConfigLink[]> {
  return apiClient.get<AppConfigLink[]>(`/private/applications/configurations/${appId}`);
}

export async function updateAppConfigLinks(
  req: LinkConfigurationsToAppRequest,
): Promise<void> {
  await apiClient.post('/private/applications/configurations', req);
}

// --- APK upload (drop → analyze → host) -------------------------------------
// See FilesResource (@Path /private/web-ui-files):
//   POST /                 multipart "file" -> FileUploadResult (parses the APK)
//   POST /update           commit the temp upload -> served file with a public url

/** Parsed APK metadata from the server's APKFileAnalyzer. */
export interface ApkFileDetails {
  pkg: string;
  name?: string;
  version?: string;
  versionCode?: number;
  arch?: string | null;
}

export interface FileUploadResult {
  name?: string;
  /** Absolute temp path on the server; pass back to commit. */
  serverPath: string;
  fileDetails?: ApkFileDetails;
  exists?: boolean;
  complete?: boolean;
}

export interface UploadedFileView {
  id?: number;
  /** Public, agent-reachable URL once committed. */
  url?: string;
  filePath?: string;
  fileName?: string;
}

/** Upload an APK; the server parses it and returns its package/version details. */
export async function uploadApk(file: File): Promise<FileUploadResult> {
  const form = new FormData();
  form.append('file', file, file.name);
  return apiClient.postForm<FileUploadResult>('/private/web-ui-files', form);
}

// --- Split-APK bundle upload (.xapk/.apks/.apkm/.zip) ------------------------
// The server unpacks the bundle, hosts each split, and returns the parts to
// install together in one session. See the /bundle endpoint on FilesResource.

export interface BundleUploadResult {
  name: string;
  packageName: string;
  version?: string;
  versionCode: number;
  parts: { url: string; sha256: string; name: string }[];
}

/** Upload a split-APK bundle; the server unpacks it and returns the hosted parts. */
export async function uploadBundle(file: File): Promise<BundleUploadResult> {
  const form = new FormData();
  form.append('file', file, file.name);
  return apiClient.postForm<BundleUploadResult>('/private/web-ui-files/bundle', form);
}

/** Commit a just-uploaded temp file into the served files area; returns its url. */
export async function commitUpload(serverPath: string): Promise<UploadedFileView> {
  return apiClient.post<UploadedFileView>('/private/web-ui-files/update', {
    tmpPath: serverPath,
    fileName: '',
    filePath: '',
    external: false,
  });
}
