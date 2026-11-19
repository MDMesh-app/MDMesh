// What the location map draws at a zoom level, from the fixes' screen positions (oldest first). Pure, no Leaflet:
// Web Mercator keeps angles, so a direction measured in pixels holds at every zoom; only lengths change.

export interface Pt {
  x: number;
  y: number;
}

/** The direction from `a` to `b` on screen, in degrees: 0 is right, clockwise (the sense of CSS rotate()). */
export function screenAngle(a: Pt, b: Pt): number {
  return (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI;
}

/**
 * The direction arrows, pointing from the older fix to the newer. A segment at least `minLength` px long holds one
 * arrow per `spacing` px, evenly inside it (never on its ends, where the fixes' dots are); along the path arrows stay
 * at least `spacing` px apart. Shorter segments get none, so a zoomed-out trail is never a row of overlapping arrows.
 */
export function arrowSegments(pts: readonly Pt[], minLength: number, spacing: number): (Pt & { angle: number })[] {
  const out: (Pt & { angle: number })[] = [];
  let walked = 0;
  let last = -Infinity;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1];
    const b = pts[i];
    const len = Math.hypot(b.x - a.x, b.y - a.y);
    if (len > 0 && len >= minLength) {
      const angle = screenAngle(a, b);
      const n = spacing > 0 ? Math.max(1, Math.floor(len / spacing)) : 1;
      for (let j = 0; j < n; j++) {
        const t = (j + 0.5) / n;
        if (walked + t * len - last < spacing) continue;
        last = walked + t * len;
        out.push({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, angle });
      }
    }
    walked += len;
  }
  return out;
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
