import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  analyzePoint,
  categorizeRelativeBearing,
  destinationPoint,
  filterPoisByDirection,
  forwardAzimuth,
  haversineDistance,
  normalizeAngle,
  normalizeDirection,
  relativeBearing,
} from '../js/spatial.js';
import { headingFromOrientationEvent, smoothHeading } from '../js/telemetry.js';

const approx = (actual, expected, tol, msg) =>
  assert.ok(Math.abs(actual - expected) <= tol, `${msg ?? ''} expected ${expected} ±${tol}, got ${actual}`);

// Angular difference along the shortest arc, for comparing bearings near 0/360.
const angDiff = (a, b) => Math.abs(((a - b + 540) % 360) - 180);

// Simulated user: Times Square, facing due east.
const USER = { lat: 40.7580, lon: -73.9855, heading: 90 };

describe('normalizeAngle', () => {
  for (const [input, expected] of [[0, 0], [360, 0], [720, 0], [-90, 270], [450, 90], [-360, 0], [359.5, 359.5]]) {
    it(`${input} → ${expected}`, () => assert.equal(normalizeAngle(input), expected));
  }
});

describe('forwardAzimuth', () => {
  it('cardinal directions from the origin', () => {
    approx(forwardAzimuth(0, 0, 1, 0), 0, 1e-9, 'north');
    approx(forwardAzimuth(0, 0, 0, 1), 90, 1e-9, 'east');
    approx(forwardAzimuth(0, 0, -1, 0), 180, 1e-9, 'south');
    approx(forwardAzimuth(0, 0, 0, -1), 270, 1e-9, 'west');
  });

  it('matches the published Baghdad → Osaka initial bearing (~60°)', () => {
    approx(forwardAzimuth(35, 45, 35, 135), 60.16, 0.05);
  });

  it('is always in [0, 360)', () => {
    for (let lon = -179; lon <= 179; lon += 17) {
      const b = forwardAzimuth(10, 0, -20, lon);
      assert.ok(b >= 0 && b < 360, `bearing ${b} out of range`);
    }
  });
});

describe('relativeBearing + categorizeRelativeBearing boundaries', () => {
  const cases = [
    [0, 'ahead'], [44.999, 'ahead'], [45, 'right'], [134.999, 'right'],
    [135, 'behind'], [224.999, 'behind'], [225, 'left'], [314.999, 'left'],
    [315, 'ahead'], [359.999, 'ahead'], [360, 'ahead'], [-45, 'ahead'], [-46, 'left'], [810, 'right'],
  ];
  for (const [rel, expected] of cases) {
    it(`relative ${rel}° → ${expected}`, () => assert.equal(categorizeRelativeBearing(rel), expected));
  }

  it('wraps heading across north (heading 350°, target 10° → +20°, ahead)', () => {
    assert.equal(relativeBearing(10, 350), 20);
    assert.equal(categorizeRelativeBearing(relativeBearing(10, 350)), 'ahead');
  });
  it('wraps the other way (heading 10°, target 350° → 340°, ahead)', () => {
    assert.equal(relativeBearing(350, 10), 340);
  });
});

describe('user facing east (heading 90°) — independent raw-offset POIs', () => {
  const d = 0.001; // ~111 m in latitude
  const cases = [
    { name: 'due east', lat: USER.lat, lon: USER.lon + d, expected: 'ahead' },
    { name: 'due south', lat: USER.lat - d, lon: USER.lon, expected: 'right' },
    { name: 'due west', lat: USER.lat, lon: USER.lon - d, expected: 'behind' },
    { name: 'due north', lat: USER.lat + d, lon: USER.lon, expected: 'left' },
  ];
  for (const c of cases) {
    it(`${c.name} is ${c.expected}`, () => assert.equal(analyzePoint(USER, c).direction, c.expected));
  }
});

describe('user facing east (heading 90°) — POIs at specific absolute bearings', () => {
  const cases = [
    [60, 330, 'ahead'], [120, 30, 'ahead'], [150, 60, 'right'], [200, 110, 'right'],
    [250, 160, 'behind'], [300, 210, 'behind'], [340, 250, 'left'], [20, 290, 'left'],
  ];
  for (const [absolute, rel, expected] of cases) {
    it(`bearing ${absolute}° → relative ${rel}° → ${expected}`, () => {
      const p = destinationPoint(USER.lat, USER.lon, absolute, 150);
      const a = analyzePoint(USER, p);
      assert.ok(angDiff(a.bearing, absolute) < 0.01, `bearing ${a.bearing} vs ${absolute}`);
      assert.ok(angDiff(a.relative, rel) < 0.01, `relative ${a.relative} vs ${rel}`);
      assert.equal(a.direction, expected);
    });
  }
});

