import { analyzePoint, compassName, filterPoisByDirection, normalizeDirection } from './spatial.js';

export const DEFAULT_SEARCH_RADIUS_M = 500;
const WALKING_SPEED_MPS = 1.4;

// Tool schemas in the JSON-schema shape LLM function-calling APIs expect.
export const TOOL_DEFINITIONS = Object.freeze([
  {
    name: 'get_current_location',
    description: "Return the user's current coordinates, GPS accuracy and compass heading.",
    input_schema: { type: 'object', properties: {}, required: [] },
  },
  {
    name: 'reroute_navigation',
    description: 'Start navigating to a named nearby destination and return distance, bearing and ETA.',
    input_schema: {
      type: 'object',
      properties: { destination: { type: 'string', description: 'Name of the place to navigate to.' } },
      required: ['destination'],
    },
  },
  {
    name: 'get_surrounding_pois',
    description: "List points of interest in a direction relative to where the user is facing.",
    input_schema: {
      type: 'object',
      properties: {
        direction: { type: 'string', enum: ['ahead', 'right', 'behind', 'left', 'all'] },
      },
      required: ['direction'],
    },
  },
]);

const round = (n, digits = 0) => Number(n.toFixed(digits));

/**
 * Build deterministic tool handlers bound to app state.
 * @param {{ getState: () => object, getPois: () => object[], onReroute?: (route: object) => void }} deps
 */
export function createTools({ getState, getPois, onReroute = () => {} }) {
  const userPose = () => {
    const { lat, lon, heading } = getState();
    return { lat, lon, heading };
  };

  return {
    get_current_location() {
      const s = getState();
      return {
        lat: round(s.lat, 6),
        lon: round(s.lon, 6),
        heading: round(s.heading, 1),
        facing: compassName(s.heading),
        accuracy_m: s.accuracy == null ? null : round(s.accuracy),
        live_gps: Boolean(s.hasFix),
      };
    },

    reroute_navigation({ destination } = {}) {
      const query = String(destination ?? '').trim().toLowerCase();
      if (!query) return { ok: false, error: 'No destination given.' };

      const pois = getPois();
      const match =
        pois.find((p) => p.name.toLowerCase() === query) ??
        pois.find((p) => p.name.toLowerCase().includes(query) || query.includes(p.name.toLowerCase())) ??
        pois.find((p) => p.category === query);
      if (!match) {
        return { ok: false, error: `No nearby place matches "${destination}".`, known: pois.map((p) => p.name) };
      }

      const a = analyzePoint(userPose(), match);
      const route = {
        ok: true,
        destination: match.name,
        target: { lat: match.lat, lon: match.lon },
        distance_m: round(a.distance),
        bearing: round(a.bearing, 1),
        compass: compassName(a.bearing),
        relative_direction: a.direction,
        eta_min: Math.max(1, Math.round(a.distance / WALKING_SPEED_MPS / 60)),
      };
      onReroute(route);
      return route;
    },

    get_surrounding_pois({ direction = 'all', radius_m = DEFAULT_SEARCH_RADIUS_M } = {}) {
      let canonical;
      try {
        canonical = normalizeDirection(direction);
      } catch (err) {
        return { ok: false, error: err.message };
      }
      const found = filterPoisByDirection(userPose(), getPois(), canonical, radius_m);
      return {
        ok: true,
        direction: canonical,
        radius_m,
        pois: found.map((p) => ({
          name: p.name,
          category: p.category,
          distance_m: round(p.distance),
          bearing: round(p.bearing, 1),
          relative_bearing: round(p.relative, 1),
          direction: p.direction,
        })),
      };
    },
  };
}
