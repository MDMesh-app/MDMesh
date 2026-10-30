// Device activity wording shared by the Overview feed and the device page: command names, the detail line of a
// command, and device event labels. Pure, no React.
import type { CommandHistoryItem } from '../api/commands';
import { classifyOutcome, outcomeSummary } from './configOutcomes';

const COMMAND_NAMES: Record<string, string> = {
  'config.apply': 'Apply configuration',
  'device.lock': 'Lock device',
  'device.reboot': 'Reboot device',
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
  'app.install': 'Install app',
  'app.uninstall': 'Uninstall app',
  'apps.scan': 'Scan apps',
  'apps.icons': 'Fetch app icons',
  'policy.apply': 'Apply policy',
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
};

/** A commandResult event's detail is "<commandId>:<status>" (agent CheckInCoordinator); null for anything else. */
export function parseCommandResult(detail: string | null | undefined): { id: string; status: string } | null {
  const m = detail?.match(/^(\d+):([\w.-]+)$/);
  return m ? { id: m[1], status: m[2] } : null;
}
