// The Devices and Apps tables as hosts: extension columns placed among core's, row-menu entries and bulk-bar buttons
// from the registry (core's included, ext/core/lists.tsx), each behind `requires` and its own boundary, and the
// helpers their actions get.
import { isValidElement, useCallback, useMemo, useState, type ReactNode } from 'react';
import { bulkQueueCommand, forceSync, isBulkForbidden, queueCommand } from '../../api/commands';
import type { DeviceView } from '../../api/devices';
import { Button } from '../../ui/Button';
import type { MenuEntry, MenuItem } from '../../ui/Menu';
import type { Column, SortDir } from '../../ui/Table';
import { useToast } from '../../ui/toast';
import { ORDER } from '../core/order';
import { ExtensionBoundary } from '../ExtensionBoundary';
import type { SlotEntry } from '../registry';
import { safeCall } from '../safeCall';
import {
  commandRequest,
  type ContributionOf,
  type DeviceActionHelpers,
  type DeviceCommand,
  type ListBulkActionContribution,
  type ListHelpers,
  type ListRows,
  type RowActionHelpers,
} from '../types';
import { useContributions } from '../useContributions';

type ListId = keyof ListRows;
type ListSlot = 'list.column' | 'list.rowAction' | 'list.bulkAction';

/** The slot's entries for one list that the role may see, in order. */
function useListEntries<S extends ListSlot>(slot: S, list: ListId): readonly SlotEntry<ContributionOf<S>>[] {
  const entries = useContributions(slot);
  return useMemo(() => entries.filter((e) => (e.contribution as { list: ListId }).list === list), [entries, list]);
}

// One list's contribution shapes. The slot types are unions over the lists, which a generic `L` cannot narrow.
type ColumnOf<L extends ListId> = Omit<Extract<ContributionOf<'list.column'>, { list: 'devices' }>, 'list' | 'cell' | 'sortValue'> & {
  cell(row: ListRows[L]): unknown;
  sortValue?(row: ListRows[L]): unknown;
};
type RowActionOf<L extends ListId> = { order: number; item(row: ListRows[L], helpers: ListHelpers[L]): unknown };

/** A core column with its place among extension columns (ORDER.devicesColumn, ORDER.appsColumn). */
export type PlacedColumn<Row> = Column<Row> & { order: number };

type SortValue = string | number | null;

/** Ascending: numbers, then text (natural order), then nothing. */
function compareValues(a: SortValue, b: SortValue): number {
  if (a === b) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'number') return -1;
  if (typeof b === 'number') return 1;
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

export interface ListColumns<Row> {
  /** Core's columns (the page's) and the extensions', in order (core's first on a tie), then `trailing` (actions). */
  place(core: readonly PlacedColumn<Row>[], trailing?: readonly Column<Row>[]): Column<Row>[];
  /**
   * How to sort by an extension column, or undefined when `key` is not one: rows without a value go last either
   * way, and equal values compare 0 (the page breaks ties).
   */
  compare(key: string, dir: SortDir): ((a: Row, b: Row) => number) | undefined;
}

/**
 * The extension columns of a table. Their header and cells render in their own boundaries, and a `cell` or
 * `sortValue` that throws counts as empty, so one broken column breaks neither the table nor the other columns.
 */
export function useListColumns<L extends ListId>(list: L): ListColumns<ListRows[L]> {
  type Row = ListRows[L];
  const entries = useListEntries('list.column', list);
  const extensionColumns = useMemo(
    () =>
      entries.map((entry) => {
        const c = entry.contribution as unknown as ColumnOf<L>;
        const column: PlacedColumn<Row> = {
          key: `x-${entry.extension.id}-${c.id}`,
          order: c.order,
          header: (
            <ExtensionBoundary entry={entry} fallback="inline">
              {c.header}
            </ExtensionBoundary>
          ),
          cell: (row) => (
            <ExtensionBoundary entry={entry} fallback="inline">
              {safeCall(c.cell, [row], '—', `${entry.key}.cell`) as ReactNode}
            </ExtensionBoundary>
          ),
          sortable: typeof c.sortValue === 'function',
          mono: c.mono,
          align: c.align,
        };
        const value = (row: Row): SortValue => {
          const v: unknown = c.sortValue ? safeCall(c.sortValue, [row], null, `${entry.key}.sortValue`) : null;
          return typeof v === 'number' ? (Number.isNaN(v) ? null : v) : typeof v === 'string' ? v : null;
        };
        return { column, value };
      }),
    [entries],
  );
  return useMemo(
    () => ({
      place: (core, trailing = []) => [
        ...[...core, ...extensionColumns.map((x) => x.column)]
          .map((column, index) => ({ column, index }))
          .sort((a, b) => a.column.order - b.column.order || a.index - b.index)
          .map(({ column: { order: _order, ...column } }) => column),
        ...trailing,
      ],
      compare: (key, dir) => {
        const found = extensionColumns.find((x) => x.column.key === key);
        if (!found) return undefined;
        const sign = dir === 'ascending' ? 1 : -1;
        return (a, b) => {
          const va = found.value(a);
          const vb = found.value(b);
          return va === null || vb === null ? compareValues(va, vb) : sign * compareValues(va, vb);
        };
      },
    }),
    [extensionColumns],
  );
}

