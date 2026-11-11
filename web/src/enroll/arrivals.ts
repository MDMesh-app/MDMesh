// The devices that enroll while the Enroll page is open, so the admin sees the device they just set up arrive and
// come online instead of being told to look under Devices.

import { useEffect, useState } from 'react';
import { searchDevices, type DeviceView } from '../api/devices';
import { isOnline } from '../data/presence';

/** How often the page looks for new devices while it is visible. */
export const ARRIVALS_POLL_MS = 5_000;
/** Newest enrollments asked for on each look (more than anyone sets up within one interval). */
const PAGE = 25;

export type ArrivalState = 'settingUp' | 'online' | 'offline';

export interface Arrival {
  number: string;
  name: string;
  enrollTime: number;
  state: ArrivalState;
}

/**
 * The devices enrolled at or after `since` (server time: the code's creation), newest first. Enrollment stamps
 * last-seen with the enrollment time itself, so a later last-seen means the device has checked in: it got its
 * configuration, and is online while that is recent.
 */
export function arrivalsSince(devices: DeviceView[] | null | undefined, since: number, now: number = Date.now()): Arrival[] {
  return (devices ?? [])
    .filter((d): d is DeviceView & { enrollTime: number } => typeof d.enrollTime === 'number' && d.enrollTime >= since)
    .sort((a, b) => b.enrollTime - a.enrollTime)
    .map((d) => {
      const checkedIn = typeof d.lastUpdate === 'number' && d.lastUpdate > d.enrollTime;
      const state: ArrivalState = !checkedIn ? 'settingUp' : isOnline(d.lastUpdate, now) ? 'online' : 'offline';
      return { number: d.number, name: d.description?.trim() || d.number, enrollTime: d.enrollTime, state };
    });
}

/**
 * Looks for devices enrolled since `since` every few seconds while the page is visible (none while hidden, at once
 * when shown again). `since` undefined: not looking.
 */
export function useArrivals(since: number | undefined): Arrival[] {
  const [devices, setDevices] = useState<DeviceView[]>([]);
  useEffect(() => {
    if (since == null) return;
    let on = true;
    let running = false;
    let t: ReturnType<typeof setTimeout> | undefined;
    const run = async () => {
      running = true;
      try {
        const res = await searchDevices({ sortBy: 'ENROLLMENT_DATE', sortDir: 'DESC', pageSize: PAGE });
        if (on) setDevices(res?.devices?.items ?? []);
      } catch {
        /* keep what is shown; the next look tries again */
      }
      running = false;
      if (on && !document.hidden) t = setTimeout(() => void run(), ARRIVALS_POLL_MS);
    };
    const onVisibility = () => {
      if (!document.hidden && !running) {
        clearTimeout(t);
        void run();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    void run();
    return () => {
      on = false;
      clearTimeout(t);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [since]);
  return since == null ? [] : arrivalsSince(devices, since);
}
