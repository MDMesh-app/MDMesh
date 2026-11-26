// The data the Policies page shows: policies, devices, and per device its brand, policy status and apps.
//
// With the Policies server plugin, every phone's details come in ONE request (getFleet), whatever the fleet size.
// Without it (plugin not installed, off, or failing), the details are asked per phone, three requests each, a few at
// a time, as before; the page is usable while they arrive. A phone the plugin couldn't give a status for gets just
// that one per-phone call.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { DeviceView } from '@mdmesh/console';
import { invalidateMembership } from './GroupCell';
import {
  cancelJob,
  fleetApps,
  getConfigStatus,
  getDeviceApps,
  getFleet,
  getGroups,
  getHardware,
  listDevices,
  listJobs,
  listPolicies,
  type DeviceApps,
  type DeviceConfigStatus,
  type FleetAnswer,
  type GroupsState,
  type Job,
  type PolicyRow,
} from './api';
import {
  compliance,
  deviceBrand,
  eachLimited,
  findExtras,
  groupForDevice,
  setCustomBrandNames,
  syncState,
  unsupportedSettings,
  type Compliance,
  type DeviceGroup,
  type SyncState,
  type Violation,
} from './model';

/** Per-device requests in flight at once (three per device: telemetry, status, history). */
const IN_FLIGHT = 6;
/** How often running jobs are checked, and for how long after they start. */
const JOB_POLL_MS = 3000;
const JOB_WATCH_MS = 30 * 60 * 1000;

/** A job whose progress is still worth checking: its phones are being woken or are working on its commands. */
export const jobActive = (j: Job, now = Date.now()) =>
  (j.phase === 'sending' || j.phase === 'running') && now - j.createdAt < JOB_WATCH_MS;

export interface DeviceDetail {
  brand?: string;
  brandGuessed?: boolean;
  status?: DeviceConfigStatus | null;
  apps?: DeviceApps | null;
  /** Only with the Policies server plugin: the agent version it reports, and whether it can open apps. */
  agentVersion?: string | null;
  canLaunch?: boolean;
}

export interface FleetDevice {
  device: DeviceView;
  /** Its brand: the one set on it by hand, else the one it reports. */
  brand: string;
  /** The brand it reports (or guessed from its name). */
  reportedBrand: string;
  brandGuessed: boolean;
  /** Its brand was set by hand (Set brand), over the one it reports. */
  brandByHand: boolean;
  /** Whether its brand, status and apps have loaded. */
  loaded: boolean;
  group?: DeviceGroup;
  /** In its group because someone picked it into it, not by its brand. */
  groupByHand: boolean;
  expectedPolicy?: PolicyRow;
  currentPolicy?: PolicyRow;
  sync: SyncState;
  unsupported: string[];
  /** Apps outside its policy's allowed list; [] when the policy checks none, null while its apps are not known. */
  violations: Violation[] | null;
  apps: DeviceApps | null;
  /** The agent version it reports (with the plugin). */
  agentVersion: string | null;
  /** Its agent can open an app on request (app.launch). */
  canLaunch: boolean;
  compliance: Compliance;
}

export interface Fleet {
  policies: PolicyRow[] | null;
  devices: FleetDevice[] | null;
  groups: DeviceGroup[];
  /** Brands made by hand. */
  customBrands: { brand: string; label: string }[];
  /** Whether groups can be used: true with the Policies server plugin, false without it, null while loading. */
  groupsAvailable: boolean | null;
  /** Take the groups state a change answered with. */
  applyGroups(state: GroupsState): void;
  /** Devices whose details have loaded, of all. */
  progress: { done: number; total: number };
  /** Where the details came from: one plugin request, or per-phone requests. */
  source: 'plugin' | 'perPhone' | null;
  error: string | null;
  /** Reload everything. */
  reload(): void;
  /** Reload the policies only (after a save). */
  reloadPolicies(): Promise<void>;
  /** Reload some devices' details (after a command). */
  refreshDevices(ids: readonly number[]): void;
  /** Bulk jobs of the last 24 hours (with the plugin), newest first. */
  jobs: Job[];
  /** Show a job just started, and watch it until it ends. */
  trackJob(job: Job): void;
  /** Stop a job: no more wakes, and its commands no phone has received yet expire. */
  cancelJob(id: number): Promise<void>;
}

/** The packages a policy enforces on its phones: its allowed list while kiosk is on, else null (nothing enforced). */
export function enforcedList(p: PolicyRow): Set<string> | null {
  return p.kiosk && p.allowed?.length ? new Set(p.allowed.map((a) => a.pkg)) : null;
}

