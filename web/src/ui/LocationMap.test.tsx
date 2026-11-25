// The location map keeps the user's view across reloads: it fits the path on its first draw and on a new time range
// only; the same fixes again change nothing, and new ones redraw the path in place.
import L from 'leaflet';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { IconClose, IconPlus } from './icons';
import { LocationMap, MAP_ICONS } from './LocationMap';
import { render } from '../test/render';

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

  it('draws its view and path under StrictMode (effects run, clean up and run again on one set of refs)', () => {
    const fitBounds = vi.spyOn(L.Map.prototype, 'fitBounds');
    // StrictMode at the root, around the test's providers: as an element inside the wrapper it didn't run the
    // effects twice here.
    const { container } = render(<LocationMap fixes={FIXES} fitKey={0} label="Map" />, { reactStrictMode: true });
    // The map left standing is the second one: it has its own view and path.
    const maps = fitBounds.mock.contexts;
    expect(maps[maps.length - 1]).not.toBe(maps[0]);
    expect(container.querySelectorAll('.leaflet-container')).toHaveLength(1);
    expect(container.querySelector('.v2-locmap__line')).not.toBeNull();
    expect(container.querySelector('.v2-locmap__latest')).not.toBeNull();
  });

  it('draws its controls with the console’s icons, and credits OpenStreetMap without Leaflet’s prefix', () => {
    const { container } = render(<LocationMap fixes={FIXES} label="Map" />);
    const zoomIn = container.querySelector('a.leaflet-control-zoom-in')!;
    const zoomOut = container.querySelector('a.leaflet-control-zoom-out')!;
    expect(zoomIn.getAttribute('aria-label')).toBe('Zoom in');
    expect(zoomOut.getAttribute('aria-label')).toBe('Zoom out');
    expect(zoomIn.querySelector('svg path')?.getAttribute('d')).toBe('M8 2.5v11M2.5 8h11');
    expect(zoomOut.querySelector('svg path')?.getAttribute('d')).toBe('M2.5 8h11');
    const credit = container.querySelector('.leaflet-control-attribution')!;
    expect(credit.textContent).toBe('© OpenStreetMap contributors');
    expect(credit.querySelector('a')?.getAttribute('href')).toBe('https://www.openstreetmap.org/copyright');
    expect(credit.querySelector('.leaflet-attribution-flag')).toBeNull();
  });

  it('keeps its icon markup in step with the console’s icon set', () => {
    const shape = (el: Element | null) => ({
      viewBox: el?.getAttribute('viewBox'),
      width: el?.getAttribute('width'),
      stroke: el?.getAttribute('stroke-width'),
      d: el?.querySelector('path')?.getAttribute('d'),
    });
    const markup = (html: string) => {
      const box = document.createElement('div');
      box.innerHTML = html;
      return box.firstElementChild;
    };
    const rendered = (icon: ReactElement) => render(icon).container.firstElementChild;
    expect(shape(markup(MAP_ICONS.zoomIn))).toEqual(shape(rendered(<IconPlus />)));
    expect(shape(markup(MAP_ICONS.close))).toEqual(shape(rendered(<IconClose />)));
    // The minus is the plus without its upright, on the same grid and stroke.
    const plus = shape(rendered(<IconPlus />));
    expect({ ...shape(markup(MAP_ICONS.zoomOut)), d: plus.d }).toEqual(plus);
  });

  it('puts className on the outer box, around the map and its key', () => {
    const { container } = render(<LocationMap fixes={FIXES} label="Map" className="x" />);
    const outer = container.firstElementChild!;
    expect(outer.classList.contains('x')).toBe(true);
    expect(outer.querySelector('[role=region]')).not.toBeNull();
    expect(outer.querySelector('ul[aria-label="Map key"]')).not.toBeNull();
  });
});
