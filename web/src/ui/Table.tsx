import { useEffect, useRef, useState, type KeyboardEvent, type Key, type MouseEvent, type ReactNode } from 'react';
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
  /** Numeric/technical cell: tabular figures in text-2 (Android version, app version). */
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
  /** Outer cell padding: the card padding (16; 20 and 24 are deprecated and render as 16), or 0. */
  edge?: 0 | 16 | 20 | 24;
  /** Below this width the table scrolls sideways inside its wrapper. */
  minWidth?: number;
  /** Hide the header row (Needs attention, Recent commands). */
  headless?: boolean;
  /** Row height: 12 = --row (default), 10 = --row-compact (recent commands, editor apps). */
  rowPad?: 10 | 12;
  /** Selection: a checkbox column first. */
  selection?: {
    selected: ReadonlySet<Key>;
    /** `shiftKey`: the toggle came with Shift held (a click or Shift+Space), to select a range. */
    onToggle: (key: Key, row: Row, how?: { shiftKey: boolean }) => void;
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
  onRowClick?: (row: Row, event: MouseEvent<HTMLTableRowElement>) => void;
  className?: string;
}

const TABBABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]';

/**
 * Whether the table's wrapper must be a Tab stop of its own: while it scrolls sideways (a narrow screen) and nothing
 * inside can take focus, a keyboard could not otherwise reach the hidden columns (2.1.1).
 */
export function useScrollStop(wrap: { current: HTMLDivElement | null }): boolean {
  const [stop, setStop] = useState(false);
  useEffect(() => {
    const el = wrap.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const update = () => setStop(el.scrollWidth > el.clientWidth + 1 && !el.querySelector(TABBABLE));
    const ro = new ResizeObserver(update);
    ro.observe(el);
    if (el.firstElementChild) ro.observe(el.firstElementChild);
    update();
    return () => ro.disconnect();
  }, [wrap]);
  return stop;
}

/**
 * The wrapper's attributes while it is a scroll stop: a named region in the Tab order, named after the table: its
 * `aria-label` ("Scrollable: Devices"), else the element its `aria-labelledby` points at, else "Scrollable table".
 */
export function scrollStopProps(stop: boolean, name?: string, labelledBy?: string) {
  if (!stop) return {};
  if (name) return { tabIndex: 0, role: 'region', 'aria-label': `Scrollable: ${name}` };
  if (labelledBy) return { tabIndex: 0, role: 'region', 'aria-labelledby': labelledBy };
  return { tabIndex: 0, role: 'region', 'aria-label': 'Scrollable table' };
}

/** Stops the text selection a Shift+press starts (Shift+click selects a range of rows instead). */
export function noShiftSelect(e: MouseEvent) {
  if (e.shiftKey && !(e.target as Element).closest('input:not([type="checkbox"]), textarea, select')) e.preventDefault();
}

/** Whether a checkbox's change came with Shift held (its change event is the click that made it). */
export const shiftOf = (e: Event): boolean => !!(e as Partial<globalThis.MouseEvent>).shiftKey;

/**
 * Shift+Space on a selection checkbox: a range, not the plain toggle Space makes. Both key events are stopped, so the
 * box doesn't toggle itself as well; `run` on the keydown.
 */
export function shiftSpace(e: KeyboardEvent, run?: () => void) {
  if (e.key !== ' ') return;
  const el = e.currentTarget;
  if (e.type === 'keydown') {
    if (!e.shiftKey) {
      // A plain Space: whatever an earlier Shift+Space left (its keyup lost to a focus change) no longer applies.
      rangeKeys.delete(el);
      return;
    }
    e.preventDefault();
    rangeKeys.add(el);
    if (!e.repeat) run?.();
  } else if (rangeKeys.delete(el)) {
    // Its keyup too, even with Shift let go first: a browser that clicks on keyup would toggle the box as well.
    e.preventDefault();
  }
}
/** Checkboxes whose Space went down with Shift, until it comes up. */
const rangeKeys = new WeakSet<EventTarget>();

/**
 * Clicks on these inside a row or card are theirs, not the row's. A tooltip's wrapper counts: a disabled control in
 * it takes no pointer events, so a click on it lands on the wrapper (a double-click on Sync now must not open the
 * device).
 */
export const ROW_CONTROLS =
  'a, button, input, select, textarea, label, [role="menu"], [role="menuitem"], .v2-tooltip-wrap';

/**
 * Whether a click on a row or card is the row's own: not on a control in it, and not the end of a drag that selected
 * text (copying a serial number must not open or toggle the device).
 */
export function isRowClick(e: MouseEvent): boolean {
  if ((e.target as Element).closest(ROW_CONTROLS)) return false;
  const sel = typeof window !== 'undefined' ? window.getSelection() : null;
  return !sel || sel.isCollapsed || !(e.currentTarget as Node).contains(sel.anchorNode);
}

/**
 * The console's one data table: a control-height head (meta/500 secondary), --row rows (--row-compact when compact) on hairline rules, a hover
 * fill, selected rows tinted, drawn checkboxes. Put row actions in a `v2-rowactions` element: they show on row hover
 * or focus (always on touch). Wrap it in <Card padding="list" clip> for the bordered look.
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
  const wrapRef = useRef<HTMLDivElement>(null);
  const scrollStop = useScrollStop(wrapRef);
  const { 'aria-label': name, 'aria-labelledby': labelledBy } = aria as { 'aria-label'?: string; 'aria-labelledby'?: string };

  return (
    <div
      ref={wrapRef}
      className={cx('v2-tablewrap', className)}
      {...scrollStopProps(scrollStop, name, labelledBy)}
    >
      <table
        className={cx('v2-table', edge === 0 && 'v2-table--edge0', rowPad === 10 && 'v2-table--compact', onRowClick && 'v2-table--rowclick')}
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
                // Shift+click selects a range: the browser's text selection it would also start is not wanted.
                onMouseDown={selection || onRowClick ? noShiftSelect : undefined}
                onClick={
                  onRowClick
                    ? (e: MouseEvent<HTMLTableRowElement>) => {
                        if (isRowClick(e)) onRowClick(r, e);
                      }
                    : undefined
                }
              >
                {selection ? (
                  <td className="v2-table__shrink">
                    <Checkbox
                      aria-label={selection.rowLabel(r)}
                      checked={!!on}
                      onChange={(e) => selection.onToggle(k, r, { shiftKey: shiftOf(e.nativeEvent) })}
                      onKeyDown={(e) => shiftSpace(e, () => selection.onToggle(k, r, { shiftKey: true }))}
                      onKeyUp={(e) => shiftSpace(e)}
                    />
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
