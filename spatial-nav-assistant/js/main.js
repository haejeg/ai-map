import { runAgent } from './agent.js';
import { createMapView } from './map.js';
import { buildMockPois } from './pois.js';
import { compassName, filterPoisByDirection } from './spatial.js';
import { state, subscribe, updateState } from './state.js';
import { setManualHeading, startGpsTracking, startOrientationTracking } from './telemetry.js';
import { createTools } from './tools.js';
import { createPushToTalk, speak, speechRecognitionSupported } from './voice.js';

const $ = (sel) => document.querySelector(sel);
const els = {
  map: $('#map'),
  coords: $('#hud-coords'),
  heading: $('#hud-heading'),
  source: $('#hud-source'),
  compassNeedle: $('#compass-needle'),
  ptt: $('#ptt'),
  pttLabel: $('#ptt-label'),
  transcript: $('#transcript'),
  log: $('#log'),
  textForm: $('#text-form'),
  textInput: $('#text-input'),
  simToggle: $('#sim-heading-toggle'),
  simSlider: $('#sim-heading'),
  simValue: $('#sim-heading-value'),
  recenter: $('#recenter'),
  notice: $('#notice'),
};

// Expose state for debugging from the console.
window.appState = state;

let pois = buildMockPois(state.lat, state.lon);
let poisAnchoredToFix = false;

const tools = createTools({
  getState: () => state,
  getPois: () => pois,
  onReroute: (route) => updateState({ route }),
});

let mapView = null;
try {
  if (typeof maplibregl === 'undefined') throw new Error('MapLibre failed to load');
  mapView = createMapView(els.map, state);
} catch (err) {
  els.map.classList.add('map-unavailable');
  els.map.textContent = 'Map unavailable (offline?). Voice and spatial features still work.';
  console.warn(err);
}

function showNotice(message) {
  els.notice.textContent = message;
  els.notice.hidden = !message;
}

function render() {
  // Re-anchor mock POIs around the first real GPS fix so the demo works anywhere.
  if (state.hasFix && !poisAnchoredToFix) {
    pois = buildMockPois(state.lat, state.lon);
    poisAnchoredToFix = true;
  }

  els.coords.textContent = `${state.lat.toFixed(5)}, ${state.lon.toFixed(5)}` +
    (state.accuracy != null ? ` ±${Math.round(state.accuracy)} m` : ' (simulated)');
  els.heading.textContent = `${Math.round(state.heading)}° ${compassName(state.heading).toUpperCase()}`;
  els.source.textContent = state.headingSource;
  els.compassNeedle.style.transform = `rotate(${state.heading}deg)`;
  if (els.simToggle.checked) {
    els.simSlider.value = String(Math.round(state.heading));
    els.simValue.textContent = `${Math.round(state.heading)}°`;
  }

  if (mapView) {
    const annotated = filterPoisByDirection(state, pois, 'all');
    mapView.updateUser(state);
    mapView.setPois(annotated);
    mapView.setRoute(state, state.route?.target ?? null);
  }
}

subscribe(render);
render();

function addLog(role, text) {
  const li = document.createElement('li');
  li.className = `log-${role}`;
  li.textContent = text;
  els.log.append(li);
  els.log.scrollTop = els.log.scrollHeight;
}

async function handleUtterance(text) {
  addLog('user', text);
  const { text: reply, trace } = await runAgent(text, tools);
  for (const step of trace) {
    addLog('tool', `${step.tool}(${JSON.stringify(step.input)})`);
    if (step.tool === 'get_surrounding_pois' && step.output.ok) {
      mapView?.highlightPois(step.output.pois.map((p) => p.name));
    }
  }
  addLog('assistant', reply);
  speak(reply);
}

// --- Telemetry -------------------------------------------------------------

startGpsTracking({ onError: (err) => showNotice(`GPS: ${err.message}. Using a simulated position.`) });

let sensorsRequested = false;
async function ensureSensors() {
  if (sensorsRequested) return;
  sensorsRequested = true;
  await startOrientationTracking({ onError: (err) => showNotice(`Compass: ${err.message}`) });
}
// Android grants orientation without a gesture; iOS needs one (handled on first PTT press).
if (typeof DeviceOrientationEvent === 'undefined' || typeof DeviceOrientationEvent.requestPermission !== 'function') {
  ensureSensors();
}

els.simToggle.addEventListener('change', () => {
  els.simSlider.disabled = !els.simToggle.checked;
  setManualHeading(els.simToggle.checked ? Number(els.simSlider.value) : null);
});
els.simSlider.addEventListener('input', () => {
  els.simValue.textContent = `${els.simSlider.value}°`;
  setManualHeading(Number(els.simSlider.value));
});

els.recenter.addEventListener('click', () => mapView?.recenter());

// --- Voice -----------------------------------------------------------------

const ptt = createPushToTalk({
  onInterim: (t) => { els.transcript.textContent = t; },
  onFinal: (t) => { els.transcript.textContent = ''; handleUtterance(t); },
  onStateChange: (listening) => {
    els.ptt.classList.toggle('listening', listening);
    els.ptt.setAttribute('aria-pressed', String(listening));
    els.pttLabel.textContent = listening ? 'Listening… release to send' : 'Hold to talk';
  },
  onError: (err) => showNotice(err.message),
});

if (!speechRecognitionSupported) {
  els.ptt.disabled = true;
  els.pttLabel.textContent = 'Voice input unsupported — type below';
}

const startTalking = (e) => {
  e.preventDefault();
  ensureSensors();
  ptt.start();
};
const stopTalking = () => ptt.stop();

els.ptt.addEventListener('pointerdown', startTalking);
els.ptt.addEventListener('pointerup', stopTalking);
els.ptt.addEventListener('pointercancel', stopTalking);
els.ptt.addEventListener('pointerleave', stopTalking);
els.ptt.addEventListener('contextmenu', (e) => e.preventDefault());
els.ptt.addEventListener('keydown', (e) => {
  if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) startTalking(e);
});
els.ptt.addEventListener('keyup', (e) => {
  if (e.key === ' ' || e.key === 'Enter') stopTalking();
});

els.textForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const text = els.textInput.value.trim();
  if (!text) return;
  els.textInput.value = '';
  ensureSensors();
  handleUtterance(text);
});

for (const chip of document.querySelectorAll('[data-prompt]')) {
  chip.addEventListener('click', () => handleUtterance(chip.dataset.prompt));
}
