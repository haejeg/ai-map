// Spatial bearing engine. Pure functions only (no DOM), so the same module runs
// in the browser and under Node's test runner.

export const EARTH_RADIUS_M = 6371008.8;

export const DIRECTIONS = Object.freeze(['ahead', 'right', 'behind', 'left']);

const toRad = (deg) => (deg * Math.PI) / 180;
const toDeg = (rad) => (rad * 180) / Math.PI;

/** Wrap any angle into [0, 360). */
export function normalizeAngle(deg) {
  const wrapped = deg % 360;
  // `+ 0` turns -0 into 0 so callers never see a negative zero.
  return (wrapped < 0 ? wrapped + 360 : wrapped) + 0;
}

/**
 * Initial great-circle bearing (forward azimuth) from point 1 to point 2,
 * in degrees clockwise from true north, range [0, 360).
 *
 *   θ = atan2( sin Δλ · cos φ2 , cos φ1 · sin φ2 − sin φ1 · cos φ2 · cos Δλ )
 */
export function forwardAzimuth(lat1, lon1, lat2, lon2) {
  const φ1 = toRad(lat1);
  const φ2 = toRad(lat2);
  const Δλ = toRad(lon2 - lon1);
  const y = Math.sin(Δλ) * Math.cos(φ2);
  const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
  return normalizeAngle(toDeg(Math.atan2(y, x)));
}

/** Great-circle distance in metres (haversine). */
export function haversineDistance(lat1, lon1, lat2, lon2) {
  const φ1 = toRad(lat1);
  const φ2 = toRad(lat2);
  const Δφ = toRad(lat2 - lat1);
  const Δλ = toRad(lon2 - lon1);
  const a = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** Point reached by travelling `distanceM` metres from a start along `bearingDeg`. */
export function destinationPoint(lat, lon, bearingDeg, distanceM) {
  const δ = distanceM / EARTH_RADIUS_M;
  const θ = toRad(bearingDeg);
  const φ1 = toRad(lat);
  const λ1 = toRad(lon);
  const φ2 = Math.asin(Math.sin(φ1) * Math.cos(δ) + Math.cos(φ1) * Math.sin(δ) * Math.cos(θ));
  const λ2 = λ1 + Math.atan2(
    Math.sin(θ) * Math.sin(δ) * Math.cos(φ1),
    Math.cos(δ) - Math.sin(φ1) * Math.sin(φ2),
  );
  return { lat: toDeg(φ2), lon: ((toDeg(λ2) + 540) % 360) - 180 };
}

/** Angle of a target relative to where the user faces: 0 = dead ahead, 90 = right. */
export function relativeBearing(targetBearing, heading) {
  return normalizeAngle(targetBearing - heading);
}

/**
 * Bucket a relative bearing into a quadrant. Ranges are half-open so every
 * angle maps to exactly one bucket:
 *   ahead  [315, 360) ∪ [0, 45)
 *   right  [45, 135)
 *   behind [135, 225)
 *   left   [225, 315)
 */
export function categorizeRelativeBearing(relative) {
  const r = normalizeAngle(relative);
  if (r >= 315 || r < 45) return 'ahead';
  if (r < 135) return 'right';
  if (r < 225) return 'behind';
  return 'left';
}

/** Full analysis of one target relative to a user pose. */
export function analyzePoint(user, target) {
  const bearing = forwardAzimuth(user.lat, user.lon, target.lat, target.lon);
  const relative = relativeBearing(bearing, user.heading);
  return {
    bearing,
    relative,
    direction: categorizeRelativeBearing(relative),
    distance: haversineDistance(user.lat, user.lon, target.lat, target.lon),
  };
}

/** Synonyms a speaker might use, mapped to the canonical quadrant. */
const DIRECTION_ALIASES = {
  ahead: 'ahead', front: 'ahead', forward: 'ahead', 'in front': 'ahead', straight: 'ahead',
  right: 'right',
  behind: 'behind', back: 'behind', rear: 'behind',
  left: 'left',
  all: 'all', around: 'all', any: 'all', everywhere: 'all',
};

export function normalizeDirection(direction) {
  if (direction == null) return 'all';
  const key = String(direction).trim().toLowerCase();
  const canonical = DIRECTION_ALIASES[key];
  if (!canonical) throw new RangeError(`Unknown direction "${direction}"`);
  return canonical;
}

/**
 * Annotate POIs with bearing/direction/distance, keep those in the requested
 * quadrant and radius, and sort nearest first.
 */
export function filterPoisByDirection(user, pois, direction = 'all', maxDistanceM = Infinity) {
  const wanted = normalizeDirection(direction);
  return pois
    .map((poi) => ({ ...poi, ...analyzePoint(user, poi) }))
    .filter((p) => (wanted === 'all' || p.direction === wanted) && p.distance <= maxDistanceM)
    .sort((a, b) => a.distance - b.distance);
}

const COMPASS_POINTS = ['north', 'northeast', 'east', 'southeast', 'south', 'southwest', 'west', 'northwest'];

export function compassName(bearing) {
  return COMPASS_POINTS[Math.round(normalizeAngle(bearing) / 45) % 8];
}
