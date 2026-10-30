// Everything the Overview shows, loaded with the classic Dashboard's requests (device list, sync summary, capped
// per-device configStatus probes, events of the 12 most recently seen devices) plus the active agent rollout, the
// app library (package → name for "installed an app") and the shell's shared supervisor status.
//
// Refreshes run only while the tab is visible. Every 60 s: the device list, the sync summary and the rollout (three
// requests), plus a configStatus probe for a device that has just become suspect. Every 5 min, on Retry, or when a
// device joins or leaves the fleet: everything, i.e. every suspect device re-probed and the activity feed's events
// reloaded. A failed refresh keeps the last good data and reports the error.
import { useCallback, useEffect, useRef, useState } from 'react';
import { listApplications } from '../../api/applications';
import { ApiError } from '../../api/client';
import { getConfigStatus, getSyncSummary, summarizeStatus, type ConfigSyncSummary } from '../../api/configSync';
import { searchDevices, type ConfigurationLookup, type DeviceView } from '../../api/devices';
import { getEvents, type DeviceEvent } from '../../api/events';
import { getActiveRollout, type ActiveRollout } from '../../api/rollout';
import type { UpdateStatus } from '../../api/updates';
import { ATTENTION_PROBE_CAP, isConfigIssue, suspectDevices, type SyncCheck } from '../../data/fleetHealth';
import { rolloutActive } from '../../data/rollout';
import { useSupervisor } from '../../data/useSupervisor';
import type { ConfigIssue } from './attention';

export const REFRESH_MS = 60_000;
/** The full refresh (every suspect re-probed, activity events reloaded). */
export const DEEP_REFRESH_MS = 5 * 60_000;
/** Devices whose events are fetched for the activity feed (per-device endpoint, so the fan-out is capped). */
const ACTIVITY_DEVICES = 12;
/** Merged events kept, so a filter still has enough rows to show 8. */
const ACTIVITY_KEEP = 60;

export interface ActivityItem {
  key: string;
  device: DeviceView;
  ev: DeviceEvent;
}

export interface OverviewData {
  devices: DeviceView[];
  total: number;
  configurations: Record<string, ConfigurationLookup>;
  /** True until the first device load settles. */
  loading: boolean;
  /** Message for the last failed device load (null when the last one worked). */
  error: string | null;
  /** When the device list last loaded successfully. */
  loadedAt: number | null;
  /** Sync summary rows; null while loading or when the last request failed (see summaryFailed). */
  summary: ConfigSyncSummary[] | null;
  summaryFailed: boolean;
  issues: Record<number, ConfigIssue>;
  syncCheck: SyncCheck;
  /** Newest first; null while loading. */
  activity: ActivityItem[] | null;
  appNames: Record<string, string>;
  /** Supervisor status; null when it is unreachable (no supervisor in dev / source installs without it). */
  update: UpdateStatus | null;
  rollout: ActiveRollout | null;
  refresh: () => void;
}

/** Same wording as the classic useDevices hook. */
function messageFor(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.httpStatus === 401 || err.httpStatus === 403) return 'Your session has expired. Sign in again.';
    if (err.httpStatus === 0) return 'Cannot reach the server.';
  }
  return 'Failed to load devices.';
}

