// Core's device tabs and rail groups. Their bodies need the device page's own state (dialogs, command history,
// location fixes) and code (the map, the action dialogs), which stay in the page's chunk: the page builds them and
// hands them over with the device value (withCoreParts), and the contributions here only pick theirs out. So core
// and extension tabs share one host: ordering, `requires`, the error boundary and the `?tab=` URL.
import type { ReactNode } from 'react';
import { IconControl, IconHistory, IconPin, IconPulse } from '../../ui/icons';
import type { KeyValueItem } from '../../ui/KeyValueList';
import { coreComponent, type DeviceContextValue, type DeviceRailGroupContribution, type DeviceTabContribution } from '../types';
import { useDevice } from '../useDevice';
import { ORDER } from './order';

type CoreTab = keyof typeof ORDER.deviceTab;
type CoreRailGroup = keyof typeof ORDER.deviceRailGroup;

/**
 * What the page builds for core's own contributions. Page-private: not part of `useDevice()`'s value. It is found
 * by the device value rather than through a context because rail groups' `items` are plain functions, not components.
 */
export interface CoreDeviceParts {
  tabs: Record<CoreTab, ReactNode>;
  rail: Record<CoreRailGroup, KeyValueItem[]>;
  /** Activity's count: events and commands of the last 24 hours. */
  activityCount: string | undefined;
}

// Kept beside the value, not on it: nothing an extension gets from useDevice() (reflection or a spread included)
// leads to them. Hosts pass on the very object the page provides, so the lookup finds it.
const PARTS = new WeakMap<DeviceContextValue, CoreDeviceParts>();

/** A copy of the device value that core's contributions find their parts for, frozen as every contribution shares it. */
export function withCoreParts(value: DeviceContextValue, parts: CoreDeviceParts): DeviceContextValue {
  const copy = Object.freeze({ ...value });
  PARTS.set(copy, parts);
  return copy;
}

const partsOf = (device: DeviceContextValue) => PARTS.get(device);

function coreTab(id: CoreTab, label: string, icon: DeviceTabContribution['icon']): DeviceTabContribution {
  function CoreTabBody() {
    return partsOf(useDevice())?.tabs[id] ?? null;
  }
  return { slot: 'device.tab', id, order: ORDER.deviceTab[id], label, icon, component: coreComponent(CoreTabBody) };
}

function coreRailGroup(id: CoreRailGroup, title: string): DeviceRailGroupContribution {
  return { slot: 'device.railGroup', id, order: ORDER.deviceRailGroup[id], title, items: (d) => partsOf(d)?.rail[id] ?? [] };
}

/** Every role sees them: the reads they show need no permission (the server checks edit_devices on each action). */
export const CORE_DEVICE_TABS: readonly DeviceTabContribution[] = [
  coreTab('control', 'Control', IconControl),
  coreTab('telemetry', 'Telemetry', IconPulse),
  { ...coreTab('activity', 'Activity', IconHistory), count: (d) => partsOf(d)?.activityCount },
  coreTab('location', 'Location', IconPin),
];

export const CORE_DEVICE_RAIL: readonly DeviceRailGroupContribution[] = [
  coreRailGroup('status', 'Status'),
  coreRailGroup('location', 'Location'),
  coreRailGroup('hardware', 'Hardware'),
  coreRailGroup('network', 'Network'),
  coreRailGroup('management', 'Management'),
];
