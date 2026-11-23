import { createElement, startTransition, useCallback, useEffect, useOptimistic } from 'react';
import { useLocation, useNavigate, useSearchParams, type Path } from 'react-router';
import { TabPanel, Tabs } from '../../ui/Tabs';
import { ExtensionBoundary } from '../ExtensionBoundary';
import type { SlotEntry } from '../registry';
import { useRegistry } from '../RegistryContext';
import { safeCall } from '../safeCall';
import type { DeviceContextValue, DeviceTabContribution } from '../types';
import { useAccess, useContributions } from '../useContributions';
import { useDevice } from '../useDevice';

/**
 * The URL parameter that names the open tab (`/devices/WH-12?tab=telemetry`). No parameter: the first tab the role
 * may see, once that is known (see `first` below).
 */
const TAB_PARAM = 'tab';

/** This address with `?tab=` set to `id`, or dropped for null; the rest of the query and the hash stay. */
function withTab(params: URLSearchParams, hash: string, id: string | null): Partial<Path> {
  const next = new URLSearchParams(params);
  if (id === null) next.delete(TAB_PARAM);
  else next.set(TAB_PARAM, id);
  const search = next.toString();
  return { search: search ? `?${search}` : '', hash };
}

export interface DeviceTabsState {
  /** The tabs the signed-in user may see, in order. */
  tabs: readonly SlotEntry<DeviceTabContribution>[];
  /** The open tab's id; null while the tab to open waits for the active server plugins. */
  active: string | null;
  /**
   * Open a tab: a new history entry, so Back returns to the previous tab. `replace` changes the entry instead, for
   * the arrow keys, so Back leaves the page rather than walking every tab passed over.
   */
  select(id: string, options?: { replace?: boolean }): void;
}

/**
 * The device page's tabs and the open one, which lives in the URL so a link can open a tab and Back and Forward
 * move between tabs. An unknown tab, or one the role may not see, opens the first tab instead and drops the
 * parameter in place (no history entry). The page calls this, so it can open a tab itself ("All activity").
 */
export function useDeviceTabs(): DeviceTabsState {
  const entries = useRegistry().entries('device.tab');
  const access = useAccess();
  const [params] = useSearchParams();
  const { hash } = useLocation();
  const navigate = useNavigate();
  const tabs = useContributions('device.tab');
  // While a plugin-gated tab ahead of the first one waits for the plugin list, no tab is first yet: opening the next
  // one would swap the open tab under the user when the list comes.
  const lead = entries.find((e) => access(e) !== 'denied');
  const first = lead && access(lead) === 'allowed' ? lead.contribution.id : null;

  const asked = params.get(TAB_PARAM);
  const askedEntry = asked === null ? undefined : entries.find((e) => e.contribution.id === asked);
  const askedAccess = askedEntry ? access(askedEntry) : 'denied';
  const inUrl = asked === null || askedAccess === 'denied' ? first : askedAccess === 'pending' ? null : asked;
  // The router renders a navigation in a transition, a moment later. A tab change shows its tab at once, as before
  // the tab was in the URL, as an optimistic value React drops when that transition is done: from then on the URL
  // decides, even when Back or Forward came in between.
  // This relies on the router committing the navigation in that same transition, which holds while the app's data
  // router has no loader, middleware, lazy route or dataStrategy (App.tsx). With one, navigate() awaits first, the
  // location lands in a later transition, and the clicked tab would flash back until then. A navigation a leave
  // guard blocks shows the tab for one frame and remounts the open panel.
  const [active, show] = useOptimistic(inUrl);

  const wrong = asked !== null && askedAccess === 'denied';
  useEffect(() => {
    if (wrong) void navigate(withTab(params, hash, null), { replace: true });
  }, [wrong, params, hash, navigate]);

  const select = useCallback(
    (id: string, { replace = false }: { replace?: boolean } = {}) => {
      if (id === active || !tabs.some((e) => e.contribution.id === id)) return;
      startTransition(() => {
        show(id);
        void navigate(withTab(params, hash, id === first ? null : id), { replace });
      });
    },
    [active, tabs, first, show, params, hash, navigate],
  );

  return { tabs, active, select };
}

/** A tab's count, when it gives a usable one: anything else would break the tab strip, outside any boundary. */
function countOf(entry: SlotEntry<DeviceTabContribution>, device: DeviceContextValue): string | undefined {
  const { count } = entry.contribution;
  const value: unknown = count ? safeCall(count, [device], undefined, `${entry.key}.count`) : undefined;
  return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
}

/** The tab strip and the open tab's body, in its own error boundary (reset per device and per tab). */
export function DeviceTabs({ tabs, active, select, idBase }: DeviceTabsState & { idBase: string }) {
  const device = useDevice();
  const items = tabs.map((entry) => {
    const { id, label, icon: Icon } = entry.contribution;
    // The icon renders in the core tab strip: in its own boundary, so a bad one costs only the icon.
    const icon = Icon ? (
      <ExtensionBoundary entry={entry} fallback="none">
        <Icon />
      </ExtensionBoundary>
    ) : undefined;
    return { key: id, label, icon, count: countOf(entry, device) };
  });
  const open = tabs.find((e) => e.contribution.id === active);
  return (
    <>
      <Tabs
        items={items}
        value={active ?? ''}
        onChange={(id, via) => select(id, { replace: via === 'keyboard' })}
        aria-label="Device sections"
        idBase={idBase}
      />
      {open ? (
        <TabPanel idBase={idBase} tabKey={open.contribution.id} className="v2-device-panel">
          <ExtensionBoundary key={`${device.device.number}:${open.key}`} entry={open}>
            {createElement(open.contribution.component)}
          </ExtensionBoundary>
        </TabPanel>
      ) : null}
    </>
  );
}
