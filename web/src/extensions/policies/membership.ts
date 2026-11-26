// Which group and brand each phone has, for places outside the Policies page: the Devices list's "Policy group"
// column and the Overview's Brands card. Loaded on demand (GroupCell imports it dynamically), so the console's first
// download doesn't grow. Two requests to the Policies server plugin (groups, fleet) and core's device list.
import { apiClient, type DeviceView } from '@mdmesh/console';
import { getFleet, getGroups, listDevices } from './api';
import { brandLabel, deviceBrand, groupForDevice, setCustomBrandNames, type DeviceGroup } from './model';

export interface PhoneGroup {
  /** The brand key (set by hand, else reported). */
  brand: string;
  brandName: string;
  group: DeviceGroup | null;
}

export interface Membership {
  /** False without the Policies server plugin: there are no groups to show. */
  available: boolean;
  groups: DeviceGroup[];
  /** Policy names by id. */
  policies: Map<number, string>;
  /** The phones as core lists them (not wiped). */
  devices: DeviceView[];
  of(d: Pick<DeviceView, 'id' | 'number' | 'description'>): PhoneGroup;
}

export async function loadMembership(): Promise<Membership> {
  const [groups, fleet, devices, configs] = await Promise.all([
    getGroups(),
    getFleet(),
    listDevices().catch(() => [] as DeviceView[]),
    apiClient.get<{ id: number; name: string }[]>('/private/configurations/search').catch(() => [] as { id: number; name: string }[]),
  ]);
  if (groups) setCustomBrandNames(groups.brands);
  const hardware = new Map((fleet?.devices ?? []).map((p) => [p.number, p.hardware ?? null]));
  const list = groups?.groups ?? [];
  const of = (d: Pick<DeviceView, 'id' | 'number' | 'description'>): PhoneGroup => {
    const brand = groups?.deviceBrands.get(d.id) ?? deviceBrand(hardware.get(d.number) ?? null, d.description).brand;
    return { brand, brandName: brandLabel(brand), group: groupForDevice(list, d.id, brand).group ?? null };
  };
  return {
    available: !!groups,
    groups: list,
    policies: new Map((configs ?? []).map((c) => [c.id, c.name])),
    devices: devices.filter((d) => !d.wipedAt),
    of,
  };
}
