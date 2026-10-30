import type { Key, MouseEvent, ReactNode } from 'react';
import { Checkbox } from './Input';
import { cx } from './cx';
import './Table.css';

export interface Column<Row> {
  key: string;
  /** Header content. Pass `headerHidden` to keep it for screen readers only ("Actions"). */
  header: ReactNode;
  headerHidden?: boolean;
  cell: (row: Row) => ReactNode;
  /** Shrink to content (`width: 1%`) — action and checkbox columns. */
  shrink?: boolean;
  align?: 'left' | 'right';
  /** Mono 13px text-2 cell (Android version, app version). */
  mono?: boolean;
  /** Sortable: the header becomes a button and gets aria-sort. */
  sortable?: boolean;
  className?: string;
}

export type SortDir = 'ascending' | 'descending';

export interface TableProps<Row> {
  columns: ReadonlyArray<Column<Row>>;
  rows: ReadonlyArray<Row>;
  rowKey: (row: Row) => Key;
  /** Accessible table name. */
  'aria-label'?: string;
  /** Outer cell padding: 16 (Devices), 20 (Library), 24 (lists inside cards), 0 (editor apps table). */
  edge?: 0 | 16 | 20 | 24;
  /** Below this width the table scrolls sideways inside its wrapper. */
  minWidth?: number;
  /** Hide the header row (Needs attention, Recent commands). */
  headless?: boolean;
  /** Row vertical padding: 12 (default) or 10 (compact lists: recent commands, editor apps). */
  rowPad?: 10 | 12;
  /** Selection: a checkbox column first. */
  selection?: {
    selected: ReadonlySet<Key>;
    onToggle: (key: Key, row: Row) => void;
    onToggleAll: (select: boolean) => void;
    /** Accessible label for a row checkbox, e.g. (r) => `Select ${r.number}`. */
    rowLabel: (row: Row) => string;
    allLabel?: string;
  };
  sort?: { key: string; dir: SortDir; onSort: (key: string) => void };
  /** Shown in one full-width cell when there are no rows. */
  empty?: ReactNode;
  /**
   * A click anywhere on a row that is not on a control in it (link, button, checkbox, menu…): open or select the
   * row. Pointer convenience only, so keep a link or button in the row for keyboard users.
   */
  onRowClick?: (row: Row) => void;
  className?: string;
}

/** Clicks on these inside a row are theirs, not the row's. */
const ROW_CONTROLS = 'a, button, input, select, textarea, label, [role="menu"], [role="menuitem"]';

/**
 * Data table from the boards: head 12/500 secondary, 12/8 cell padding, 1px #EEF0F3 row rules, selected rows on
 * #F4F6F9, 16px ink checkboxes. Wrap it in <Card padding="list" clip> for the bordered look.
 */
export function Table<Row>({
  columns,
  rows,
  rowKey,
  edge = 16,
  minWidth,
  headless,
  rowPad = 12,
  selection,
  sort,
  empty,
  onRowClick,
  className,
  ...aria
}: TableProps<Row>) {
  const keys = rows.map(rowKey);
  const selectedCount = selection ? keys.filter((k) => selection.selected.has(k)).length : 0;
  const all = selection && rows.length > 0 && selectedCount === rows.length;
  const some = selection && selectedCount > 0 && !all;
  const colCount = columns.length + (selection ? 1 : 0);

  return (
    <div className={cx('v2-tablewrap', className)}>
      <table
        className={cx('v2-table', `v2-table--edge${edge}`, rowPad === 10 && 'v2-table--pad10', onRowClick && 'v2-table--rowclick')}
        style={minWidth ? { minWidth } : undefined}
        {...aria}
      >
        {!headless ? (
          <thead>
            <tr>
              {selection ? (
                <th scope="col" className="v2-table__shrink">
                  <Checkbox
                    aria-label={selection.allLabel ?? 'Select all on this page'}
                    checked={!!all}
                    indeterminate={!!some}
                    onChange={() => selection.onToggleAll(!all)}
                  />
                </th>
              ) : null}
              {columns.map((c) => {
                const sorted = sort && sort.key === c.key ? sort.dir : undefined;
                return (
                  <th
                    key={c.key}
                    scope="col"
                    aria-sort={c.sortable ? (sorted ?? 'none') : undefined}
                    className={cx(c.shrink && 'v2-table__shrink', c.align === 'right' && 'is-right', c.className)}
                  >
                    {c.headerHidden ? (
                      <span className="v2-visually-hidden">{c.header}</span>
                    ) : c.sortable && sort ? (
                      <button type="button" className={cx('v2-table__sort', sorted && 'is-sorted')} onClick={() => sort.onSort(c.key)}>
                        {c.header}
                        {sorted ? <span aria-hidden="true">{sorted === 'descending' ? ' ↓' : ' ↑'}</span> : null}
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
        ) : null}
        <tbody>
          {rows.length === 0 && empty ? (
            <tr>
              <td colSpan={colCount} className="v2-table__empty">
                {empty}
              </td>
            </tr>
          ) : null}
          {rows.map((r, i) => {
            const k = keys[i];
            const on = selection?.selected.has(k);
            return (
              <tr
                key={k}
                className={cx(on && 'is-selected')}
                onClick={
                  onRowClick
                    ? (e: MouseEvent<HTMLTableRowElement>) => {
                        if (!(e.target as Element).closest(ROW_CONTROLS)) onRowClick(r);
                      }
                    : undefined
                }
              >
                {selection ? (
                  <td className="v2-table__shrink">
                    <Checkbox aria-label={selection.rowLabel(r)} checked={!!on} onChange={() => selection.onToggle(k, r)} />
                  </td>
                ) : null}
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={cx(
                      c.shrink && 'v2-table__shrink',
                      c.align === 'right' && 'is-right',
                      c.mono && 'v2-table__mono',
                      c.className,
                    )}
                  >
                    {c.cell(r)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
