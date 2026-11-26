// Policies: device groups, one-click security levels, application control and a compliance view over
// MDMesh's configurations (EXTENDING.md). Policy settings live in each configuration's `extensions.policies`
// namespace; groups, brands, bulk jobs and automation live in the Policies server plugin
// (plugins/fork/policies). It also adds a "Policy group" column and "Add to group" / "Set brand" to the Devices list,
// and a Brands card and an "On group policy" tile to the Overview.
import { createElement } from 'react';
import { defineExtension, IconDevices, IconLock, lazyComponent, ORDER, type DeviceView } from '@mdmesh/console';
import { groupCell, groupSortValue } from './GroupCell';

/**
 * The Devices list can't open a dialog of the extension's own, so its "Add to group" and "Set brand" open the
 * Policies page's Groups view with the chosen phones in the address, where the dialog opens. A full page load: the
 * session is a cookie, so the user stays signed in.
 */
function openAssign(mode: 'group' | 'brand', rows: readonly DeviceView[]) {
  const ids = rows.map((d) => d.id).filter((id) => Number.isInteger(id));
  if (!ids.length) return;
  window.location.assign(`/policies?view=groups&do=${mode}&assign=${ids.join(',')}`);
}

export default defineExtension({
  id: 'policies',
  apiVersion: 1,
  title: 'Policies',
  // Reading and saving configurations is what every part of it does.
  requires: { permission: 'configurations' },
  contributions: [
    {
      slot: 'route.page',
      id: 'policies',
      path: '/policies',
      component: lazyComponent(() => import('./PoliciesPage'), 'PoliciesPage'),
    },
    {
      slot: 'route.page',
      id: 'policy',
      path: '/policies/:id',
      component: lazyComponent(() => import('./PoliciesPage'), 'PolicyLinkPage'),
    },
    {
      slot: 'nav.item',
      id: 'policies',
      order: ORDER.navItem.configurations - 50,
      label: 'Policies',
      icon: IconLock,
      to: '/policies',
    },
    {
      slot: 'list.column',
      id: 'policy-group',
      list: 'devices',
      order: ORDER.devicesColumn.configuration + 50,
      header: 'Policy group',
      cell: (row) => groupCell(row),
      sortValue: (row) => groupSortValue(row),
    },
    {
      slot: 'list.bulkAction',
      id: 'add-to-group',
      list: 'devices',
      order: ORDER.devicesBulkAction.move + 20,
      label: 'Add to group',
      icon: createElement(IconDevices),
      run: (rows) => openAssign('group', rows),
    },
    {
      slot: 'list.bulkAction',
      id: 'set-brand',
      list: 'devices',
      order: ORDER.devicesBulkAction.move + 30,
      label: 'Set brand',
      run: (rows) => openAssign('brand', rows),
    },
    {
      slot: 'list.rowAction',
      id: 'add-to-group',
      list: 'devices',
      order: ORDER.devicesRowAction.move + 20,
      item: (row) => ({ key: 'add-to-group', label: 'Add to group…', textValue: 'Add to group', onSelect: () => openAssign('group', [row]) }),
    },
    {
      slot: 'list.rowAction',
      id: 'set-brand',
      list: 'devices',
      order: ORDER.devicesRowAction.move + 30,
      item: (row) => ({ key: 'set-brand', label: 'Set brand…', textValue: 'Set brand', onSelect: () => openAssign('brand', [row]) }),
    },
    {
      slot: 'overview.card',
      id: 'brands',
      placement: 'aside',
      order: ORDER.overviewCard.byConfiguration + 50,
      component: lazyComponent(() => import('./BrandsCard'), 'BrandsCard'),
    },
    {
      slot: 'overview.card',
      id: 'group-policy',
      placement: 'stat',
      order: ORDER.overviewStat.sync + 50,
      component: lazyComponent(() => import('./BrandsCard'), 'GroupStat'),
    },
  ],
});
