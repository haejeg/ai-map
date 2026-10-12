// Web Speech API wrapper: push-to-talk recognition and spoken replies.

const Recognition = typeof window !== 'undefined'
  ? window.SpeechRecognition || window.webkitSpeechRecognition
  : undefined;

export const speechRecognitionSupported = Boolean(Recognition);
export const speechSynthesisSupported = typeof window !== 'undefined' && 'speechSynthesis' in window;

/**
 * Create a push-to-talk controller.
 * @param {{ onInterim?: (t: string) => void, onFinal: (t: string) => void,
 *           onStateChange?: (listening: boolean) => void, onError?: (e: Error) => void }} handlers
 */
export function createPushToTalk({ onInterim, onFinal, onStateChange, onError }) {
  if (!Recognition) {
    return { supported: false, start() {}, stop() {}, get listening() { return false; } };
  }

  const rec = new Recognition();
  rec.lang = navigator.language || 'en-US';
  rec.interimResults = true;
  rec.continuous = false;
  rec.maxAlternatives = 1;

  let listening = false;
  let finalText = '';

  rec.onresult = (event) => {
    let interim = '';
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const res = event.results[i];
      if (res.isFinal) finalText += res[0].transcript;
      else interim += res[0].transcript;
    }
    onInterim?.(finalText + interim);
  };
  rec.onerror = (event) => {
    if (event.error !== 'no-speech' && event.error !== 'aborted') {
      onError?.(new Error(`Speech recognition error: ${event.error}`));
    }
  };
  rec.onend = () => {
    listening = false;
    onStateChange?.(false);
    const text = finalText.trim();
    finalText = '';
    if (text) onFinal(text);
  };

  return {
    supported: true,
    get listening() { return listening; },
    start() {
      if (listening) return;
      stopSpeaking(); // don't transcribe our own voice
      finalText = '';
      try {
        rec.start();
        listening = true;
        onStateChange?.(true);
      } catch (err) {
        onError?.(err);
      }
    },
    stop() {
      if (listening) rec.stop();
    },
  };
}

export function speak(text, { rate = 1, onEnd } = {}) {
  if (!speechSynthesisSupported) {
    onEnd?.();
    return;
  }
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.rate = rate;
  utterance.lang = navigator.language || 'en-US';
  utterance.onend = () => onEnd?.();
  utterance.onerror = () => onEnd?.();
  window.speechSynthesis.speak(utterance);
}

export function stopSpeaking() {
  if (speechSynthesisSupported) window.speechSynthesis.cancel();
}
