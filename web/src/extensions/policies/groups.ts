// Device groups: pure helpers over what the Policies server plugin stores (DeviceGroup) and the fleet. No React, no
// network: model.test.ts covers them.
import { UNKNOWN_BRAND, type DeviceGroup } from './model';

/** A group as the plugin saves it. */
export interface GroupInput {
  name: string;
  configurationId: number | null;
  brands: string[];
  devices: number[];
}

/** What these helpers need of a phone. */
export interface GroupPhone {
  device: { id: number; number: string; configurationId?: number };
  brand: string;
  group?: DeviceGroup;
}

/** The group's phones that are not on its policy yet: what Apply moves. None while it has no policy. */
export function offPolicy<P extends GroupPhone>(group: DeviceGroup, phones: readonly P[]): P[] {
  if (group.policyId == null) return [];
  return phones.filter((p) => p.group?.id === group.id && p.device.configurationId !== group.policyId);
}

export interface BrandSummary {
  brand: string;
  /** Phones with this brand (reported, or set by hand). */
  count: number;
  /** The group that covers it. */
  group?: DeviceGroup;
  /** Made by hand (Groups → New brand), so it can be deleted when nothing uses it. */
  custom: boolean;
}

/**
 * Every brand worth listing, most phones first: the ones phones have, those groups cover, and those made by hand
 * (even with no phone yet). Phones that report no brand count under "unknown", which no group can cover.
 */
export function brandSummary(
  phones: readonly { brand: string }[],
  groups: readonly DeviceGroup[],
  custom: readonly { brand: string }[],
): BrandSummary[] {
  const counts = new Map<string, number>();
  for (const p of phones) counts.set(p.brand, (counts.get(p.brand) ?? 0) + 1);
  for (const g of groups) for (const b of g.brands) if (!counts.has(b)) counts.set(b, 0);
  for (const c of custom) if (!counts.has(c.brand)) counts.set(c.brand, 0);
  const made = new Set(custom.map((c) => c.brand));
  return [...counts.entries()]
    .map(([brand, count]) => ({ brand, count, group: groups.find((g) => g.brands.includes(brand)), custom: made.has(brand) }))
    .sort((a, b) => b.count - a.count || a.brand.localeCompare(b.brand));
}

/** The brands a group may cover: never "unknown" (phones that report no brand get one by hand instead). */
export const groupableBrand = (brand: string) => brand !== UNKNOWN_BRAND;
