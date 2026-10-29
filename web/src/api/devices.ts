import { ApiError, apiClient } from './client';

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
  /** Match `value` as the exact device number (or its fast-search suffix) instead of a substring of any field. */
  fastSearch?: boolean;
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

/** Rename a device's friendly description — POST /private/devices/{id}/description
 *  (raw text body; requires the edit_device_desc permission, which Observer does not have). */
export async function updateDeviceDescription(
  deviceId: number,
  description: string,
): Promise<void> {
  await apiClient.postText(`/private/devices/${deviceId}/description`, description);
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

/** The server's answer when no accessible device has the number (Response.DEVICE_NOT_FOUND_ERROR). */
const DEVICE_NOT_FOUND = 'error.notfound.device';

/** The device with exactly this number (GET /private/devices/number/{number}, DeviceResource#getDevice), or null
 *  when no accessible device has it. Any other failure throws, so a caller never mistakes a server error for "no
 *  such number" and falls back to another device. */
export async function getDeviceByNumber(number: string): Promise<DeviceView | null> {
  try {
    const d = await apiClient.get<DeviceView | null>(`/private/devices/number/${encodeURIComponent(number)}`);
    return d && typeof d === 'object' && !Array.isArray(d) && d.number === number ? d : null;
  } catch (e) {
    // Not found, or a number the server can't route at all (one with "/" is rejected before the resource).
    if (e instanceof ApiError && (e.message === DEVICE_NOT_FOUND || e.httpStatus === 400 || e.httpStatus === 404)) return null;
    throw e;
  }
}
