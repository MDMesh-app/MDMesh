import { apiClient } from './client';

// Queue a command for a device (agent v1 contract):
//   POST /rest/private/agent/v1/devices/{deviceId}/commands
//   body: { type, requiresCapability?, payload? }   payload is a JSON STRING.
// Returns the queued command (we read its id defensively for the toast).

export interface QueueCommandRequest {
  type: string;
  requiresCapability?: string;
  /** A JSON-encoded string, not an object. */
  payload?: string;
}

export interface QueuedCommand {
  id?: number | string;
  type?: string;
  status?: string;
  [key: string]: unknown;
}

/** A user-facing command the console can dispatch. */
export interface CommandTemplate {
  key: string;
  label: string;
  description: string;
  /** Visual tone: a destructive/disruptive action is flagged. */
  danger?: boolean;
  request: QueueCommandRequest;
}

export const COMMAND_TEMPLATES: CommandTemplate[] = [
  {
    key: 'wifi-off',
    label: 'Disable Wi-Fi',
    description: 'Apply a policy that turns the device Wi-Fi radio off.',
    request: {
      type: 'policy.apply',
      requiresCapability: 'policy.wifi',
      payload: JSON.stringify({ policy: 'wifi', value: false }),
    },
  },
  {
    key: 'wifi-on',
    label: 'Enable Wi-Fi',
    description: 'Apply a policy that turns the device Wi-Fi radio on.',
    request: {
      type: 'policy.apply',
      requiresCapability: 'policy.wifi',
      payload: JSON.stringify({ policy: 'wifi', value: true }),
    },
  },
  {
    key: 'camera-off',
    label: 'Disable camera',
    description: 'Apply a policy that blocks the device camera.',
    request: {
      type: 'policy.apply',
      requiresCapability: 'policy.camera',
      payload: JSON.stringify({ policy: 'camera', value: false }),
    },
  },
  {
    key: 'reboot',
    label: 'Reboot device',
    description: 'Restart the device now.',
    danger: true,
    request: { type: 'device.reboot' },
  },
  {
    key: 'lock',
    label: 'Lock device',
    description: 'Lock the device screen immediately.',
    danger: true,
    request: { type: 'device.lock' },
  },
];

export async function queueCommand(
  deviceId: number | string,
  req: QueueCommandRequest,
): Promise<QueuedCommand> {
  return apiClient.post<QueuedCommand>(
    `/private/agent/v1/devices/${deviceId}/commands`,
    req,
  );
}

// --- Remote Actions catalog + device state/history/sync ----------------------------

export interface ActionParam {
  key: string;
  label: string;
  kind: 'text' | 'password' | 'number';
  required?: boolean;
  placeholder?: string;
}

export interface CommandTemplateExt extends CommandTemplate {
  /** Inputs gathered before sending; values folded into the payload by `build`. */
  params?: ActionParam[];
  /** Confirmation strength required before queueing. */
  confirm?: 'simple' | 'type-to-confirm';
  /** Bucket for grouping in the UI. */
  group?: 'safe' | 'disruptive' | 'destructive';
  /** Builds the request from gathered params (overrides static `request` when present). */
  build?: (values: Record<string, string>) => QueueCommandRequest;
}

