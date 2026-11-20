import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { cx } from './cx';
import './LocationMap.css';
import type { LocationFix } from '../api/deviceLocations';
import { fmtDateTime } from './format';
import { arrowSegments, showPoints, type Pt } from './locationTrail';

/** On screen: arrows on segments from 20 px long, 72 px apart; a dot per fix once their median gap is 14 px. */
const ARROW_MIN_PX = 20;
const ARROW_SPACING_PX = 72;
const POINT_GAP_PX = 14;

/**
 * The console's icons (icons.tsx) as markup, for the controls Leaflet draws itself: IconPlus, its minus, and
 * IconClose, same viewBox, paths and stroke (LocationMap.test.tsx keeps them in step).
 */
const svg = (viewBox: string, size: number, stroke: number, d: string) =>
  `<svg width="${size}" height="${size}" viewBox="${viewBox}" fill="none" stroke="currentColor" ` +
  `stroke-width="${stroke}" aria-hidden="true" focusable="false"><path d="${d}"></path></svg>`;
export const MAP_ICONS = {
  zoomIn: svg('0 0 16 16', 15, 1.8, 'M8 2.5v11M2.5 8h11'),
  zoomOut: svg('0 0 16 16', 15, 1.8, 'M2.5 8h11'),
  close: svg('0 0 12 12', 12, 1.6, 'M3 3l6 6M9 3l-6 6'),
};

/** A fix's popup: the console's popover, no tip, a few px off its marker. */
const POPUP: L.PopupOptions = { offset: [0, 12], closeButton: true };

/** A text-only popup body (never parsed as HTML). */
function popupText(text: string): HTMLElement {
  const el = document.createElement('span');
  el.textContent = text;
  return el;
}

/** A fix's popup: which end it is (if one), when, how accurate, from which provider. */
function popupFor(f: LocationFix, role?: string): HTMLElement {
  return popupText(
    (role ? `${role} · ` : '') +
      fmtDateTime(f.capturedAt) +
      (f.accuracy != null ? ` · ±${Math.round(f.accuracy)} m` : '') +
      (f.provider ? ` · ${f.provider}` : ''),
  );
}

/** A map and what is drawn on it (LocationMap's mapRef). */
interface MapState {
  map: L.Map;
  /** The path and its two ends. */
  layer: L.LayerGroup;
  /** What depends on the view (the arrows and the fixes' dots), redrawn after every zoom and pan. */
  zoomed: { dots: L.LayerGroup; arrows: L.LayerGroup; draw: () => void };
  /** The fixes drawn last (count and newest), and the fitKey the view was last fitted for (null: never fitted). */
  drawn: string | null;
  fitted: { key: string | number | undefined } | null;
}

/** A direction arrow: a notched arrowhead pointing right, turned to its segment (colours in the CSS). */
function arrowIcon(angle: number): L.DivIcon {
  return L.divIcon({
    className: 'v2-locmap__arrow',
    // Turned by an SVG transform attribute, not an inline style, which a CSP style-src would block.
    html: `<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path transform="rotate(${angle.toFixed(1)} 8 8)" d="M3 3 L14 8 L3 13 L6 8 Z"/></svg>`,
    iconSize: [16, 16],
  });
}

/**
 * Leaflet + OpenStreetMap breadcrumb map. Not in the barrel (it pulls in Leaflet): import it from '../ui/LocationMap'.
 * `fixes` are newest-first as the API returns them. The path is drawn oldest to newest as a dark line on a white
 * casing (3:1 on any tile, mapContrast.test.ts), with arrows along it for the direction, a ring at the oldest fix and
 * a solid dot at the latest, and a key under the map. Each fix's own dot shows only while fixes sit far enough apart
 * not to crowd, so zoomed out the path stays a clean line; every dot opens a popup. Colours are set by the CSS
 * (tokens). SVG and div markers only: no image assets, no bundler icon-path issues.
 *
 * The map fits the path on its first draw and when `fitKey` changes (the time range); a reload that brings new fixes
 * redraws the path but keeps the user's pan and zoom, and one that brings the same fixes (same count, same newest)
 * changes nothing. `className` goes on the outer box (the map and its key).
 */
