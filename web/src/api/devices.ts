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
  /** Legacy agent info. */
  info?: { imei?: string };
  /** IMEIs the current agent reported in its last check-in, one per SIM slot. */
  reportedImeis?: string[];
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
  /** When the server delivered a factory reset (device.wipe) to it, ms since epoch; absent when it was not wiped.
   *  The device erases itself before it can report, so it never checks in again under this record. */
  wipedAt?: number;
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

/** Bulk delete deleted some devices, not all (DeviceResource.BULK_DELETE_PARTIAL); data says which. */
export const BULK_DELETE_PARTIAL = 'error.device.delete.partial';

/** Delete devices in bulk — POST /private/devices/deleteBulk {ids}. Refused for all when the user can't reach one;
 *  otherwise each is deleted on its own, and an ApiError {@link BULK_DELETE_PARTIAL} lists any not deleted. */
export async function deleteDevicesBulk(ids: number[]): Promise<void> {
  await apiClient.post('/private/devices/deleteBulk', { ids });
}

/** When a bulk delete left some devices: the ids deleted and the ids not deleted, else null. */
export function bulkDeletePartial(e: unknown): { deleted: number[]; notDeleted: number[] } | null {
  if (!(e instanceof ApiError) || e.message !== BULK_DELETE_PARTIAL) return null;
  const data = (e.data ?? {}) as { deleted?: unknown; notDeleted?: unknown };
  const ids = (v: unknown) => (Array.isArray(v) ? v.filter((id): id is number => typeof id === 'number') : []);
  return { deleted: ids(data.deleted), notDeleted: ids(data.notDeleted) };
}

const bulkMessages: Record<string, string> = {
  'error.permission.denied': 'You can’t change one or more of these devices, so nothing was changed.',
  'error.device.configuration.required': 'Choose a configuration to move the devices to.',
  [BULK_DELETE_PARTIAL]: 'Some devices weren’t deleted. Try again.',
};
/** What a refused bulk device change says, by the server's error key; other messages as they are. A change to a
 *  configuration can also be refused for the configuration, so its refusal says so. */
export function bulkErrorText(e: unknown, change: 'devices' | 'configuration' = 'devices'): string {
  const message = e instanceof Error ? e.message : '';
  if (change === 'configuration' && message === 'error.permission.denied') {
    return 'You can’t change one or more of these devices, or use this configuration, so nothing was changed.';
  }
  return bulkMessages[message] ?? message;
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
