// The configuration editor's assigned-apps table, with search, an "On devices" filter and a sort once the list is
// long. The controls only choose which rows show and in what order: every edit goes to the editor's draft by app id,
// so what is saved, the changed count and the leave guard never depend on them.
import { useRef, useState } from 'react';
import type { ConfigApp } from '../../api/configurations';
import {
  AppTile,
  Button,
  Checkbox,
  EmptyState,
  IconButton,
  IconClose,
  IconSearch,
  LabelledSelect,
  SearchInput,
  Segmented,
  Select,
} from '../../ui';

/** Lists this long or shorter show no search, filter or sort, and keep their stored order. */
export const APP_CONTROLS_THRESHOLD = 8;

type StateFilter = 'all' | 'install' | 'skip' | 'remove';
type SortKey = 'name' | 'pkg' | 'state';

/** The `action` values (On devices): 1 Install, 0 Do not install, 2 Remove. */
const ACTION: Record<Exclude<StateFilter, 'all'>, number> = { install: 1, skip: 0, remove: 2 };
/** Sort by state in the filter's order: Install, Do not install, Remove, then anything else. */
const STATE_RANK: Record<number, number> = { 1: 0, 0: 1, 2: 2 };

const SORT_LABEL: Record<SortKey, string> = { name: 'Name', pkg: 'Package', state: 'On devices' };

const actionOf = (a: ConfigApp) => a.action ?? 1;
const nameOf = (a: ConfigApp) => a.name ?? a.pkg ?? `#${a.id}`;
const collator = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true });

export interface AssignedAppsProps {
  /** The draft's assigned apps. */
  apps: ConfigApp[];
  /** The assigned apps have loaded, or failed to (`error`). */
  ready: boolean;
  error: boolean;
  readOnly: boolean;
  /** Kiosk mode is on: the Show in kiosk column. */
  kioskColumn: boolean;
  onAction: (id: number, action: number) => void;
  onKioskVisibility: (id: number, shown: boolean) => void;
  onRemove: (id: number) => void;
  /** Changes when the editor discards its edits: the rows are filed again by their restored state. */
  resetKey: number;
}