export const ACTION_TEMPLATES: CommandTemplateExt[] = [
  {
    key: 'lockscreen-message', label: 'Set lock-screen message', group: 'safe',
    description: 'Show a custom message on the device lock screen (empty clears it).',
    params: [{ key: 'message', label: 'Message', kind: 'text', placeholder: 'Property of ACME IT' }],
    request: { type: 'device.lockscreenMessage', requiresCapability: 'device.lockscreenMessage' },
    build: (v) => ({
      type: 'device.lockscreenMessage', requiresCapability: 'device.lockscreenMessage',
      payload: JSON.stringify({ message: v.message ?? '' }),
    }),
  },
  {
    key: 'alert', label: 'Send alert', group: 'safe',
    description: 'Pop a high-priority message on the device.',
    params: [
      { key: 'title', label: 'Title', kind: 'text', placeholder: 'Message from IT' },
      { key: 'body', label: 'Message', kind: 'text', required: true },
    ],
    request: { type: 'device.alert', requiresCapability: 'device.alert' },
    build: (v) => ({
      type: 'device.alert', requiresCapability: 'device.alert',
      payload: JSON.stringify({ title: v.title || undefined, body: v.body ?? '' }),
    }),
  },
  {
    key: 'ring', label: 'Ring device', group: 'safe',
    description: 'Play a loud locate tone for 30 seconds.',
    request: {
      type: 'device.ring', requiresCapability: 'device.ring',
      payload: JSON.stringify({ durationMs: 30000 }),
    },
  },
  {
    key: 'ring-stop', label: 'Stop ringing', group: 'safe',
    description: 'Silence an active locate tone.',
    request: { type: 'device.ringStop', requiresCapability: 'device.ringStop' },
  },
  {
    key: 'lock', label: 'Lock device', group: 'disruptive', danger: true,
    description: 'Lock the device screen immediately.',
    request: { type: 'device.lock', requiresCapability: 'device.lock' },
  },
  {
    key: 'reboot', label: 'Reboot device', group: 'disruptive', danger: true,
    description: 'Restart the device now.', confirm: 'simple',
    request: { type: 'device.reboot', requiresCapability: 'device.reboot' },
  },
  {
    key: 'passcode-reset', label: 'Reset passcode', group: 'destructive', danger: true,
    description: 'Set or clear the device passcode (empty clears it).', confirm: 'simple',
    params: [{ key: 'newPassword', label: 'New passcode (blank to clear)', kind: 'password' }],
    request: { type: 'device.passcodeReset', requiresCapability: 'device.passcodeReset' },
    build: (v) => ({
      type: 'device.passcodeReset', requiresCapability: 'device.passcodeReset',
      payload: JSON.stringify({ newPassword: v.newPassword ?? '' }),
    }),
  },
  {
    key: 'wipe', label: 'Factory reset (wipe)', group: 'destructive', danger: true,
    description: 'Irreversibly erase the device. Cannot be undone.', confirm: 'type-to-confirm',
    request: { type: 'device.wipe', requiresCapability: 'device.wipe' },
  },
  {
    key: 'power-adaptive', label: 'Connectivity: Battery-saver', group: 'safe',
    description: 'Hold the live connection only when the screen is on or charging; use the low-power heartbeat when idle. Battery-friendly default.',
    request: {
      type: 'device.powerMode', requiresCapability: 'device.powerMode',
      payload: JSON.stringify({ mode: 'adaptive' }),
    },
  },
  {
    key: 'power-always', label: 'Connectivity: Always-on', group: 'safe',
    description: 'Keep the live connection up 24/7 for constant instant connectivity (higher battery use).',
    request: {
      type: 'device.powerMode', requiresCapability: 'device.powerMode',
      payload: JSON.stringify({ mode: 'alwaysOn' }),
    },
  },
  {
    key: 'location-passive', label: 'Location: Battery-saver', group: 'safe',
    description: 'Report the device’s last-known location each check-in — near-zero battery, no active GPS.',
    request: {
      type: 'device.locationMode', requiresCapability: 'device.locationMode',
      payload: JSON.stringify({ mode: 'passive' }),
    },
  },
  {
    key: 'location-active', label: 'Location: Accurate', group: 'safe',
    description: 'Take a fresh GPS fix each check-in for tighter tracking (higher battery use).',
    request: {
      type: 'device.locationMode', requiresCapability: 'device.locationMode',
      payload: JSON.stringify({ mode: 'active' }),
    },
  },
  {
    // Handled specially by ActionConsole: opens the app-picker modal (scans the device, builds the
    // KioskApplyPayload, queues kiosk.enter ungated). Listed here only for the button + grouping.
    key: 'kiosk-enter', label: 'Enter kiosk', group: 'disruptive', danger: true,
    description: 'Lock the device to one app or a set of apps, chosen from a scan of the device.',
    request: { type: 'kiosk.enter' },
  },
  {
    key: 'kiosk-exit', label: 'Exit kiosk', group: 'disruptive',
    description: 'Release kiosk mode and restore the normal home screen.',
    request: { type: 'kiosk.exit' },
  },
];

export interface DeviceState {
  battery: number; charging: boolean; locked: boolean; kioskActive: boolean;
  androidRelease: string; lastBootAt: number; updatedAt: number;
  agentVersion?: string | null;
  powerMode?: string | null;
}

export interface CommandHistoryItem {
  id: number | string; type: string; status: string;
  detail?: string | null; createdAt?: number; completedAt?: number;
}

export async function getDeviceState(deviceId: number | string): Promise<DeviceState | null> {
  return apiClient.get<DeviceState | null>(`/private/agent/v1/devices/${deviceId}/state`);
}

export async function listCommandHistory(
  deviceId: number | string, since = 0,
): Promise<CommandHistoryItem[]> {
  return apiClient.get<CommandHistoryItem[]>(
    `/private/agent/v1/devices/${deviceId}/commands?since=${since}`,
  );
}

export async function forceSync(deviceId: number | string): Promise<void> {
  await apiClient.post(`/private/agent/v1/devices/${deviceId}/sync`, {});
}

// --- App deploy (app store) -------------------------------------------------

export interface AppInstallSpec {
  url: string;
  packageName: string;
  versionCode?: number;
  sha256?: string;
  runAfterInstall?: boolean;
}

/** Queue an `app.install` for a device (the proven OTA path). */
export async function installApp(
  deviceId: number | string,
  spec: AppInstallSpec,
): Promise<QueuedCommand> {
  return queueCommand(deviceId, {
    type: 'app.install',
    // The gate token is the prefixed appManagement key (app.<key>); the agent advertises
    // app.silentInstall. (Bare 'silentInstall' never matched — see proto/endpoints.md.)
    requiresCapability: 'app.silentInstall',
    payload: JSON.stringify({
      url: spec.url,
      packageName: spec.packageName,
      versionCode: spec.versionCode,
      sha256: spec.sha256,
      runAfterInstall: spec.runAfterInstall ?? false,
    }),
  });
}
