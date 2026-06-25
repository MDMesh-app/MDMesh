// Field schema for the configuration editor. Drives the "Focused" core fields and
// the grouped "Advanced" section, each with a concise explainer. Field keys are the
// exact Configuration JSON properties (see com.hmdm.persistence.domain.Configuration).
//
// type:
//   text/textarea/password/color/time → string input
//   int    → number
//   enum   → <select> from options
//   switch → plain boolean (Java primitive; false default)
//   tri    → nullable Boolean: null=unmanaged · true=on · false=off
//   app    → application id selector (mainAppId / contentAppId)

export type FieldType =
  | 'text' | 'textarea' | 'password' | 'int' | 'enum'
  | 'switch' | 'tri' | 'time' | 'color' | 'app';

export type FieldGroup =
  | 'Identity' | 'Apps' | 'Kiosk' | 'Network'
  | 'Security' | 'Restrictions' | 'Display' | 'Updates' | 'Advanced';

export interface FieldOption {
  value: string | number;
  label: string;
}

export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  group: FieldGroup;
  help: string;
  /** Shown in the always-visible core section. */
  focused?: boolean;
  options?: FieldOption[];
  min?: number;
  max?: number;
}

export const GROUP_ORDER: FieldGroup[] = [
  'Identity', 'Apps', 'Kiosk', 'Network',
  'Security', 'Restrictions', 'Display', 'Updates', 'Advanced',
];

