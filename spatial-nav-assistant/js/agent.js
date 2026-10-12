// Simulated LLM agent with tool calling. `mockLlm` mimics the contract of a
// real chat-completions endpoint: it receives the conversation plus tool
// definitions and returns either tool_use blocks or a final text answer.
// Swapping it for a real model call only requires matching that contract.

import { TOOL_DEFINITIONS } from './tools.js';

const DIRECTION_PATTERNS = [
  ['left', /\bleft\b/],
  ['right', /\bright\b(?!\s+now)/],
  ['behind', /\b(behind|back|rear)\b/],
  ['ahead', /\b(ahead|in front|front|forward|straight)\b/],
  ['all', /\b(around|nearby|near me|surrounding|close by)\b/],
];

const REROUTE_PATTERN =
  /\b(?:navigate|take me|directions?|reroute|route|guide me|walk me|go)\b(?:\s+me)?\s+(?:to|towards?)\s+(?:the\s+)?(.+?)[.?!]*$/;

const LOCATION_PATTERN =
  /\b(where am i|my (?:current )?location|current location|my position|which way am i facing|what direction am i facing|my heading)\b/;

/** Map an utterance to a tool call, or null if no tool applies. */
export function parseIntent(utterance) {
  const text = String(utterance).toLowerCase().trim();

  const reroute = text.match(REROUTE_PATTERN);
  if (reroute) return { name: 'reroute_navigation', input: { destination: reroute[1].trim() } };

  if (LOCATION_PATTERN.test(text)) return { name: 'get_current_location', input: {} };

  for (const [direction, pattern] of DIRECTION_PATTERNS) {
    if (pattern.test(text)) return { name: 'get_surrounding_pois', input: { direction } };
  }
  return null;
}

const DIRECTION_PHRASE = {
  ahead: 'Ahead of you',
  right: 'To your right',
  behind: 'Behind you',
  left: 'To your left',
  all: 'Around you',
};

const RELATIVE_PHRASE = { ahead: 'ahead of you', right: 'to your right', behind: 'behind you', left: 'to your left' };

function spokenDistance(m) {
  if (m >= 1000) return `${(m / 1000).toFixed(1)} kilometers`;
  return `${Math.max(10, Math.round(m / 10) * 10)} meters`;
}

function joinList(items) {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join('; ')}; and ${items[items.length - 1]}`;
}

/** Turn a tool result into the sentence the assistant speaks. */
export function composeReply(toolName, result) {
  switch (toolName) {
    case 'get_surrounding_pois': {
      if (!result.ok) return `Sorry, ${result.error}`;
      const lead = DIRECTION_PHRASE[result.direction];
      if (result.pois.length === 0) {
        return `${lead}, I don't see any places within ${spokenDistance(result.radius_m)}.`;
      }
      const items = result.pois.map((p) => {
        const extra = result.direction === 'all' ? `, ${RELATIVE_PHRASE[p.direction]}` : '';
        return `${p.name}, a ${p.category} about ${spokenDistance(p.distance_m)} away${extra}`;
      });
      const count = result.pois.length === 1 ? 'one place' : `${result.pois.length} places`;
      return `${lead}, I found ${count}: ${joinList(items)}.`;
    }
    case 'get_current_location': {
      const fix = result.live_gps
        ? `GPS accuracy is about ${result.accuracy_m ?? 'unknown'} meters.`
        : 'This is a simulated position; no GPS fix yet.';
      return `You're at latitude ${result.lat.toFixed(5)}, longitude ${result.lon.toFixed(5)}, ` +
        `facing ${result.facing} at ${Math.round(result.heading)} degrees. ${fix}`;
    }
    case 'reroute_navigation': {
      if (!result.ok) {
        const hint = result.known ? ` Nearby options include ${result.known.slice(0, 3).join(', ')}.` : '';
        return `Sorry, ${result.error}${hint}`;
      }
      const mins = result.eta_min === 1 ? '1 minute' : `${result.eta_min} minutes`;
      return `Rerouting to ${result.destination}. It's ${spokenDistance(result.distance_m)} ` +
        `${RELATIVE_PHRASE[result.relative_direction]}, toward the ${result.compass}. About ${mins} on foot.`;
    }
    default:
      return 'Done.';
  }
}

const HELP_TEXT =
  "I can tell you where you are, what's to your left, right, ahead or behind, or navigate somewhere. " +
  "Try asking: what is to my left?";

let toolUseCounter = 0;

/**
 * Simulated model turn. Given the conversation so far and available tools,
 * return an assistant message whose content is text and/or tool_use blocks.
 */
export async function mockLlm(messages, tools = TOOL_DEFINITIONS) {
  const last = messages[messages.length - 1];

  // Second turn: a tool result came back, so verbalise it.
  if (Array.isArray(last.content) && last.content[0]?.type === 'tool_result') {
    const { tool_name: toolName, content } = last.content[0];
    return { role: 'assistant', stop_reason: 'end_turn', content: [{ type: 'text', text: composeReply(toolName, content) }] };
  }

  // First turn: decide whether to call a tool.
  const call = parseIntent(last.content);
  if (call && tools.some((t) => t.name === call.name)) {
    return {
      role: 'assistant',
      stop_reason: 'tool_use',
      content: [{ type: 'tool_use', id: `toolu_${++toolUseCounter}`, name: call.name, input: call.input }],
    };
  }
  return { role: 'assistant', stop_reason: 'end_turn', content: [{ type: 'text', text: HELP_TEXT }] };
}

/**
 * Agent loop: send the utterance to the model, execute any requested tools,
 * feed results back, and return the final reply plus a trace for the UI.
 */
export async function runAgent(utterance, handlers, { llm = mockLlm, maxSteps = 4 } = {}) {
  const messages = [{ role: 'user', content: utterance }];
  const trace = [];

  for (let step = 0; step < maxSteps; step++) {
    const reply = await llm(messages, TOOL_DEFINITIONS);
    messages.push(reply);

    const toolUses = reply.content.filter((b) => b.type === 'tool_use');
    if (toolUses.length === 0) {
      const text = reply.content.filter((b) => b.type === 'text').map((b) => b.text).join(' ');
      return { text, trace, messages };
    }

    const results = toolUses.map((use) => {
      const handler = handlers[use.name];
      const output = handler ? handler(use.input) : { ok: false, error: `Unknown tool ${use.name}` };
      trace.push({ tool: use.name, input: use.input, output });
      return { type: 'tool_result', tool_use_id: use.id, tool_name: use.name, content: output };
    });
    messages.push({ role: 'user', content: results });
  }
  return { text: 'Sorry, I could not complete that request.', trace, messages };
}
