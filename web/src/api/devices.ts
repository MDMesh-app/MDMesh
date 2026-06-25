import { apiClient } from './client';

// Endpoint (see server: com.hmdm.rest.resource.DeviceResource#getAllDevices):
//   POST /rest/private/devices/search   body: DeviceSearchRequest
// Response data is a DeviceListView:
//   { configurations: { [id]: ConfigurationView }, devices: PaginatedData<DeviceView> }
// where PaginatedData = { items: DeviceView[], totalItemsCount: number }.

/** Request shape — see com.hmdm.persistence.domain.DeviceSearchRequest. */
export interface DeviceSearchRequest {
  value?: string;
  groupId?: number;
  configurationId?: number;
  pageSize?: number;
  /** 1-based. */
  pageNum?: number;
  sortBy?: string;
  sortDir?: 'ASC' | 'DESC';
}

/**
 * A single device row — see com.hmdm.rest.json.view.devicelist.DeviceView.
 * The server uses JsonInclude.NON_NULL, so most fields are optional.
 */
export interface DeviceView {
  id: number;
  number: string;
  configurationId?: number;
  description?: string;
  /** Last sync time, ms since epoch. */
  lastUpdate?: number;
  imei?: string;
  phone?: string;
  publicIp?: string;
  serial?: string;
  androidVersion?: string;
  enrollTime?: number;
  mdmMode?: boolean;
  kioskMode?: boolean;
  launcherVersion?: string;
  /** Status colour: green | red | yellow | brown | grey. */
  statusCode?: string;
  /** Stable per-device hardware id (enrollment-specific id / ANDROID_ID). Shared
   *  values across rows indicate the same physical device enrolled more than once. */
  hardwareId?: string;
  custom1?: string;
  custom2?: string;
  custom3?: string;
  oldNumber?: string;
  launcherPkg?: string;
  groups?: { id: number; name: string }[];
}

/** Configuration lookup entry — see ConfigurationView. */
export interface ConfigurationLookup {
  id: number;
  name: string;
}

export interface PaginatedData<T> {
  items: T[];
  totalItemsCount: number;
}

export interface DeviceListView {
  configurations?: Record<string, ConfigurationLookup>;
  devices: PaginatedData<DeviceView>;
}

/** Reassign one or more devices to a configuration — see DeviceResource#updateDevice
 *  (PUT /private/devices with {ids, configurationId}; server loops + notifies). */
export async function bulkSetConfiguration(
  ids: number[],
  configurationId: number,
): Promise<void> {
  await apiClient.put('/private/devices', { ids, configurationId });
}

/** Delete devices in bulk — POST /private/devices/deleteBulk {ids}. */
export async function deleteDevicesBulk(ids: number[]): Promise<void> {
  await apiClient.post('/private/devices/deleteBulk', { ids });
}

export async function searchDevices(
  req: DeviceSearchRequest = {},
): Promise<DeviceListView> {
  // NOTE: do NOT default sortBy — the server's DeviceListSortBy enum only accepts values
  // like NUMBER / ANDROID_VERSION / STATUS / CUSTOM1.. (not "description"); an unknown value
  // makes the whole search return HTTP 400. Omit it for the server default; callers may pass
  // a valid enum value explicitly.
  const body: DeviceSearchRequest = {
    value: '',
    pageNum: 1,
    pageSize: 50,
    ...req,
  };
  return apiClient.post<DeviceListView>('/private/devices/search', body);
}
