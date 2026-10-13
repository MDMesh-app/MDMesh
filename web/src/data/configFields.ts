// Field schema for the configuration editor. The main editor shows what the MDMesh agent applies
// (plus the provisioning Wi-Fi the enrollment QR reads); every other field sits in one collapsed
// "Headwind launcher only" section. Field keys are the exact Configuration JSON properties (see
// com.hmdm.persistence.domain.Configuration). The editor round-trips the whole object on save, so a
// field that is hidden, or not listed here at all, keeps its stored value.
//
// type:
//   text/textarea/password/color/time → string input
//   int    → number
//   enum   → <select> from options
//   switch → plain boolean (Java primitive; false default)
//   tri    → nullable Boolean with three segments (see `segments`)
//   flag   → nullable Boolean where null means the same as false on the device: a checkbox, or two
//            segments when `flagLabels` is set. Turning it off restores a stored null, so opening and
//            saving never turns null into false (that would change the config revision).
//   app    → application version id selector (mainAppId / contentAppId)

export type FieldType =
  | 'text' | 'textarea' | 'password' | 'int' | 'enum'
  | 'switch' | 'tri' | 'flag' | 'time' | 'color' | 'app';

export type FieldGroup =
  | 'Identity' | 'Apps' | 'Kiosk' | 'Kiosk appearance' | 'Network' | 'Restrictions' | 'Enrollment'
  | 'Security' | 'Display' | 'Updates' | 'Advanced';

export interface FieldOption {
  value: string | number;
  label: string;
}

export interface TriSegment {
  value: boolean | null;
  label: string;
}

export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  group: FieldGroup;
  help: string;
  /** Applied on devices by the MDMesh agent via config.apply. Unset = Headwind launcher field, not applied. */
  enforced?: boolean;
  /** Takes effect only while kiosk mode is on. */
  kioskOnly?: boolean;
  /** Not sent to devices, but read by the console's enrollment QR (provisioning Wi-Fi). */
  enrollQr?: boolean;
  /** Configuration metadata (name, description): always shown, never sent to devices, not "Enforced". */
  metadata?: boolean;
  options?: FieldOption[];
  /** tri: the segments in display order. Default: Not managed / On / Off. */
  segments?: TriSegment[];
  /** flag: [label for off/null, label for true]; renders two segments instead of a checkbox. */
  flagLabels?: [string, string];
  min?: number;
  max?: number;
}

/** Policy where true = allowed, false = blocked. */
const ALLOW_BLOCK: TriSegment[] = [
  { value: null, label: 'Not managed' }, { value: true, label: 'Allow' }, { value: false, label: 'Block' },
];
/** Policy stored as "disableX": true = blocked, false = allowed. */
const ALLOW_BLOCK_INVERTED: TriSegment[] = [
  { value: null, label: 'Not managed' }, { value: false, label: 'Allow' }, { value: true, label: 'Block' },
];
export const DEFAULT_SEGMENTS: TriSegment[] = [
  { value: null, label: 'Not managed' }, { value: true, label: 'On' }, { value: false, label: 'Off' },
];

// Behaviour that depends on the agent version (agent 0.3.4 / protocol 1.4).
const WIFI_HELP = 'Block locks the Wi-Fi settings so users cannot change networks. It does not turn Wi-Fi off: that is Wi-Fi radio. Allow lifts the lock.';
const WIFI_RADIO_HELP = 'Needs agent 0.3.4 or later. On turns Wi-Fi on. Off turns it off and, on Android 13 and later, stops users turning it back on. Off is deferred while Wi-Fi is the only network, so the device stays reachable and reports why. Not managed lifts the lock and leaves the radio as it is.';
const LOCK_BUTTONS_HELP = 'Block the power menu in kiosk. Volume buttons are a separate setting, Lock volume.';
const LOCK_VOLUME_HELP = 'Applies in kiosk mode, with agent 0.3.4 or later: blocks volume changes. Android mutes the device while volume is locked; the lock is lifted when kiosk ends.';
const LOCATION_HELP = 'Do not track collects no location with agent 0.3.4 or later, and the server drops any location an older agent still reports. Last known reports the location the device already has at each check-in; GPS asks for a fresh fix (more accurate, more battery).';