export function useFleet(): Fleet {
  const [policies, setPolicies] = useState<PolicyRow[] | null>(null);
  const [rawDevices, setRawDevices] = useState<DeviceView[] | null>(null);
  const [details, setDetails] = useState<Record<number, DeviceDetail>>({});
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [source, setSource] = useState<'plugin' | 'perPhone' | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [jobsTick, setJobsTick] = useState(0);
  const [groupsState, setGroupsState] = useState<GroupsState | null>(null);
  const [groupsAvailable, setGroupsAvailable] = useState<boolean | null>(null);
  const gen = useRef(0);

  const applyGroups = useCallback((state: GroupsState) => {
    setCustomBrandNames(state.brands);
    invalidateMembership();
    setGroupsState(state);
    setGroupsAvailable(true);
  }, []);

  const loadDetail = useCallback(async (d: DeviceView, g: number) => {
    const [hardware, status, apps] = await Promise.all([
      getHardware(d.number).catch(() => null),
      getConfigStatus(d.number).catch(() => null),
      getDeviceApps(d.number).catch(() => null),
    ]);
    if (g !== gen.current) return;
    const { brand, guessed } = deviceBrand(hardware, d.description);
    setDetails((prev) => ({ ...prev, [d.id]: { brand, brandGuessed: guessed, status, apps } }));
  }, []);

  /**
   * Take the plugin's answer for `devices`. Phones it has no status for get core's per-phone status (one request
   * each); phones missing from the answer get the full per-phone load.
   */
  const applyFleet = useCallback(
    (answer: FleetAnswer, devices: readonly DeviceView[], g: number) => {
      const byNumber = new Map(answer.devices.map((p) => [p.number, p]));
      const next: Record<number, DeviceDetail> = {};
      const needStatus: DeviceView[] = [];
      const missing: DeviceView[] = [];
      for (const d of devices) {
        const p = byNumber.get(d.number);
        if (!p) {
          missing.push(d);
          continue;
        }
        const { brand, guessed } = deviceBrand(p.hardware ?? null, d.description);
        next[d.id] = {
          brand,
          brandGuessed: guessed,
          status: p.status ?? null,
          apps: fleetApps(p),
          agentVersion: p.agentVersion ?? null,
          canLaunch: p.canLaunch === true,
        };
        if (!p.status) needStatus.push(d);
      }
      setDetails((prev) => ({ ...prev, ...next }));
      void eachLimited(
        needStatus,
        IN_FLIGHT,
        async (d) => {
          const status = await getConfigStatus(d.number).catch(() => null);
          if (g !== gen.current) return;
          setDetails((prev) => (prev[d.id] ? { ...prev, [d.id]: { ...prev[d.id], status } } : prev));
        },
        () => g !== gen.current,
      );
      void eachLimited(missing, IN_FLIGHT, (dev) => loadDetail(dev, g), () => g !== gen.current);
    },
    [loadDetail],
  );

  useEffect(() => {
    const g = ++gen.current;
    setError(null);
    setDetails({});
    Promise.all([listPolicies(), listDevices(), getFleet(), getGroups()])
      .then(([p, d, answer, groupsAnswer]) => {
        if (g !== gen.current) return;
        if (groupsAnswer) applyGroups(groupsAnswer);
        else setGroupsAvailable(false);
        setPolicies(p);
        setRawDevices(d);
        setSource(answer ? 'plugin' : 'perPhone');
        if (answer) applyFleet(answer, d, g);
        else void eachLimited(d, IN_FLIGHT, (dev) => loadDetail(dev, g), () => g !== gen.current);
      })
      .catch((e: unknown) => {
        if (g !== gen.current) return;
        setPolicies((prev) => prev ?? []);
        setRawDevices((prev) => prev ?? []);
        setError(e instanceof Error ? e.message : 'Could not load the fleet.');
      });
    return () => {
      gen.current++;
    };
  }, [attempt, loadDetail, applyFleet, applyGroups]);

  const reloadPolicies = useCallback(async () => {
    const p = await listPolicies();
    setPolicies(p);
  }, []);

  const refreshDevices = useCallback(
    (ids: readonly number[]) => {
      const g = gen.current;
      const list = (rawDevices ?? []).filter((d) => ids.includes(d.id));
      if (!list.length) return;
      // With the plugin, one request refreshes them all, however many there are.
      if (source === 'plugin') {
        void getFleet().then((answer) => {
          if (g !== gen.current) return;
          if (answer) applyFleet(answer, list, g);
          else void eachLimited(list, IN_FLIGHT, (dev) => loadDetail(dev, g));
        });
        return;
      }
      void eachLimited(list, IN_FLIGHT, (dev) => loadDetail(dev, g));
    },
    [rawDevices, loadDetail, applyFleet, source],
  );

  // Jobs: read once with the plugin, then every few seconds only while one is active (one small request each time).
  useEffect(() => {
    if (source !== 'plugin') return;
    let on = true;
    listJobs()
      .then((j) => on && setJobs(j))
      .catch(() => undefined);
    return () => {
      on = false;
    };
  }, [source, jobsTick]);

  const anyActive = jobs.some((j) => jobActive(j));
  useEffect(() => {
    if (!anyActive) return;
    const t = setTimeout(() => setJobsTick((n) => n + 1), JOB_POLL_MS);
    return () => clearTimeout(t);
  }, [anyActive, jobs]);

  // When a job stops being active, its phones' details are read again: one fleet request for all of them.
  const watching = useRef(new Set<number>());
  useEffect(() => {
    const now = new Set(jobs.filter((j) => jobActive(j)).map((j) => j.id));
    const ended = [...watching.current].some((id) => !now.has(id));
    watching.current = now;
    if (ended && rawDevices) refreshDevices(rawDevices.map((d) => d.id));
    // refreshDevices changes with the device list; the jobs are what this effect follows.
  }, [jobs]);

  const trackJob = useCallback((job: Job) => setJobs((prev) => [job, ...prev.filter((j) => j.id !== job.id)]), []);
  const stopJob = useCallback(
    async (id: number) => {
      trackJob(await cancelJob(id));
    },
    [trackJob],
  );

  const groups = useMemo(() => groupsState?.groups ?? [], [groupsState]);

  const devices = useMemo<FleetDevice[] | null>(() => {
    if (!rawDevices || !policies) return null;
    const byId = new Map(policies.map((p) => [p.id, p]));
    return rawDevices.map((device) => {
      const det = details[device.id];
      const fallback = deviceBrand(null, device.description);
      const reportedBrand = det?.brand ?? fallback.brand;
      const setBrand = groupsState?.deviceBrands.get(device.id);
      const brand = setBrand ?? reportedBrand;
      const { group, manual: groupByHand } = groupForDevice(groups, device.id, brand);
      const expectedPolicy = group?.policyId != null ? byId.get(group.policyId) : undefined;
      const currentPolicy = device.configurationId != null ? byId.get(device.configurationId) : undefined;
      const sync = det ? syncState(det.status) : 'unknown';
      const unsupported = unsupportedSettings(det?.status?.lastCommand?.detail);
      // Extra apps are judged against the policy the phone is on now (that is what its user lives with), and only
      // when that policy enforces its allowed list: kiosk on, with apps allowed.
      const control = currentPolicy?.data.apps;
      const allowed = currentPolicy ? enforcedList(currentPolicy) : null;
      const checks = !!control && !!allowed && control.mode !== 'off';
      const apps = det?.apps ?? null;
      const agentVersion = det?.agentVersion ?? null;
      const violations = !checks ? [] : apps?.apps ? findExtras(apps.apps, allowed, control, device.number, apps.removing) : null;
      return {
        device,
        brand,
        reportedBrand,
        brandGuessed: setBrand ? false : (det?.brandGuessed ?? fallback.guessed),
        brandByHand: setBrand != null,
        loaded: !!det,
        group,
        groupByHand,
        expectedPolicy,
        currentPolicy,
        sync,
        unsupported,
        violations,
        apps,
        agentVersion,
        canLaunch: det?.canLaunch === true,
        compliance: compliance({
          expectedPolicyId: group ? group.policyId : undefined,
          configurationId: device.configurationId,
          sync,
          unsupported: unsupported.length,
          violations: violations ? violations.length : null,
          appMode: checks ? control.mode : 'off',
        }),
      };
    });
  }, [rawDevices, policies, details, groups, groupsState]);

  return {
    policies,
    devices,
    groups,
    customBrands: groupsState?.brands ?? [],
    groupsAvailable,
    applyGroups,
    progress: { done: Object.keys(details).length, total: rawDevices?.length ?? 0 },
    source,
    error,
    reload: () => setAttempt((n) => n + 1),
    reloadPolicies,
    refreshDevices,
    jobs,
    trackJob,
    cancelJob: stopJob,
  };
}
