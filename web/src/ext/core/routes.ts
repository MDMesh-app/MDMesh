import { paths } from '../../routes';
import { lazyComponent, type RoutePageContribution } from '../types';
import { CORE_PAGE_ACCESS } from './access';

/** The component gallery ships only in dev builds, or in a build made with VITE_GALLERY=1 (compare harness). */
const GALLERY_ENABLED = import.meta.env.DEV || import.meta.env.VITE_GALLERY === '1';

/**
 * The signed-in pages, each its own lazy chunk (leaflet, qrcode and friends stay out of the shell). The signed-out
 * pages (/login, /set-password) are not part of the shell and stay in App.tsx.
 */
export const CORE_ROUTES: readonly RoutePageContribution[] = [
  {
    slot: 'route.page',
    id: 'overview',
    path: paths.overview,
    component: lazyComponent(() => import('../../pages/OverviewPage'), 'OverviewPage'),
  },
  {
    slot: 'route.page',
    id: 'devices',
    path: paths.devices,
    component: lazyComponent(() => import('../../pages/DevicesPage'), 'DevicesPage'),
  },
  {
    slot: 'route.page',
    id: 'device',
    path: `${paths.devices}/:id`,
    component: lazyComponent(() => import('../../pages/DeviceDetailPage'), 'DeviceDetailPage'),
  },
  {
    slot: 'route.page',
    id: 'configurations',
    path: paths.configurations,
    requires: CORE_PAGE_ACCESS.configurations,
    component: lazyComponent(() => import('../../pages/ConfigurationsPage'), 'ConfigurationsPage'),
  },
  {
    slot: 'route.page',
    id: 'configuration',
    path: `${paths.configurations}/:id`,
    requires: CORE_PAGE_ACCESS.configurations,
    component: lazyComponent(() => import('../../pages/ConfigEditorPage'), 'ConfigEditorPage'),
  },
  {
    slot: 'route.page',
    id: 'apps',
    path: paths.apps,
    requires: CORE_PAGE_ACCESS.apps,
    component: lazyComponent(() => import('../../pages/AppsPage'), 'AppsPage'),
  },
  {
    slot: 'route.page',
    id: 'enroll',
    path: paths.enroll,
    requires: CORE_PAGE_ACCESS.enroll,
    component: lazyComponent(() => import('../../pages/EnrollPage'), 'EnrollPage'),
  },
  {
    slot: 'route.page',
    id: 'settings',
    path: paths.settings,
    component: lazyComponent(() => import('../../pages/SettingsPage'), 'SettingsPage'),
  },
  ...(GALLERY_ENABLED
    ? [
        {
          slot: 'route.page',
          id: 'gallery',
          path: paths.gallery,
          component: lazyComponent(() => import('../../pages/GalleryPage'), 'default'),
        } as const,
      ]
    : []),
];