export const GROUP_ORDER: FieldGroup[] = [
  'Identity', 'Apps', 'Kiosk', 'Kiosk appearance', 'Network', 'Restrictions', 'Enrollment',
  'Security', 'Display', 'Updates', 'Advanced',
];

/** One-line notes under a section heading of the main editor. */
export const GROUP_NOTES: Partial<Record<FieldGroup, string>> = {
  Kiosk: 'Everything below kiosk mode applies only while kiosk mode is on.',
  'Kiosk appearance': 'The look of the MDMesh kiosk launcher. Applies only while kiosk mode is on.',
  Network: 'Not managed leaves the device as it is, including a block applied earlier.',
  Restrictions: 'Not managed leaves the device as it is, including a block applied earlier.',
  Enrollment: 'Not sent to devices. The Enroll page can load this Wi-Fi into the enrollment QR so a new device joins it during setup.',
};

export const CONFIG_FIELDS: FieldDef[] = [
  // ── Identity ──────────────────────────────────────────────────────────────
  { key: 'name', label: 'Name', type: 'text', group: 'Identity', metadata: true, help: 'Unique name for this configuration template.' },
  { key: 'description', label: 'Description', type: 'textarea', group: 'Identity', metadata: true, help: 'Optional notes about what this template is for.' },

  // ── Apps ──────────────────────────────────────────────────────────────────
  { key: 'mainAppId', label: 'Main app', type: 'app', group: 'Apps', enforced: true, kioskOnly: true, help: 'The app the kiosk opens. It is pinned full-screen when it is the only app shown in kiosk.' },
  { key: 'contentAppId', label: 'Content app', type: 'app', group: 'Apps', help: 'App the Headwind launcher showed instead of its icon grid.' },
  { key: 'autostartForeground', label: 'Keep apps foreground', type: 'tri', group: 'Apps', help: 'Headwind launcher kept auto-started apps in the foreground.' },

  // ── Kiosk ─────────────────────────────────────────────────────────────────
  { key: 'kioskMode', label: 'Kiosk mode', type: 'switch', group: 'Kiosk', enforced: true, help: 'Lock the device to the main app, or to the apps shown in kiosk.' },
  { key: 'kioskExit', label: 'Kiosk exit', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, flagLabels: ['Hidden gesture', 'Visible button'], help: 'How to reach the exit PIN prompt: 7 taps in the top-right corner, or a visible exit button.' },
  { key: 'password', label: 'Kiosk exit PIN', type: 'password', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Asked for when someone leaves kiosk mode on the device. Stored as entered.' },
  { key: 'kioskHome', label: 'Home button', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the Home button in kiosk.' },
  { key: 'kioskRecents', label: 'Recents button', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the Recent-apps button in kiosk.' },
  { key: 'kioskNotifications', label: 'Notifications', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the notification shade in kiosk.' },
  { key: 'kioskSystemInfo', label: 'System info', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Show the status bar (time, battery, signal) in kiosk.' },
  { key: 'kioskKeyguard', label: 'Lock screen', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the lock screen in kiosk.' },
  { key: 'kioskLockButtons', label: 'Lock hardware buttons', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: LOCK_BUTTONS_HELP },
  { key: 'kioskLockVolume', label: 'Lock volume', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: LOCK_VOLUME_HELP },
  { key: 'runDefaultLauncher', label: 'Allow stock launcher', type: 'tri', group: 'Kiosk', help: 'Headwind launcher did not make itself the Home app. MDMesh only takes Home in kiosk.' },
  { key: 'kioskScreenOn', label: 'Keep screen on', type: 'tri', group: 'Kiosk', help: 'Headwind launcher kept the screen awake in kiosk.' },
  { key: 'showWifi', label: 'Show Wi-Fi on error', type: 'tri', group: 'Kiosk', help: 'Headwind launcher showed Wi-Fi settings when it could not reach the server.' },

  // ── Kiosk appearance ──────────────────────────────────────────────────────
  { key: 'backgroundColor', label: 'Background color', type: 'color', group: 'Kiosk appearance', enforced: true, kioskOnly: true, help: 'Kiosk launcher background.' },
  { key: 'textColor', label: 'Text color', type: 'color', group: 'Kiosk appearance', enforced: true, kioskOnly: true, help: 'Kiosk launcher text.' },
  { key: 'iconSize', label: 'Icon size', type: 'enum', group: 'Kiosk appearance', enforced: true, kioskOnly: true, help: 'App icon size in the kiosk launcher.', options: [
    { value: 'SMALL', label: 'Small' }, { value: 'MEDIUM', label: 'Medium' }, { value: 'LARGE', label: 'Large' },
  ] },

  // ── Network ───────────────────────────────────────────────────────────────
  { key: 'wifi', label: 'Wi-Fi settings', type: 'tri', group: 'Network', enforced: true, segments: ALLOW_BLOCK, help: WIFI_HELP },
  { key: 'wifiRadio', label: 'Wi-Fi radio', type: 'tri', group: 'Network', enforced: true, segments: DEFAULT_SEGMENTS, help: WIFI_RADIO_HELP },
  { key: 'bluetooth', label: 'Bluetooth', type: 'tri', group: 'Network', enforced: true, segments: ALLOW_BLOCK, help: 'Block turns Bluetooth off and stops users turning it on. Allow lifts the block (it does not turn Bluetooth on).' },
  { key: 'requestUpdates', label: 'Location reporting', type: 'enum', group: 'Network', enforced: true, help: LOCATION_HELP, options: [
    { value: 'DONOTTRACK', label: 'Do not track' }, { value: 'WIFI', label: 'Last known (passive)' }, { value: 'GPS', label: 'GPS (fresh fix)' },
  ] },
  { key: 'mobileData', label: 'Mobile data', type: 'tri', group: 'Network', help: 'Headwind launcher switched mobile data (needed system-app rights).' },
  { key: 'gps', label: 'GPS / location', type: 'tri', group: 'Network', help: 'Headwind launcher switched the location setting.' },
  { key: 'mobileEnrollment', label: 'Enroll over mobile data', type: 'switch', group: 'Network', help: "Read only by Headwind's server QR. The MDMesh enrollment QR ignores it." },

  // ── Restrictions ──────────────────────────────────────────────────────────
  { key: 'disableSafeBoot', label: 'Safe Mode', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK_INVERTED, help: 'Block prevents rebooting into Safe Mode, which would bypass the kiosk.' },
  { key: 'disableFactoryReset', label: 'Factory reset', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK_INVERTED, help: 'Block prevents a factory reset from Settings.' },
  { key: 'disableScreenshots', label: 'Screenshots', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK_INVERTED, help: 'Block prevents screenshots and screen recording.' },
  { key: 'usbStorage', label: 'USB storage', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK, help: 'Block stops USB file transfer and mounting external storage.' },
  { key: 'blockStatusBar', label: 'Lock status bar', type: 'switch', group: 'Restrictions', help: 'Headwind launcher covered the status bar.' },
  { key: 'lockVolume', label: 'Lock volume', type: 'tri', group: 'Restrictions', help: 'Headwind launcher blocked volume changes.' },
  { key: 'allowedClasses', label: 'Allowed app classes', type: 'text', group: 'Restrictions', help: 'Headwind launcher allowlist of settings screens.' },
  { key: 'restrictions', label: 'Android restrictions', type: 'textarea', group: 'Restrictions', help: 'Comma-separated Android user restrictions the Headwind launcher applied.' },

  // ── Enrollment (provisioning Wi-Fi for the console's QR) ───────────────────
  { key: 'wifiSSID', label: 'Setup Wi-Fi name (SSID)', type: 'text', group: 'Enrollment', enrollQr: true, help: 'Wi-Fi network a new device joins during setup.' },
  { key: 'wifiPassword', label: 'Setup Wi-Fi password', type: 'password', group: 'Enrollment', enrollQr: true, help: 'Embedded in the enrollment QR in plain text.' },
  { key: 'wifiSecurityType', label: 'Setup Wi-Fi security', type: 'enum', group: 'Enrollment', enrollQr: true, help: 'Security type of the setup Wi-Fi network.', options: [
    { value: 'NONE', label: 'Open' }, { value: 'WPA', label: 'WPA/WPA2' }, { value: 'WEP', label: 'WEP' }, { value: 'EAP', label: 'Enterprise (EAP)' },
  ] },

  // ── Security ──────────────────────────────────────────────────────────────
  { key: 'appPermissions', label: 'App permissions', type: 'enum', group: 'Security', help: 'How the Headwind launcher handled runtime permissions for managed apps.', options: [
    { value: 'GRANTALL', label: 'Grant all automatically' }, { value: 'ASKLOCATION', label: 'Ask for location only' },
    { value: 'DENYLOCATION', label: 'Deny location' }, { value: 'ASKALL', label: 'Ask for everything' },
  ] },
  { key: 'passwordMode', label: 'Passcode quality', type: 'text', group: 'Security', help: 'Headwind launcher passcode rule: present, easy, moderate or strong.' },
  { key: 'encryptDevice', label: 'Require encryption', type: 'switch', group: 'Security', help: 'Headwind launcher asked for encryption. Android 10 and later always encrypt.' },
  { key: 'permissive', label: 'Permissive mode', type: 'tri', group: 'Security', help: 'Headwind launcher mode that locked nothing down.' },
  { key: 'lockSafeSettings', label: 'Lock safe settings', type: 'tri', group: 'Security', help: 'Old Headwind launcher setting. To block Safe Mode, use Restrictions → Safe Mode.' },
  { key: 'disableLocation', label: 'Block location permission', type: 'tri', group: 'Security', help: 'Never read, even by the Headwind launcher.' },

  // ── Display ───────────────────────────────────────────────────────────────
  { key: 'autoBrightness', label: 'Auto brightness', type: 'tri', group: 'Display', help: 'Headwind launcher brightness mode.' },
  { key: 'brightness', label: 'Brightness', type: 'int', group: 'Display', min: 0, max: 255, help: 'Manual brightness 0–255 (when auto brightness is off).' },
  { key: 'manageTimeout', label: 'Manage screen timeout', type: 'tri', group: 'Display', help: 'Headwind launcher set the screen-off timeout.' },
  { key: 'timeout', label: 'Screen timeout (s)', type: 'int', group: 'Display', min: 0, help: 'Seconds before the screen turns off (when managed).' },
  { key: 'manageVolume', label: 'Manage volume', type: 'tri', group: 'Display', help: 'Headwind launcher set the system volume.' },
  { key: 'volume', label: 'Volume (%)', type: 'int', group: 'Display', min: 0, max: 100, help: 'System volume 0–100% (when managed).' },
  { key: 'orientation', label: 'Screen orientation', type: 'enum', group: 'Display', help: 'Headwind launcher locked its own screen orientation.', options: [
    { value: 0, label: 'No lock (auto)' }, { value: 1, label: 'Portrait' }, { value: 2, label: 'Landscape' },
  ] },
  { key: 'useDefaultDesignSettings', label: 'Default launcher design', type: 'switch', group: 'Display', help: 'Headwind launcher ignored its custom colors. The MDMesh kiosk always uses Kiosk appearance.' },
  { key: 'backgroundImageUrl', label: 'Background image URL', type: 'text', group: 'Display', help: 'Headwind launcher wallpaper.' },
  { key: 'desktopHeader', label: 'Launcher header', type: 'enum', group: 'Display', help: 'Header line above the Headwind launcher.', options: [
    { value: 'NO_HEADER', label: 'None' }, { value: 'DEVICE_ID', label: 'Device ID' }, { value: 'DESCRIPTION', label: 'Description' },
    { value: 'CUSTOM1', label: 'Custom 1' }, { value: 'CUSTOM2', label: 'Custom 2' }, { value: 'CUSTOM3', label: 'Custom 3' }, { value: 'TEMPLATE', label: 'Template' },
  ] },
  { key: 'desktopHeaderTemplate', label: 'Header template', type: 'text', group: 'Display', help: 'Custom header text (when header = Template).' },
  { key: 'displayStatus', label: 'Show status bar', type: 'switch', group: 'Display', help: 'Headwind launcher status strip (battery, time).' },

  // ── Updates ───────────────────────────────────────────────────────────────
  { key: 'systemUpdateType', label: 'System updates', type: 'enum', group: 'Updates', help: 'When the Headwind launcher let Android OS updates install.', options: [
    { value: 0, label: 'Default' }, { value: 1, label: 'Immediately' }, { value: 2, label: 'Scheduled' }, { value: 3, label: 'Postponed' },
  ] },
  { key: 'systemUpdateFrom', label: 'System update from', type: 'time', group: 'Updates', help: 'Start of the system-update window (HH:MM, when Scheduled).' },
  { key: 'systemUpdateTo', label: 'System update to', type: 'time', group: 'Updates', help: 'End of the system-update window (HH:MM, when Scheduled).' },
  { key: 'scheduleAppUpdate', label: 'Schedule app updates', type: 'switch', group: 'Updates', help: 'Headwind launcher installed app updates only within a time window.' },
  { key: 'appUpdateFrom', label: 'App update from', type: 'time', group: 'Updates', help: 'Start of the app-update window (HH:MM).' },
  { key: 'appUpdateTo', label: 'App update to', type: 'time', group: 'Updates', help: 'End of the app-update window (HH:MM).' },
  { key: 'downloadUpdates', label: 'Download updates over', type: 'enum', group: 'Updates', help: 'Which connection Headwind launcher downloads could use.', options: [
    { value: 'UNLIMITED', label: 'Any connection' }, { value: 'LIMITED', label: 'Limited data OK' }, { value: 'WIFI', label: 'Wi-Fi only' },
  ] },

  // ── Advanced ──────────────────────────────────────────────────────────────
  { key: 'timeZone', label: 'Time zone', type: 'text', group: 'Advanced', help: "Olson time zone (e.g. America/New_York) or 'auto', set by the Headwind launcher." },
  { key: 'newServerUrl', label: 'Migrate to server URL', type: 'text', group: 'Advanced', help: 'Headwind launcher moved itself to this server.' },
  { key: 'eventReceivingComponent', label: 'Event receiver', type: 'text', group: 'Advanced', help: 'package/class that received Headwind launcher broadcasts.' },
  { key: 'qrParameters', label: 'Extra QR parameters', type: 'textarea', group: 'Advanced', help: "Extra fields for Headwind's server QR. The MDMesh enrollment QR ignores them." },
  { key: 'adminExtras', label: 'QR admin extras', type: 'textarea', group: 'Advanced', help: "Extra admin-extras entries for Headwind's server QR. The MDMesh enrollment QR ignores them." },
];

export const ENFORCED_FIELDS = CONFIG_FIELDS.filter((f) => f.enforced);
/** Always-visible editor fields: metadata, enforced, and the enrollment-QR Wi-Fi. */
export const PRIMARY_FIELDS = CONFIG_FIELDS.filter((f) => f.enforced || f.metadata || f.enrollQr);
/** Fields only the Headwind launcher read: stored and round-tripped, shown in the collapsed section. */
export const LEGACY_FIELDS = CONFIG_FIELDS.filter((f) => !f.enforced && !f.metadata && !f.enrollQr);
export const ENFORCED_KEYS: ReadonlySet<string> = new Set(ENFORCED_FIELDS.map((f) => f.key));
/** Changing any of these re-enters/exits kiosk on every device of the configuration. */
export const KIOSK_AFFECTING_KEYS: ReadonlySet<string> = new Set([
  'kioskMode', 'mainAppId', 'kioskExit', 'kioskHome', 'kioskRecents', 'kioskNotifications', 'kioskSystemInfo',
  'kioskKeyguard', 'kioskLockButtons', 'kioskLockVolume', 'password', 'backgroundColor', 'textColor', 'iconSize', 'applications',
]);

/**
 * The value a `flag` field gets when it is switched off: a stored null stays null (the agent treats null and
 * false alike, but the config revision does not), anything else becomes false. Pure.
 */
export function flagOffValue(stored: unknown): boolean | null {
  return stored == null ? null : false;
}
