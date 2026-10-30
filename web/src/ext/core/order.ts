// The order values of core's contributions, public as `ORDER` so a fork can place an item between two of ours
// (`ORDER.navItem.devices + 50`) and keep its place when core reorders. Core leaves gaps of 100; on an equal order
// core's item comes first (ext/registry.ts compareEntries, and the list hosts' columns).
export const ORDER = Object.freeze({
  navItem: Object.freeze({
    overview: 100,
    devices: 200,
    configurations: 300,
    apps: 400,
    enroll: 500,
    settings: 600,
  }),
  deviceTab: Object.freeze({
    control: 100,
    telemetry: 200,
    activity: 300,
    location: 400,
  }),
  deviceRailGroup: Object.freeze({
    status: 100,
    location: 200,
    hardware: 300,
    network: 400,
    management: 500,
  }),
  /** The configuration editor's sections. */
  configSection: Object.freeze({
    identity: 100,
    apps: 200,
    kiosk: 300,
    network: 400,
    security: 500,
    enrollment: 600,
    headwind: 700,
  }),
  settingsSection: Object.freeze({
    account: 100,
    server: 200,
    updates: 300,
    agentRollout: 400,
    enrollment: 500,
    appearance: 600,
  }),
  /** The Overview's `main` and `aside` cards. */
  overviewCard: Object.freeze({
    needsAttention: 100,
    byConfiguration: 200,
    recentActivity: 300,
    server: 400,
  }),
  /** The Overview's `stat` tiles, a sequence of their own. */
  overviewStat: Object.freeze({
    sync: 100,
    rollout: 200,
  }),
  /** Core's Devices columns are the page's own (pages/DevicesPage.tsx); these are their places. Actions stays last. */
  devicesColumn: Object.freeze({
    device: 100,
    status: 200,
    configuration: 300,
    android: 400,
    lastSeen: 500,
  }),
  /** Core's Apps library columns are the page's own (pages/AppsLibrary.tsx); these are their places. Actions stays last. */
  appsColumn: Object.freeze({
    app: 100,
    version: 200,
    source: 300,
    checksum: 400,
    usedIn: 500,
  }),
  /** The Devices row menu: commands, then (from `move` on) below a separator, changes to the device record. */
  devicesRowAction: Object.freeze({
    lock: 100,
    ring: 200,
    move: 300,
    delete: 400,
  }),
  devicesBulkAction: Object.freeze({
    run: 100,
    move: 200,
    delete: 300,
  }),
  appsRowAction: Object.freeze({
    checksum: 100,
    remove: 200,
  }),
});
