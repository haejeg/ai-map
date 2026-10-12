// Device telemetry: continuous GPS + compass heading, written into global state.

import { normalizeAngle } from './spatial.js';
import { state, updateState } from './state.js';

/**
 * Derive a compass heading (degrees clockwise from north) from an orientation event.
 * - iOS Safari exposes `webkitCompassHeading`, already clockwise from north.
 * - Android/Chrome exposes `alpha`, which is counter-clockwise, so heading = 360 − alpha.
 * `screenAngle` corrects for landscape rotation of the screen.
 * Returns { heading, source } or null when the event carries no usable data.
 */
export function headingFromOrientationEvent(event, screenAngle = 0) {
  if (typeof event.webkitCompassHeading === 'number' && !Number.isNaN(event.webkitCompassHeading)) {
    return { heading: normalizeAngle(event.webkitCompassHeading + screenAngle), source: 'ios-compass' };
  }
  if (typeof event.alpha === 'number') {
    return {
      heading: normalizeAngle(360 - event.alpha + screenAngle),
      source: event.absolute ? 'absolute-alpha' : 'alpha',
    };
  }
  return null;
}

/** Low-pass filter along the shortest arc, so 359° → 1° doesn't swing through 180°. */
export function smoothHeading(previous, next, factor = 0.25) {
  if (previous == null) return next;
  const delta = ((next - previous + 540) % 360) - 180;
  return normalizeAngle(previous + delta * factor);
}

function screenAngle() {
  if (typeof screen !== 'undefined' && screen.orientation && typeof screen.orientation.angle === 'number') {
    return screen.orientation.angle;
  }
  return typeof window.orientation === 'number' ? window.orientation : 0;
}

let geoWatchId = null;

export function startGpsTracking({ onError } = {}) {
  if (!('geolocation' in navigator)) {
    onError?.(new Error('Geolocation is not supported by this browser.'));
    return;
  }
  if (geoWatchId !== null) return;
  geoWatchId = navigator.geolocation.watchPosition(
    (pos) => {
      updateState({
        lat: pos.coords.latitude,
        lon: pos.coords.longitude,
        accuracy: pos.coords.accuracy,
        hasFix: true,
      });
    },
    (err) => onError?.(err),
    { enableHighAccuracy: true, maximumAge: 1000, timeout: 15000 },
  );
}

let orientationStarted = false;

/**
 * Begin compass tracking. On iOS 13+ this must be called from a user gesture
 * because DeviceOrientationEvent.requestPermission() requires one.
 */
export async function startOrientationTracking({ onError } = {}) {
  if (orientationStarted) return true;
  if (typeof DeviceOrientationEvent === 'undefined') {
    onError?.(new Error('Device orientation is not supported on this device.'));
    return false;
  }
  if (typeof DeviceOrientationEvent.requestPermission === 'function') {
    try {
      const result = await DeviceOrientationEvent.requestPermission();
      if (result !== 'granted') throw new Error('Compass permission denied.');
    } catch (err) {
      onError?.(err);
      return false;
    }
  }

  let gotAbsolute = false;
  const handle = (event) => {
    if (state.headingSource === 'manual') return;
    const reading = headingFromOrientationEvent(event, screenAngle());
    if (!reading) return;
    // Once an absolute (north-referenced) stream exists, ignore relative alpha.
    if (reading.source === 'absolute-alpha' || reading.source === 'ios-compass') gotAbsolute = true;
    else if (gotAbsolute) return;
    const prev = state.headingSource === 'none' ? null : state.heading;
    updateState({ heading: smoothHeading(prev, reading.heading), headingSource: reading.source });
  };

  // Chrome on Android fires `deviceorientationabsolute` with north-referenced alpha.
  if ('ondeviceorientationabsolute' in window) {
    window.addEventListener('deviceorientationabsolute', handle);
  }
  window.addEventListener('deviceorientation', handle);
  orientationStarted = true;
  return true;
}

/** Manual heading override for desktops without a compass. Pass null to resume sensors. */
export function setManualHeading(deg) {
  if (deg == null) updateState({ headingSource: 'none' });
  else updateState({ heading: normalizeAngle(deg), headingSource: 'manual' });
}
