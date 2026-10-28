import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { cx } from './cx';
import './LocationMap.css';
import type { LocationFix } from '../api/deviceLocations';
import { fmtDateTime } from './format';

/** A token's computed value on the element (so the trail follows the theme), with a fallback. */
function token(el: HTMLElement, name: string, fallback: string): string {
  const v = getComputedStyle(el).getPropertyValue(name).trim();
  return v || fallback;
}

/** A text-only popup body (never parsed as HTML). */
function popupText(text: string): HTMLElement {
  const el = document.createElement('span');
  el.textContent = text;
  return el;
}

/**
 * Leaflet + OpenStreetMap breadcrumb map (the console's colours). Not in the barrel (it pulls in Leaflet):
 * import it from '../ui/LocationMap'. `fixes` are newest-first as the API
 * returns them; the trail is drawn chronologically, the latest fix highlighted, each fix with a popup.
 * Circle markers only (no image assets, no bundler icon-path issues).
 */
export function LocationMap({ fixes, label, className }: { fixes: LocationFix[]; label: string; className?: string }) {
  const elRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);

  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    if (!mapRef.current) {
      mapRef.current = L.map(el, { worldCopyJump: true });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '© OpenStreetMap contributors',
        // The edge sends Referrer-Policy: same-origin, so tiles would go without a Referer, which OpenStreetMap's
        // tile policy requires. Send the origin only (never the console path), as browsers do by default.
        referrerPolicy: 'strict-origin-when-cross-origin',
      }).addTo(mapRef.current);
      layerRef.current = L.layerGroup().addTo(mapRef.current);
    }
    const map = mapRef.current;
    const layer = layerRef.current!;
    layer.clearLayers();

    if (!fixes.length) {
      map.setView([20, 0], 2);
      return;
    }
    const trail = token(el, '--text-2', '#384152');
    const latestColour = token(el, '--online', '#1aa766');
    const chrono = [...fixes].reverse();
    const pts = chrono.map((f) => [f.lat, f.lon] as [number, number]);
    L.polyline(pts, { color: trail, weight: 3, opacity: 0.55 }).addTo(layer);
    fixes.forEach((f, i) => {
      const latest = i === 0;
      L.circleMarker([f.lat, f.lon], {
        radius: latest ? 7 : 4,
        color: latest ? latestColour : trail,
        fillColor: latest ? latestColour : trail,
        fillOpacity: latest ? 0.9 : 0.45,
        weight: latest ? 2 : 1,
      })
        // A DOM node, not a string: Leaflet renders popup strings as HTML, and `provider` comes from the device.
        .bindPopup(
          popupText(
            `${fmtDateTime(f.capturedAt)}` +
              (f.accuracy != null ? ` · ±${Math.round(f.accuracy)} m` : '') +
              (f.provider ? ` · ${f.provider}` : ''),
          ),
        )
        .addTo(layer);
    });
    map.fitBounds(L.latLngBounds(pts), { padding: [24, 24], maxZoom: 17 });
    // The container is sized by layout after first paint; recompute so the tiles fill it.
    const t = setTimeout(() => map.invalidateSize(), 0);
    return () => clearTimeout(t);
  }, [fixes]);

  useEffect(
    () => () => {
      mapRef.current?.remove();
      mapRef.current = null;
    },
    [],
  );

  return <div ref={elRef} className={cx('v2-locmap', className)} role="region" aria-label={label} />;
}