export function AssignedApps({
  apps,
  ready,
  error,
  readOnly,
  kioskColumn,
  onAction,
  onKioskVisibility,
  onRemove,
  resetKey,
}: AssignedAppsProps) {
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<StateFilter>('all');
  const [sort, setSort] = useState<SortKey>('name');
  const searchRef = useRef<HTMLInputElement>(null);

  // Each app's state when the view last changed (search, filter, sort, Discard). Rows are filtered and sorted by it,
  // so a row edited to a state the filter hides stays where it is until the view changes; an app added since has
  // none, and shows whatever the filter.
  const capture = () => new Map(apps.map((a) => [a.id, actionOf(a)]));
  const [filed, setFiled] = useState<ReadonlyMap<number, number>>(() => new Map());
  const [filedFor, setFiledFor] = useState(resetKey);
  if (filedFor !== resetKey) {
    setFiledFor(resetKey);
    setFiled(capture());
  }
  // A change of view files the rows again; the same value again (Segmented repeats one on Home/End) is no change.
  const view =
    <T,>(set: (v: T) => void, current: T) =>
    (v: T) => {
      if (Object.is(v, current)) return;
      set(v);
      setFiled(capture());
    };

  const controls = ready && apps.length > APP_CONTROLS_THRESHOLD;
  // The controls come and go as the list crosses the threshold: each time, the view starts again from every app,
  // so a filter left from before doesn't come back over states changed while it was hidden.
  const [hadControls, setHadControls] = useState(controls);
  if (hadControls !== controls) {
    setHadControls(controls);
    setQ('');
    setFilter('all');
    setFiled(capture());
  }
  const needle = controls ? q.trim().toLowerCase() : '';
  const shownFilter: StateFilter = controls ? filter : 'all';
  const filtering = needle !== '' || shownFilter !== 'all';
  const stateOf = (a: ConfigApp) => filed.get(a.id) ?? actionOf(a);

  const rows = apps.filter(
    (a) =>
      !filed.has(a.id) ||
      ((!needle || (a.name ?? '').toLowerCase().includes(needle) || (a.pkg ?? '').toLowerCase().includes(needle)) &&
        (shownFilter === 'all' || stateOf(a) === ACTION[shownFilter])),
  );
  if (controls) {
    const byName = (x: ConfigApp, y: ConfigApp) => collator.compare(nameOf(x), nameOf(y));
    const rank = (a: ConfigApp) => STATE_RANK[stateOf(a)] ?? 3;
    rows.sort(
      sort === 'pkg'
        ? // An app without a package goes last.
          (x, y) => Number(!x.pkg) - Number(!y.pkg) || collator.compare(x.pkg ?? '', y.pkg ?? '') || byName(x, y)
        : sort === 'state'
          ? (x, y) => rank(x) - rank(y) || byName(x, y)
          : byName,
    );
  }

  const count = (f: Exclude<StateFilter, 'all'>) => apps.filter((a) => actionOf(a) === ACTION[f]).length;
  const clear = () => {
    setQ('');
    setFilter('all');
    setFiled(capture());
    searchRef.current?.focus();
  };

  const cols = kioskColumn ? 4 : 3;
  return (
    <>
      {controls ? (
        <div className="v2-cfged-appsbar">
          <SearchInput
            ref={searchRef}
            className="v2-cfged-appsbar__search"
            aria-label="Search assigned apps"
            placeholder="Search name or package"
            value={q}
            onChange={view(setQ, q)}
          />
          <Segmented<StateFilter>
            size="lg"
            aria-label="On devices"
            value={filter}
            onChange={view(setFilter, filter)}
            options={[
              { value: 'all', label: 'All', count: apps.length },
              { value: 'install', label: 'Install', count: count('install') },
              { value: 'skip', label: 'Do not install', count: count('skip') },
              { value: 'remove', label: 'Remove', count: count('remove') },
            ]}
          />
          <LabelledSelect label="Sort" value={sort} onChange={(e) => view(setSort, sort)(e.target.value as SortKey)}>
            {(Object.keys(SORT_LABEL) as SortKey[]).map((k) => (
              <option key={k} value={k}>
                {SORT_LABEL[k]}
              </option>
            ))}
          </LabelledSelect>
          <span className="v2-visually-hidden" aria-live="polite">
            {filtering ? `Showing ${rows.length} of ${apps.length} apps` : ''}
          </span>
        </div>
      ) : null}
      <div className="v2-cfged-apps">
        <table className="v2-cfged-apps__table">
          <thead>
            <tr>
              <th scope="col">App</th>
              <th scope="col">On devices</th>
              {kioskColumn ? <th scope="col">Show in kiosk</th> : null}
              <th scope="col" className="v2-cfged-apps__rm">
                <span className="v2-visually-hidden">Remove</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {!ready && !error ? (
              <tr>
                <td colSpan={cols} className="v2-cfged-apps__empty">
                  Loading the assigned apps…
                </td>
              </tr>
            ) : apps.length === 0 ? (
              <tr>
                <td colSpan={cols} className="v2-cfged-apps__empty">
                  No apps assigned.
                </td>
              </tr>
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={cols} className="v2-cfged-apps__empty">
                  <EmptyState
                    bare
                    icon={<IconSearch />}
                    title="No apps match"
                    action={
                      <Button size="sm" onClick={clear}>
                        Clear
                      </Button>
                    }
                  />
                </td>
              </tr>
            ) : (
              rows.map((a) => (
                <AppRow
                  key={a.id}
                  app={a}
                  readOnly={readOnly}
                  kioskColumn={kioskColumn}
                  onAction={onAction}
                  onKioskVisibility={onKioskVisibility}
                  onRemove={onRemove}
                />
              ))
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

function AppRow({
  app: a,
  readOnly,
  kioskColumn,
  onAction,
  onKioskVisibility,
  onRemove,
}: { app: ConfigApp } & Pick<AssignedAppsProps, 'readOnly' | 'kioskColumn' | 'onAction' | 'onKioskVisibility' | 'onRemove'>) {
  const name = nameOf(a);
  const action = actionOf(a);
  const shown = a.showIcon !== false;
  const kioskText = action !== 1 ? (action === 2 ? 'Removed' : 'Not installed') : shown ? 'Shown' : 'Hidden';
  return (
    <tr>
      <td className="v2-cfged-apps__app">
        <div className="v2-cfged-app">
          <AppTile name={name} size={32} />
          <span className="v2-cfged-app__text">
            <span className="v2-cfged-app__name">
              {name}
              {a.version ? <span className="v2-cfged-app__ver"> {a.version}</span> : null}
            </span>
            {a.pkg ? <span className="v2-cfged-app__pkg">{a.pkg}</span> : null}
          </span>
        </div>
      </td>
      <td className="v2-cfged-apps__action">
        <Select
          size="xs"
          aria-label={`What happens to ${name}`}
          value={action}
          disabled={readOnly}
          onChange={(e) => onAction(a.id, Number(e.target.value))}
        >
          <option value={1}>Install</option>
          <option value={2}>Remove</option>
          <option value={0}>Do not install</option>
        </Select>
      </td>
      {kioskColumn ? (
        <td className="v2-cfged-apps__kiosk">
          <Checkbox
            className={`v2-cfged-app__kiosk is-${action !== 1 ? 'off' : shown ? 'on' : 'hidden'}`}
            label={
              <>
                {kioskText}
                <span className="v2-visually-hidden"> in kiosk: {name}</span>
              </>
            }
            title="Allow this installed app to appear and launch in kiosk mode"
            checked={action === 1 && shown}
            disabled={readOnly || action !== 1}
            onChange={(e) => onKioskVisibility(a.id, e.target.checked)}
          />
        </td>
      ) : null}
      <td className="v2-cfged-apps__rm">
        {readOnly ? null : (
          <IconButton size="sm" label={`Remove ${name}`} onClick={() => onRemove(a.id)}>
            <IconClose />
          </IconButton>
        )}
      </td>
    </tr>
  );
}
