// MapLibre GL rendering: user marker with heading cone, POI markers, route line.
/* global maplibregl */

const STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';

const DIRECTION_COLORS = {
  ahead: '#2f9e44',
  right: '#1971c2',
  behind: '#868e96',
  left: '#e8590c',
};

export function createMapView(container, { lat, lon }) {
  const map = new maplibregl.Map({
    container,
    style: STYLE_URL,
    center: [lon, lat],
    zoom: 16,
    attributionControl: { compact: true },
  });
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: false }), 'top-right');

  const userEl = document.createElement('div');
  userEl.className = 'user-marker';
  userEl.innerHTML = '<div class="user-cone"></div><div class="user-dot"></div>';
  const userMarker = new maplibregl.Marker({ element: userEl, rotationAlignment: 'map' })
    .setLngLat([lon, lat])
    .addTo(map);

  const poiMarkers = new Map();
  let followUser = true;
  map.on('dragstart', () => { followUser = false; });

  const ready = new Promise((resolve) => map.on('load', resolve));
  ready.then(() => {
    map.addSource('route', { type: 'geojson', data: emptyLine() });
    map.addLayer({
      id: 'route-line',
      type: 'line',
      source: 'route',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#7048e8', 'line-width': 5, 'line-dasharray': [1, 1.5] },
    });
  });

  return {
    map,
    updateUser({ lat: uLat, lon: uLon, heading }) {
      userMarker.setLngLat([uLon, uLat]).setRotation(heading);
      if (followUser) map.easeTo({ center: [uLon, uLat], duration: 400 });
    },
    recenter() {
      followUser = true;
      map.easeTo({ center: userMarker.getLngLat(), zoom: 16, duration: 600 });
    },
    /** @param {Array<{id: string, name: string, lat: number, lon: number, direction?: string}>} pois */
    setPois(pois) {
      for (const poi of pois) {
        let marker = poiMarkers.get(poi.id);
        if (!marker) {
          const el = document.createElement('div');
          el.className = 'poi-marker';
          el.title = poi.name;
          el.innerHTML = `<span class="poi-pin"></span><span class="poi-label">${escapeHtml(poi.name)}</span>`;
          marker = new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat([poi.lon, poi.lat]).addTo(map);
          poiMarkers.set(poi.id, marker);
        }
        marker.setLngLat([poi.lon, poi.lat]);
        const el = marker.getElement();
        el.style.setProperty('--poi-color', DIRECTION_COLORS[poi.direction] ?? '#495057');
        el.dataset.direction = poi.direction ?? '';
      }
    },
    highlightPois(names) {
      const wanted = new Set(names);
      for (const marker of poiMarkers.values()) {
        const el = marker.getElement();
        el.classList.toggle('highlight', wanted.has(el.title));
      }
    },
    async setRoute(from, to) {
      await ready;
      const data = to
        ? { type: 'Feature', geometry: { type: 'LineString', coordinates: [[from.lon, from.lat], [to.lon, to.lat]] } }
        : emptyLine();
      map.getSource('route').setData(data);
    },
  };
}

function emptyLine() {
  return { type: 'Feature', geometry: { type: 'LineString', coordinates: [] } };
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