describe('exhaustive sweep: every heading × relative angle', () => {
  const expectedFor = (rel) => (rel >= 315 || rel < 45 ? 'ahead' : rel < 135 ? 'right' : rel < 225 ? 'behind' : 'left');
  const boundaries = [0, 45, 135, 225, 315, 360];

  it('categorises 4,320 synthetic POIs correctly at multiple latitudes', () => {
    let checked = 0;
    for (const lat of [-60, 0, 40.758, 70]) {
      for (let heading = 0; heading < 360; heading += 30) {
        for (let rel = 0.5; rel < 360; rel += 4) {
          if (boundaries.some((b) => Math.abs(rel - b) < 0.05)) continue;
          const user = { lat, lon: 10, heading };
          const p = destinationPoint(lat, 10, heading + rel, 250);
          const got = analyzePoint(user, p).direction;
          assert.equal(got, expectedFor(rel), `lat ${lat} heading ${heading} rel ${rel}`);
          checked++;
        }
      }
    }
    assert.equal(checked, 4 * 12 * 90);
  });
});

describe('distance helpers', () => {
  it('destinationPoint and haversineDistance round-trip', () => {
    for (const bearing of [0, 37, 90, 181, 299]) {
      const p = destinationPoint(USER.lat, USER.lon, bearing, 400);
      approx(haversineDistance(USER.lat, USER.lon, p.lat, p.lon), 400, 0.01, `bearing ${bearing}`);
    }
  });
  it('one degree of latitude ≈ 111.2 km', () => {
    approx(haversineDistance(0, 0, 1, 0), 111195, 5);
  });
});

describe('filterPoisByDirection', () => {
  const pois = [
    { id: 'n', name: 'North Deli', ...destinationPoint(USER.lat, USER.lon, 0, 100) },
    { id: 'nn', name: 'Far North Bank', ...destinationPoint(USER.lat, USER.lon, 355, 900) },
    { id: 'nw', name: 'Northwest Pizza', ...destinationPoint(USER.lat, USER.lon, 330, 50) },
    { id: 'e', name: 'East Bakery', ...destinationPoint(USER.lat, USER.lon, 90, 200) },
    { id: 's', name: 'South Gym', ...destinationPoint(USER.lat, USER.lon, 180, 150) },
    { id: 'w', name: 'West Cafe', ...destinationPoint(USER.lat, USER.lon, 270, 300) },
  ];

  it('left of an east-facing user = northern POIs, nearest first', () => {
    const names = filterPoisByDirection(USER, pois, 'left').map((p) => p.name);
    assert.deepEqual(names, ['Northwest Pizza', 'North Deli', 'Far North Bank']);
  });
  it('respects the search radius', () => {
    const names = filterPoisByDirection(USER, pois, 'left', 500).map((p) => p.name);
    assert.deepEqual(names, ['Northwest Pizza', 'North Deli']);
  });
  it('ahead/right/behind', () => {
    assert.deepEqual(filterPoisByDirection(USER, pois, 'ahead').map((p) => p.id), ['e']);
    assert.deepEqual(filterPoisByDirection(USER, pois, 'right').map((p) => p.id), ['s']);
    assert.deepEqual(filterPoisByDirection(USER, pois, 'behind').map((p) => p.id), ['w']);
  });
  it('"all" returns every POI exactly once', () => {
    assert.equal(filterPoisByDirection(USER, pois, 'all').length, pois.length);
  });
  it('accepts spoken synonyms and rejects junk', () => {
    assert.equal(normalizeDirection('Front'), 'ahead');
    assert.equal(normalizeDirection('back'), 'behind');
    assert.throws(() => normalizeDirection('upwards'), RangeError);
  });
});

describe('telemetry heading extraction', () => {
  it('iOS webkitCompassHeading is used directly', () => {
    assert.deepEqual(headingFromOrientationEvent({ webkitCompassHeading: 90, alpha: 12 }), { heading: 90, source: 'ios-compass' });
  });
  it('Android alpha is counter-clockwise: alpha 270 → heading 90', () => {
    assert.deepEqual(headingFromOrientationEvent({ alpha: 270, absolute: true }), { heading: 90, source: 'absolute-alpha' });
    assert.equal(headingFromOrientationEvent({ alpha: 0, absolute: true }).heading, 0);
  });
  it('corrects for landscape screen rotation', () => {
    assert.equal(headingFromOrientationEvent({ alpha: 0, absolute: true }, 90).heading, 90);
  });
  it('returns null without sensor data', () => {
    assert.equal(headingFromOrientationEvent({ alpha: null }), null);
  });
  it('smoothing takes the short way across north', () => {
    const h = smoothHeading(350, 10, 0.5);
    assert.ok(angDiff(h, 0) < 1e-9, `got ${h}`);
  });
});
