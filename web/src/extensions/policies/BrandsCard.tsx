// The Overview's Brands card and "On group policy" tile. The card lists phones per brand, two brands to a row, each
// with a bar: on their group's policy, waiting for Apply, in no group. The tile sits in the fleet summary beside core's
// "Config in sync". Both use membership.ts (one fetch shared with the Devices list's column).
import { useMemo } from 'react';
import { Card, CardHeader, EmptyState, Link, SkeletonFacts, StackedBar, StatCell, type StatNote } from '@mdmesh/console';
import { useMembership } from './GroupCell';
import type { Membership } from './membership';
import { brandLabel } from './model';
import './brands.css';

/** Brands shown before "and N more". */
const SHOWN = 8;

interface BrandRow {
  brand: string;
  count: number;
  onPolicy: number;
  waiting: number;
  noGroup: number;
  /** The group of most of its phones, and that group's policy. */
  group: string | null;
  policy: string | null;
}

interface Totals {
  total: number;
  onPolicy: number;
  waiting: number;
  noGroup: number;
}

function summarize(m: Membership): { rows: BrandRow[]; totals: Totals } {
  const by = new Map<string, BrandRow & { groups: Map<number, number> }>();
  const totals: Totals = { total: 0, onPolicy: 0, waiting: 0, noGroup: 0 };
  for (const d of m.devices) {
    const { brand, group } = m.of(d);
    const r = by.get(brand) ?? { brand, count: 0, onPolicy: 0, waiting: 0, noGroup: 0, group: null, policy: null, groups: new Map<number, number>() };
    r.count++;
    totals.total++;
    if (!group) {
      r.noGroup++;
      totals.noGroup++;
    } else {
      r.groups.set(group.id, (r.groups.get(group.id) ?? 0) + 1);
      if (group.policyId != null && d.configurationId === group.policyId) {
        r.onPolicy++;
        totals.onPolicy++;
      } else {
        r.waiting++;
        totals.waiting++;
      }
    }
    by.set(brand, r);
  }
  const rows = [...by.values()]
    .map(({ groups, ...r }) => {
      const top = [...groups.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
      const g = top != null ? m.groups.find((x) => x.id === top) : undefined;
      return { ...r, group: g?.name ?? null, policy: g?.policyId != null ? (m.policies.get(g.policyId) ?? null) : null };
    })
    .sort((a, b) => b.count - a.count || a.brand.localeCompare(b.brand));
  return { rows, totals };
}

function useSummary() {
  const m = useMembership();
  const s = useMemo(() => (m?.available ? summarize(m) : null), [m]);
  return { m, s };
}

export function BrandsCard() {
  const { m, s } = useSummary();
  // Without the Policies server plugin there is nothing to show: the card takes no room.
  if (m === null || (m && !m.available)) return null;
  const rows = s?.rows ?? [];
  const max = rows[0]?.count ?? 0;

  return (
    <Card aria-labelledby="x-policies-ov-brands">
      <CardHeader spacing={8} title="Brands" titleId="x-policies-ov-brands" actions={<Link to="/policies?view=groups">Groups</Link>} />
      {m === undefined ? (
        <SkeletonFacts count={4} label="Loading brands…" />
      ) : rows.length === 0 ? (
        <EmptyState bare title="No devices yet">
          Phones are counted here by the brand they report, or the one set on them.
        </EmptyState>
      ) : (
        <>
          <ul className="x-brands">
            {rows.slice(0, SHOWN).map((r) => (
              <li
                key={r.brand}
                className="x-brands__item"
                title={r.group ? `${r.group} · ${r.policy ?? 'no policy yet'}` : 'In no group'}
              >
                <span className="x-brands__head">
                  <span className="x-brands__name">{brandLabel(r.brand)}</span>
                  <span className="x-brands__count">{r.count}</span>
                </span>
                <StackedBar
                  track
                  label={`${brandLabel(r.brand)}: ${r.count} phones, ${r.onPolicy} on their group's policy, ${r.waiting} not yet, ${r.noGroup} in no group`}
                  segments={[
                    { value: r.onPolicy, tone: 'ok' },
                    { value: r.waiting, tone: 'warn' },
                    { value: r.noGroup, tone: 'pending' },
                    { value: max - r.count, tone: 'track' },
                  ]}
                />
                <span className="x-brands__group">{r.group ?? 'No group'}</span>
              </li>
            ))}
          </ul>
          <div className="x-brands__foot">
            <span className="x-brands__legend">
              <span className="x-brands__dot v2-bar--ok" /> On its policy
              <span className="x-brands__dot v2-bar--warn" /> Not applied
              <span className="x-brands__dot v2-bar--pending" /> No group
            </span>
            {rows.length > SHOWN ? (
              <Link to="/policies?view=groups">
                +{rows.length - SHOWN} more
              </Link>
            ) : null}
          </div>
        </>
      )}
    </Card>
  );
}

/** The fleet summary's "On group policy" tile: phones on their group's policy, of all. */
export function GroupStat() {
  const { m, s } = useSummary();
  if (m === null || (m && !m.available)) return null;
  if (!s) return <StatCell label="On group policy" value="—" notes={[{ text: 'Checking…' }]} />;
  const { total, onPolicy, waiting, noGroup } = s.totals;
  const notes: StatNote[] = [];
  if (waiting) notes.push({ text: `${waiting} not applied`, tone: 'warn' });
  if (noGroup) notes.push({ text: `${noGroup} in no group` });
  if (!notes.length) notes.push(total ? { text: 'All on their policy', tone: 'ok' } : { text: 'No devices yet' });
  return <StatCell label={<Link to="/policies?view=groups" className="v2-ov-statlink">On group policy</Link>} value={onPolicy} of={`/${total}`} notes={notes} />;
}
