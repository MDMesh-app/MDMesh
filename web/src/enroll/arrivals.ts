// The devices that enroll while the Enroll page is open, so the admin sees the device they just set up arrive and
// come online instead of being told to look under Devices.

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listEnrolledSince, type EnrolledDevice } from '../api/enroll';
import { isOnline } from '../data/presence';

/** How often the page looks for new devices while it is visible. */
export const ARRIVALS_POLL_MS = 5_000;
/** The page stops looking after this long (a code left open on a screen), until asked to look again. */
export const ARRIVALS_WATCH_MS = 30 * 60_000;
/** A device enrolled this long ago that hasn't checked in is no longer "getting its configuration". */
export const SETTING_UP_MS = 10 * 60_000;
/** Newest enrollments asked for on each look (the server bounds it at 50). */
const PAGE = 25;

export type ArrivalState = 'settingUp' | 'online' | 'silent' | 'offline';

export interface Arrival {
  number: string;
  name: string;
  enrollTime: number;
  state: ArrivalState;
}

/**
 * The devices enrolled at or after `since` (server time: the code's creation), newest first. Enrollment stamps
 * last-seen with the enrollment time itself, so a later last-seen means the device has checked in: it got its
 * configuration, and is online while that is recent. One that hasn't checked in within a few minutes is said so.
 */
export function arrivalsSince(devices: EnrolledDevice[] | null | undefined, since: number, now: number = Date.now()): Arrival[] {
  return (devices ?? [])
    .filter((d) => typeof d.enrollTime === 'number' && d.enrollTime >= since)
    .sort((a, b) => b.enrollTime - a.enrollTime)
    .map((d) => {
      const checkedIn = typeof d.lastUpdate === 'number' && d.lastUpdate > d.enrollTime;
      let state: ArrivalState;
      if (!checkedIn) state = now - d.enrollTime > SETTING_UP_MS ? 'silent' : 'settingUp';
      else state = isOnline(d.lastUpdate, now) ? 'online' : 'offline';
      return { number: d.number, name: d.description?.trim() || d.number, enrollTime: d.enrollTime, state };
    });
}

export interface ArrivalsWatch {
  arrivals: Arrival[];
  /** False once the page stopped looking (after {@link ARRIVALS_WATCH_MS}, or a server without the endpoint). */
  watching: boolean;
  /** The server can't list them (it predates the endpoint): say nothing about arrivals. */
  unsupported: boolean;
  /** Look again for another {@link ARRIVALS_WATCH_MS}. */
  watchAgain: () => void;
}

/**
 * Looks for devices enrolled since `since` every few seconds while the page is visible: none while hidden (at once
 * when shown again), nothing after unmount, and not after {@link ARRIVALS_WATCH_MS}. `since` undefined: not looking.
 */
export function useArrivals(since: number | undefined): ArrivalsWatch {
  const [devices, setDevices] = useState<EnrolledDevice[]>([]);
  const [watching, setWatching] = useState(true);
  const [unsupported, setUnsupported] = useState(false);
  const [round, setRound] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (since == null || unsupported) return;
    let on = true;
    let running = false;
    let t: ReturnType<typeof setTimeout> | undefined;
    const ctrl = new AbortController();
    const until = Date.now() + ARRIVALS_WATCH_MS;
    setWatching(true);
    const run = async () => {
      if (Date.now() >= until) {
        if (on) setWatching(false);
        return;
      }
      running = true;
      try {
        const list = await listEnrolledSince(since, PAGE, ctrl.signal);
        if (on) {
          setDevices(list);
          setNow(Date.now());
        }
      } catch (e) {
        if (e instanceof ApiError && (e.httpStatus === 404 || e.httpStatus === 405)) {
          if (on) {
            setUnsupported(true);
            setWatching(false);
          }
          running = false;
          return;
        }
        /* keep what is shown; the next look tries again */
      }
      running = false;
      if (on && !document.hidden) t = setTimeout(() => void run(), ARRIVALS_POLL_MS);
    };
    const onVisibility = () => {
      if (document.hidden) {
        clearTimeout(t);
      } else if (!running) {
        clearTimeout(t);
        void run();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    void run();
    return () => {
      on = false;
      ctrl.abort();
      clearTimeout(t);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [since, round, unsupported]);

  const watchAgain = useCallback(() => setRound((r) => r + 1), []);
  return {
    arrivals: since == null ? [] : arrivalsSince(devices, since, now),
    watching: since != null && watching,
    unsupported,
    watchAgain,
  };
}
