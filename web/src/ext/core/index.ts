import { CORE_ID } from '../registry';
import { defineExtension, EXTENSION_API_VERSION } from '../types';
import { CORE_CONFIG_SECTIONS } from './config';
import { CORE_DEVICE_RAIL, CORE_DEVICE_TABS } from './device';
import { CORE_LIST_ACTIONS } from './lists';
import { CORE_NAV } from './nav';
import { CORE_OVERVIEW_CARDS } from './overview';
import { CORE_ROUTES } from './routes';
import { CORE_SETTINGS_SECTIONS } from './settings';

/** MDMesh's own contributions. Core fills its slots through the same registry as extensions, so the slots stay real. */
export const CORE_EXTENSION = defineExtension({
  id: CORE_ID,
  apiVersion: EXTENSION_API_VERSION,
  title: 'MDMesh',
  contributions: [
    ...CORE_NAV,
    ...CORE_ROUTES,
    ...CORE_DEVICE_TABS,
    ...CORE_DEVICE_RAIL,
    ...CORE_CONFIG_SECTIONS,
    ...CORE_SETTINGS_SECTIONS,
    ...CORE_OVERVIEW_CARDS,
    ...CORE_LIST_ACTIONS,
  ],
});
