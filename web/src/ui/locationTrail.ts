// What the location map draws at a zoom level, from the fixes' screen positions (oldest first). Pure, no Leaflet:
// Web Mercator keeps angles, so a direction measured in pixels holds at every zoom; only lengths change.

export interface Pt {
  x: number;
  y: number;
}

/** The direction from `a` to `b` on screen, in degrees: 0 is right, clockwise (as the arrows' SVG rotate() turns). */
export function screenAngle(a: Pt, b: Pt): number {
  return (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI;
}

/**
 * The direction arrows, pointing from the older fix to the newer. A segment at least `minLength` px long holds one
 * arrow per `spacing` px, evenly inside it (never on its ends, where the fixes' dots are); along the path arrows stay
 * at least `spacing` px apart. Shorter segments get none, so a zoomed-out trail is never a row of overlapping arrows.
 * With `view`, only the arrows inside it are made (zoomed in on a long jump there would be millions); where they sit
 * doesn't depend on the view, so they hold still as the map pans.
 */
export function arrowSegments(
  pts: readonly Pt[],
  minLength: number,
  spacing: number,
  view?: { min: Pt; max: Pt },
): (Pt & { angle: number })[] {
  const out: (Pt & { angle: number })[] = [];
  let walked = 0;
  let last = -Infinity;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1];
    const b = pts[i];
    const len = Math.hypot(b.x - a.x, b.y - a.y);
    if (len > 0 && len >= minLength) {
      const n = spacing > 0 ? Math.max(1, Math.floor(len / spacing)) : 1;
      // Arrows in a segment are at least `spacing` apart (len / n >= spacing): once one is far enough from the last
      // kept, every later one is too. So the kept ones are j0..n-1.
      const j0 = Math.max(0, Math.ceil(((last + spacing - walked) / len) * n - 0.5));
      if (j0 < n) {
        last = walked + ((n - 0.5) / n) * len;
        const [t0, t1] = view ? clip(a, b, view) : [0, 1];
        const angle = screenAngle(a, b);
        for (let j = Math.max(j0, Math.ceil(t0 * n - 0.5)); j <= Math.min(n - 1, Math.floor(t1 * n - 0.5)); j++) {
          const t = (j + 0.5) / n;
          out.push({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, angle });
        }
      }
    }
    walked += len;
  }
  return out;
}

/** The part of segment a→b inside the box, as [t0, t1] along it (t0 > t1: none). Liang–Barsky. */
function clip(a: Pt, b: Pt, { min, max }: { min: Pt; max: Pt }): [number, number] {
  let t0 = 0;
  let t1 = 1;
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  for (const [p, q] of [
    [-dx, a.x - min.x],
    [dx, max.x - a.x],
    [-dy, a.y - min.y],
    [dy, max.y - a.y],
  ]) {
    if (p === 0) {
      if (q < 0) return [1, 0];
    } else {
      const r = q / p;
      if (p < 0) t0 = Math.max(t0, r);
      else t1 = Math.min(t1, r);
    }
  }
  return [t0, t1];
}

/**
 * Whether each fix gets its own dot: only when the typical (median) gap between fixes is at least `minGap` px, so
 * zoomed out the trail stays a clean line instead of a string of beads. Fewer than three fixes: always.
 */
export function showPoints(pts: readonly Pt[], minGap: number): boolean {
  if (pts.length < 3) return true;
  const gaps = pts
    .slice(1)
    .map((b, i) => Math.hypot(b.x - pts[i].x, b.y - pts[i].y))
    .sort((p, q) => p - q);
  return gaps[Math.floor(gaps.length / 2)] >= minGap;
}
