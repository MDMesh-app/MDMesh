import type { Requirement } from '../types';

/** Every device write (CORE_ACTION_ACCESS below); Enroll is one, as minting the code is the page. */
const EDIT_DEVICES = { permission: 'edit_devices' } as const satisfies Requirement;

/**
 * The server permission each core page needs (the console hides what the role cannot use). The
 * permission sits on the page's route; its sidebar item inherits it (ext/registry.ts linkNavItems). Each entry is
 * what the page's main REST calls are refused without:
 *
 * - Overview `/`: nothing. Its reads are open to every user of the customer.
 * - Devices, `/devices/:id`: nothing. DeviceResource search and lookup are open (limited to the user's device
 *   groups); each change checks edit_devices itself.
 * - Configurations and the editor: `configurations` (ConfigurationResource GET /search and /{id}).
 * - Apps: `applications` (ApplicationResource GET /search).
 * - Enroll: `edit_devices` (AgentAdminResource POST /agent/v1/token: minting the code is the page).
 * - Settings: nothing. Account, Server and Appearance read nothing guarded; the Updates status (supervisor
 *   GET /update/status) and the Rollout reads are open. Its changes check for themselves: the rollout's need
 *   edit_devices and the whole fleet. Checking for, installing and scheduling updates follow no permission but
 *   v0.3.8's deploymentAdmin rule (a super-admin, or the org admin of a single-customer install), which the supervisor
 *   asks the server about; the console reads it with auth/useMayManageDeployment.
 *
 * The open pages also make optional reads that are guarded, and get less without them, but still work: Overview
 * reads the app library (`applications`) for app names, and shows package names instead; Devices reads the
 * configuration list (`configurations`) for its filter, and says it could not load it; Settings reads it for the
 * default-configuration picker; the device page reads the device's configuration (`configurations`) and the
 * library (`applications`) for its kiosk and lock details. They stay open on purpose: their main data needs
 * nothing. Check this list again when a page gains a read.
 *
 * Headwind's seeded roles (server db.changelog.xml) then see: Super-admin and Admin (2) everything, as before;
 * User (3) everything (it holds configurations, applications and edit_devices); Observer (100, applications and
 * files only) Overview, Devices, Apps and Settings.
 */
export const CORE_PAGE_ACCESS = {
  configurations: { permission: 'configurations' },
  apps: { permission: 'applications' },
  enroll: EDIT_DEVICES,
} as const satisfies Record<string, Requirement>;

/**
 * The server permission core's actions need (the console hides a button the server would refuse, and keeps every
 * read). Each entry is the check of the endpoint behind it.
 */
export const CORE_ACTION_ACCESS = {
  /**
   * Every device write. AgentAdminResource (canEditDevices) checks it for a command and a bulk command (every remote
   * action, kiosk enter and exit, power and location modes, Deploy from Apps), Sync now (POST /sync), Install config
   * apps (POST /syncApps) and the enrollment code; DeviceResource for moving devices to a configuration
   * (PUT /devices), deleting them (POST /deleteBulk, DELETE /{id}) and group changes. So it hides: the device page's
   * quick actions, its Control tab's remote actions and "Retry apply"; the Devices list's Sync, row menu, bulk
   * actions and selection; the Overview's Sync now; pushing to devices from Deploy in Apps; and the links to Enroll.
   */
  editDevices: EDIT_DEVICES,
  /**
   * Settings' rollout buttons. RolloutResource checks edit_devices for starting, promoting, finishing and cancelling
   * an agent rollout, and then canReachWholeFleet: a user limited to some device groups may not, as the one active
   * rollout is the whole fleet's.
   */
  rollout: { ...EDIT_DEVICES, when: ({ user }) => user.allDevices },
  /**
   * The device name's pencil: DeviceResource POST /{id}/description checks edit_device_desc (Observer lost it in
   * 26.10.13).
   */
  renameDevice: { permission: 'edit_device_desc' },
  /** The library's "Remove from library…": ApplicationResource DELETE /{id} checks edit_applications. */
  removeApp: { permission: 'edit_applications' },
  /**
   * Deploy's "Add to a configuration": ApplicationResource POST /configurations checks edit_applications. Its picker
   * lists ConfigurationResource GET /list, which checks nothing. Deploy's other mode, pushing to devices, is
   * `editDevices`; Deploy shows when either passes.
   */
  addToConfiguration: { permission: 'edit_applications' },
  /**
   * "SHA-256 checksum…" and the missing-checksum notice's "Add checksum": ApplicationResource
   * PUT /versions/{id}/sha256 checks edit_application_versions.
   */
  setAppChecksum: { permission: 'edit_application_versions' },
  /**
   * "Add to library" on F-Droid and Custom APK (and the library entry Custom APK makes after an upload):
   * ApplicationResource PUT /android checks edit_applications. Apps shows those two tabs to a role that passes this
   * or Deploy: without, the tab leads to neither the library nor a device.
   */
  addApp: { permission: 'edit_applications' },
  /**
   * Custom APK's "Upload a file": FilesResource POST /web-ui-files and /bundle, and POST /update that hosts the
   * upload, check edit_files.
   */
  uploadFile: { permission: 'edit_files' },
  /**
   * "New configuration", blank or from a template, and the editor at /configurations/new (and ?from=), which without
   * it says "You don't have access": ConfigurationResource PUT / with no id (a new configuration) checks add_config.
   * Saving an existing one needs only `configurations`, the page's own permission, and access to that configuration.
   */
  addConfig: { permission: 'add_config' },
  /**
   * Duplicate and Delete on the Configurations list and in the editor: ConfigurationResource PUT /copy and
   * DELETE /{id} both check copy_config. Duplicate has no route of its own: it opens the copy it made.
   */
  copyConfig: { permission: 'copy_config' },
} as const satisfies Record<string, Requirement>;