export const CONFIG_FIELDS: FieldDef[] = [
  // ── Identity ──────────────────────────────────────────────────────────────
  { key: 'name', label: 'Name', type: 'text', group: 'Identity', focused: true, help: 'Unique name for this configuration template.' },
  { key: 'description', label: 'Description', type: 'textarea', group: 'Identity', focused: true, help: 'Optional notes about what this template is for.' },

  // ── Apps ──────────────────────────────────────────────────────────────────
  { key: 'mainAppId', label: 'Main app', type: 'app', group: 'Apps', focused: true, help: 'Primary app launched on the device (the kiosk app in kiosk mode).' },
  { key: 'contentAppId', label: 'Content app', type: 'app', group: 'Apps', help: 'Optional app used for content delivery.' },
  { key: 'autostartForeground', label: 'Keep apps foreground', type: 'tri', group: 'Apps', help: 'Hold auto-started apps in the foreground.' },

  // ── Kiosk ───────────────────────────────────────────────────────────────-─
  { key: 'kioskMode', label: 'Kiosk mode', type: 'switch', group: 'Kiosk', focused: true, help: 'Lock the device to the main app (single-app lockdown).' },
  { key: 'runDefaultLauncher', label: 'Allow stock launcher', type: 'tri', group: 'Kiosk', help: 'Permit the default Android launcher instead of the MDM home.' },
  { key: 'kioskExit', label: 'Kiosk exit button', type: 'tri', group: 'Kiosk', help: 'Show a button to leave kiosk mode.' },
  { key: 'kioskHome', label: 'Home button', type: 'tri', group: 'Kiosk', help: 'Allow the Home button while in kiosk.' },
  { key: 'kioskRecents', label: 'Recents button', type: 'tri', group: 'Kiosk', help: 'Allow the Recent-apps button while in kiosk.' },
  { key: 'kioskNotifications', label: 'Notifications', type: 'tri', group: 'Kiosk', help: 'Allow the notification shade while in kiosk.' },
  { key: 'kioskSystemInfo', label: 'System info', type: 'tri', group: 'Kiosk', help: 'Show the status/system-info bar while in kiosk.' },
  { key: 'kioskKeyguard', label: 'Lock screen', type: 'tri', group: 'Kiosk', help: 'Allow the keyguard / lock screen while in kiosk.' },
  { key: 'kioskLockButtons', label: 'Lock hardware buttons', type: 'tri', group: 'Kiosk', help: 'Disable power/volume buttons while in kiosk.' },
  { key: 'kioskScreenOn', label: 'Keep screen on', type: 'tri', group: 'Kiosk', help: 'Force the screen to stay awake while in kiosk.' },
  { key: 'showWifi', label: 'Show Wi-Fi on error', type: 'tri', group: 'Kiosk', help: 'Surface Wi-Fi settings if the device loses connectivity in kiosk.' },

  // ── Network ─────────────────────────────────────────────────────────────-─
  { key: 'wifi', label: 'Wi-Fi', type: 'tri', group: 'Network', focused: true, help: 'Wi-Fi radio: unmanaged, force on, or force off.' },
  { key: 'mobileData', label: 'Mobile data', type: 'tri', group: 'Network', focused: true, help: 'Mobile data: unmanaged, on, or off.' },
  { key: 'bluetooth', label: 'Bluetooth', type: 'tri', group: 'Network', focused: true, help: 'Bluetooth radio: unmanaged, on, or off.' },
  { key: 'gps', label: 'GPS / location', type: 'tri', group: 'Network', focused: true, help: 'Location radio: unmanaged, on, or off.' },
  { key: 'requestUpdates', label: 'Location reporting', type: 'enum', group: 'Network', help: 'How the device reports its location back to the server.', options: [
    { value: 'DONOTTRACK', label: 'Do not track' }, { value: 'GPS', label: 'GPS' }, { value: 'WIFI', label: 'Network (Wi-Fi/cell)' },
  ] },
  { key: 'wifiSSID', label: 'Provision Wi-Fi SSID', type: 'text', group: 'Network', help: 'Wi-Fi network to auto-join during enrollment.' },
  { key: 'wifiPassword', label: 'Provision Wi-Fi password', type: 'password', group: 'Network', help: 'Password for the provisioning Wi-Fi network.' },
  { key: 'wifiSecurityType', label: 'Provision Wi-Fi security', type: 'enum', group: 'Network', help: 'Security type of the provisioning Wi-Fi network.', options: [
    { value: 'NONE', label: 'Open' }, { value: 'WPA', label: 'WPA/WPA2' }, { value: 'WEP', label: 'WEP' }, { value: 'EAP', label: 'Enterprise (EAP)' },
  ] },
  { key: 'mobileEnrollment', label: 'Enroll over mobile data', type: 'switch', group: 'Network', help: 'Prefer mobile data over Wi-Fi during provisioning.' },

  // ── Security ───────────────────────────────────────────────────────────-─
  { key: 'password', label: 'Admin password', type: 'password', group: 'Security', help: 'Password to unlock MDM settings on the device (sent plain, hashed server-side).' },
  { key: 'appPermissions', label: 'App permissions', type: 'enum', group: 'Security', help: 'How runtime permissions are handled for managed apps.', options: [
    { value: 'GRANTALL', label: 'Grant all automatically' }, { value: 'ASKLOCATION', label: 'Ask for location only' },
    { value: 'DENYLOCATION', label: 'Deny location' }, { value: 'ASKALL', label: 'Ask for everything' },
  ] },
  { key: 'encryptDevice', label: 'Require encryption', type: 'switch', group: 'Security', help: 'Require full-device encryption.' },
  { key: 'permissive', label: 'Permissive mode', type: 'tri', group: 'Security', help: 'Relax policy enforcement (permissive mode).' },
  { key: 'lockSafeSettings', label: 'Lock safe-mode settings', type: 'tri', group: 'Security', help: 'Block access to settings that could bypass the MDM.' },
  { key: 'disableLocation', label: 'Block location permission', type: 'tri', group: 'Security', help: 'Prevent apps from being granted location permission.' },
  { key: 'passwordMode', label: 'Password policy', type: 'text', group: 'Security', help: 'Device passcode policy (advanced; JSON string).' },

  // ── Restrictions ─────────────────────────────────────────────────────────
  { key: 'usbStorage', label: 'USB storage', type: 'tri', group: 'Restrictions', focused: true, help: 'Allow access to USB mass storage.' },
  { key: 'blockStatusBar', label: 'Lock status bar', type: 'switch', group: 'Restrictions', help: 'Prevent pulling down the system status bar.' },
  { key: 'disableScreenshots', label: 'Block screenshots', type: 'tri', group: 'Restrictions', help: 'Prevent screenshots and screen recording.' },
  { key: 'lockVolume', label: 'Lock volume', type: 'tri', group: 'Restrictions', help: 'Disable the volume buttons.' },
  { key: 'allowedClasses', label: 'Allowed app classes', type: 'text', group: 'Restrictions', help: 'Comma-separated list of permitted app component classes.' },
  { key: 'restrictions', label: 'Android restrictions', type: 'textarea', group: 'Restrictions', help: 'Comma-separated Android user restrictions to apply in MDM mode.' },

  // ── Display ────────────────────────────────────────────────────────────-─
  { key: 'autoBrightness', label: 'Auto brightness', type: 'tri', group: 'Display', help: 'Manage automatic screen brightness.' },
  { key: 'brightness', label: 'Brightness', type: 'int', group: 'Display', min: 0, max: 255, help: 'Manual brightness 0–255 (when auto-brightness is off).' },
  { key: 'manageTimeout', label: 'Manage screen timeout', type: 'tri', group: 'Display', help: 'Control the screen-off timeout.' },
  { key: 'timeout', label: 'Screen timeout (s)', type: 'int', group: 'Display', min: 0, help: 'Seconds before the screen turns off (when managed).' },
  { key: 'manageVolume', label: 'Manage volume', type: 'tri', group: 'Display', help: 'Control the system volume level.' },
  { key: 'volume', label: 'Volume (%)', type: 'int', group: 'Display', min: 0, max: 100, help: 'System volume 0–100% (when managed).' },
  { key: 'orientation', label: 'Screen orientation', type: 'enum', group: 'Display', help: 'Lock the screen orientation.', options: [
    { value: 0, label: 'No lock (auto)' }, { value: 1, label: 'Portrait' }, { value: 2, label: 'Landscape' },
  ] },
  { key: 'useDefaultDesignSettings', label: 'Default launcher design', type: 'switch', group: 'Display', help: 'Use the stock launcher look (ignore the custom colors below).' },
  { key: 'backgroundColor', label: 'Background color', type: 'color', group: 'Display', help: 'Launcher background color.' },
  { key: 'textColor', label: 'Text color', type: 'color', group: 'Display', help: 'Launcher text color.' },
  { key: 'backgroundImageUrl', label: 'Background image URL', type: 'text', group: 'Display', help: 'URL of a custom launcher background image.' },
  { key: 'iconSize', label: 'Icon size', type: 'enum', group: 'Display', help: 'App icon size on the launcher.', options: [
    { value: 'SMALL', label: 'Small' }, { value: 'MEDIUM', label: 'Medium' }, { value: 'LARGE', label: 'Large' },
  ] },
  { key: 'desktopHeader', label: 'Launcher header', type: 'enum', group: 'Display', help: 'What to show in the header above the launcher.', options: [
    { value: 'NO_HEADER', label: 'None' }, { value: 'DEVICE_ID', label: 'Device ID' }, { value: 'DESCRIPTION', label: 'Description' },
    { value: 'CUSTOM1', label: 'Custom 1' }, { value: 'CUSTOM2', label: 'Custom 2' }, { value: 'CUSTOM3', label: 'Custom 3' }, { value: 'TEMPLATE', label: 'Template' },
  ] },
  { key: 'desktopHeaderTemplate', label: 'Header template', type: 'text', group: 'Display', help: 'Custom header text (when header = Template).' },
  { key: 'displayStatus', label: 'Show status bar', type: 'switch', group: 'Display', help: 'Show device status (battery, time) in the launcher.' },

  // ── Updates ───────────────────────────────────────────────────────────-──
  { key: 'systemUpdateType', label: 'System updates', type: 'enum', group: 'Updates', help: 'When Android OS updates install.', options: [
    { value: 0, label: 'Default' }, { value: 1, label: 'Immediately' }, { value: 2, label: 'Scheduled' }, { value: 3, label: 'Postponed' },
  ] },
  { key: 'systemUpdateFrom', label: 'System update from', type: 'time', group: 'Updates', help: 'Start of the system-update window (HH:MM, when Scheduled).' },
  { key: 'systemUpdateTo', label: 'System update to', type: 'time', group: 'Updates', help: 'End of the system-update window (HH:MM, when Scheduled).' },
  { key: 'scheduleAppUpdate', label: 'Schedule app updates', type: 'switch', group: 'Updates', help: 'Only install app updates within a time window.' },
  { key: 'appUpdateFrom', label: 'App update from', type: 'time', group: 'Updates', help: 'Start of the app-update window (HH:MM).' },
  { key: 'appUpdateTo', label: 'App update to', type: 'time', group: 'Updates', help: 'End of the app-update window (HH:MM).' },
  { key: 'downloadUpdates', label: 'Download updates over', type: 'enum', group: 'Updates', help: 'Which connection app/system downloads may use.', options: [
    { value: 'UNLIMITED', label: 'Any connection' }, { value: 'LIMITED', label: 'Limited data OK' }, { value: 'WIFI', label: 'Wi-Fi only' },
  ] },

  // ── Advanced / Other ───────────────────────────────────────────────────-─
  { key: 'timeZone', label: 'Time zone', type: 'text', group: 'Advanced', help: "Olson time zone (e.g. America/New_York) or 'auto'." },
  { key: 'newServerUrl', label: 'Migrate to server URL', type: 'text', group: 'Advanced', help: 'Move devices to a different MDM server URL.' },
  { key: 'eventReceivingComponent', label: 'Event receiver', type: 'text', group: 'Advanced', help: 'package/class that receives MDM broadcasts.' },
  { key: 'qrParameters', label: 'Extra QR parameters', type: 'textarea', group: 'Advanced', help: 'Additional fields injected into the provisioning QR.' },
  { key: 'adminExtras', label: 'QR admin extras', type: 'textarea', group: 'Advanced', help: 'Extra entries for the QR admin bundle.' },
];

export const FOCUSED_FIELDS = CONFIG_FIELDS.filter((f) => f.focused);
export const ADVANCED_FIELDS = CONFIG_FIELDS.filter((f) => !f.focused);
