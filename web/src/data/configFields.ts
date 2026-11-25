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
  /** One line, at most 80 characters. */
  help: string;
  /** The longer explanation, behind "Learn more". */
  more?: string;
  /** The agent version a setting needs, shown as a tag ("Agent 0.3.4+"); older agents ignore it. */
  agent?: string;
  /** Applied on devices by the MDMesh agent via config.apply. Unset = Headwind launcher field, not applied. */
  enforced?: boolean;
  /** Takes effect only while kiosk mode is on. */
  kioskOnly?: boolean;
  /** Not sent to devices, but read by the console's enrollment QR (provisioning Wi-Fi). */
  enrollQr?: boolean;
  /**
   * Not a column of its own: the setting is the Extra QR parameters entry of this name (without its
   * `android.app.extra.` prefix), read from and written to `qrParameters`.
   */
  qrKey?: string;
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

// Behaviour that depends on the agent version (agent 0.3.4 / protocol 1.4): `help` is one line, `more` the detail.
const WIFI_RADIO_MORE = 'Off is skipped (shown as deferred) while Wi-Fi is the only network, so the device stays reachable, and is tried again at the next configuration change, reboot or agent update. Phones that switch mobile data off while on Wi-Fi cannot apply it. If the device cannot reach the server for about 30 minutes after Wi-Fi was turned off (SIM removed, server only reachable on the Wi-Fi network), the agent turns Wi-Fi back on and keeps it on until the configuration is changed; Off then shows as deferred. A longer server outage, or a user cutting the device off (airplane mode, pulling the SIM), does the same. Any change to the configuration applies Off again. Not managed lifts the lock and leaves the radio as it is.';
const CONNECTIVITY_MORE = 'Older agents ignore it. Battery-saver holds the live connection only while the screen is on or the device is charging, so a command sent to a locked phone on battery waits for the next heartbeat (about 10 minutes, longer in deep Doze) or for the screen to come on. Always-on keeps the connection up 24/7, so commands such as Lock, Ring or Wipe reach a locked phone without waiting for the heartbeat, at a battery cost. The mode set here is applied again when the configuration changes, the device restarts or the agent updates, so a mode set on a device page lasts only until then. To keep a lost device on Always-on, set its configuration to Always-on (or move the device to one that is). To switch devices back, choose Battery-saver; — (not managed) leaves each device as it is.';
const LOCATION_MORE = 'Do not track collects no location with agent 0.3.4 or later, and the server drops any location an older agent still reports. Last known (passive) reports the location the device already has at each check-in; GPS (fresh fix) asks for a new fix each time (more accurate, more battery).';
const LOCATION_SERVICES_MORE = 'Needs an Android 11+ Device Owner device; older agents report it unsupported. While managed, the user cannot change the setting. Not managed restores the user setting control but leaves Location services in their current state. This is separate from Location reporting, which controls what MDMesh collects.';

export const GROUP_ORDER: FieldGroup[] = [
  'Identity', 'Apps', 'Kiosk', 'Kiosk appearance', 'Network', 'Restrictions', 'Enrollment',
  'Security', 'Display', 'Updates', 'Advanced',
];

/** One-line notes under a section heading of the main editor. */
export const GROUP_NOTES: Partial<Record<FieldGroup, string>> = {
  Kiosk: 'Everything below kiosk mode applies only while kiosk mode is on.',
  'Kiosk appearance': 'The look of the MDMesh kiosk launcher, while kiosk mode is on.',
  Network: 'Not managed leaves a setting as it is, except Wi-Fi radio and Location services: Not managed lifts their lock.',
  Restrictions:
    'Not managed leaves a setting as it is, except Camera (agent 0.5.0+: it restores access after an MDMesh block) and Safe Mode (see its setting).',
  Enrollment: 'Not sent to devices: the Enroll page puts these in the enrollment QR.',
};

