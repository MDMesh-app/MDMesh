// Core's row and bulk actions on the Devices and Apps lists. Lock and Ring are plain commands, written as a fork
// would write one. The others open the page's own dialogs (Change configuration, Delete, Run action, the checksum
// and remove dialogs), so the page builds them and hands them over with the list's helpers (withCore*Parts), and the
// contributions here only pick theirs out. Core's columns stay the page's own: see ORDER.devicesColumn.
import type { Application } from '../../api/applications';
import { ACTION_TEMPLATES } from '../../api/commands';
import type { DeviceView } from '../../api/devices';
import { IconPlay, IconSliders, IconTrash } from '../../ui/icons';
import type { MenuItem } from '../../ui/Menu';
import type {
  DeviceActionHelpers,
  ListBulkActionContribution,
  ListRowActionContribution,
  RowActionHelpers,
} from '../types';
import { CORE_ACTION_ACCESS } from './access';
import { ORDER } from './order';

/** What the Devices page builds for core's actions. Page-private: not part of the helpers' public shape. */
export interface CoreDevicesParts {
  /** "Change configuration…" and "Delete…" for one row. */
  row: Record<'move' | 'delete', (device: DeviceView) => MenuItem>;
  /** The bulk bar's own buttons, on the page's selection. */
  bulk: Record<'run' | 'move' | 'delete', () => void>;
}

/** What the Apps library builds for core's row actions: each null where it does not apply to the app. */
export interface CoreAppsParts {
  row: Record<'checksum' | 'remove', (app: Application) => MenuItem | null>;
}

// Kept beside the helpers, not on them: nothing an extension's action gets leads to them (as ext/core/device.tsx).
const DEVICES = new WeakMap<object, CoreDevicesParts>();
const APPS = new WeakMap<object, CoreAppsParts>();

/**
 * A copy of the Devices list's helpers that core's actions find their parts by. Frozen: every action gets this one
 * object, so none may swap a helper out from under the others (core's Lock and Ring among them).
 */
export function withCoreDevicesParts(helpers: DeviceActionHelpers, parts: CoreDevicesParts): DeviceActionHelpers {
  const copy = Object.freeze({ ...helpers });
  DEVICES.set(copy, parts);
  return copy;
}

/** A copy of the Apps library's helpers that core's actions find their parts by, frozen as the Devices one. */
export function withCoreAppsParts(helpers: RowActionHelpers, parts: CoreAppsParts): RowActionHelpers {
  const copy = Object.freeze({ ...helpers });
  APPS.set(copy, parts);
  return copy;
}

const editDevices = CORE_ACTION_ACCESS.editDevices;

/** A command sent straight from the menu, as before: no confirmation. Its toasts say "Lock device queued". */
function command(id: 'lock' | 'ring', label: string): ListRowActionContribution {
  const request = ACTION_TEMPLATES.find((t) => t.key === id)?.request;
  return {
    slot: 'list.rowAction',
    list: 'devices',
    id,
    order: ORDER.devicesRowAction[id],
    requires: editDevices,
    item: (device, helpers) =>
      request ? { key: id, label, onSelect: () => void helpers.sendCommand([device], { label, request }) } : null,
  };
}

function deviceRow(id: 'move' | 'delete'): ListRowActionContribution {
  return {
    slot: 'list.rowAction',
    list: 'devices',
    id,
    order: ORDER.devicesRowAction[id],
    requires: editDevices,
    item: (device, helpers) => DEVICES.get(helpers)?.row[id](device) ?? null,
  };
}

function appRow(id: 'checksum' | 'remove'): ListRowActionContribution {
  return {
    slot: 'list.rowAction',
    list: 'apps',
    id,
    order: ORDER.appsRowAction[id],
    requires: id === 'remove' ? CORE_ACTION_ACCESS.removeApp : CORE_ACTION_ACCESS.setAppChecksum,
    item: (app, helpers) => APPS.get(helpers)?.row[id](app) ?? null,
  };
}

function bulk(id: 'run' | 'move' | 'delete', label: string, icon: ListBulkActionContribution['icon'], danger?: boolean) {
  return {
    slot: 'list.bulkAction',
    list: 'devices',
    id,
    order: ORDER.devicesBulkAction[id],
    requires: editDevices,
    label,
    icon,
    ...(danger ? { danger } : {}),
    run: (_devices, helpers) => DEVICES.get(helpers)?.bulk[id](),
  } satisfies ListBulkActionContribution;
}

/**
 * Every Devices action writes to devices, so each needs edit_devices; the Apps ones need the library permission
 * their endpoint checks (core/access.ts), and the page decides per app, as before.
 */
export const CORE_LIST_ACTIONS: readonly (ListRowActionContribution | ListBulkActionContribution)[] = [
  command('lock', 'Lock device'),
  command('ring', 'Ring device'),
  deviceRow('move'),
  deviceRow('delete'),
  appRow('checksum'),
  appRow('remove'),
  bulk('run', 'Run action', <IconPlay />),
  bulk('move', 'Change configuration', <IconSliders />),
  bulk('delete', 'Delete', <IconTrash />, true),
];
