import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';
import { hex, parseColour, parseTokens, ratio, resolve } from './contrast';

// The location map's trail is drawn over OpenStreetMap's tiles, not over a console surface: it must reach 3:1
// (1.4.11) against the tiles' own fills in both themes, where the dark theme dims the tiles (LocationMap.css).
const read = (p: string) => readFileSync(resolvePath(import.meta.dirname, p), 'utf8');
const themes = parseTokens(read('../tokens.css'));
const dim = Number(read('../ui/LocationMap.css').match(/\.leaflet-tile-pane \{\s*filter: brightness\(([\d.]+)\)/)?.[1]);

/** OpenStreetMap Carto's main area and road fills (openstreetmap-carto style/*.mss). */
const TILES: Record<string, string> = {
  land: '#f2efe9',
  'minor road': '#ffffff',
  residential: '#e0dfdf',
  park: '#c8facc',
  forest: '#add19e',
  water: '#aad3df',
  motorway: '#e892a2',
  trunk: '#f9b29c',
  primary: '#fcd6a4',
  secondary: '#f7fabf',
  building: '#d9d0c9',
  commercial: '#f2dad9',
  industrial: '#ebdbe8',
};

describe.each(['light', 'dark'] as const)('location map, %s theme', (theme) => {
  const trail = resolve(themes[theme], 'map-trail');
  const halo = resolve(themes[theme], 'map-halo');
  const k = theme === 'dark' ? dim : 1;

  it('reads the dark theme’s tile dimming from LocationMap.css', () => {
    expect(dim).toBeGreaterThan(0.5);
    expect(dim).toBeLessThan(1);
  });

  it('the trail reaches 3:1 on every tile fill, even without its halo', () => {
    const low = Object.entries(TILES)
      .map(([name, v]) => {
        const [r, g, b] = parseColour(v);
        const tile = [r * k, g * k, b * k, 1] as [number, number, number, number];
        return { name, tile: hex(tile), ratio: ratio(trail, tile) };
      })
      .filter((t) => t.ratio < 3)
      .map((t) => `${t.name} ${t.tile}: ${t.ratio.toFixed(2)}:1`);
    expect(low).toEqual([]);
  });

  it('the trail reaches 3:1 on its halo (the markers’ rings)', () => {
    expect(ratio(trail, halo)).toBeGreaterThanOrEqual(3);
  });
});
