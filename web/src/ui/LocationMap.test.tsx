// The location map keeps the user's view across reloads: it fits the path on its first draw and on a new time range
// only; the same fixes again change nothing, and new ones redraw the path in place.
import { render } from '@testing-library/react';
import L from 'leaflet';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocationMap } from './LocationMap';

const fix = (id: number, lat: number, lon: number) => ({ id, lat, lon, capturedAt: 1_700_000_000_000 + id * 60_000 });
const FIXES = [fix(3, 37.78, -122.42), fix(2, 37.77, -122.41), fix(1, 37.76, -122.4)];

afterEach(() => vi.restoreAllMocks());

describe('LocationMap', () => {
  it('fits once, keeps the view on reloads, and fits again for a new range', () => {
    const fitBounds = vi.spyOn(L.Map.prototype, 'fitBounds');
    const { rerender, container } = render(<LocationMap fixes={FIXES} fitKey={0} label="Map" />);
    expect(fitBounds).toHaveBeenCalledTimes(1);
    const line = () => container.querySelector('.v2-locmap__line');
    const first = line();
    expect(first).not.toBeNull();

    // The same fixes in a new array (a poll, a device state report): nothing is redrawn.
    rerender(<LocationMap fixes={FIXES.map((f) => ({ ...f }))} fitKey={0} label="Map" />);
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(line()).toBe(first);

    // A new fix: the path is redrawn, the view stays.
    rerender(<LocationMap fixes={[fix(4, 37.79, -122.43), ...FIXES]} fitKey={0} label="Map" />);
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(line()).not.toBe(first);

    // Another range: fitted to it.
    rerender(<LocationMap fixes={FIXES.slice(0, 2)} fitKey={24} label="Map" />);
    expect(fitBounds).toHaveBeenCalledTimes(2);
  });

  it('puts className on the outer box, around the map and its key', () => {
    const { container } = render(<LocationMap fixes={FIXES} label="Map" className="x" />);
    const outer = container.firstElementChild!;
    expect(outer.classList.contains('x')).toBe(true);
    expect(outer.querySelector('[role=region]')).not.toBeNull();
    expect(outer.querySelector('ul[aria-label="Map key"]')).not.toBeNull();
  });
});
