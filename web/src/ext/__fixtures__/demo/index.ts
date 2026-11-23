// A test-only extension, written exactly as a fork would write one: it imports nothing but `@mdmesh/console`.
// Tests register it through a fixture registry (never through the production glob in ext/load.ts).
import { createElement } from 'react';
import { configSection, defineExtension, IconPulse, lazyComponent, ORDER, type DeviceCommand } from '@mdmesh/console';
import type { DemoSettings } from './DemoConfigSection';

const PING: DeviceCommand = { label: 'Demo ping', request: { type: 'demo.ping' } };

export default defineExtension({
  id: 'demo',
  apiVersion: 1,
  title: 'Demo',
  contributions: [
    {
      slot: 'nav.item',
      id: 'demo',
      order: ORDER.navItem.devices + 50,
      label: 'Demo',
      icon: IconPulse,
      to: '/demo',
    },
    {
      slot: 'route.page',
      id: 'demo',
      path: '/demo',
      component: lazyComponent(() => import('./DemoPage'), 'DemoPage'),
    },
    {
      slot: 'route.page',
      id: 'demo-admin',
      path: '/demo/admin',
      requires: { permission: 'settings' },
      component: lazyComponent(() => import('./DemoPage'), 'DemoPage'),
    },
    {
      slot: 'device.tab',
      id: 'demo',
      order: ORDER.deviceTab.telemetry + 50,
      label: 'Demo',
      icon: IconPulse,
      component: lazyComponent(() => import('./DemoDeviceTab'), 'DemoDeviceTab'),
    },
    {
      slot: 'device.tab',
      id: 'demo-admin',
      order: ORDER.deviceTab.location + 50,
      label: 'Demo admin',
      requires: { permission: 'settings' },
      component: lazyComponent(() => import('./DemoDeviceTab'), 'DemoDeviceTab'),
    },
    {
      slot: 'device.railGroup',
      id: 'demo',
      order: ORDER.deviceRailGroup.network + 50,
      title: 'Demo',
      items: ({ state }) => [{ key: 'agent', label: 'Demo agent', value: state?.agentVersion ?? '—', mono: true }],
    },
    configSection<DemoSettings>({
      id: 'demo',
      order: ORDER.configSection.network + 50,
      title: 'Demo',
      subtitle: 'Saved under extensions.demo.',
      // Never written until the user changes something.
      storage: { defaults: { label: '', on: false } },
      validate: (value) => (value.on && !value.label.trim() ? ['Give the demo a label before turning it on.'] : []),
      component: lazyComponent(() => import('./DemoConfigSection'), 'DemoConfigSection'),
    }),
    {
      slot: 'settings.section',
      id: 'demo',
      order: ORDER.settingsSection.updates + 50,
      title: 'Demo',
      component: lazyComponent(() => import('./DemoSettingsSection'), 'DemoSettingsSection'),
    },
    {
      slot: 'overview.card',
      id: 'demo',
      order: ORDER.overviewCard.byConfiguration + 50,
      placement: 'aside',
      component: lazyComponent(() => import('./DemoOverviewCard'), 'DemoOverviewCard'),
    },
    {
      slot: 'overview.card',
      id: 'demo-stat',
      order: ORDER.overviewStat.sync + 50,
      placement: 'stat',
      component: lazyComponent(() => import('./DemoOverviewCard'), 'DemoStat'),
    },
    {
      slot: 'list.column',
      id: 'demo',
      list: 'devices',
      order: ORDER.devicesColumn.android + 50,
      header: 'Demo id',
      cell: (device) => `#${device.id}`,
      sortValue: (device) => device.id,
      mono: true,
    },
    {
      slot: 'list.column',
      id: 'demo-apps',
      list: 'apps',
      order: ORDER.appsColumn.version + 50,
      header: createElement('abbr', { title: 'Package length' }, 'Pkg'),
      cell: (app) => app.pkg.length,
    },
    {
      slot: 'list.rowAction',
      id: 'demo',
      list: 'devices',
      order: ORDER.devicesRowAction.ring + 50,
      // It queues a command, so it needs what the server checks for one, as core's Lock and Ring do.
      requires: { permission: 'edit_devices' },
      item: (device, { sendCommand }) => ({ key: 'ping', label: 'Demo ping', onSelect: () => void sendCommand([device], PING) }),
    },
    {
      slot: 'list.rowAction',
      id: 'demo-note',
      list: 'devices',
      order: ORDER.devicesRowAction.delete + 50,
      // Writes nothing, so every role gets it.
      item: (device, { toast }) => ({ key: 'note', label: 'Demo note', onSelect: () => toast.push('ok', 'Demo note', device.number) }),
    },
    {
      slot: 'list.bulkAction',
      id: 'demo',
      list: 'devices',
      order: ORDER.devicesBulkAction.move + 50,
      requires: { permission: 'settings' },
      label: 'Demo export',
      icon: createElement(IconPulse),
      run: (devices, { toast }) => toast.push('ok', 'Demo export', devices.map((d) => d.number).join(', ')),
    },
  ],
});