/** Languages offered for the enrollment QR's PROVISIONING_LOCALE (Android's language_COUNTRY form). */
const QR_LOCALES: FieldOption[] = [
  ['de_DE', 'Deutsch (Deutschland)'], ['en_GB', 'English (UK)'], ['en_US', 'English (US)'], ['es_ES', 'Español (España)'],
  ['fr_BE', 'Français (Belgique)'], ['fr_CA', 'Français (Canada)'], ['fr_CH', 'Français (Suisse)'], ['fr_FR', 'Français (France)'],
  ['it_IT', 'Italiano (Italia)'], ['nl_NL', 'Nederlands (Nederland)'], ['pl_PL', 'Polski (Polska)'], ['pt_BR', 'Português (Brasil)'],
  ['pt_PT', 'Português (Portugal)'],
].map(([value, label]) => ({ value, label: `${label} · ${value}` }));

/** Time zones offered for the enrollment QR's PROVISIONING_TIME_ZONE: the browser's IANA list, else a short one. */
const QR_TIME_ZONES: FieldOption[] = (() => {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  let zones: string[] = [];
  try {
    zones = intl.supportedValuesOf?.('timeZone') ?? [];
  } catch {
    zones = [];
  }
  if (zones.length === 0) zones = ['UTC', 'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'America/New_York', 'America/Los_Angeles'];
  return zones.map((z) => ({ value: z, label: z }));
})();

export const CONFIG_FIELDS: FieldDef[] = [
  // ── Identity ──────────────────────────────────────────────────────────────
  { key: 'name', label: 'Name', type: 'text', group: 'Identity', metadata: true, help: 'Unique name for this configuration template.' },
  { key: 'description', label: 'Description', type: 'textarea', group: 'Identity', metadata: true, help: 'Optional notes about what this template is for.' },

  // ── Apps ──────────────────────────────────────────────────────────────────
  { key: 'mainAppId', label: 'Main app', type: 'app', group: 'Apps', enforced: true, kioskOnly: true, help: 'The app the kiosk opens, pinned full-screen when it is the only kiosk app.' },
  { key: 'contentAppId', label: 'Content app', type: 'app', group: 'Apps', help: 'App the Headwind launcher showed instead of its icon grid.' },
  { key: 'autostartForeground', label: 'Keep apps foreground', type: 'tri', group: 'Apps', help: 'Headwind launcher kept auto-started apps in the foreground.' },

  // ── Kiosk ─────────────────────────────────────────────────────────────────
  { key: 'kioskMode', label: 'Kiosk mode', type: 'switch', group: 'Kiosk', enforced: true, help: 'Lock the device to the main app, or to the apps shown in kiosk.' },
  { key: 'kioskExit', label: 'Kiosk exit', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, flagLabels: ['Hidden gesture', 'Visible button'], help: 'How to reach the exit PIN prompt: a visible button, or 7 taps top-right.' },
  { key: 'password', label: 'Kiosk exit PIN', type: 'password', group: 'Kiosk', enforced: true, kioskOnly: true, help: '6 to 12 digits, asked for when someone leaves kiosk on the device.', more: 'Leave it empty and the device has no exit (end kiosk from the console; agents before 0.3.6 let anyone leave). With agent 0.5.0, 5 wrong PINs lock the exit for 1 minute, each further 3 for longer up to 1 hour, then every wrong PIN for another hour; Clear PIN lockout on the device page ends a lockout, and so does a new PIN. Administrators get an email if the server can send email (SMTP).' },
  { key: 'kioskHome', label: 'Home button', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the Home button in kiosk.' },
  { key: 'kioskRecents', label: 'Recents button', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the Recent-apps button in kiosk. Needs Home button on.' },
  { key: 'kioskNotifications', label: 'Notifications', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the notification shade in kiosk. Needs Home button on.' },
  { key: 'kioskSystemInfo', label: 'System info', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Show the status bar (time, battery, signal) in kiosk.' },
  { key: 'kioskKeyguard', label: 'Lock screen', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Allow the lock screen in kiosk.' },
  { key: 'kioskLockButtons', label: 'Lock hardware buttons', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Block the power menu in kiosk. Volume buttons have their own setting.' },
  { key: 'kioskLockVolume', label: 'Lock volume buttons (kiosk)', type: 'flag', group: 'Kiosk', enforced: true, kioskOnly: true, help: 'Blocks volume changes in kiosk. Android mutes the device while locked.', more: 'Ring is silent while volume is locked; the lock is lifted when kiosk ends.', agent: '0.3.4' },
  { key: 'runDefaultLauncher', label: 'Allow stock launcher', type: 'tri', group: 'Kiosk', help: 'Headwind launcher did not make itself Home. MDMesh takes Home in kiosk only.' },
  { key: 'kioskScreenOn', label: 'Keep screen on', type: 'tri', group: 'Kiosk', help: 'Headwind launcher kept the screen awake in kiosk.' },
  { key: 'showWifi', label: 'Show Wi-Fi on error', type: 'tri', group: 'Kiosk', help: 'Headwind launcher showed Wi-Fi settings when it could not reach the server.' },

  // ── Kiosk appearance ──────────────────────────────────────────────────────
  { key: 'backgroundColor', label: 'Background color', type: 'color', group: 'Kiosk appearance', enforced: true, kioskOnly: true, help: 'Kiosk launcher background. Always solid: transparency is ignored.' },
  { key: 'textColor', label: 'Text color', type: 'color', group: 'Kiosk appearance', enforced: true, kioskOnly: true, help: 'Kiosk launcher text. Needs 4.5:1 contrast with the background color.', more: 'With less, the device uses a readable color instead.' },
  { key: 'iconSize', label: 'Icon size', type: 'enum', group: 'Kiosk appearance', enforced: true, kioskOnly: true, help: 'App icon size in the kiosk launcher.', options: [
    { value: 'SMALL', label: 'Small' }, { value: 'MEDIUM', label: 'Medium' }, { value: 'LARGE', label: 'Large' },
  ] },

  // ── Network ───────────────────────────────────────────────────────────────
  { key: 'wifi', label: 'Wi-Fi settings', type: 'tri', group: 'Network', enforced: true, segments: ALLOW_BLOCK, help: 'Block locks the Wi-Fi settings so users can’t change networks.', more: 'It does not turn Wi-Fi off: that is Wi-Fi radio. Allow lifts the lock.' },
  { key: 'wifiRadio', label: 'Wi-Fi radio', type: 'tri', group: 'Network', enforced: true, segments: DEFAULT_SEGMENTS, help: 'Turns Wi-Fi on or off. On Android 13+, Off also stops users turning it on.', more: WIFI_RADIO_MORE, agent: '0.3.4' },
  { key: 'locationServices', label: 'Location services', type: 'tri', group: 'Network', enforced: true, segments: DEFAULT_SEGMENTS, help: 'Turns Android Location services on or off, and locks the setting.', more: LOCATION_SERVICES_MORE, agent: '0.5.0' },
  { key: 'bluetooth', label: 'Bluetooth', type: 'tri', group: 'Network', enforced: true, segments: ALLOW_BLOCK, help: 'Block turns Bluetooth off and stops users turning it on.', more: 'Allow lifts the block (it does not turn Bluetooth on).' },
  { key: 'requestUpdates', label: 'Location reporting', type: 'enum', group: 'Network', enforced: true, help: 'Which location the device reports at each check-in.', more: LOCATION_MORE, options: [
    { value: 'DONOTTRACK', label: 'Do not track' }, { value: 'WIFI', label: 'Last known (passive)' }, { value: 'GPS', label: 'GPS (fresh fix)' },
  ] },
  { key: 'connectivityMode', label: 'Connectivity', type: 'enum', group: 'Network', enforced: true, help: 'How quickly commands reach a locked phone on battery.', more: CONNECTIVITY_MORE, agent: '0.4.0', options: [
    { value: 'adaptive', label: 'Battery-saver' }, { value: 'alwaysOn', label: 'Always-on' },
  ] },
  { key: 'mobileData', label: 'Mobile data', type: 'tri', group: 'Network', help: 'Headwind launcher switched mobile data (needed system-app rights).' },
  { key: 'gps', label: 'GPS / location', type: 'tri', group: 'Network', help: 'Headwind launcher switched the location setting.' },

  // ── Restrictions ──────────────────────────────────────────────────────────
  { key: 'disableSafeBoot', label: 'Safe Mode', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK_INVERTED, help: 'Block prevents rebooting into Safe Mode, which would bypass the kiosk.', more: 'Not managed blocks it on a configuration with kiosk on, and on any device in kiosk (agent 0.3.7 or later); otherwise it is allowed, and agent 0.3.7 lifts a block nothing asks for. Older agents keep a block until this is set to Allow. Allow permits it, even in kiosk.' },
  { key: 'disableFactoryReset', label: 'Factory reset', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK_INVERTED, help: 'Block prevents a factory reset from Settings. A factory reset sent from the console lifts the block first.', more: 'Agents before 0.5.1 wipe without lifting it, and some devices then stop in recovery. Before wiping one of those, set this to Allow and sync.' },
  { key: 'disableScreenshots', label: 'Screenshots', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK_INVERTED, help: 'Block prevents screenshots and screen recording.' },
  { key: 'usbStorage', label: 'USB storage', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK, help: 'Block stops USB file transfer and mounting external storage.' },
  { key: 'camera', label: 'Camera', type: 'tri', group: 'Restrictions', enforced: true, segments: ALLOW_BLOCK, help: 'Block stops every app using the camera hardware.', more: 'It does not hide camera apps, install one, or remove photos. On agent 0.5.0 or later, Not managed restores access after MDMesh blocked it. On older agents, choose Allow to lift a block.' },
  { key: 'blockStatusBar', label: 'Lock status bar', type: 'switch', group: 'Restrictions', help: 'Headwind launcher covered the status bar.' },
  { key: 'lockVolume', label: 'Lock volume (legacy)', type: 'tri', group: 'Restrictions', help: 'Headwind launcher blocked volume changes.' },
  { key: 'allowedClasses', label: 'Allowed app classes', type: 'text', group: 'Restrictions', help: 'Headwind launcher allowlist of settings screens.' },
  { key: 'restrictions', label: 'Android restrictions', type: 'textarea', group: 'Restrictions', help: 'Comma-separated Android user restrictions the Headwind launcher applied.' },

  // ── Enrollment (provisioning Wi-Fi for the console's QR) ───────────────────
  { key: 'wifiSSID', label: 'Setup Wi-Fi name (SSID)', type: 'text', group: 'Enrollment', enrollQr: true, help: 'Wi-Fi network a new device joins during setup.' },
  { key: 'wifiPassword', label: 'Setup Wi-Fi password', type: 'password', group: 'Enrollment', enrollQr: true, help: 'Embedded in the enrollment QR in plain text.' },
  { key: 'wifiSecurityType', label: 'Setup Wi-Fi security', type: 'enum', group: 'Enrollment', enrollQr: true, help: 'Security type of the setup Wi-Fi network.', options: [
    { value: 'NONE', label: 'Open' }, { value: 'WPA', label: 'WPA/WPA2' }, { value: 'WEP', label: 'WEP' }, { value: 'EAP', label: 'Enterprise (EAP)' },
  ] },
  { key: 'qrWifiHidden', qrKey: 'PROVISIONING_WIFI_HIDDEN', label: 'Hidden network', type: 'switch', group: 'Enrollment', enrollQr: true, help: "The setup Wi-Fi doesn't broadcast its name." },
  // Setup screens: entries of the Extra QR parameters, shown as settings of their own.
  { key: 'qrSkipEducation', qrKey: 'PROVISIONING_SKIP_EDUCATION_SCREENS', label: 'Skip intro screens', type: 'switch', group: 'Enrollment', enrollQr: true, help: "Hides Android's education screens during setup. Google's terms still show.", more: 'Android 8 and later. The setup wizard steps that come after enrollment (Google terms, accounts) are up to the device maker and still show.' },
  { key: 'qrLocale', qrKey: 'PROVISIONING_LOCALE', label: 'Language', type: 'enum', group: 'Enrollment', enrollQr: true, help: 'Language and region set during setup.', options: QR_LOCALES },
  { key: 'qrTimeZone', qrKey: 'PROVISIONING_TIME_ZONE', label: 'Time zone', type: 'enum', group: 'Enrollment', enrollQr: true, help: 'Time zone set during setup.', options: QR_TIME_ZONES },
  { key: 'mobileEnrollment', label: 'Enroll over mobile data', type: 'switch', group: 'Enrollment', enrollQr: true, help: 'Lets setup download the agent on mobile data. Android 10 and later.', more: 'Adds PROVISIONING_USE_MOBILE_DATA to the enrollment QR. Without it, Android waits for Wi-Fi before downloading the agent.' },

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
  { key: 'useDefaultDesignSettings', label: 'Default launcher design', type: 'switch', group: 'Display', help: 'Headwind launcher ignored its custom colors. MDMesh uses Kiosk appearance.' },
  { key: 'backgroundImageUrl', label: 'Background image URL', type: 'text', group: 'Display', help: 'Headwind launcher wallpaper. Another URL here replaces the kiosk image.', more: 'With a kiosk background image, this shows its address.' },
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
  { key: 'qrParameters', label: 'Extra QR parameters', type: 'textarea', group: 'Enrollment', enrollQr: true, help: 'Other Android setup entries, as JSON members.', more: 'MDMesh passes on only a few known entries (mobile data, sensors permission opt-out, local time, organization name, support URL), each with a value of its type; the editor says which are left out. For example "android.app.extra.PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT": true. JSON members, as Headwind took them, or one JSON object. The Enroll page adds them to the code it shows for this configuration. Entries naming the agent, its download, its checksum or its enrollment extras, and MDMesh\'s defaults (system apps stay enabled, no encryption step), are never replaced. Wi-Fi entries here are not used: Wi-Fi comes from the setup Wi-Fi above and the Enroll page.' },
  { key: 'adminExtras', label: 'QR admin extras', type: 'textarea', group: 'Advanced', help: 'Extra admin-extras entries for Headwind\'s server QR. MDMesh\'s QR ignores them.' },
];

/** Always-visible editor fields: metadata, enforced, and the enrollment-QR Wi-Fi. */
export const PRIMARY_FIELDS = CONFIG_FIELDS.filter((f) => f.enforced || f.metadata || f.enrollQr);
/** Fields only the Headwind launcher read: stored and round-tripped, shown in the collapsed section. */
export const LEGACY_FIELDS = CONFIG_FIELDS.filter((f) => !f.enforced && !f.metadata && !f.enrollQr);
/** Changing any of these re-enters/exits kiosk on every device of the configuration. */
export const KIOSK_AFFECTING_KEYS: ReadonlySet<string> = new Set([
  'kioskMode', 'mainAppId', 'kioskExit', 'kioskHome', 'kioskRecents', 'kioskNotifications', 'kioskSystemInfo',
  'kioskKeyguard', 'kioskLockButtons', 'kioskLockVolume', 'password', 'backgroundColor', 'backgroundImageFileId', 'textColor', 'iconSize', 'applications',
]);

/**
 * The value a `flag` field gets when it is switched off: a stored null stays null (the agent treats null and
 * false alike, but the config revision does not), anything else becomes false. Pure.
 */
export function flagOffValue(stored: unknown): boolean | null {
  return stored == null ? null : false;
}
