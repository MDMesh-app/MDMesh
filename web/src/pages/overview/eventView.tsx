import type { ReactNode } from 'react';
import type { CommandHistoryItem } from '../../api/commands';
import type { DeviceEvent } from '../../api/events';
import { EVENT_VERBS, eventDetail, parseCommandResult } from '../../data/deviceActivity';
import {
  IconAlert,
  IconBattery,
  IconCheck,
  IconDownload,
  IconEnterKiosk,
  IconExitKiosk,
  IconInfo,
  IconLock,
  IconNetwork,
  IconPlus,
  IconPower,
  IconTrash,
  type StatusTone,
} from '../../ui';

/**
 * How the Overview's recent activity shows a device event: icon, tone, verb phrase and the event's own detail in
 * words (eventDetail: a command result names its command from the device's `commands` history; an app event, the
 * library's name for the package). An event without a detail has no detail line: never the device's description.
 */
export function eventView(
  ev: DeviceEvent,
  appNames: Record<string, string>,
  commands?: CommandHistoryItem[],
): { icon: ReactNode; tone: StatusTone; what: string; detail?: string } {
  const what = EVENT_VERBS[ev.type] ?? ev.type;
  const detail = eventDetail(ev, { commands, appNames });
  switch (ev.type) {
    case 'enrolled':
      return { icon: <IconPlus size={15} />, tone: 'ok', what, detail };
    case 'appInstalled':
    case 'appUninstalled':
      return {
        icon: ev.type === 'appInstalled' ? <IconDownload size={15} /> : <IconTrash size={15} />,
        tone: 'idle',
        what,
        detail,
      };
    case 'commandResult': {
      const s = parseCommandResult(ev.detail)?.status.toLowerCase();
      const tone: StatusTone = s === 'done' ? 'ok' : s === 'failed' ? 'alert' : s === 'expired' ? 'warn' : 'idle';
      return { icon: tone === 'alert' ? <IconAlert size={15} /> : <IconCheck size={15} />, tone, what, detail };
    }
    case 'kioskExit':
      return { icon: <IconExitKiosk size={15} />, tone: 'idle', what, detail };
    case 'kioskReturn':
      return { icon: <IconEnterKiosk size={15} />, tone: 'idle', what, detail };
    case 'lowBattery':
      return { icon: <IconBattery size={15} />, tone: 'warn', what, detail };
    case 'connectivityChange':
      return { icon: <IconNetwork size={15} />, tone: 'idle', what, detail };
    case 'boot':
      return { icon: <IconPower size={15} />, tone: 'idle', what, detail };
    case 'kioskPinLockout':
      return { icon: <IconLock size={15} />, tone: 'alert', what, detail };
    case 'kioskCrashLoop':
    case 'wipeNotStarted':
    case 'storageRecovered':
    case 'configReapplyFailed':
    case 'eventsDropped':
      return { icon: <IconAlert size={15} />, tone: 'warn', what, detail };
    default:
      return { icon: <IconInfo size={15} />, tone: 'idle', what, detail };
  }
}
