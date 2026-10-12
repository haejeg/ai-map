import { destinationPoint } from './spatial.js';

// Mock POIs defined by a fixed absolute bearing/distance from an anchor point.
// Anchoring them to the user's first fix keeps the demo meaningful anywhere in
// the world while staying fully deterministic.
export const POI_TEMPLATES = Object.freeze([
  { id: 'cafe', name: 'Blue Door Coffee', category: 'cafe', bearing: 10, distance: 120 },
  { id: 'pharmacy', name: 'Corner Pharmacy', category: 'pharmacy', bearing: 40, distance: 260 },
  { id: 'park', name: 'Linden Pocket Park', category: 'park', bearing: 75, distance: 180 },
  { id: 'bus', name: 'Market Street Bus Stop', category: 'transit', bearing: 100, distance: 90 },
  { id: 'library', name: 'Public Library', category: 'library', bearing: 150, distance: 340 },
  { id: 'grocery', name: 'Fresh Grocer', category: 'grocery', bearing: 200, distance: 210 },
  { id: 'museum', name: 'City History Museum', category: 'museum', bearing: 250, distance: 420 },
  { id: 'atm', name: 'ATM Kiosk', category: 'atm', bearing: 290, distance: 60 },
  { id: 'hospital', name: 'General Hospital', category: 'hospital', bearing: 330, distance: 480 },
]);

export function buildMockPois(anchorLat, anchorLon, templates = POI_TEMPLATES) {
  return templates.map(({ bearing, distance, ...rest }) => ({
    ...rest,
    ...destinationPoint(anchorLat, anchorLon, bearing, distance),
  }));
}
