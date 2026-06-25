import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { LocationFix } from '../api/deviceLocations';

/**
 * Leaflet + OpenStreetMap breadcrumb map. [fixes] are newest-first (as the API returns them);
 * we draw the trail chronologically with a polyline + circle markers and highlight the latest fix.
 * Uses circleMarkers (no image assets) to avoid Leaflet's bundler icon-path issues.
 */
export function LocationMap({ fixes }: { fixes: LocationFix[] }) {
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
    const chrono = [...fixes].reverse();
    const pts = chrono.map((f) => [f.lat, f.lon] as [number, number]);
    L.polyline(pts, { color: '#3b82f6', weight: 3, opacity: 0.65 }).addTo(layer);
    fixes.forEach((f, i) => {
      const latest = i === 0;
      L.circleMarker([f.lat, f.lon], {
        radius: latest ? 7 : 4,
        color: latest ? '#16a34a' : '#3b82f6',
        fillColor: latest ? '#16a34a' : '#3b82f6',
        fillOpacity: latest ? 0.9 : 0.5,
        weight: latest ? 2 : 1,
      })
        .bindPopup(
          `${new Date(f.capturedAt).toLocaleString()}` +
            (f.accuracy != null ? ` · ±${Math.round(f.accuracy)} m` : '') +
            (f.provider ? ` · ${f.provider}` : ''),
        )
        .addTo(layer);
    });
    map.fitBounds(L.latLngBounds(pts), { padding: [24, 24], maxZoom: 17 });
    // Container is often sized by layout after first paint; recompute so tiles fill it.
    setTimeout(() => map.invalidateSize(), 0);
  }, [fixes]);

  useEffect(
    () => () => {
      mapRef.current?.remove();
      mapRef.current = null;
    },
    [],
  );

  return <div ref={elRef} className="loc-map" />;
}