export function LocationMap({
  fixes,
  label,
  className,
  fitKey,
}: {
  fixes: LocationFix[];
  label: string;
  className?: string;
  fitKey?: string | number;
}) {
  const elRef = useRef<HTMLDivElement>(null);
  /**
   * The map and everything drawn on it, made and dropped together: a map made again (StrictMode runs effects twice,
   * a remount keeps refs) starts with nothing drawn and nothing fitted.
   */
  const mapRef = useRef<MapState | null>(null);

  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    if (!mapRef.current) {
      const map = L.map(el, { worldCopyJump: true, zoomControl: false });
      // Zoom in and out as the console's icon buttons, with its plus and minus icons (LocationMap.css).
      L.control
        .zoom({
          zoomInText: MAP_ICONS.zoomIn,
          zoomOutText: MAP_ICONS.zoomOut,
          zoomInTitle: 'Zoom in',
          zoomOutTitle: 'Zoom out',
        })
        .addTo(map);
      // The credit OpenStreetMap's licence asks for, linked to its copyright page; no Leaflet prefix (or its flag).
      map.attributionControl.setPrefix(false);
      // A popup's close button: the console's close icon in place of Leaflet's "×".
      map.on('popupopen', (e) => {
        const close = e.popup.getElement()?.querySelector<HTMLElement>('.leaflet-popup-close-button');
        if (close && !close.querySelector('svg')) close.innerHTML = MAP_ICONS.close;
      });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution:
          '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">' +
          'OpenStreetMap</a> contributors',
        // The edge sends Referrer-Policy: same-origin, so tiles would go without a Referer, which OpenStreetMap's
        // tile policy requires. Send the origin only (never the console path), as browsers do by default.
        referrerPolicy: 'strict-origin-when-cross-origin',
      }).addTo(map);
      // Over the path (overlayPane, 400), under the popups: the arrows, then the oldest and latest markers.
      map.createPane('v2-trail-arrows').style.zIndex = '450';
      map.createPane('v2-trail-ends').style.zIndex = '460';
      const zoomed = { dots: L.layerGroup().addTo(map), arrows: L.layerGroup().addTo(map), draw: () => {} };
      map.on('moveend', () => zoomed.draw());
      mapRef.current = { map, layer: L.layerGroup().addTo(map), zoomed, drawn: null, fitted: null };
    }
    const state = mapRef.current;
    const { map, layer, zoomed } = state;
    const drawn = `${fixes.length}:${fixes[0]?.id ?? ''}:${fixes[0]?.capturedAt ?? ''}`;
    const fit = !state.fitted || state.fitted.key !== fitKey;
    if (drawn === state.drawn && !fit) return;
    state.drawn = drawn;
    layer.clearLayers();
    zoomed.dots.clearLayers();
    zoomed.arrows.clearLayers();
    zoomed.draw = () => {};

    if (!fixes.length) {
      map.setView([20, 0], 2);
      state.fitted = null;
      return;
    }
    const chrono = [...fixes].reverse();
    const pts = chrono.map((f) => L.latLng(f.lat, f.lon));
    const line = { interactive: false, lineCap: 'round', lineJoin: 'round', opacity: 1 } as const;
    L.polyline(pts, { ...line, className: 'v2-locmap__halo', weight: 8 }).addTo(layer);
    L.polyline(pts, { ...line, className: 'v2-locmap__line', weight: 4 }).addTo(layer);
    // Rings 2 px, the dots' 1.5 px: the console icons' strokes, a size up for the map.
    const end = { pane: 'v2-trail-ends', weight: 2, fillOpacity: 1 };
    if (chrono.length > 1) {
      L.circleMarker(pts[0], { ...end, className: 'v2-locmap__oldest', radius: 6 })
        // A DOM node, not a string: Leaflet renders popup strings as HTML, and `provider` comes from the device.
        .bindPopup(popupFor(chrono[0], 'Oldest'), POPUP)
        .addTo(layer);
    }
    L.circleMarker(pts[pts.length - 1], { ...end, className: 'v2-locmap__latest', radius: 8 })
      .bindPopup(popupFor(fixes[0], 'Latest'), POPUP)
      .addTo(layer);

    // The dots are rebuilt only when they come or go (a pan must not close a dot's open popup); the arrows after
    // every move, for the part of the path in and near the view (zoomed in on a long trail there would be thousands).
    let dotsShown = false;
    zoomed.draw = () => {
      const z = map.getZoom();
      const px: Pt[] = pts.map((p) => map.project(p, z));
      const dots = showPoints(px, POINT_GAP_PX);
      if (dots !== dotsShown) {
        dotsShown = dots;
        zoomed.dots.clearLayers();
        for (let i = 1; dots && i < chrono.length - 1; i++) {
          L.circleMarker(pts[i], { className: 'v2-locmap__point', radius: 3.5, weight: 1.5, fillOpacity: 1 })
            .bindPopup(popupFor(chrono[i]), POPUP)
            .addTo(zoomed.dots);
        }
      }
      zoomed.arrows.clearLayers();
      const view = map.getPixelBounds().pad(0.25);
      for (const a of arrowSegments(px, ARROW_MIN_PX, ARROW_SPACING_PX, { min: view.min!, max: view.max! })) {
        L.marker(map.unproject([a.x, a.y], z), {
          icon: arrowIcon(a.angle),
          pane: 'v2-trail-arrows',
          interactive: false,
          keyboard: false,
        }).addTo(zoomed.arrows);
      }
    };
    if (fit) {
      state.fitted = { key: fitKey };
      // Not animated: the view is final at once, and its moveend draws the arrows and dots for it.
      map.fitBounds(L.latLngBounds(pts), { padding: [32, 32], maxZoom: 17, animate: false });
    } else {
      zoomed.draw();
    }
    // The container is sized by layout after first paint; recompute so the tiles fill it.
    const t = setTimeout(() => map.invalidateSize(), 0);
    return () => clearTimeout(t);
  }, [fixes, fitKey]);

  useEffect(
    () => () => {
      mapRef.current?.map.remove();
      mapRef.current = null;
    },
    [],
  );

  const trail = fixes.length > 1;
  return (
    <div className={cx('v2-locmap-wrap', className)}>
      <div ref={elRef} className="v2-locmap" role="region" aria-label={label} />
      {/* What the marks mean (each fix's time is in its popup and in the list below). */}
      <ul className="v2-locmap-key" aria-label="Map key">
        {/* Swatches on the console's 16 px icon grid, drawn as the marks on the map. */}
        {trail ? (
          <li>
            <svg className="v2-locmap-key__swatch" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <path className="v2-locmap-key__halo" d="M2 8h12" />
              <path className="v2-locmap-key__line" d="M2 8h12" />
              <path className="v2-locmap-key__arrow" d="M5.5 4.5 L12 8 L5.5 11.5 L7.5 8 Z" />
            </svg>
            Path, oldest to latest
          </li>
        ) : null}
        {trail ? (
          <li>
            <svg className="v2-locmap-key__swatch" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <circle className="v2-locmap-key__oldest" cx="8" cy="8" r="4.5" />
            </svg>
            Oldest
          </li>
        ) : null}
        <li>
          <svg className="v2-locmap-key__swatch" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
            <circle className="v2-locmap-key__latest" cx="8" cy="8" r="5.5" />
          </svg>
          Latest
        </li>
      </ul>
    </div>
  );
}