/** The menu item an action returned, checked: anything else would break the menu, which renders outside any boundary. */
function checkedItem(entry: SlotEntry, value: unknown): MenuItem | null {
  if (typeof value !== 'object' || value === null) return null;
  const item = value as Partial<MenuItem>;
  const { label, icon, onSelect } = item;
  const text = typeof label === 'string' && label.trim() !== '';
  if (typeof onSelect !== 'function' || (!text && !isValidElement(label))) return null;
  return {
    key: entry.key,
    label: text ? label : (
      <ExtensionBoundary entry={entry} fallback="inline">
        {label}
      </ExtensionBoundary>
    ),
    textValue: typeof item.textValue === 'string' ? item.textValue : text ? (label as string) : undefined,
    icon: isValidElement(icon) ? (
      <ExtensionBoundary entry={entry} fallback="none">
        {icon}
      </ExtensionBoundary>
    ) : undefined,
    danger: item.danger === true,
    disabled: item.disabled === true,
    onSelect: () => safeCall(onSelect, [], undefined, `${entry.key}.onSelect`),
  };
}

/** Where a row menu's groups start, by order: Devices puts the changes to the device record below its commands. */
const ROW_MENU_GROUPS: Record<ListId, readonly number[]> = {
  devices: [ORDER.devicesRowAction.move],
  apps: [],
};

export interface RowMenu<Row> {
  /** Whether any row action is there for the role: without one, the list has no menu to show. */
  available: boolean;
  /** The row's entries: each action's item for it, in order, the groups split by a separator. */
  items(row: Row): MenuEntry[];
}

/** A row's "More actions" menu. An item that throws, or is not a menu item, is left out. */
export function useRowMenu<L extends ListId>(list: L, helpers: ListHelpers[L]): RowMenu<ListRows[L]> {
  const entries = useListEntries('list.rowAction', list);
  const items = useCallback(
    (row: ListRows[L]) => {
      const groups: MenuItem[][] = ROW_MENU_GROUPS[list].map(() => []).concat([[]]);
      for (const entry of entries) {
        const c = entry.contribution as unknown as RowActionOf<L>;
        const item = checkedItem(entry, safeCall(c.item, [row, helpers], null, `${entry.key}.item`));
        if (!item) continue;
        const group = ROW_MENU_GROUPS[list].filter((start) => c.order >= start).length;
        groups[group].push(item);
      }
      return groups.filter((g) => g.length).flatMap((g, i): MenuEntry[] => (i ? ['separator', ...g] : g));
    },
    [entries, list, helpers],
  );
  return { available: entries.length > 0, items };
}

/** The Devices bulk-bar buttons the role may use. */
export function useBulkActions(): readonly SlotEntry<ListBulkActionContribution>[] {
  return useListEntries('list.bulkAction', 'devices') as readonly SlotEntry<ListBulkActionContribution>[];
}

/** One bulk-bar button: disabled while its `run` is in flight, its icon in its own boundary. */
function BulkButton({
  entry,
  rows,
  helpers,
}: {
  entry: SlotEntry<ListBulkActionContribution>;
  rows: readonly DeviceView[];
  helpers: DeviceActionHelpers;
}) {
  const [running, setRunning] = useState(false);
  const { label, icon, danger, run } = entry.contribution;
  const click = () => {
    const result = safeCall(run, [rows, helpers], undefined, `${entry.key}.run`);
    if (!(result instanceof Promise)) return;
    setRunning(true);
    result
      .catch((error: unknown) => console.error(`[ext:${entry.key}.run] failed`, error))
      .finally(() => setRunning(false));
  };
  return (
    <Button
      variant={danger ? 'danger' : undefined}
      icon={
        icon ? (
          <ExtensionBoundary entry={entry} fallback="none">
            {icon}
          </ExtensionBoundary>
        ) : undefined
      }
      disabled={running}
      onClick={click}
    >
      {label}
    </Button>
  );
}

/** The bulk bar's buttons, in order, acting on `rows` (the selected devices the list shows). */
export function BulkActions({
  actions,
  rows,
  helpers,
}: {
  actions: readonly SlotEntry<ListBulkActionContribution>[];
  rows: readonly DeviceView[];
  helpers: DeviceActionHelpers;
}) {
  return actions.map((entry) => <BulkButton key={entry.key} entry={entry} rows={rows} helpers={helpers} />);
}

const errText = (e: unknown) => (e instanceof Error ? e.message : '');
const plural = (n: number) => `${n} device${n === 1 ? '' : 's'}`;

/** The Devices list's helpers: `refresh` reloads the list, `sendCommand` queues and toasts. */
export function useDeviceActionHelpers(refresh: () => void): DeviceActionHelpers {
  const toast = useToast();
  return useMemo(() => {
    async function sendCommand(devices: readonly DeviceView[], command: DeviceCommand, values: Record<string, string> = {}) {
      if (!devices.length) return false;
      const { label } = command;
      try {
        const request = commandRequest(command, values);
        // The bulk endpoint refuses these; one device would go through the single-device one, which does not.
        // Core sends them only from the device page, behind its confirmation.
        if (isBulkForbidden(request.type)) {
          toast.push('err', `${label} failed`, 'Wipe and passcode reset are sent from the device’s page only.');
          return false;
        }
        if (devices.length === 1) {
          const [device] = devices;
          await queueCommand(device.number, request);
          toast.push('ok', `${label} queued`, device.number);
          await forceSync(device.number).catch(() => undefined); // nudge, as on the device page
        } else {
          const res = await bulkQueueCommand(
            devices.map((d) => d.id),
            request,
          );
          const skipped = res.skipped?.length ?? 0;
          toast.push('ok', `${label} queued`, `Queued for ${plural(res.queued)}` + (skipped ? ` (${skipped} skipped)` : '') + '.');
        }
        return true;
      } catch (e) {
        toast.push('err', `${label} failed`, errText(e));
        return false;
      }
    }
    return { toast, refresh, sendCommand };
  }, [toast, refresh]);
}

/** The Apps library's helpers. */
export function useRowActionHelpers(refresh: () => void): RowActionHelpers {
  const toast = useToast();
  return useMemo(() => ({ toast, refresh }), [toast, refresh]);
}
