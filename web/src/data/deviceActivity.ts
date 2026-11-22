// Device activity wording shared by the Overview feed and the device page: command names, the detail line of a
// command, and device event labels. Pure, no React.
import type { CommandHistoryItem } from '../api/commands';
import { classifyOutcome, outcomeSummary } from './configOutcomes';

const COMMAND_NAMES: Record<string, string> = {
  'config.apply': 'Apply configuration',
  'config.sync': 'Sync configuration',
  'device.lock': 'Lock',
  'device.reboot': 'Reboot',
  'device.wipe': 'Factory reset',
  'device.passcodeReset': 'Reset passcode',
  'device.alert': 'Send alert',
  'device.lockscreenMessage': 'Lock-screen message',
  'device.ring': 'Ring',
  'device.ringStop': 'Stop ringing',
  'device.powerMode': 'Connection mode',
  'device.locationMode': 'Location mode',
  'kiosk.enter': 'Enter kiosk',
  'kiosk.exit': 'Exit kiosk',
  'kiosk.clearPinLockout': 'Clear PIN lockout',
  'app.install': 'Install app',
  'app.uninstall': 'Uninstall app',
  'apps.scan': 'Scan apps',
  'apps.icons': 'Fetch app icons',
  'policy.apply': 'Apply policy',
  'remote.startSession': 'Start remote session',
  'remote.stopSession': 'Stop remote session',
};

export function commandName(type: string): string {
  return COMMAND_NAMES[type] ?? type;
}

/** The time a command row shows: when it finished, else when it was delivered, else queued. */
export function commandTime(c: CommandHistoryItem): number | undefined {
  return c.completedAt || c.deliveredAt || c.createdAt || undefined;
}

/**
 * The detail line for a command: JSON results are summarised (config.apply outcomes, app scans, icon batches);
 * everything else is the agent's text. App commands lead with their package.
 */
export function commandDetail(c: CommandHistoryItem): string | undefined {
  const parts: string[] = [];
  if (c.subject) parts.push(c.subject);
  const d = c.detail?.trim();
  if (d) {
    let summary: string | undefined;
    if (d.startsWith('{')) {
      try {
        const j = JSON.parse(d) as Record<string, unknown>;
        if (j.outcomes && typeof j.outcomes === 'object') {
          const list = Object.entries(j.outcomes as Record<string, string>).map(([k, v]) => classifyOutcome(k, String(v)));
          const bad = list.filter((o) => o.kind === 'failed');
          summary = bad.length
            ? bad.map((o) => (o.reason ? `${o.label}: ${o.reason}` : `${o.label} failed`)).join('; ')
            : outcomeSummary(list).replace(/\.$/, '');
        } else if (Array.isArray(j.apps)) {
          summary = `${j.apps.length} app${j.apps.length === 1 ? '' : 's'} found`;
        } else if (Array.isArray(j.icons)) {
          summary = `${j.icons.length} icon${j.icons.length === 1 ? '' : 's'}`;
        }
      } catch {
        /* not JSON after all: show the text */
      }
    }
    parts.push(summary ?? d);
  }
  return parts.length ? parts.join(' · ') : undefined;
}

/** Device event type → label (the device page's event list, the v0.3 console's EventTimeline wording). */
export const EVENT_LABELS: Record<string, string> = {
  boot: 'Booted',
  appInstalled: 'App installed',
  appUninstalled: 'App uninstalled',
  commandResult: 'Command',
  connectivityChange: 'Network change',
  lowBattery: 'Low battery',
  enrolled: 'Enrolled',
  kioskPinLockout: 'Exit PIN locked',
  kioskCrashLoop: 'Kiosk app keeps closing',
  kioskExit: 'Left kiosk mode',
  kioskReturn: 'Returned to kiosk mode',
  wipeNotStarted: 'Factory reset didn’t start',
  storageRecovered: 'Storage recovered',
  configReapplyFailed: 'Config re-apply failed',
  eventsDropped: 'Events dropped',
};

/** The same events as a verb phrase after the device name ("KIOSK-FRONT booted"), the v0.3 console's Dashboard wording. */
export const EVENT_VERBS: Record<string, string> = {
  boot: 'booted',
  appInstalled: 'installed an app',
  appUninstalled: 'removed an app',
  commandResult: 'ran a command',
  connectivityChange: 'changed network',
  lowBattery: 'reported low battery',
  enrolled: 'enrolled',
  kioskPinLockout: 'locked the exit PIN',
  kioskCrashLoop: 'reported the kiosk app keeps closing',
  kioskExit: 'left kiosk mode',
  kioskReturn: 'returned to kiosk mode',
  wipeNotStarted: 'didn’t start its factory reset',
  storageRecovered: 'recovered damaged storage',
  configReapplyFailed: 'failed to re-apply its configuration',
  eventsDropped: 'dropped events it could not send',
};

/** A commandResult event's detail is "<commandId>:<status>" (agent CheckInCoordinator); null for anything else. */
export function parseCommandResult(detail: string | null | undefined): { id: string; status: string } | null {
  const m = detail?.match(/^(\d+):([\w.-]+)$/);
  return m ? { id: m[1], status: m[2] } : null;
}

/** A command's reported step, as words after its name ("Lock: done"). */
const STEP_WORDS: Record<string, string> = {
  done: 'done',
  failed: 'failed',
  expired: 'expired',
  unsupported: 'not supported',
  accepted: 'running',
  delivered: 'delivered',
  pending: 'pending',
};

/** The agent's fixed sentences for the on-device kiosk exit and return (KioskLauncherActivity, StatusViewModel). */
const KIOSK_DETAILS: Record<string, string> = {
  'exited on-device': 'With the exit PIN, on the device',
  'returned on-device': 'From the device',
};

/**
 * An event's detail line, in words. A command result names its command and step ("Lock: done", "Install app:
 * failed · com.x · checksum mismatch") from the device's command history, when `commands` has it; an app event
 * shows the library's name for the package (`appNames`); the on-device kiosk exit and return say so plainly.
 * Anything else is the agent's detail as sent (network type, battery level and the like, when it sends them).
 */
export function eventDetail(
  ev: { type: string; detail?: string | null },
  { commands, appNames }: { commands?: readonly CommandHistoryItem[]; appNames?: Record<string, string> } = {},
): string | undefined {
  const raw = ev.detail?.trim() || undefined;
  switch (ev.type) {
    case 'commandResult': {
      const r = parseCommandResult(raw);
      if (!r) return raw;
      const status = r.status.toLowerCase();
      const step = STEP_WORDS[status] ?? status;
      const c = commands?.find((x) => String(x.id) === r.id);
      if (!c) return `Command #${r.id}: ${step}`;
      // The history holds the command's last result: its detail belongs to this event only when this is that step.
      const more = c.status === status ? commandDetail(c) : c.subject || undefined;
      return `${commandName(c.type)}: ${step}${more ? ` · ${more}` : ''}`;
    }
    case 'appInstalled':
    case 'appUninstalled':
      return raw && (appNames?.[raw] ?? raw);
    case 'kioskExit':
    case 'kioskReturn':
      return raw && (KIOSK_DETAILS[raw] ?? raw);
    default:
      return raw;
  }
}
