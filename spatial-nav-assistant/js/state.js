// Global telemetry/app state. A single mutable object plus a tiny pub/sub so
// the map, HUD and agent tools all read the same values.

// Fallback pose used until the first GPS fix arrives (Union Square, San Francisco).
export const DEFAULT_POSITION = Object.freeze({ lat: 37.7880, lon: -122.4075 });

export const state = {
  lat: DEFAULT_POSITION.lat,
  lon: DEFAULT_POSITION.lon,
  heading: 0, // degrees clockwise from north, [0, 360)
  accuracy: null, // metres, from the Geolocation API
  hasFix: false, // true once a real GPS fix has been received
  headingSource: 'none', // 'ios-compass' | 'absolute-alpha' | 'alpha' | 'manual' | 'none'
  route: null, // active reroute_navigation result, if any
};

const listeners = new Set();

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function updateState(patch) {
  Object.assign(state, patch);
  for (const fn of listeners) fn(state);
}