export function useOverviewData(): OverviewData {
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [total, setTotal] = useState(0);
  const [configurations, setConfigurations] = useState<Record<string, ConfigurationLookup>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loadedAt, setLoadedAt] = useState<number | null>(null);
  const [summary, setSummary] = useState<ConfigSyncSummary[] | null>(null);
  const [summaryFailed, setSummaryFailed] = useState(false);
  const [issues, setIssues] = useState<Record<number, ConfigIssue>>({});
  const [syncCheck, setSyncCheck] = useState<SyncCheck>('pending');
  const [activity, setActivity] = useState<ActivityItem[] | null>(null);
  const [appNames, setAppNames] = useState<Record<string, string>>({});
  const [rollout, setRollout] = useState<ActiveRollout | null>(null);

  // The shell's supervisor status (kept 5 min fresh while the Overview is open); null while unreachable.
  const sup = useSupervisor(DEEP_REFRESH_MS);
  const update: UpdateStatus | null = sup.reachable ? sup.status : null;

  // Each refresh gets a generation; results of an older one (or after unmount) are dropped.
  const gen = useRef(0);
  const lastRun = useRef(0);
  const lastDeep = useRef(0);
  /** Device ids of the last good list, sorted and joined: a change (enrolled, deleted) forces a full refresh. */
  const fleetKey = useRef<string | null>(null);
  /** Last configStatus probe per device id (undefined: never probed), reused by the light refresh. */
  const probed = useRef(new Map<number, ConfigIssue | null>());
  /** Devices whose probe failed since the last full refresh (retried by the next full one). */
  const probeFailed = useRef(new Set<number>());

  const refresh = useCallback(async (force = false) => {
    const g = ++gen.current;
    const now = Date.now();
    lastRun.current = now;
    const live = () => gen.current === g;

    // Side panel: independent of the device list, never blocks it.
    void getActiveRollout()
      .then((r) => live() && setRollout(rolloutActive(r) ? r : null))
      .catch(() => live() && setRollout(null));

    const summaryP = getSyncSummary().catch(() => null);
    let list: DeviceView[];
    try {
      const res = await searchDevices({ value: '', pageSize: 1000 });
      if (!live()) return;
      list = res.devices?.items ?? [];
      setDevices(list);
      setTotal(res.devices?.totalItemsCount ?? 0);
      setConfigurations(res.configurations ?? {});
      setError(null);
      setLoadedAt(Date.now());
    } catch (err) {
      if (!live()) return;
      setError(messageFor(err));
      // Keep the last good data; with none, the page shows its empty/error state.
      setActivity((a) => a ?? []);
      setSyncCheck((c) => (c === 'pending' ? 'failed' : c));
      return;
    } finally {
      if (live()) setLoading(false);
    }

    const key = list.map((d) => d.id).sort((a, b) => a - b).join(',');
    const deep = force || fleetKey.current !== key || now - lastDeep.current >= DEEP_REFRESH_MS;
    fleetKey.current = key;
    if (deep) {
      probed.current = new Map();
      probeFailed.current = new Set();
    }

    // Activity: the most recently seen devices' events, merged (full refresh only: 12 requests).
    if (list.length === 0) {
      setActivity([]);
      if (deep) lastDeep.current = now;
    } else if (deep) {
      const top = [...list].sort((a, b) => (b.lastUpdate ?? 0) - (a.lastUpdate ?? 0)).slice(0, ACTIVITY_DEVICES);
      void Promise.allSettled(top.map((d) => getEvents(d.number).then((evs) => ({ d, evs })))).then((results) => {
        if (!live()) return;
        const items: ActivityItem[] = [];
        for (const r of results) {
          if (r.status !== 'fulfilled') continue;
          const { d, evs } = r.value;
          for (const ev of evs ?? []) items.push({ key: `${d.id}-${ev.id}`, device: d, ev });
        }
        items.sort((a, b) => b.ev.ts - a.ev.ts);
        setActivity(items.slice(0, ACTIVITY_KEEP));
        lastDeep.current = now; // a full refresh superseded before this point leaves the next one full too
      });
    }

    // Configuration problems: online devices in a configuration the summary doesn't call clean are probed (capped),
    // so a healthy fleet costs one request. Between full refreshes only devices not probed since the last one are.
    const sum = await summaryP;
    if (!live()) return;
    if (!sum) {
      setSummaryFailed(true);
      setSyncCheck('failed');
      return;
    }
    setSummary(sum);
    setSummaryFailed(false);
    const suspects = suspectDevices(list, sum);
    const probe = suspects.slice(0, ATTENTION_PROBE_CAP);
    const cache = probed.current;
    const failedSet = probeFailed.current;
    const fresh = probe.filter((d) => !cache.has(d.id) && !failedSet.has(d.id));
    const results = await Promise.allSettled(
      fresh.map((d) => getConfigStatus(d.number).then((s) => ({ id: d.id, v: summarizeStatus(s) }))),
    );
    if (!live()) return;
    results.forEach((r, i) => {
      if (r.status !== 'fulfilled') failedSet.add(fresh[i].id);
      else cache.set(r.value.id, isConfigIssue(r.value.v) ? r.value.v : null);
    });
    const failed = probe.filter((d) => failedSet.has(d.id)).length;
    // Only devices that are still suspects keep an issue (a configuration the summary now calls clean clears it).
    const next: Record<number, ConfigIssue> = {};
    for (const d of probe) {
      const v = cache.get(d.id);
      if (v) next[d.id] = v;
    }
    setIssues(next);
    setSyncCheck(suspects.length > probe.length || failed > 0 ? 'partial' : 'complete');
  }, []);

  // First load, the 60 s refresh (visible tab only), and a refresh on return to a stale tab (a full one when due).
  useEffect(() => {
    void refresh();
    const t = setInterval(() => {
      if (document.visibilityState === 'visible') void refresh();
    }, REFRESH_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible' && Date.now() - lastRun.current >= REFRESH_MS) void refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(t);
      document.removeEventListener('visibilitychange', onVisible);
      gen.current++; // drop in-flight results
    };
  }, [refresh]);

  // The library, once: "installed an app com.acme.x" → the app's name.
  useEffect(() => {
    let on = true;
    listApplications()
      .then((apps) => {
        if (!on) return;
        const m: Record<string, string> = {};
        for (const a of apps ?? []) if (a.pkg && a.name) m[a.pkg] = a.name;
        setAppNames(m);
      })
      .catch(() => {
        /* names are a nicety; the package is shown instead */
      });
    return () => {
      on = false;
    };
  }, []);

  return {
    devices,
    total,
    configurations,
    loading,
    error,
    loadedAt,
    summary,
    summaryFailed,
    issues,
    syncCheck,
    activity,
    appNames,
    update,
    rollout,
    refresh: () => void refresh(true),
  };
}
