// The Devices list's "Policy group" column: each phone's group and brand. Kept small, as index.ts loads it with the
// console; the data (membership.ts) is fetched once for the whole list, on demand, and again after a minute or after
// a group change on the Policies page (invalidateMembership).
import { createElement, useEffect, useSyncExternalStore } from 'react';
import type { DeviceView } from '@mdmesh/console';
import type { Membership } from './membership';

const FRESH_MS = 60_000;

let snapshot: { data: Membership | null; at: number } | null = null;
let pending: Promise<void> | null = null;
const listeners = new Set<() => void>();

function notify() {
  for (const l of listeners) l();
}

/** Fetch the membership unless a fresh one is in, or a fetch is under way. */
export function ensureMembership(): void {
  if (pending || (snapshot && Date.now() - snapshot.at < FRESH_MS)) return;
  pending = import('./membership')
    .then((m) => m.loadMembership())
    .then((data) => {
      snapshot = { data, at: Date.now() };
    })
    .catch(() => {
      snapshot = { data: null, at: Date.now() };
    })
    .finally(() => {
      pending = null;
      notify();
    });
}

/** Groups or brands changed: the next reader fetches again. */
export function invalidateMembership(): void {
  if (snapshot) snapshot = { ...snapshot, at: 0 };
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function useMembership(): Membership | null | undefined {
  const s = useSyncExternalStore(subscribe, () => snapshot);
  useEffect(ensureMembership, []);
  return s ? s.data : undefined;
}

function GroupCell({ device }: { device: DeviceView }) {
  const m = useMembership();
  if (m === undefined) return createElement('span', { 'aria-label': 'Loading' }, '…');
  if (!m || !m.available) return createElement('span', null, '—');
  const { group, brandName } = m.of(device);
  // Two lines, as the Device column shows a name over its number: the group, then the brand in small secondary text.
  return createElement(
    'span',
    {
      title: group ? `${group.name}, by ${group.devices.includes(device.id) ? 'hand' : 'brand'} (${brandName})` : `No group (${brandName})`,
      style: { display: 'inline-flex', flexDirection: 'column', minWidth: 0, whiteSpace: 'nowrap' },
    },
    createElement('span', null, group ? group.name : 'No group'),
    createElement('span', { style: { color: 'var(--text-2)', fontSize: 'var(--fs-meta)' } }, brandName),
  );
}

/** The column's cell for one row. */
export const groupCell = (device: DeviceView) => createElement(GroupCell, { device });

/** The column's sort value: group name, then brand (phones loaded before the data sort as "…"). */
export function groupSortValue(device: DeviceView): string | null {
  const m = snapshot?.data;
  if (!m || !m.available) return null;
  const { group, brandName } = m.of(device);
  return `${group ? group.name : '~'} ${brandName}`.toLowerCase();
}
