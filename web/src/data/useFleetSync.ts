// Fleet presence + configuration-sync state for a device list (the Devices list, table and cards), on the shared
// fleet health rules (fleetHealth.ts). It mirrors the the v0.3 console's DashboardPage probe:
//   1. one GET agent/configurations/syncSummary;
//   2. a configuration whose devices are all in sync (nothing out of sync, unsupported, never seen or deferred)
//      makes every device in it "In sync" without asking per device;
//   3. online devices in the other ("suspect") configurations are probed one request each, capped;
//   4. rows on screen whose state is still unknown are probed too (bounded by the page size).
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getConfigStatus, getSyncSummary, summarizeStatus, type ConfigStatus, type ConfigSyncSummary } from '../api/configSync';
import type { DeviceView } from '../api/devices';
import { isOnline } from './presence';
import {
  ATTENTION_PROBE_CAP,
  isCleanSummary,
  isConfigIssue,
  presenceOf as presenceFor,
  suspectDevices,
  type Presence,
  type SyncCheck,
  type SyncVerdict,
} from './fleetHealth';

const NO_CONFIGURATION: SyncVerdict = { tone: 'idle', label: 'No configuration' };
const IN_SYNC: SyncVerdict = { tone: 'ok', label: 'In sync' };
const NOT_REPORTED: SyncVerdict = { tone: 'idle', label: 'Not reported yet' };

export interface FleetSync {
  /** Known sync state, or undefined while unknown. */
  syncOf: (d: DeviceView) => SyncVerdict | undefined;
  /** Online / attention (online with a configuration problem) / offline. */
  presenceOf: (d: DeviceView, now: number) => Presence;
  /** How complete the attention picture is: 'partial' when some online suspect device is still unknown. */
  check: SyncCheck;
  /** Re-ask one device (after "Sync now"). */
  refresh: (d: DeviceView) => void;
}

/**
 * summarizeStatus, except that a device the list shows with a configuration never reads "No configuration": an
 * empty answer means the server has nothing from it yet.
 */
function summarize(d: DeviceView, s: ConfigStatus | null): SyncVerdict {
  if (d.configurationId != null && (!s || s.configurationId == null)) return NOT_REPORTED;
  return summarizeStatus(s);
}

export function useFleetSync(devices: DeviceView[], loading: boolean, visible: DeviceView[]): FleetSync {
  const [summary, setSummary] = useState<Map<number, ConfigSyncSummary> | 'pending' | 'failed'>('pending');
  const [results, setResults] = useState<Record<number, SyncVerdict>>({});
  const [failed, setFailed] = useState<Record<number, true>>({});
  const requested = useRef<Set<number>>(new Set());
  const generation = useRef(0);

  const probe = useCallback((list: DeviceView[]) => {
    const gen = generation.current;
    const todo = list.filter((d) => !requested.current.has(d.id));
    if (todo.length === 0) return;
    for (const d of todo) requested.current.add(d.id);
    for (const d of todo) {
      getConfigStatus(d.number)
        .then((s) => {
          if (gen !== generation.current) return;
          setResults((r) => ({ ...r, [d.id]: summarize(d, s) }));
        })
        .catch(() => {
          if (gen !== generation.current) return;
          setFailed((f) => ({ ...f, [d.id]: true }));
        });
    }
  }, []);

  // A new device list (load, search, bulk change) starts a fresh picture.
  useEffect(() => {
    if (loading) return;
    const gen = ++generation.current;
    requested.current = new Set();
    setResults({});
    setFailed({});
    setSummary('pending');
    getSyncSummary()
      .then((list) => {
        if (gen !== generation.current) return;
        const m = new Map<number, ConfigSyncSummary>();
        for (const s of list ?? []) m.set(s.configurationId, s);
        setSummary(m);
        probe(suspectDevices(devices, list ?? []).slice(0, ATTENTION_PROBE_CAP));
      })
      .catch(() => {
        if (gen !== generation.current) return;
        setSummary('failed');
      });
  }, [devices, loading, probe]);

  const syncOf = useCallback(
    (d: DeviceView): SyncVerdict | undefined => {
      if (d.configurationId == null) return NO_CONFIGURATION;
      const r = results[d.id];
      if (r) return r;
      if (summary instanceof Map) {
        const s = summary.get(d.configurationId);
        if (s && isCleanSummary(s)) return IN_SYNC;
      }
      return undefined;
    },
    [results, summary],
  );

  // Rows on screen with an unknown state are asked about once the summary has answered (or failed).
  const visibleKey = visible.map((d) => d.id).join(',');
  useEffect(() => {
    if (summary === 'pending') return;
    probe(visible.filter((d) => syncOf(d) === undefined && !failed[d.id]));
    // visibleKey stands in for `visible`
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleKey, summary, probe]);

  const presenceOf = useCallback(
    (d: DeviceView, now: number): Presence => {
      return presenceFor(d.lastUpdate, isConfigIssue(syncOf(d)), now);
    },
    [syncOf],
  );

  const check: SyncCheck = useMemo(() => {
    if (loading || summary === 'pending') return 'pending';
    if (summary === 'failed') return 'failed';
    const unknown = devices.some((d) => isOnline(d.lastUpdate) && syncOf(d) === undefined);
    return unknown ? 'partial' : 'complete';
  }, [devices, loading, summary, syncOf]);

  const refresh = useCallback((d: DeviceView) => {
    const gen = generation.current;
    getConfigStatus(d.number)
      .then((s) => {
        if (gen === generation.current) setResults((r) => ({ ...r, [d.id]: summarize(d, s) }));
      })
      .catch(() => undefined);
  }, []);

  return { syncOf, presenceOf, check, refresh };
}
